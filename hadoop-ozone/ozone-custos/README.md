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
   GetSessionTokenResponse { token }
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
        ◀──────────────── CustosToken ─────────────────────
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
        ◀──────────────── CustosToken ─────────────────────
  8. CLI opens TLS to OM (trusting the out-of-band SCM root), sends
     CustosToken; OM verifies locally
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

### How the client trusts OM (and Custos)

Ozone runs OM's gRPC endpoint over TLS in secure mode, using a certificate
issued by the **SCM internal CA**; Custos serves its own gRPC over TLS the same
way (see [Server TLS](#server-tls-scm-issued-certificate)). A fresh client (for
example the Rust `ozone` CLI on a laptop) must therefore trust the **SCM root
CA**, and that anchor is distributed **out of band** — the operator ships the
root CA (or a SHA-256 pin of it) to the client once. The same root validates
both the Custos and the OM connection, and because it is the *root* it survives
leaf/intermediate certificate rotation, so this is a one-time, low-churn step.

Custos deliberately does **not** return the CA in the token response. Handing
back the trust anchor over a channel the client cannot yet authenticate would be
trust-on-first-use (see the next section); and once the client holds the root
out of band, a delivered CA adds nothing — the root already validates OM's chain
(sent during OM's own TLS handshake) across rotations.

### Trust bootstrap and MITM (important)

Custos serves gRPC over **server-only TLS** when `ozone.security.enabled` and
`hdds.grpc.tls.enabled` are on, using its SCM-issued certificate (see
[Server TLS](#server-tls-scm-issued-certificate)). When TLS is off (development)
the endpoint is plaintext, and an active man-in-the-middle on the client↔Custos
hop can steal the bearer credentials: the OIDC JWT sent to Custos and the
returned `CustosToken` travel in cleartext, sniffable and replayable to OM until
they expire — this works even for a *passive* eavesdropper. The client also
cannot cryptographically validate what Custos sends on its own (the token is
signed with an SCM HMAC key only OM/SCM hold), so a plaintext hop cannot be made
safe from the server side. The fix is channel authentication plus the
out-of-band anchor above:

- **Out-of-band CA / truststore** (the model here) — the operator ships the SCM
  root CA to the client (e.g. `--om-ca-cert <pem>`); it anchors TLS to both
  Custos and OM.
- **Fingerprint pin** — distribute a 64-char SHA-256 pin of the root out of band
  and have the client reject a server chain that doesn't match. Cheaper to
  distribute than a full PEM.
- **TOFU pinning** — trust on first connect, store the fingerprint, fail on
  change. Dev-grade only; misses a MITM present on the first run.

### Server TLS (SCM-issued certificate)

When `ozone.security.enabled` and `hdds.grpc.tls.enabled` are both true, Custos:

1. On first boot, records the cluster id (from SCM) and a Custos uuid in a
   VERSION file under `<ozone.metadata.dirs>/custos` (`CustosStorageConfig`).
2. Obtains an SCM-issued certificate through `CustosCertificateClient`, which
   CSRs to SCM via the generic `getCertificateChain` RPC with `NodeType.CUSTOS`
   (the same path Recon uses). The cert serial id is persisted, so the
   certificate is reused across restarts.
3. Serves gRPC with `SslContextBuilder.forServer(certClient.getKeyManager())` —
   **server-only** TLS. The primary caller is an external client with no
   certificate of its own, so the client authenticates the server (trusting the
   SCM CA) but is not required to present one. Service-to-service callers that
   already hold SCM certs may layer mTLS on later (future work).

The Java `CustosGrpcClient` trusts the server by passing the CA certificate(s)
(the out-of-band SCM root) to `GrpcSslContexts.forClient().trustManager(caCerts)`;
with no CA supplied it stays plaintext (development). If TLS is requested but the
certificate cannot be loaded, the server fails fast rather than silently falling
back to plaintext.

---

## Endpoint discovery (`GetClusterInfo`)

A client on a cluster node reads both the OM and the Custos address from the
shared `ozone-site.xml`. An external client (e.g. a laptop CLI) has no such
config, so it would otherwise have to pass both endpoints. Since Custos is the
client's first hop (it authenticates there), the client can instead pass **only
the Custos endpoint** and discover OM from it.

`GetClusterInfo` returns the OM gRPC endpoint(s) and the token audience:

```
  client --(--custos only)--> Custos.GetClusterInfo()
        ◀── { om_grpc_address: ["om:8981"], audience: "om-service-1" }
  then: GetSessionToken(jwt, audience) ; connect to the discovered OM
```

- Custos resolves the OM gRPC endpoint(s) from its own config at startup, reusing
  `OmUtils` (non-HA `ozone.om.address`; HA `ozone.om.service.ids`) and the OM gRPC
  port (`ozone.om.grpc.port`, default 8981) exactly as `GrpcOmTransport` does.
- The audience comes from `ozone.custos.token.audience` (else the OM service id).
  A client that omits the audience in `GetSessionToken` gets the token bound to
  this configured value, so `--audience` can be dropped too.
- `GetClusterInfo` is unauthenticated: it exposes only the OM gRPC address and the
  audience — non-secret topology — over the (server-only) TLS channel. It is *not*
  a CA/trust endpoint; the trust anchor is still the out-of-band SCM root.

The flag-omission logic lives in the external client; the CLI passes `--custos`,
calls `GetClusterInfo`, then proceeds. (Note: `GetClusterInfo` returns the OM
**gRPC** endpoint — target that transport, not OM's Hadoop-RPC port.)

### Reaching the returned address from outside the cluster

`GetClusterInfo` returns the OM endpoint using the cluster's **internal** hostname
(for example `om:8981`, because Custos resolves `ozone.om.address=om`). Inside the
cluster that name resolves; on a laptop it does not, so the client fails with a DNS
error. Do **not** try to fix this by changing `ozone.om.address` to `localhost` —
that is how every service finds OM inside the cluster, and it would also break TLS
(OM's certificate SAN is its hostname `om`, not `localhost`).

Instead, map the service names to loopback in the client machine's `/etc/hosts`
(the compose profile already publishes the ports to the host):

```
127.0.0.1  om scm custos recon s3g keycloak
```

This makes `om:8981` resolve to the published `127.0.0.1:8981` **and** keeps TLS
verification valid — the client connects with authority `om`, which matches the
certificate SAN. (`localhost:8981` would reach the port but fail hostname
verification.) This is the same `/etc/hosts` approach used for the OIDC issuer.

---

## Folder layout

```
ozone-custos/src/main/java/org/apache/hadoop/ozone/custos/server/
├── Custos.java                  # CLI entry point (`ozone custos`); fetchClusterCa()
├── CustosAuthService.java       # orchestrates auth + identity + signing
├── CustosConfig.java            # @ConfigGroup server settings
├── CustosGrpcServer.java        # gRPC server (server-only TLS when enabled)
├── CustosServiceImpl.java       # CustosService gRPC impl (returns token + CA)
├── CustosHttpServer.java        # GET /health
├── CustosProviderRegistry.java  # loads ozone.custos.providers
├── IdentityProviderRegistry.java# loads ozone.custos.identity.providers
├── CredentialIdentityMapping.java
├── SecretKeySignedTokenSigner.java  # HMAC signing via SCM secret keys
├── CustosCertificateClient.java # SCM-issued cert (NodeType.CUSTOS) for TLS
├── CustosStorageConfig.java     # VERSION file: clusterId, uuid, cert serial id
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
obtains a JWT, and calls Custos, which vends a CustosToken. Because the compose
profile sets `hdds.grpc.tls.enabled=true`, Custos serves gRPC over TLS, so the
client must trust the SCM **root** CA (obtained out of band) to reach Custos and
OM (`:8981`). Watch the flow:

```bash
docker compose logs -f custos | grep -iE "OIDC|GetSessionToken"
```

> The issuer in tokens must match `ozone.custos.oidc.issuer`
> (`http://keycloak:8080/realms/ozone`). If the CLI runs on the host, map the
> cluster hostnames to loopback in `/etc/hosts` so the issuer URL, Custos, and
> the OM endpoint returned by `GetClusterInfo` all resolve (and TLS SANs match):
> `127.0.0.1 om scm custos recon s3g keycloak` — see
> [Reaching the returned address from outside the cluster](#reaching-the-returned-address-from-outside-the-cluster).

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
- **Mutual TLS for service callers.** Server-only gRPC TLS is implemented (see
  [Server TLS](#server-tls-scm-issued-certificate)). **TODO:** optionally require
  a client certificate (mTLS) for service-to-service callers that already hold
  SCM certs, while keeping server-only TLS for external clients.
- **Phase out the Custos Kerberos keytab.** Custos still logs in with a keytab to
  call SCM. **TODO:** move those calls onto its SCM-issued certificate (mTLS),
  removing the KDC dependency — see
  [Future Work in the design doc](../../hadoop-hdds/docs/content/design/common-auth-service.md#13-future-work).
