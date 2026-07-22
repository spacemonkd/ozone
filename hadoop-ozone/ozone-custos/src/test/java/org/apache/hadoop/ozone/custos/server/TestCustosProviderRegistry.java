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

import static org.apache.hadoop.ozone.custos.server.CustosConfigKeys.OZONE_CUSTOS_PROVIDERS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Collections;
import org.apache.hadoop.hdds.conf.OzoneConfiguration;
import org.apache.hadoop.ozone.custos.CredentialType;
import org.apache.hadoop.ozone.custos.CustosCredential;
import org.apache.hadoop.ozone.custos.CustosException;
import org.apache.hadoop.ozone.custos.CustosIdentity;
import org.junit.jupiter.api.Test;

/**
 * Tests config-driven provider loading and credential-type routing in
 * {@link CustosProviderRegistry}.
 */
class TestCustosProviderRegistry {

  private static CustosCredential credential(CredentialType type,
      String attrKey, String attrValue) {
    return new CustosCredential(type, new byte[0],
        Collections.singletonMap(attrKey, attrValue));
  }

  @Test
  void routesEachCredentialTypeToItsProvider() throws CustosException {
    OzoneConfiguration conf = new OzoneConfiguration();
    conf.set(OZONE_CUSTOS_PROVIDERS,
        StubOidcProvider.class.getName() + ","
            + StubS3Provider.class.getName());
    CustosProviderRegistry registry = new CustosProviderRegistry(conf);

    assertThat(registry.supportedTypes())
        .containsExactlyInAnyOrder(CredentialType.OIDC_JWT,
            CredentialType.S3_SIGV4);

    CustosIdentity oidc = registry.authenticate(
        credential(CredentialType.OIDC_JWT, "subject", "alice"));
    assertThat(oidc.getSubject()).isEqualTo("alice");
    assertThat(oidc.getAuthMethod()).isEqualTo("stub-oidc");

    CustosIdentity s3 = registry.authenticate(
        credential(CredentialType.S3_SIGV4, "accessId", "AKIA123"));
    assertThat(s3.getSubject()).isEqualTo("AKIA123");
    assertThat(s3.getAuthMethod()).isEqualTo("stub-s3");
  }

  @Test
  void emptyConfigLoadsNoProviders() {
    CustosProviderRegistry registry =
        new CustosProviderRegistry(new OzoneConfiguration());
    assertThat(registry.supportedTypes()).isEmpty();
  }

  @Test
  void unsupportedCredentialTypeIsRejected() {
    OzoneConfiguration conf = new OzoneConfiguration();
    conf.set(OZONE_CUSTOS_PROVIDERS, StubOidcProvider.class.getName());
    CustosProviderRegistry registry = new CustosProviderRegistry(conf);

    assertThatThrownBy(() -> registry.authenticate(
        credential(CredentialType.SPNEGO, "subject", "bob")))
        .isInstanceOf(CustosException.class)
        .hasMessageContaining("SPNEGO");
  }

  @Test
  void unknownProviderClassFailsWithClearError() {
    OzoneConfiguration conf = new OzoneConfiguration();
    conf.set(OZONE_CUSTOS_PROVIDERS, "org.apache.hadoop.ozone.custos.NoSuchProvider");

    assertThatThrownBy(() -> new CustosProviderRegistry(conf))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("NoSuchProvider");
  }

  @Test
  void twoProvidersForSameTypeFailStartup() {
    OzoneConfiguration conf = new OzoneConfiguration();
    conf.set(OZONE_CUSTOS_PROVIDERS,
        StubOidcProvider.class.getName() + ","
            + StubDuplicateOidcProvider.class.getName());

    assertThatThrownBy(() -> new CustosProviderRegistry(conf))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(CredentialType.OIDC_JWT.name());
  }
}
