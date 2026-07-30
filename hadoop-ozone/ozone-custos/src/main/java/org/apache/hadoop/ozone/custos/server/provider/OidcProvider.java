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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Locator;
import java.io.IOException;
import java.security.Key;
import org.apache.hadoop.ozone.custos.CredentialType;
import org.apache.hadoop.ozone.custos.CustosAuthResult;
import org.apache.hadoop.ozone.custos.CustosCredential;
import org.apache.hadoop.ozone.custos.CustosException;
import org.apache.hadoop.ozone.custos.server.CustosConfig;
import org.apache.hadoop.ozone.custos.server.provider.oidc.JwksKeyLocator;
import org.apache.hadoop.ozone.custos.server.provider.oidc.OidcHttp;
import org.apache.hadoop.ozone.custos.server.provider.oidc.OidcJwtValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * OIDC authentication provider: validates an OIDC JWT (presented by the client
 * as an {@code OIDC_JWT} credential) against the issuer's JWKS and maps its
 * claims to the authenticated subject, groups, and roles.
 *
 * <p>The JWKS URL is taken from {@code ozone.custos.oidc.jwks-uri}, or
 * discovered from {@code {issuer}/.well-known/openid-configuration} when not
 * set. The signing keys and JWT parser are built lazily on first use, so the
 * Custos service starts even if the IdP is momentarily unreachable.
 */
public class OidcProvider extends AbstractCustosProvider {

  private static final Logger LOG =
      LoggerFactory.getLogger(OidcProvider.class);
  private static final int DISCOVERY_TIMEOUT_MS = 10_000;

  private String issuer;
  private String audience;
  private String configuredJwksUri;
  private String usernameClaim;
  private String groupsClaim;
  private String rolesClaim;
  private long clockSkewSeconds;

  private volatile OidcJwtValidator validator;

  @Override
  public CredentialType supportedType() {
    return CredentialType.OIDC_JWT;
  }

  @Override
  protected void init() {
    issuer = getConf().get(CustosConfig.Keys.OIDC_ISSUER,
        CustosConfig.Defaults.OIDC_ISSUER);
    audience = getConf().get(CustosConfig.Keys.OIDC_AUDIENCE,
        CustosConfig.Defaults.OIDC_AUDIENCE);
    configuredJwksUri = getConf().get(CustosConfig.Keys.OIDC_JWKS_URI,
        CustosConfig.Defaults.OIDC_JWKS_URI);
    usernameClaim = getConf().get(CustosConfig.Keys.OIDC_USERNAME_CLAIM,
        CustosConfig.Defaults.OIDC_USERNAME_CLAIM);
    groupsClaim = getConf().get(CustosConfig.Keys.OIDC_GROUPS_CLAIM,
        CustosConfig.Defaults.OIDC_GROUPS_CLAIM);
    rolesClaim = getConf().get(CustosConfig.Keys.OIDC_ROLES_CLAIM,
        CustosConfig.Defaults.OIDC_ROLES_CLAIM);
    clockSkewSeconds = getConf().getLong(CustosConfig.Keys.OIDC_CLOCK_SKEW_SECONDS,
        CustosConfig.Defaults.OIDC_CLOCK_SKEW_SECONDS);
    LOG.info("OIDC provider configured: issuer='{}', audience='{}', "
        + "usernameClaim='{}', groupsClaim='{}'", issuer, audience,
        usernameClaim, groupsClaim);
  }

  @Override
  public String validateSubject(CustosCredential credential)
      throws CustosException {
    return authenticate(credential).getSubject();
  }

  @Override
  public CustosAuthResult authenticate(CustosCredential credential)
      throws CustosException {
    if (credential.getType() != CredentialType.OIDC_JWT) {
      throw new CustosException("OidcProvider cannot handle credential type "
          + credential.getType());
    }
    return validator().validate(credential.getMaterial());
  }

  private OidcJwtValidator validator() throws CustosException {
    OidcJwtValidator local = validator;
    if (local == null) {
      synchronized (this) {
        local = validator;
        if (local == null) {
          local = new OidcJwtValidator(createKeyLocator(), issuer, audience,
              usernameClaim, groupsClaim, rolesClaim, clockSkewSeconds);
          validator = local;
          LOG.info("OIDC provider initialized");
        }
      }
    }
    return local;
  }

  /**
   * Create the signing-key source. Overridable so tests can supply an in-memory
   * JWKS without a network call.
   */
  protected Locator<Key> createKeyLocator() throws CustosException {
    String jwksUri = resolveJwksUri();
    LOG.info("OIDC provider resolving signing keys from JWKS {}", jwksUri);
    return new JwksKeyLocator(jwksUri);
  }

  private String resolveJwksUri() throws CustosException {
    if (configuredJwksUri != null && !configuredJwksUri.isEmpty()) {
      return configuredJwksUri;
    }
    if (issuer == null || issuer.isEmpty()) {
      throw new CustosException("OIDC provider needs "
          + CustosConfig.Keys.OIDC_JWKS_URI + " or "
          + CustosConfig.Keys.OIDC_ISSUER + " to be configured.");
    }
    String base = issuer.endsWith("/")
        ? issuer.substring(0, issuer.length() - 1) : issuer;
    String discoveryUrl = base + "/.well-known/openid-configuration";
    try {
      JsonNode node = new ObjectMapper()
          .readTree(OidcHttp.get(discoveryUrl, DISCOVERY_TIMEOUT_MS));
      JsonNode jwks = node.get("jwks_uri");
      if (jwks == null || jwks.asText().isEmpty()) {
        throw new CustosException("OpenID configuration at " + discoveryUrl
            + " does not advertise a jwks_uri.");
      }
      return jwks.asText();
    } catch (IOException e) {
      throw new CustosException("Failed to read OpenID configuration from "
          + discoveryUrl, e);
    }
  }
}
