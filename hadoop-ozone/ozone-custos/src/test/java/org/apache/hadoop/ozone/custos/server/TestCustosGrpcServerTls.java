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

import static org.apache.hadoop.hdds.HddsConfigKeys.HDDS_GRPC_TLS_ENABLED;
import static org.apache.hadoop.hdds.HddsConfigKeys.OZONE_METADATA_DIRS;
import static org.apache.hadoop.ozone.OzoneConfigKeys.OZONE_SECURITY_ENABLED_KEY;
import static org.apache.hadoop.ozone.custos.server.CustosConfig.Keys.IDENTITY_PROVIDERS;
import static org.apache.hadoop.ozone.custos.server.CustosConfig.Keys.PROVIDERS;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.util.Collections;
import org.apache.hadoop.hdds.conf.OzoneConfiguration;
import org.apache.hadoop.hdds.security.SecurityConfig;
import org.apache.hadoop.hdds.security.x509.certificate.client.CertificateClientTestImpl;
import org.apache.hadoop.ozone.custos.CredentialType;
import org.apache.hadoop.ozone.custos.CustosCredential;
import org.apache.hadoop.ozone.custos.client.CustosGrpcClient;
import org.apache.hadoop.ozone.custos.proto.CustosTokenProtos.CustosTokenProto;
import org.apache.hadoop.ozone.custos.server.identity.OidcIdentityProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end TLS test: with security and {@code hdds.grpc.tls.enabled} on, the
 * Custos gRPC server presents its (test) SCM-issued certificate and a client
 * trusting the CA completes {@code GetSessionToken} over TLS. The test cert's
 * subject is {@code localhost}, so the client dials {@code localhost}.
 */
class TestCustosGrpcServerTls {

  @Test
  void getSessionTokenOverTls(@TempDir Path metaDir) throws Exception {
    OzoneConfiguration conf = new OzoneConfiguration();
    conf.setBoolean(OZONE_SECURITY_ENABLED_KEY, true);
    conf.setBoolean(HDDS_GRPC_TLS_ENABLED, true);
    conf.set(OZONE_METADATA_DIRS, metaDir.toFile().getAbsolutePath());
    conf.set(PROVIDERS, StubOidcProvider.class.getName());
    conf.set(IDENTITY_PROVIDERS, OidcIdentityProvider.class.getName());

    SecurityConfig securityConfig = new SecurityConfig(conf);
    CertificateClientTestImpl certClient = new CertificateClientTestImpl(conf);

    CustosAuthService authService = new CustosAuthService(
        new CustosProviderRegistry(conf), new IdentityProviderRegistry(conf));
    CustosGrpcServer server = new CustosGrpcServer(
        new InetSocketAddress("localhost", 0), authService,
        Collections.emptyList(), "", securityConfig, certClient);
    server.start();

    int port = server.getListenAddress().getPort();
    X509Certificate caCert = certClient.getCACertificate();
    CustosCredential credential = new CustosCredential(CredentialType.OIDC_JWT,
        new byte[] {1, 2, 3}, Collections.singletonMap("subject", "alice"));

    try (CustosGrpcClient client = new CustosGrpcClient("localhost", port,
        10_000L, 5_000L, Collections.singletonList(caCert))) {
      CustosTokenProto token =
          client.getSessionToken(credential, "om-service-1", 60_000L);
      assertThat(token.getSubject()).isEqualTo("alice");
    } finally {
      server.stop();
    }
  }
}
