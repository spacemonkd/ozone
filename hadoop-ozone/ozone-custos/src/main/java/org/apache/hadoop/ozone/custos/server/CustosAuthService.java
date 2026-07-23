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

import java.util.UUID;
import org.apache.hadoop.ozone.custos.CustosCredential;
import org.apache.hadoop.ozone.custos.CustosException;
import org.apache.hadoop.ozone.custos.CustosIdentity;
import org.apache.hadoop.ozone.custos.identity.IdentityContext;
import org.apache.hadoop.ozone.custos.identity.IdentityProviderType;
import org.apache.hadoop.ozone.custos.identity.TokenBinding;
import org.apache.hadoop.ozone.custos.proto.CustosTokenProtos.CustosTokenProto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Orchestrates credential validation, identity resolution, and token binding.
 *
 * <p>For Kerberos: {@link CustosProviderRegistry} validates the SPNEGO token and
 * returns the subject; {@link IdentityProviderRegistry} resolves groups through
 * {@link org.apache.hadoop.ozone.custos.server.identity.KerberosIdentityProvider}
 * and binds the identity to a {@link CustosTokenProto}. Token signing is
 * applied in a later step.
 */
public class CustosAuthService {

  private static final Logger LOG = LoggerFactory.getLogger(CustosAuthService.class);

  private static final long DEFAULT_SESSION_TTL_MS = 24L * 60 * 60 * 1000;
  private static final long DEFAULT_MAX_LIFETIME_MS = 7L * 24 * 60 * 60 * 1000;

  private final CustosProviderRegistry providerRegistry;
  private final IdentityProviderRegistry identityProviderRegistry;

  public CustosAuthService(CustosProviderRegistry providerRegistry,
      IdentityProviderRegistry identityProviderRegistry) {
    this.providerRegistry = providerRegistry;
    this.identityProviderRegistry = identityProviderRegistry;
  }

  /**
   * Validate a credential, resolve identity, and return an unsigned session
   * token ready for signing.
   */
  public CustosTokenProto getSessionToken(CustosCredential credential,
      String audience, long requestedTtlMs) throws CustosException {
    String subject = providerRegistry.validateSubject(credential);
    IdentityProviderType identityType =
        CredentialIdentityMapping.forCredential(credential.getType());

    IdentityContext context = IdentityContext.newBuilder()
        .setProviderType(identityType)
        .setSubject(subject)
        .build();
    CustosIdentity identity = identityProviderRegistry.resolveIdentity(context);

    long sessionTtlMs = requestedTtlMs > 0 ? requestedTtlMs : DEFAULT_SESSION_TTL_MS;
    long issuedAtMs = System.currentTimeMillis();
    TokenBinding binding = new TokenBinding(
        UUID.randomUUID().toString(),
        audience,
        issuedAtMs,
        issuedAtMs + sessionTtlMs,
        issuedAtMs + DEFAULT_MAX_LIFETIME_MS,
        true);

    CustosTokenProto token = identityProviderRegistry
        .associateToToken(identity, identityType, binding)
        .build();
    LOG.debug("Issued unsigned session token {} for subject {}",
        token.getTokenId(), token.getSubject());
    return token;
  }

  /**
   * Extend {@code expiryMs} on a presented session token when it is still
   * within its maximum lifetime. Signing is applied in a later step.
   */
  public CustosTokenProto renewSessionToken(CustosTokenProto presented,
      long requestedTtlMs) throws CustosException {
    if (presented == null || !presented.hasTokenId()) {
      throw new CustosException("Cannot renew a token without a token id.");
    }
    if (!presented.hasIssuedAtMs() || !presented.hasMaxLifetimeMs()) {
      throw new CustosException("Cannot renew a token without lifetime metadata.");
    }
    long now = System.currentTimeMillis();
    if (now > presented.getIssuedAtMs() + presented.getMaxLifetimeMs()) {
      throw new CustosException("Session token " + presented.getTokenId()
          + " is past its maximum lifetime.");
    }
    long sessionTtlMs = requestedTtlMs > 0 ? requestedTtlMs : DEFAULT_SESSION_TTL_MS;
    CustosTokenProto renewed = presented.toBuilder()
        .setExpiryMs(now + sessionTtlMs)
        .build();
    LOG.debug("Renewed session token {} until {}", renewed.getTokenId(),
        renewed.getExpiryMs());
    return renewed;
  }
}
