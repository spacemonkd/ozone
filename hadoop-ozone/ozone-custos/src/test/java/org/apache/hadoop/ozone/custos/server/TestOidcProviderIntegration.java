/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.ozone.custos.server;

import static org.apache.hadoop.ozone.custos.server.CustosConfig.Keys.GRPC_BIND_HOST;
import static org.apache.hadoop.ozone.custos.server.CustosConfig.Keys.GRPC_BIND_PORT;
import static org.apache.hadoop.ozone.custos.server.CustosConfig.Keys.HTTP_BIND_HOST;
import static org.apache.hadoop.ozone.custos.server.CustosConfig.Keys.HTTP_BIND_PORT;
import static org.apache.hadoop.ozone.custos.server.CustosConfig.Keys.IDENTITY_PROVIDERS;
import static org.apache.hadoop.ozone.custos.server.CustosConfig.Keys.OIDC_AUDIENCE;
import static org.apache.hadoop.ozone.custos.server.CustosConfig.Keys.OIDC_ISSUER;
import static org.apache.hadoop.ozone.custos.server.CustosConfig.Keys.PROVIDERS;
import static org.assertj.core.api.Assertions.assertThat;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.Locator;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.Arrays;
import java.util.Date;
import org.apache.hadoop.hdds.conf.OzoneConfiguration;
import org.apache.hadoop.ozone.custos.CredentialType;
import org.apache.hadoop.ozone.custos.CustosCredential;
import org.apache.hadoop.ozone.custos.CustosException;
import org.apache.hadoop.ozone.custos.client.CustosGrpcClient;
import org.apache.hadoop.ozone.custos.identity.IdentityProviderType;
import org.apache.hadoop.ozone.custos.proto.CustosTokenProtos.CustosTokenProto;
import org.apache.hadoop.ozone.custos.server.identity.OidcIdentityProvider;
import org.apache.hadoop.ozone.custos.server.provider.OidcProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for the OIDC path inside Custos: that the real
 * {@link OidcProvider} loads by configuration, and that a signed OIDC JWT
 * presented over gRPC yields a {@code CustosToken} carrying the subject and the
 * groups from the token's claims.
 */
class TestOidcProviderIntegration {

  private static final String ISSUER = "https://idp.example.com/realms/ozone";
  private static final String AUDIENCE = "om-service-1";

  private final Custos custos = new Custos();

  @AfterEach
  void tearDown() {
    custos.stop();
  }

  private OzoneConfiguration newConf() {
    OzoneConfiguration conf = new OzoneConfiguration();
    conf.set(HTTP_BIND_HOST, "127.0.0.1");
    conf.setInt(HTTP_BIND_PORT, 0);
    conf.set(GRPC_BIND_HOST, "127.0.0.1");
    conf.setInt(GRPC_BIND_PORT, 0);
    return conf;
  }

  @Test
  void realOidcProviderLoadsFromConfig() throws Exception {
    OzoneConfiguration conf = newConf();
    conf.set(PROVIDERS, OidcProvider.class.getName());
    conf.set(OIDC_ISSUER, ISSUER);
    conf.set(OIDC_AUDIENCE, AUDIENCE);
    custos.start(conf);

    assertThat(custos.getProviderRegistry().supportedTypes())
        .containsExactly(CredentialType.OIDC_JWT);
  }

  @Test
  void oidcJwtOverGrpcYieldsTokenWithSubjectAndGroups() throws Exception {
    KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
    gen.initialize(2048);
    KeyPair keyPair = gen.generateKeyPair();
    InMemoryOidcProvider.publicKey = keyPair.getPublic();

    OzoneConfiguration conf = newConf();
    conf.set(PROVIDERS, InMemoryOidcProvider.class.getName());
    conf.set(IDENTITY_PROVIDERS, OidcIdentityProvider.class.getName());
    conf.set(OIDC_ISSUER, ISSUER);
    conf.set(OIDC_AUDIENCE, AUDIENCE);
    custos.start(conf);

    String jwt = Jwts.builder()
        .header().keyId("k1").and()
        .issuer(ISSUER)
        .audience().add(AUDIENCE).and()
        .subject("alice")
        .claim("groups", Arrays.asList("dev", "ops"))
        .issuedAt(Date.from(Instant.now()))
        .expiration(Date.from(Instant.now().plusSeconds(300)))
        .signWith(keyPair.getPrivate(), Jwts.SIG.RS256)
        .compact();

    CustosCredential credential = new CustosCredential(CredentialType.OIDC_JWT,
        jwt.getBytes(StandardCharsets.UTF_8));

    InetSocketAddress grpcAddress = custos.getGrpcAddress();
    try (CustosGrpcClient client = new CustosGrpcClient(
        grpcAddress.getHostString(), grpcAddress.getPort(), 10_000L)) {
      CustosTokenProto token =
          client.getSessionToken(credential, AUDIENCE, 60_000L);
      assertThat(token.getSubject()).isEqualTo("alice");
      assertThat(token.getAuthProvider())
          .isEqualTo(IdentityProviderType.OIDC.name());
      assertThat(token.getGroupsList()).containsExactlyInAnyOrder("dev", "ops");
    }
  }

  /**
   * The real {@link OidcProvider} with its signing keys served from memory
   * instead of a JWKS endpoint, so the test needs no network or IdP.
   */
  public static class InMemoryOidcProvider extends OidcProvider {
    private static volatile Key publicKey;

    @Override
    protected Locator<Key> createKeyLocator() throws CustosException {
      Key key = publicKey;
      if (key == null) {
        throw new CustosException("test public key not set");
      }
      return header -> key;
    }
  }
}
