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

package org.apache.hadoop.ozone.custos;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * Tests the immutability and log-safety of the Custos SPI value types.
 */
class TestCustosTypes {

  @Test
  void credentialCopiesMaterialDefensively() {
    byte[] material = "secret".getBytes(StandardCharsets.UTF_8);
    CustosCredential credential =
        new CustosCredential(CredentialType.OIDC_JWT, material);

    material[0] = 'X';
    assertThat(credential.getMaterial())
        .isEqualTo("secret".getBytes(StandardCharsets.UTF_8));

    byte[] returned = credential.getMaterial();
    Arrays.fill(returned, (byte) 0);
    assertThat(credential.getMaterial())
        .isEqualTo("secret".getBytes(StandardCharsets.UTF_8));
  }

  @Test
  void credentialToStringDoesNotLeakMaterial() {
    CustosCredential credential = new CustosCredential(CredentialType.S3_SIGV4,
        "top-secret".getBytes(StandardCharsets.UTF_8),
        java.util.Collections.singletonMap("accessId", "AKIA123"));

    String printed = credential.toString();
    assertThat(printed).doesNotContain("top-secret");
    // Attribute values (which may be sensitive) are not printed, only keys.
    assertThat(printed).contains("accessId").doesNotContain("AKIA123");
  }

  @Test
  void identityBuilderProducesUnmodifiableGroups() {
    CustosIdentity identity = CustosIdentity.newBuilder()
        .setSubject("alice")
        .setGroups(Arrays.asList("g1", "g2"))
        .build();

    assertThat(identity.getSubject()).isEqualTo("alice");
    assertThat(identity.getGroups()).containsExactly("g1", "g2");
  }
}
