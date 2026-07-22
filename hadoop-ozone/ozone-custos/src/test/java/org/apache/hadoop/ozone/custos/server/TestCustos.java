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

import static org.apache.hadoop.ozone.custos.server.CustosConfigKeys.OZONE_CUSTOS_HTTP_BIND_HOST;
import static org.apache.hadoop.ozone.custos.server.CustosConfigKeys.OZONE_CUSTOS_HTTP_BIND_PORT;
import static org.apache.hadoop.ozone.custos.server.CustosConfigKeys.OZONE_CUSTOS_PROVIDERS;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import org.apache.hadoop.hdds.conf.OzoneConfiguration;
import org.apache.hadoop.ozone.custos.CredentialType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Boots the Custos service on an ephemeral port and checks it is reachable and
 * healthy, and that it loads the configured providers.
 */
class TestCustos {

  private final Custos custos = new Custos();

  @AfterEach
  void tearDown() {
    custos.stop();
  }

  private OzoneConfiguration newConf() {
    OzoneConfiguration conf = new OzoneConfiguration();
    conf.set(OZONE_CUSTOS_HTTP_BIND_HOST, "127.0.0.1");
    conf.setInt(OZONE_CUSTOS_HTTP_BIND_PORT, 0);
    return conf;
  }

  @Test
  void bootsAndAnswersHealthCheck() throws Exception {
    custos.start(newConf());

    InetSocketAddress address = custos.getHttpAddress();
    assertThat(address).isNotNull();
    assertThat(address.getPort()).isPositive();

    URL url = new URL("http", address.getHostString(), address.getPort(),
        "/health");
    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
    conn.setRequestMethod("GET");
    try {
      assertThat(conn.getResponseCode()).isEqualTo(200);
      try (BufferedReader reader = new BufferedReader(new InputStreamReader(
          conn.getInputStream(), StandardCharsets.UTF_8))) {
        assertThat(reader.readLine()).isEqualTo("OK");
      }
    } finally {
      conn.disconnect();
    }
  }

  @Test
  void loadsConfiguredProvidersOnBoot() throws Exception {
    OzoneConfiguration conf = newConf();
    conf.set(OZONE_CUSTOS_PROVIDERS, StubOidcProvider.class.getName());
    custos.start(conf);

    assertThat(custos.getProviderRegistry().supportedTypes())
        .containsExactly(CredentialType.OIDC_JWT);
  }
}
