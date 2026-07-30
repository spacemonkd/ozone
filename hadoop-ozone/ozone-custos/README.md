# ozone-custos

Runnable Custos authentication service (`ozone custos`). Custos is a
protocol-neutral, server-side authentication service: a client presents a
credential (SPNEGO token, OIDC JWT, …), Custos validates it through a pluggable
provider, resolves the caller's groups, and returns a short-lived, HMAC-signed
`CustosToken` that OM verifies locally.

This module is the standalone service process. It depends on
`ozone-custos-common` for the SPI types, protos, and the `CustosGrpcClient`
that Ozone Java clients use — clients do **not** depend on this module.

Design reference:
[`common-auth-service.md`](../../hadoop-hdds/docs/content/design/common-auth-service.md)

## Two SPIs: authentication vs. identity

Custos separates *who you are* from *what groups you are in*, so the two are
independently pluggable:

- **`CustosProvider`** (authentication) — validates a raw credential and returns
  the authenticated **subject**. Selected by `CredentialType`
  (`SPNEGO`, `OIDC_JWT`, …). Implementations live under `server/provider/`.
- **`IdentityProvider`** (identity) — takes the authenticated subject and
  resolves **groups/roles** into a `CustosIdentity`. Selected by
  `IdentityProviderType` (`KERBEROS`, `OIDC`, `LDAP`, `LOCAL`). Implementations
  live under `server/identity/`.

`CredentialIdentityMapping` links the two: it maps a validated `CredentialType`
to the `IdentityProviderType` that resolves its groups (today `SPNEGO → KERBEROS`,
`OIDC_JWT → OIDC`). See [Future work](#future-work) for making this configurable.

## Server request flow

```
  client credential (SPNEGO | OIDC_JWT)
              │  gRPC GetSessionToken
              ▼
      CustosServiceImpl
              │
              ▼
      CustosAuthService
    ┌─────────┴───────────────────────────────────────────┐
    │ 1. CustosProviderRegistry.authenticate(credential)   │  auth layer
    │       → subject (+ claim groups/roles for OIDC)       │  server/provider/
    ├───────────────────────────────────────────────────── ┤
    │ 2. CredentialIdentityMapping.forCredential(type)      │  SPNEGO→KERBEROS
    │       → IdentityProviderType                          │  OIDC_JWT→OIDC
    ├───────────────────────────────────────────────────── ┤
    │ 3. IdentityProviderRegistry.resolveIdentity(context)  │  identity layer
    │       → CustosIdentity (subject + groups + roles)     │  server/identity/
    ├───────────────────────────────────────────────────── ┤
    │ 4. associateToToken() → CustosTokenProto              │
    │ 5. CustosTokenSigner.sign() with SCM-managed HMAC key │  secure mode only
    └─────────┬─────────────────────────────────────────────┘
              ▼
   GetSessionTokenResponse { token, ca_cert_pem[] }
```

Token signing uses a symmetric key managed by SCM (the same `SecretKeyClient`
infrastructure used for block/delegation tokens), so any OM can verify a token
locally without calling Custos. In insecure mode no signer is configured and
the token is returned unsigned (tests only).

---

## Kerberos flow, step by step

Kerberos reuses the client's existing login — the same `UserGroupInformation`
used for OM RPC — to mint a SPNEGO token for the Custos service principal.

```
  Ozone Java client                Custos                       SCM
  ─────────────────                ──────                       ───
  1. UGI Kerberos login (kinit / keytab)
  2. KerberosCredentials.fromCurrentUser(conf, custosHost)
       - resolve custos principal (custos/_HOST@REALM)
       - GSS initSecContext → SPNEGO token bytes
  3. CustosCredential{type=SPNEGO, material=spnego,
                       attr[servicePrincipal]=custos/custos@REALM}
        ───────────── GetSessionToken (gRPC) ─────────────▶
                                    4. KerberosProvider.validateSubject()
                                         SpnegoValidator.validate() via
                                         server keytab (GSS acceptSecContext)
                                         → subject = client principal
                                    5. KerberosIdentityProvider.resolveGroups()
                                         Hadoop Groups mapping for subject
                                    6. sign token with SCM HMAC key ◀── secret key
        ◀──────────── CustosToken (+ cluster CA) ──────────
  7. client attaches CustosToken to OMRequest; OM verifies locally
```

**Key points**

- The client sends the Custos **service principal** in the credential
  attributes (`servicePrincipal`); the server validates the SPNEGO token with
  its keytab (`ozone.custos.kerberos.keytab`) and does not read a principal
  config locally.
- Groups come from the Hadoop `GroupMappingServiceProvider`
  (`KerberosIdentityProvider`), i.e. whatever `hadoop.security.group.mapping`
  resolves on the Custos host.

---

## OIDC flow, step by step

OIDC lets a user authenticate with an external IdP (Keycloak, Okta, …) via the
**device authorization grant** — ideal for a CLI on a laptop with no callback
URL. Custos never sees the user's password; it only validates the resulting JWT.

```
  user + CLI              IdP (Keycloak)          Custos                 OM
  ──────────              ──────────────          ──────                 ──
  1. device auth request ─▶
     ◀─ user_code + verification_uri
  2. user opens URL in browser, logs in, approves
  3. CLI polls token endpoint ─▶
     ◀─ access_token (signed JWT: iss, aud, exp, preferred_username, groups)
  4. CustosCredential{type=OIDC_JWT, material=JWT bytes}
        ───────────── GetSessionToken (gRPC) ─────────────▶
                                       5. OidcProvider.authenticate()
                                          OidcJwtValidator:
                                            - fetch JWKS (from issuer discovery
                                              or ozone.custos.oidc.jwks-uri),
                                              cached lazily
                                            - verify RS256/384/512 signature
                                              (rejects alg=none)
                                            - require iss + aud, check exp/nbf
                                              within clock-skew
                                            - subject = username-claim
                                            - groups/roles = claim fields
                                       6. OIDC_JWT → OIDC identity provider
                                          OidcIdentityProvider (claim groups)
                                       7. sign token with SCM HMAC key
        ◀──────── CustosToken + ca_cert_pem[] ─────────────
  8. CLI trusts ca_cert_pem, opens TLS to OM, sends CustosToken; OM verifies
```

**Signature validation (`OidcJwtValidator`, JJWT `io.jsonwebtoken`)**

- Signing keys are located from the issuer's JWKS. The URL is either
  `ozone.custos.oidc.jwks-uri` or discovered from
  `{issuer}/.well-known/openid-configuration`. The parser/keys are built lazily
  on first token, so Custos starts even if the IdP is momentarily unreachable.
- Only asymmetric RS256/RS384/RS512 are accepted; unsecured (`alg=none`) tokens
  are rejected.
- `iss` and `aud` must match config; `exp`/`nbf` are checked with
  `ozone.custos.oidc.clock-skew-seconds` tolerance.

### Why the response carries the cluster CA

Ozone runs OM's gRPC endpoint over TLS in secure mode, using a certificate
issued by the **SCM internal CA**. A freshly installed client (for example the
Rust `ozone` CLI on a laptop) has no way to trust that certificate — and in a
real deployment the cluster sits behind a VPN, so copying the CA out of band is
impractical.

Custos solves this as part of the login itself: on a **successful**
`GetSessionToken`, the response includes `ca_cert_pem[]` — the cluster CA
certificate(s) that Custos fetched from SCM
(`SCMSecurityProtocol.getRootCACertificate()` / `getCACertificate()`). The
client trusts those anchors and can immediately establish TLS to OM. The CA is
public trust material and is returned **only after authentication**, so there is
no anonymous CA endpoint to abuse. Fetching the CA is best-effort: if SCM is
unreachable, token issuance still succeeds and `ca_cert_pem` is empty (the
client must then trust the CA some other way).

```
  GetSessionTokenResponse
  ├── token         CustosTokenProto (HMAC-signed; OM verifies locally)
  ├── ca_cert_pem   repeated string  (PEM CA bundle; empty if unchanged/unavailable)
  ├── ca_unchanged  bool             (true: reuse your cached CA)
  └── ca_fingerprint string          (fingerprint of Custos's current CA bundle)
```

### CA fingerprint optimization (bandwidth only)

Custos fetches the CA once at startup and would otherwise re-send the same few
KB on every `GetSessionToken`. To avoid that, the client can tell Custos which
bundle it already holds:

- `GetSessionTokenRequest.known_ca_fingerprint` — the `ca_fingerprint` value the
  client received in a previous response (opaque; the client just stores and
  echoes it — no need to recompute it).
- If it matches Custos's current bundle, the response sets `ca_unchanged=true`
  and **omits** `ca_cert_pem`; the client reuses its cached CA.
- If it differs (first call, or the CA changed), Custos sends the full bundle
  and the new `ca_fingerprint`.
- `ca_unchanged` disambiguates the two empty-`ca_cert_pem` cases: `true` means
  "reuse yours", `false` with an empty `ca_fingerprint` means "Custos has no CA".

```
  1st login:  known_ca_fingerprint=""      → ca_cert_pem=[…], ca_fingerprint="abc…"
  next login: known_ca_fingerprint="abc…"  → ca_cert_pem=[],  ca_unchanged=true
  after rotate: known_ca_fingerprint="abc…"→ ca_cert_pem=[…], ca_fingerprint="def…"
```

The fingerprint is a lowercase-hex SHA-256 over the trimmed PEM entries joined by
`\n`, computed server-side. **This is a payload optimization, not a security
control** — it does not authenticate Custos or the CA. See the next section.

### Trust bootstrap and MITM (important)

The Custos gRPC endpoint is **plaintext today** (TLS is future work). A client
that blindly trusts the delivered `ca_cert_pem` is doing trust-on-first-use with
no anchor, which an active man-in-the-middle on the client↔Custos hop can abuse:

- **CA substitution** — swap `ca_cert_pem` for the attacker's CA, then MITM the
  OM connection with an OM cert signed by that CA, which the client now
  "verifies" successfully.
- **Credential theft (worse, and passive)** — the OIDC JWT sent to Custos and
  the returned `CustosToken` travel in cleartext on that hop; both are bearer
  secrets, sniffable and replayable to OM until they expire.

The fingerprint optimization above does **not** address either: it only avoids
re-sending known bytes. The client cannot cryptographically validate what Custos
sends on its own (the token is signed with an SCM HMAC key only OM/SCM hold), so
the fix cannot be server-side — it needs an **out-of-band trust anchor** and/or
channel authentication:

- **Out-of-band CA / truststore** (strongest) — the operator ships the SCM root
  CA to the client (e.g. `--om-ca-cert <pem>`); the delivered CA is then only a
  convenience/refresh, never the anchor.
- **Fingerprint pin** — distribute a 64-char SHA-256 pin out of band; the client
  rejects a delivered CA whose hash differs. Cheap; defeats CA substitution.
  Note this is a *client-supplied security pin*, distinct from the bandwidth
  `ca_fingerprint` above, and it does **not** stop the cleartext credential leak.
- **TOFU pinning** — trust on first connect, store the fingerprint, fail on
  change. Dev-grade only; misses a MITM present on the first run.

The credential-in-cleartext leak is only truly closed by **TLS on the Custos
endpoint** (see Future work). Once Custos serves TLS with an SCM-issued cert,
the bootstrap becomes: distribute the SCM **root** CA out of band once (it
rotates rarely); it anchors TLS to Custos *and* to OM. Authentication then
happens over a confidential, authenticated channel, and the CA in the response
arrives trustworthy — resolving the chicken-and-egg by bootstrapping a single
long-lived root rather than every leaf certificate.

---

## Folder layout

```
ozone-custos/src/main/java/org/apache/hadoop/ozone/custos/server/
├── Custos.java                  # CLI entry point (`ozone custos`); fetchClusterCa()
├── CustosAuthService.java       # orchestrates auth + identity + signing
├── CustosConfig.java            # @ConfigGroup server settings
├── CustosGrpcServer.java        # gRPC server
├── CustosServiceImpl.java       # CustosService gRPC impl (returns token + CA)
├── CustosHttpServer.java        # GET /health
├── CustosProviderRegistry.java  # loads ozone.custos.providers
├── IdentityProviderRegistry.java# loads ozone.custos.identity.providers
├── CredentialIdentityMapping.java
├── SecretKeySignedTokenSigner.java  # HMAC signing via SCM secret keys
├── provider/                    # authentication providers
│   ├── AbstractCustosProvider.java
│   ├── KerberosProvider.java / SpnegoValidator.java   # SPNEGO validation
│   ├── OidcProvider.java                              # OIDC_JWT validation
│   └── oidc/                                          # OidcJwtValidator, JwksKeyLocator, OidcHttp
└── identity/                    # identity providers
    ├── KerberosIdentityProvider.java  # Hadoop Groups mapping
    ├── OidcIdentityProvider.java      # groups from JWT claims
    └── LdapIdentityProvider.java      # scaffold (see Future work)
```

## Server configuration

| Key | Default | Purpose |
|-----|---------|---------|
| `ozone.custos.enabled` | `false` | Master switch |
| `ozone.custos.providers` | — | Comma-separated `CustosProvider` class names |
| `ozone.custos.identity.providers` | — | Comma-separated `IdentityProvider` class names |
| `ozone.custos.grpc-bind-host` / `-port` | `0.0.0.0` / `9894` | gRPC listen address |
| `ozone.custos.http-bind-host` / `-port` | `0.0.0.0` / `9893` | Health endpoint |
| `ozone.custos.kerberos.keytab` | — | Keytab to validate client SPNEGO |
| `ozone.custos.oidc.issuer` | — | Expected `iss`; base for JWKS discovery |
| `ozone.custos.oidc.audience` | — | Expected `aud` |
| `ozone.custos.oidc.jwks-uri` | — | Explicit JWKS URL (else discovered from issuer) |
| `ozone.custos.oidc.username-claim` | `sub` | JWT claim used as the subject |
| `ozone.custos.oidc.groups-claim` | `groups` | JWT claim read for groups |
| `ozone.custos.oidc.roles-claim` | `roles` | JWT claim read for roles |
| `ozone.custos.oidc.clock-skew-seconds` | `30` | Tolerance for `exp`/`nbf` |

Both a Kerberos and an OIDC path can be enabled at once:

```properties
ozone.custos.providers=\
  org.apache.hadoop.ozone.custos.server.provider.KerberosProvider,\
  org.apache.hadoop.ozone.custos.server.provider.OidcProvider
ozone.custos.identity.providers=\
  org.apache.hadoop.ozone.custos.server.identity.KerberosIdentityProvider,\
  org.apache.hadoop.ozone.custos.server.identity.OidcIdentityProvider
ozone.custos.kerberos.keytab=/etc/security/keytabs/custos.keytab
ozone.custos.oidc.issuer=http://keycloak:8080/realms/ozone
ozone.custos.oidc.audience=om-service-1
ozone.custos.oidc.username-claim=preferred_username
```

---

## Testing

### Unit / integration tests (JVM)

```bash
mvn -pl :ozone-custos-common,:ozone-custos test -DskipShade -DskipRecon -DskipDocs
```

Relevant suites:

| Test | Covers |
|------|--------|
| `provider/TestKerberosProvider` | SPNEGO credential routing + validation error paths (type mismatch, missing service principal, no valid keytab) |
| `provider/TestOidcProvider` | JWT signature/claim validation against an in-memory JWKS |
| `TestOidcProviderIntegration` | OIDC_JWT → CustosToken end-to-end in one JVM |
| `TestCustosGrpcClient` | gRPC round-trip through `CustosGrpcClient` |
| `TestCustosServiceImpl` | `GetSessionToken` returns token **and** the CA bundle |
| `TestCustos` | boot + `/health` (binds fixed port 9894 — stop any local Custos container first) |

### End-to-end with the secure compose cluster

The `ozonesecure` compose profile runs Custos alongside a KDC and a Keycloak
IdP, so both flows can be exercised against a live cluster.

```bash
# Build a distribution, then from the compose profile:
cd hadoop-ozone/dist/target/ozone-*-SNAPSHOT/compose/ozonesecure
docker compose up -d           # brings up scm, om, custos, kdc, keycloak, …
```

Fixed ports: Custos gRPC `9894`, health `9893`; OM gRPC `8981`; Keycloak `8080`.

**Kerberos path** — from inside a cluster container the client already has a
Kerberos login; an `ozone` shell command against OM exercises the SPNEGO →
Custos → OM flow. Confirm Custos issued a token via its logs:

```bash
docker compose logs custos | grep -i "GetSessionToken succeeded"
```

**OIDC path** — Keycloak imports the `ozone` realm (public client `ozone-cli`
with the device grant enabled, user `alice`, an `om-audience` mapper). A client
that supports the device flow (e.g. the Rust `ozone` CLI) logs in as `alice`,
obtains a JWT, and calls Custos, which vends a CustosToken plus the cluster CA.
The returned CA lets the client open TLS to OM at `:8981` without any out-of-band
trust setup. Watch the flow:

```bash
docker compose logs -f custos | grep -iE "OIDC|GetSessionToken"
```

> The issuer in tokens must match `ozone.custos.oidc.issuer`
> (`http://keycloak:8080/realms/ozone`). If the CLI runs on the host, add a
> `keycloak` entry to `/etc/hosts` so the issuer URL resolves identically.

Before wrapping up a change here, run the repo checks:

```bash
./hadoop-ozone/dev-support/checks/checkstyle.sh
./hadoop-ozone/dev-support/checks/rat.sh    # if you added files
```

---

## Future work

- **LDAP identity provider.** `IdentityProviderType.LDAP` and
  `LdapIdentityProvider` exist as a scaffold; its `resolveGroups()` currently
  falls back to claim groups. **TODO:** implement the directory (JNDI/LDAP)
  group lookup for `context.getSubject()` so group membership can come from
  LDAP/AD regardless of how the user authenticated.
- **Configurable credential→identity mapping.** `CredentialIdentityMapping` is a
  hard-coded `switch` (`OIDC_JWT → OIDC`). **TODO:** make it configuration-driven
  so the authentication backend and the identity backend can be chosen
  independently — e.g. authenticate with OIDC but resolve groups from LDAP.
- **OIDC userinfo lookup.** `OidcIdentityProvider` reads groups from token
  claims only. **TODO:** optionally call the IdP userinfo endpoint when the
  token omits group claims.
- **LDAP bind authentication.** A username/password `CustosProvider` (new
  `CredentialType`) that authenticates by binding to LDAP, for deployments
  without an OIDC IdP.
- **gRPC TLS / mTLS for Custos itself** and phasing out the Kerberos keytab in
  favour of an SCM-issued certificate — see
  [Future Work in the design doc](../../hadoop-hdds/docs/content/design/common-auth-service.md#13-future-work).
