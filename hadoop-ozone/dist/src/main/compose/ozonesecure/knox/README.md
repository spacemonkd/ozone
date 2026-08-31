<!--
  Licensed to the Apache Software Foundation (ASF) under one or more
  contributor license agreements. See the NOTICE file distributed with
  this work for additional information regarding copyright ownership.
  The ASF licenses this file to You under the Apache License, Version 2.0
  (the "License"); you may not use this file except in compliance with
  the License. You may obtain a copy of the License at

      http://www.apache.org/licenses/LICENSE-2.0

  Unless required by applicable law or agreed to in writing, software
  distributed under the License is distributed on an "AS IS" BASIS,
  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  See the License for the specific language governing permissions and
  limitations under the License.
-->

# Apache Knox + OM credential REST endpoint (PoC)

This overlay adds an Apache Knox gateway in front of a secure Ozone cluster and
turns on the **OM credential REST endpoint**, so an external client can bootstrap
a standard Ozone **delegation token** without `kinit` and then use `ofs://`
directly.

- OM authenticates the gateway with **SPNEGO** (`knox` Kerberos identity) and the
  gateway impersonates the end user with **`doAs`** — the same pattern WebHDFS,
  HttpFS, and Oozie use. This is how Knox integrates with a Kerberized backend in
  CDP; the gateway does **not** present a client certificate to OM.
- The endpoint is `http://om:9874/credential/token` (`GET` issue, `POST` renew,
  `DELETE` cancel). It is enabled by `ozone.om.credential.rest.enabled=true` and
  `ozone.om.credential.rest.auth=spnego` (set in `knox.yaml`).

## Layout

| Path | Purpose |
|---|---|
| `../knox.yaml` | Compose overlay: enables the endpoint + `hadoop.proxyuser.knox.*` on OM, adds the `knox` service. |
| `topologies/ozone.xml` | Knox topology at `/gateway/ozone` (Anonymous edge auth — PoC only). |
| `services/ozonedt/1.0.0/service.xml` | Custom service `OZONEDT` (SPNEGO `DefaultDispatch`). |
| `services/ozonedt/1.0.0/rewrite.xml` | Path/query rewrite to the OM endpoint. |
| `gateway-site.xml` | `gateway.hadoop.kerberos.secured=true` + krb5 for the dispatch. |
| `krb5JAASLogin.conf` | JAAS initiate entry using `knox.keytab`. |

The `knox` keytab (`knox/knox@EXAMPLE.COM`, `HTTP/knox@EXAMPLE.COM`) is created by
`../../common/init-kdc.sh`.

## Deploy

From the `compose/ozonesecure` directory:

```bash
docker compose -f docker-compose.yaml -f knox.yaml up -d
```

Wait for the datanode to register (`docker compose ... ps`, OM/SCM healthy).

## Verify the endpoint directly (SPNEGO + doAs) — reliable path

This exercises the OM endpoint exactly as Knox would, without depending on Knox
topology wiring. Run inside the OM container:

```bash
docker compose -f docker-compose.yaml -f knox.yaml exec om bash
# authenticate as the knox proxy user
kinit -kt /etc/security/keytabs/knox.keytab knox/knox@EXAMPLE.COM
# bootstrap a delegation token for end user "testuser"
curl -sS --negotiate -u : \
  "http://om:9874/credential/token?doas=testuser&renewer=knox" \
  -o /tmp/token.bin
# use the token (no kinit) as testuser
kdestroy
HADOOP_TOKEN_FILE_LOCATION=/tmp/token.bin ozone sh volume list
```

Renew / cancel:

```bash
kinit -kt /etc/security/keytabs/knox.keytab knox/knox@EXAMPLE.COM
curl -sS --negotiate -u : -X POST --data-binary @/tmp/token.bin \
  "http://om:9874/credential/token"                 # -> new expiry (epoch ms)
curl -sS --negotiate -u : -X DELETE --data-binary @/tmp/token.bin \
  "http://om:9874/credential/token"                 # -> 200
```

## Verify through Knox

```bash
# from the host (Knox gateway is on https://localhost:8443)
curl -sSk \
  "https://localhost:8443/gateway/ozone/ozonedt/token?doas=testuser&renewer=knox" \
  -o token.bin
```

Knox authenticates to OM with the `knox` SPNEGO identity and forwards
`doas=testuser`; OM returns the serialized `Credentials` blob.

> Note: the Knox topology, custom service definition, rewrite rules, and secure
> SPNEGO dispatch are environment-specific and were authored from the Knox docs
> and image source but not executed in this environment. If the Knox-fronted call
> does not work on the first `up`, check `docker compose ... logs knox` and:
> - confirm `gateway.log` shows the `knox` JAAS login succeeding;
> - confirm the rewrite forwards the `doas`/`renewer` query string (adjust
>   `rewrite.xml` if they are dropped);
> - confirm OM logs show a request authenticated as `knox` with `doAs=testuser`.
> The direct-SPNEGO path above does not depend on any of this and is the
> authoritative check that the endpoint works.

## Production notes

- Replace the **Anonymous** edge provider with a real one (pac4j OIDC/SAML, shiro
  LDAP, or hadoopauth). The end user then comes from the edge login rather than a
  client-supplied `doas`.
- Scope `hadoop.proxyuser.knox.{users,groups,hosts}` instead of `*`.
- The token owner is the `doas` end user, so OM native ACL / Ranger, admin checks,
  and audit all evaluate the real user — not `knox`.
