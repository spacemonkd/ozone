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

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import org.apache.hadoop.ozone.custos.CustosException;
import org.apache.hadoop.ozone.custos.CustosIdentity;
import org.apache.hadoop.ozone.custos.proto.CustosTokenProtos.CustosTokenProto;

/**
 * Base class for {@link IdentityProvider} implementations.
 *
 * <p>Subclasses implement {@link #resolveGroups(IdentityContext)} for their
 * backend (OIDC claims, LDAP lookup, Hadoop group mapping, ...). The base class
 * builds a {@link CustosIdentity} from the result and maps it onto a
 * {@link CustosTokenProto} in {@link #associateToToken}.
 */
public abstract class AbstractIdentityProvider implements IdentityProvider {

  @Override
  public CustosIdentity resolveIdentity(IdentityContext context)
      throws CustosException {
    if (context.getSubject() == null || context.getSubject().isEmpty()) {
      throw new CustosException("Identity subject must not be empty.");
    }
    List<String> groups = resolveGroups(context);
    List<String> roles = resolveRoles(context);
    Instant now = Instant.now();
    return CustosIdentity.newBuilder()
        .setSubject(context.getSubject())
        .setGroups(groups)
        .setRoles(roles)
        .setIssuer(context.getIssuer())
        .setAuthMethod(supportedType().name())
        .setAuthenticatedAt(now)
        .build();
  }

  @Override
  public CustosTokenProto.Builder associateToToken(CustosIdentity identity,
      TokenBinding binding) throws CustosException {
    if (identity == null) {
      throw new CustosException("Cannot bind a token without an identity.");
    }
    if (binding == null) {
      throw new CustosException("Cannot bind a token without binding metadata.");
    }
    CustosTokenProto.Builder builder = CustosTokenProto.newBuilder()
        .setSubject(identity.getSubject())
        .setAuthProvider(identity.getAuthMethod())
        .setTokenId(binding.getTokenId())
        .setAudience(binding.getAudience())
        .setIssuedAtMs(binding.getIssuedAtMs())
        .setExpiryMs(binding.getExpiryMs())
        .setMaxLifetimeMs(binding.getMaxLifetimeMs())
        .setMultiUse(binding.isMultiUse())
        .addAllGroups(identity.getGroups());
    if (identity.getIssuer() != null) {
      builder.setIssuer(identity.getIssuer());
    }
    return builder;
  }

  /**
   * Resolve group membership for the subject. The default uses claim groups
   * from the context when present; subclasses override to consult LDAP, OIDC
   * userinfo, or the Hadoop group mapping.
   */
  protected List<String> resolveGroups(IdentityContext context)
      throws CustosException {
    if (context.getClaimGroups() != null && !context.getClaimGroups().isEmpty()) {
      return context.getClaimGroups();
    }
    return Collections.emptyList();
  }

  /**
   * Resolve roles for the subject. The default returns claim roles from the
   * context when present.
   */
  protected List<String> resolveRoles(IdentityContext context) {
    if (context.getClaimRoles() != null && !context.getClaimRoles().isEmpty()) {
      return context.getClaimRoles();
    }
    return Collections.emptyList();
  }
}
