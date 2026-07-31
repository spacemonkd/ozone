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
import static org.apache.hadoop.ozone.custos.server.CustosConfig.Keys.PROVIDERS;
import static org.apache.hadoop.ozone.custos.server.CustosConfig.Keys.TOKEN_AUDIENCE;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetSocketAddress;
import java.util.Collections;
import org.apache.hadoop.hdds.conf.OzoneConfiguration;
import org.apache.hadoop.ozone.custos.CredentialType;
import org.apache.hadoop.ozone.custos.CustosCredential;
import org.apache.hadoop.ozone.custos.client.CustosGrpcClient;
import org.apache.hadoop.ozone.custos.identity.IdentityProviderType;
import org.apache.hadoop.ozone.custos.proto.CustosTokenProtos.CustosTokenProto;
import org.apache.hadoop.ozone.custos.server.identity.OidcIdentityProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class TestCustosGrpcClient {

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
  void getSessionTokenOverGrpc() throws Exception {
    OzoneConfiguration conf = newConf();
    conf.set(PROVIDERS, StubOidcProvider.class.getName());
    conf.set(IDENTITY_PROVIDERS, OidcIdentityProvider.class.getName());
    custos.start(conf);

    InetSocketAddress grpcAddress = custos.getGrpcAddress();
    assertThat(grpcAddress).isNotNull();
    assertThat(grpcAddress.getPort()).isPositive();

    CustosCredential credential = new CustosCredential(CredentialType.OIDC_JWT,
        new byte[] {1, 2, 3},
        Collections.singletonMap("subject", "alice"));

    try (CustosGrpcClient client = new CustosGrpcClient(
        grpcAddress.getHostString(), grpcAddress.getPort(), 10_000L)) {
      CustosTokenProto token = client.getSessionToken(credential, "om1", 60_000L);
      assertThat(token.getSubject()).isEqualTo("alice");
      assertThat(token.getAuthProvider()).isEqualTo(IdentityProviderType.OIDC.name());
      assertThat(token.getAudience()).isEqualTo("om1");
      assertThat(token.getExpiryMs()).isPositive();
    }
  }

  @Test
  void getClusterInfoReturnsOmEndpointAndAudience() throws Exception {
    OzoneConfiguration conf = newConf();
    conf.set(PROVIDERS, StubOidcProvider.class.getName());
    conf.set(IDENTITY_PROVIDERS, OidcIdentityProvider.class.getName());
    conf.set("ozone.om.address", "myom");
    conf.set(TOKEN_AUDIENCE, "om-service-1");
    custos.start(conf);

    InetSocketAddress grpcAddress = custos.getGrpcAddress();
    try (CustosGrpcClient client = new CustosGrpcClient(
        grpcAddress.getHostString(), grpcAddress.getPort(), 10_000L)) {
      CustosGrpcClient.ClusterInfo info = client.getClusterInfo();
      // Host from ozone.om.address, port is OM's gRPC port (default 8981).
      assertThat(info.getOmGrpcAddresses()).containsExactly("myom:8981");
      assertThat(info.getAudience()).isEqualTo("om-service-1");
    }
  }

  @Test
  void emptyAudienceDefaultsToConfiguredAudience() throws Exception {
    OzoneConfiguration conf = newConf();
    conf.set(PROVIDERS, StubOidcProvider.class.getName());
    conf.set(IDENTITY_PROVIDERS, OidcIdentityProvider.class.getName());
    conf.set(TOKEN_AUDIENCE, "om-service-1");
    custos.start(conf);

    InetSocketAddress grpcAddress = custos.getGrpcAddress();
    CustosCredential credential = new CustosCredential(CredentialType.OIDC_JWT,
        new byte[] {1}, Collections.singletonMap("subject", "alice"));
    try (CustosGrpcClient client = new CustosGrpcClient(
        grpcAddress.getHostString(), grpcAddress.getPort(), 10_000L)) {
      // Client omits the audience; Custos binds the token to the configured one.
      CustosTokenProto token = client.getSessionToken(credential, "", 60_000L);
      assertThat(token.getAudience()).isEqualTo("om-service-1");
    }
  }
}
