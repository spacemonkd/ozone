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

package org.apache.hadoop.ozone.custos.identity;

import org.apache.hadoop.ozone.custos.CustosException;
import org.apache.hadoop.ozone.custos.CustosIdentity;
import org.apache.hadoop.ozone.custos.proto.CustosTokenProtos.CustosTokenProto;

/**
 * Resolves an authenticated subject into the {@link CustosIdentity} that is
 * bound to a {@link CustosTokenProto}.
 *
 * <p>Authentication providers validate credentials; identity providers map the
 * resulting subject to groups, roles, and issuer through a backend such as
 * OIDC, LDAP, or the local Hadoop group mapping. Custos loads implementations
 * by class name from {@code ozone.custos.identity.providers}.
 */
public interface IdentityProvider {

  /**
   * @return the identity backend this provider implements.
   */
  IdentityProviderType supportedType();

  /**
   * Resolve the full identity for the authenticated subject in {@code context}.
   *
   * @param context subject and claims from the authentication step
   * @return the identity to associate with a CustosToken
   * @throws CustosException if the subject cannot be resolved
   */
  CustosIdentity resolveIdentity(IdentityContext context)
      throws CustosException;

  /**
   * Associate a resolved identity with a {@link CustosTokenProto}. Signing is
   * applied separately once the token fields are populated.
   *
   * @param identity the verified identity
   * @param binding audience, expiry, and token metadata
   * @return a token builder with identity fields set; signature fields unset
   * @throws CustosException if the identity cannot be bound
   */
  CustosTokenProto.Builder associateToToken(CustosIdentity identity,
      TokenBinding binding) throws CustosException;
}
