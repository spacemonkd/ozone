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

/**
 * The Custos authentication plugin contract.
 *
 * <p>Each credential kind is validated by one provider. Custos loads providers
 * by class name from {@code ozone.custos.providers} and routes an incoming
 * {@link CustosCredential} to the provider whose {@link #supportedType()}
 * matches. A provider validates the credential and returns the authenticated
 * subject; {@link org.apache.hadoop.ozone.custos.identity.IdentityProvider}
 * resolves groups and binds the identity to a token.
 */
public interface CustosProvider {

  /**
   * @return the credential kind this provider validates.
   */
  CredentialType supportedType();

  /**
   * Validate the credential and return the authenticated subject (realm
   * stripped for Kerberos). Does not resolve groups.
   *
   * @param credential the client-presented credential
   * @return the authenticated subject name
   * @throws CustosException if the credential is invalid or cannot be validated
   */
  String validateSubject(CustosCredential credential) throws CustosException;

  /**
   * Validate the credential and return the authenticated subject together with
   * any claims extracted during validation (for example OIDC groups, roles, and
   * issuer). Providers whose credential also carries identity claims override
   * this; the default returns the subject alone and leaves group resolution to
   * an {@link org.apache.hadoop.ozone.custos.identity.IdentityProvider}.
   *
   * @param credential the client-presented credential
   * @return the authenticated subject and any extracted claims
   * @throws CustosException if the credential is invalid or cannot be validated
   */
  default CustosAuthResult authenticate(CustosCredential credential)
      throws CustosException {
    return CustosAuthResult.of(validateSubject(credential));
  }
}
