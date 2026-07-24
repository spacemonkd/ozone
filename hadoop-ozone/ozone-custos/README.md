# ozone-custos

Runnable Custos authentication service (`ozone custos`). Loads auth and
identity providers from configuration, exposes gRPC token endpoints, and serves
a health check over HTTP.

Depends on `ozone-custos-common` for SPI types and protos. Ozone Java clients
use the common module's `CustosGrpcClient`; they do not depend on this module.

Design reference:
[`common-auth-service.md`](../../hadoop-hdds/docs/content/design/common-auth-service.md)

## Request flow (server)

```
GetSessionToken (gRPC)
        │
        ▼
CustosServiceImpl
        │
        ▼
CustosAuthService
        │
        ├─► CustosProviderRegistry.validateSubject()     [auth layer]
        │         KerberosProvider, OidcProvider, …
        │
        ├─► CredentialIdentityMapping                      [SPNEGO → KERBEROS, …]
        │
        └─► IdentityProviderRegistry                       [identity layer]
                  resolveIdentity() + associateToToken()
                        KerberosIdentityProvider, …
        │
        ▼
CustosTokenProto (unsigned; signing TODO)
```

## Folder layout

```
ozone-custos/
├── pom.xml
├── README.md
└── src/
    ├── main/java/org/apache/hadoop/ozone/custos/server/
    │   ├── Custos.java                  # CLI entry point (`ozone custos`)
    │   ├── CustosAuthService.java       # orchestrates auth + identity + token
    │   ├── CustosConfig.java            # @ConfigGroup server settings
    │   ├── CustosGrpcServer.java        # gRPC server
    │   ├── CustosServiceImpl.java       # CustosService gRPC implementation
    │   ├── CustosHttpServer.java        # GET /health
    │   ├── CustosProviderRegistry.java
    │   ├── IdentityProviderRegistry.java
    │   ├── CredentialIdentityMapping.java
    │   ├── CustosToken.java             # PoC holder (pre-signing)
    │   ├── provider/
    │   │   ├── AbstractCustosProvider.java   # base for auth providers
    │   │   ├── KerberosProvider.java           # SPNEGO validation
    │   │   └── SpnegoValidator.java            # GSS acceptSecContext
    │   └── identity/
    │       ├── KerberosIdentityProvider.java   # Hadoop Groups mapping
    │       ├── OidcIdentityProvider.java     # scaffold
    │       └── LdapIdentityProvider.java       # scaffold
    └── test/java/org/apache/hadoop/ozone/custos/server/
        ├── Custos.java                  # boot + health tests
        ├── TestCustosGrpcClient.java    # gRPC round-trip
        ├── TestCustosProviderRegistry.java
        ├── StubOidcProvider.java        # test auth provider
        ├── StubS3Provider.java
        ├── StubDuplicateOidcProvider.java
        └── provider/TestKerberosProvider.java
```

## `AbstractCustosProvider`

Base class for `CustosProvider` implementations in this module.

**Provides:**

- `Configurable` wiring — `setConf` / `getConf` inject `OzoneConfiguration`
- `init()` hook — called once after config injection (subclasses read keytab,
  endpoints, etc.)

**Subclasses must implement:**

- `supportedType()` — which `CredentialType` they validate
- `validateSubject(CustosCredential)` — return authenticated subject only;
  no group resolution

Group resolution and token binding are **not** done here; they belong to
`IdentityProvider` via `CustosAuthService`.

Example: `KerberosProvider` validates SPNEGO bytes and returns the principal;
`KerberosIdentityProvider` resolves groups.

## Identity providers (extend `AbstractIdentityProvider` in common)

Implemented under `server.identity/`. They extend
`org.apache.hadoop.ozone.custos.identity.AbstractIdentityProvider` from
`ozone-custos-common`.

| Class | Type | Status |
|-------|------|--------|
| `KerberosIdentityProvider` | `KERBEROS` | Hadoop `Groups` mapping |
| `OidcIdentityProvider` | `OIDC` | scaffold (claim groups) |
| `LdapIdentityProvider` | `LDAP` | scaffold |

## Server configuration

| Key | Default | Purpose |
|-----|---------|---------|
| `ozone.custos.enabled` | `false` | Master switch |
| `ozone.custos.providers` | — | Comma-separated `CustosProvider` class names |
| `ozone.custos.identity.providers` | — | Comma-separated `IdentityProvider` class names |
| `ozone.custos.grpc-bind-host` | `0.0.0.0` | gRPC listen host |
| `ozone.custos.grpc-bind-port` | `9894` | gRPC listen port |
| `ozone.custos.http-bind-host` | `0.0.0.0` | Health endpoint host |
| `ozone.custos.http-bind-port` | `9893` | Health endpoint port |
| `ozone.custos.kerberos.keytab` | — | Keytab to validate client SPNEGO |

Kerberos example (both registries required):

```properties
ozone.custos.providers=org.apache.hadoop.ozone.custos.server.provider.KerberosProvider
ozone.custos.identity.providers=org.apache.hadoop.ozone.custos.server.identity.KerberosIdentityProvider
ozone.custos.kerberos.keytab=/etc/security/custos.keytab
```

The Custos service principal is sent by the client in the credential (see
`KerberosCredentials` in common); the server does not read
`ozone.custos.kerberos.principal` locally.

## Run locally

```bash
mvn -pl :ozone-custos -am package -DskipTests -DskipShade -DskipRecon -DskipDocs
ozone custos
```

Health check: `http://<host>:9893/health` → `OK`

## Build and test

```bash
mvn -pl :ozone-custos-common,:ozone-custos test -DskipShade -DskipRecon -DskipDocs
```

## Not yet implemented

- HMAC signing of `CustosTokenProto` (SCM key)
- OM `OMRequest.custosToken` field and `CustosTokenVerifier`
- Ozone client wiring (`getDelegationToken` → `CustosGrpcClient`)
- gRPC TLS
