# ozone-custos-common

Shared library for the Custos authentication service. Depends on OM, OzoneFS
clients, and the Custos server process. Holds the provider SPI, wire protos,
gRPC client, and types OM uses to verify `CustosToken` locally.

Design reference:
[`common-auth-service.md`](../../hadoop-hdds/docs/content/design/common-auth-service.md)

## Role in the stack

```
Ozone Java client                    Custos server              OM
─────────────────                    ─────────────              ──
KerberosCredentials  ──gRPC──►  (validates credential)
CustosGrpcClient                     issues CustosToken
                                     │
RpcClient (Hadoop RPC) ─────────────────────────────────────► verifies token
                             OMRequest.custosToken
```

Custos is contacted **once per session** for token issuance. Namespace
operations still go to OM over Hadoop RPC.

## Folder layout

```
ozone-custos-common/
├── pom.xml
├── README.md
└── src/
    ├── main/
    │   ├── java/org/apache/hadoop/ozone/custos/
    │   │   ├── CredentialType.java          # SPNEGO, OIDC_JWT, S3_SIGV4, …
    │   │   ├── CustosCredential.java        # credential input to providers
    │   │   ├── CustosException.java
    │   │   ├── CustosIdentity.java          # subject, groups, roles, …
    │   │   ├── CustosProvider.java          # auth-provider SPI (interface)
    │   │   ├── KerberosCredentials.java     # mint SPNEGO from current UGI
    │   │   ├── client/
    │   │   │   ├── CustosClientConfig.java  # client config key constants
    │   │   │   ├── CustosGrpcClient.java    # gRPC client (Get/RenewSessionToken)
    │   │   │   └── CustosProtoHelper.java   # Java ↔ protobuf translation
    │   │   └── identity/
    │   │       ├── AbstractIdentityProvider.java  # base for identity backends
    │   │       ├── IdentityContext.java     # subject + claims after auth
    │   │       ├── IdentityProvider.java    # identity-provider SPI (interface)
    │   │       ├── IdentityProviderType.java
    │   │       └── TokenBinding.java        # audience, expiry, token id
    │   └── proto/
    │       ├── CustosService.proto          # GetSessionToken, RenewSessionToken
    │       └── CustosToken.proto            # CustosCredentialProto, CustosTokenProto
    └── test/
        └── java/.../TestCustosTypes.java
```

Generated at build time (not checked in):

- `org.apache.hadoop.ozone.custos.proto.CustosServiceGrpc`
- `org.apache.hadoop.ozone.custos.proto.CustosTokenProtos`
- `org.apache.hadoop.ozone.custos.proto.CustosServiceProtos`

## Two plugin layers

Custos splits **authentication** (validate credential → subject) from
**identity** (resolve subject → groups → token fields).

| Layer | SPI | Responsibility |
|-------|-----|----------------|
| Auth | `CustosProvider` | Validate `CustosCredential`, return subject |
| Identity | `IdentityProvider` | Resolve groups/roles, bind to `CustosTokenProto` |

Server implementations of both SPIs live in `ozone-custos`. This module
defines the contracts and shared types only.

## `AbstractIdentityProvider`

Base class for `IdentityProvider` implementations (for example
`KerberosIdentityProvider` in the server module).

**Subclasses implement:**

- `supportedType()` — which `IdentityProviderType` they handle
- `resolveGroups(IdentityContext)` — optional override; default uses claim
  groups from the context

**The base class provides:**

- `resolveIdentity(context)` — builds `CustosIdentity` from subject, groups,
  roles, issuer, and auth method
- `associateToToken(identity, binding)` — maps identity + `TokenBinding` onto
  a `CustosTokenProto.Builder` (signature fields left unset)

Typical subclass work is one method: how to resolve groups for a backend
(Hadoop `Groups`, LDAP, OIDC claims, …).

## `CustosProvider` (interface, not abstract)

Contract for credential validators. Each implementation declares one
`CredentialType` and implements `validateSubject(CustosCredential)`.

There is no `AbstractCustosProvider` in this module; the server-side base class
lives in `ozone-custos` under `server.provider`.

## Client config keys

| Key | Default | Purpose |
|-----|---------|---------|
| `ozone.custos.enabled` | `false` | Master switch |
| `ozone.custos.grpc.host` | `localhost` | Custos gRPC host |
| `ozone.custos.grpc.port` | `9894` | Custos gRPC port |
| `ozone.custos.grpc.deadline.ms` | `30000` | RPC deadline |
| `ozone.custos.kerberos.principal` | — | Custos service principal pattern (`_HOST`) for SPNEGO minting |

## Build

```bash
mvn -pl :ozone-custos-common install -DskipTests -DskipShade -DskipRecon -DskipDocs
```
