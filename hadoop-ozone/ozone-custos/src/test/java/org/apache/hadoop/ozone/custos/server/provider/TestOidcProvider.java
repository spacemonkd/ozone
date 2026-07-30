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

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.Locator;
import java.math.BigInteger;
import java.security.Key;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import org.apache.hadoop.ozone.custos.CustosAuthResult;
import org.apache.hadoop.ozone.custos.CustosException;
import org.apache.hadoop.ozone.custos.server.provider.oidc.JwksKeyLocator;
import org.apache.hadoop.ozone.custos.server.provider.oidc.OidcJwtValidator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for OIDC JWT validation (the OidcProvider auth seam): signature,
 * issuer/audience/expiry, algorithm restriction, and claim mapping.
 */
class TestOidcProvider {

  private static final String ISSUER = "https://idp.example.com/realms/ozone";
  private static final String AUDIENCE = "om-service-1";

  private static KeyPair keyPair;
  private static KeyPair otherKeyPair;

  @BeforeAll
  static void generateKeys() throws Exception {
    KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
    gen.initialize(2048);
    keyPair = gen.generateKeyPair();
    otherKeyPair = gen.generateKeyPair();
  }

  private OidcJwtValidator validator(Locator<Key> locator) {
    return new OidcJwtValidator(locator, ISSUER, AUDIENCE, "sub", "groups",
        "roles", 30);
  }

  private Locator<Key> locatorFor(KeyPair kp) {
    return header -> kp.getPublic();
  }

  private String sign(PrivateKey key, String issuer, String audience,
      String subject, Object groups, Instant expiry) {
    return Jwts.builder()
        .header().keyId("k1").and()
        .issuer(issuer)
        .audience().add(audience).and()
        .subject(subject)
        .claim("groups", groups)
        .issuedAt(Date.from(Instant.now()))
        .expiration(Date.from(expiry))
        .signWith(key, Jwts.SIG.RS256)
        .compact();
  }

  @Test
  void validTokenIsAcceptedAndClaimsAreMapped() throws Exception {
    String jwt = sign(keyPair.getPrivate(), ISSUER, AUDIENCE, "alice",
        Arrays.asList("dev", "ops"), Instant.now().plusSeconds(300));

    CustosAuthResult result =
        validator(locatorFor(keyPair)).validate(jwt.getBytes(UTF_8));

    assertEquals("alice", result.getSubject());
    assertEquals(ISSUER, result.getIssuer());
    assertTrue(result.getClaimGroups().containsAll(Arrays.asList("dev", "ops")));
  }

  @Test
  void expiredTokenIsRejected() {
    String jwt = sign(keyPair.getPrivate(), ISSUER, AUDIENCE, "alice", null,
        Instant.now().minusSeconds(120));
    OidcJwtValidator v = validator(locatorFor(keyPair));
    assertThrows(CustosException.class, () -> v.validate(jwt.getBytes(UTF_8)));
  }

  @Test
  void wrongAudienceIsRejected() {
    String jwt = sign(keyPair.getPrivate(), ISSUER, "some-other-audience",
        "alice", null, Instant.now().plusSeconds(300));
    OidcJwtValidator v = validator(locatorFor(keyPair));
    assertThrows(CustosException.class, () -> v.validate(jwt.getBytes(UTF_8)));
  }

  @Test
  void wrongIssuerIsRejected() {
    String jwt = sign(keyPair.getPrivate(), "https://evil.example.com", AUDIENCE,
        "alice", null, Instant.now().plusSeconds(300));
    OidcJwtValidator v = validator(locatorFor(keyPair));
    assertThrows(CustosException.class, () -> v.validate(jwt.getBytes(UTF_8)));
  }

  @Test
  void badSignatureIsRejected() {
    // Signed by a different key than the locator resolves.
    String jwt = sign(otherKeyPair.getPrivate(), ISSUER, AUDIENCE, "alice", null,
        Instant.now().plusSeconds(300));
    OidcJwtValidator v = validator(locatorFor(keyPair));
    assertThrows(CustosException.class, () -> v.validate(jwt.getBytes(UTF_8)));
  }

  @Test
  void unsecuredAlgNoneIsRejected() {
    String header = b64url("{\"alg\":\"none\"}".getBytes(UTF_8));
    String payload = b64url(("{\"sub\":\"alice\",\"iss\":\"" + ISSUER
        + "\",\"aud\":\"" + AUDIENCE + "\"}").getBytes(UTF_8));
    String noneToken = header + "." + payload + ".";
    OidcJwtValidator v = validator(locatorFor(keyPair));
    assertThrows(CustosException.class,
        () -> v.validate(noneToken.getBytes(UTF_8)));
  }

  @Test
  void groupsAsDelimitedStringAreSplit() throws Exception {
    String jwt = sign(keyPair.getPrivate(), ISSUER, AUDIENCE, "alice",
        "dev ops", Instant.now().plusSeconds(300));
    List<String> groups =
        validator(locatorFor(keyPair)).validate(jwt.getBytes(UTF_8))
            .getClaimGroups();
    assertTrue(groups.containsAll(Arrays.asList("dev", "ops")));
  }

  @Test
  void jwksLocatorParsesKeysAndValidatesToken() throws Exception {
    RSAPublicKey pub = (RSAPublicKey) keyPair.getPublic();
    String jwks = "{\"keys\":[{\"kty\":\"RSA\",\"kid\":\"k1\",\"use\":\"sig\","
        + "\"alg\":\"RS256\",\"n\":\"" + b64url(unsigned(pub.getModulus()))
        + "\",\"e\":\"" + b64url(unsigned(pub.getPublicExponent())) + "\"}]}";

    Locator<Key> locator = new JwksKeyLocator("http://unused") {
      @Override
      protected String fetch() {
        return jwks;
      }
    };

    String jwt = sign(keyPair.getPrivate(), ISSUER, AUDIENCE, "alice",
        Arrays.asList("dev"), Instant.now().plusSeconds(300));

    CustosAuthResult result = validator(locator).validate(jwt.getBytes(UTF_8));
    assertEquals("alice", result.getSubject());
    assertTrue(result.getClaimGroups().contains("dev"));
  }

  private static String b64url(byte[] bytes) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private static byte[] unsigned(BigInteger value) {
    byte[] bytes = value.toByteArray();
    if (bytes.length > 1 && bytes[0] == 0) {
      byte[] trimmed = new byte[bytes.length - 1];
      System.arraycopy(bytes, 1, trimmed, 0, trimmed.length);
      return trimmed;
    }
    return bytes;
  }
}
