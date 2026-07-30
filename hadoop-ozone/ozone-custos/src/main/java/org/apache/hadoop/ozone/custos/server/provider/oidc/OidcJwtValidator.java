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

package org.apache.hadoop.ozone.custos.server.provider.oidc;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.JwtParserBuilder;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.Locator;
import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.apache.hadoop.ozone.custos.CustosAuthResult;
import org.apache.hadoop.ozone.custos.CustosException;

/**
 * Validates an OIDC JWT and maps its claims to a {@link CustosAuthResult}.
 *
 * <p>Verification is done with JJWT: the RSA signature is checked against the
 * key the {@link Locator} resolves by {@code kid}, and {@code iss}/{@code exp}
 * (with clock skew) are enforced by the parser. {@code aud} is checked here, and
 * only RSA algorithms (RS256/RS384/RS512) are accepted — {@code alg=none} is
 * rejected because an unsecured JWT never parses as a signed one.
 */
public class OidcJwtValidator {

  private static final Set<String> ALLOWED_ALGS = Collections.unmodifiableSet(
      new HashSet<>(Arrays.asList("RS256", "RS384", "RS512")));

  private final JwtParser parser;
  private final String audience;
  private final String usernameClaim;
  private final String groupsClaim;
  private final String rolesClaim;

  public OidcJwtValidator(Locator<Key> keyLocator, String issuer, String audience,
      String usernameClaim, String groupsClaim, String rolesClaim,
      long clockSkewSeconds) {
    this.audience = audience;
    this.usernameClaim = usernameClaim;
    this.groupsClaim = groupsClaim;
    this.rolesClaim = rolesClaim;
    JwtParserBuilder builder = Jwts.parser()
        .keyLocator(keyLocator)
        .clockSkewSeconds(clockSkewSeconds);
    if (issuer != null && !issuer.isEmpty()) {
      builder.requireIssuer(issuer);
    }
    this.parser = builder.build();
  }

  /**
   * Validate a raw JWT (UTF-8 bytes) and return the authenticated subject and
   * claims.
   *
   * @throws CustosException if the token is unsigned, uses a non-RSA algorithm,
   *     has a bad signature, is expired, or fails the issuer/audience checks.
   */
  public CustosAuthResult validate(byte[] jwtBytes) throws CustosException {
    String jwt = new String(jwtBytes, StandardCharsets.UTF_8).trim();
    final Jws<Claims> jws;
    try {
      jws = parser.parseSignedClaims(jwt);
    } catch (JwtException | IllegalArgumentException e) {
      throw new CustosException("OIDC token validation failed: "
          + e.getMessage(), e);
    }

    String alg = jws.getHeader().getAlgorithm();
    if (!ALLOWED_ALGS.contains(alg)) {
      throw new CustosException("OIDC token uses unsupported algorithm '" + alg
          + "'; only RS256/RS384/RS512 are accepted.");
    }

    Claims claims = jws.getPayload();
    if (audience != null && !audience.isEmpty()) {
      Set<String> aud = claims.getAudience();
      if (aud == null || !aud.contains(audience)) {
        throw new CustosException("OIDC token audience " + aud + " does not "
            + "include the expected audience '" + audience + "'.");
      }
    }

    String subject = claimAsString(claims.get(usernameClaim));
    if (subject == null || subject.isEmpty()) {
      throw new CustosException("OIDC token is missing the username claim '"
          + usernameClaim + "'.");
    }

    return CustosAuthResult.newBuilder()
        .setSubject(subject)
        .setClaimGroups(claimAsList(claims.get(groupsClaim)))
        .setClaimRoles(claimAsList(claims.get(rolesClaim)))
        .setIssuer(claims.getIssuer())
        .build();
  }

  private static String claimAsString(Object value) {
    return value == null ? null : String.valueOf(value);
  }

  /**
   * A claim may be a JSON array (groups/roles), or a single or
   * whitespace/comma-delimited string. Normalize both to a list.
   */
  private static List<String> claimAsList(Object value) {
    List<String> result = new ArrayList<>();
    if (value == null) {
      return result;
    }
    if (value instanceof Collection) {
      for (Object o : (Collection<?>) value) {
        if (o != null) {
          result.add(String.valueOf(o));
        }
      }
    } else {
      String s = String.valueOf(value).trim();
      if (!s.isEmpty()) {
        for (String part : s.split("[\\s,]+")) {
          if (!part.isEmpty()) {
            result.add(part);
          }
        }
      }
    }
    return result;
  }
}
