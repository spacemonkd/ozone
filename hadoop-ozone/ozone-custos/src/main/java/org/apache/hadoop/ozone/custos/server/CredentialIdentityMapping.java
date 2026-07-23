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

import org.apache.hadoop.ozone.custos.CredentialType;
import org.apache.hadoop.ozone.custos.CustosException;
import org.apache.hadoop.ozone.custos.identity.IdentityProviderType;

/**
 * Maps a validated {@link CredentialType} to the {@link IdentityProviderType}
 * that resolves the subject into a full identity for token binding.
 */
public final class CredentialIdentityMapping {

  private CredentialIdentityMapping() {
  }

  public static IdentityProviderType forCredential(CredentialType credentialType)
      throws CustosException {
    switch (credentialType) {
    case SPNEGO:
      return IdentityProviderType.KERBEROS;
    case OIDC_JWT:
      return IdentityProviderType.OIDC;
    case S3_SIGV4:
    case DELEGATION_TOKEN:
    default:
      throw new CustosException("No identity provider mapping for credential"
          + " type " + credentialType + ".");
    }
  }
}
