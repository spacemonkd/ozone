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

package org.apache.hadoop.ozone.custos.server.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Collections;
import org.apache.hadoop.hdds.conf.OzoneConfiguration;
import org.apache.hadoop.ozone.custos.CredentialType;
import org.apache.hadoop.ozone.custos.CustosCredential;
import org.apache.hadoop.ozone.custos.CustosException;
import org.apache.hadoop.ozone.custos.KerberosCredentials;
import org.apache.hadoop.ozone.custos.server.CustosConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TestKerberosProvider {

  private KerberosProvider provider;

  @BeforeEach
  void setUp() {
    OzoneConfiguration conf = new OzoneConfiguration();
    conf.set(CustosConfig.Keys.KERBEROS_KEYTAB, "/etc/security/custos.keytab");
    provider = new KerberosProvider();
    provider.setConf(conf);
  }

  @Test
  void supportsSpnego() {
    assertThat(provider.supportedType()).isEqualTo(CredentialType.SPNEGO);
  }

  @Test
  void rejectsNonSpnegoCredential() {
    CustosCredential wrong =
        new CustosCredential(CredentialType.OIDC_JWT, new byte[] {1, 2, 3});
    assertThatThrownBy(() -> provider.validateSubject(wrong))
        .isInstanceOf(CustosException.class)
        .hasMessageContaining("cannot handle");
  }

  @Test
  void requiresServicePrincipalFromClient() {
    CustosCredential spnego =
        new CustosCredential(CredentialType.SPNEGO, new byte[] {1, 2, 3, 4});
    assertThatThrownBy(() -> provider.validateSubject(spnego))
        .isInstanceOf(CustosException.class)
        .hasMessageContaining(KerberosCredentials.SERVICE_PRINCIPAL_ATTRIBUTE);
  }

  @Test
  void spnegoValidationFailsWithoutValidKeytab() {
    CustosCredential spnego = new CustosCredential(CredentialType.SPNEGO,
        new byte[] {1, 2, 3, 4},
        Collections.singletonMap(KerberosCredentials.SERVICE_PRINCIPAL_ATTRIBUTE,
            "HTTP/custos.example.com@EXAMPLE.COM"));
    assertThatThrownBy(() -> provider.validateSubject(spnego))
        .isInstanceOf(CustosException.class);
  }
}
