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

import io.jsonwebtoken.Header;
import io.jsonwebtoken.Locator;
import io.jsonwebtoken.security.Jwk;
import io.jsonwebtoken.security.JwkSet;
import io.jsonwebtoken.security.Jwks;
import java.io.IOException;
import java.security.Key;
import java.security.interfaces.RSAPublicKey;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.hadoop.ozone.custos.CustosException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Resolves an OIDC signing key from a JWKS endpoint by the JWT's {@code kid}.
 *
 * <p>The JWKS document is fetched once at construction and cached as a
 * {@code kid → RSA public key} map. If a token names a {@code kid} not in the
 * cache — typically after the IdP rotates keys — the document is re-fetched
 * once and the lookup retried.
 */
public class JwksKeyLocator implements Locator<Key> {

  private static final Logger LOG =
      LoggerFactory.getLogger(JwksKeyLocator.class);
  private static final int TIMEOUT_MS = 10_000;

  private final String jwksUri;
  private final AtomicReference<Map<String, Key>> cache =
      new AtomicReference<>(Collections.emptyMap());

  public JwksKeyLocator(String jwksUri) throws CustosException {
    if (jwksUri == null || jwksUri.isEmpty()) {
      throw new CustosException("JWKS URI must not be empty.");
    }
    this.jwksUri = jwksUri;
    // Keys are fetched lazily on first use (see locate) so the Custos service
    // starts even when the IdP is momentarily unreachable.
  }

  @Override
  public Key locate(Header header) {
    String kid = header.containsKey("kid") ? String.valueOf(header.get("kid"))
        : null;
    Key key = lookup(kid);
    if (key == null) {
      try {
        refresh();
      } catch (CustosException e) {
        throw new IllegalStateException("Failed to refresh JWKS from " + jwksUri
            + " while resolving kid=" + kid, e);
      }
      key = lookup(kid);
    }
    if (key == null) {
      throw new IllegalStateException("No JWKS signing key found for kid=" + kid
          + " at " + jwksUri);
    }
    return key;
  }

  private Key lookup(String kid) {
    Map<String, Key> keys = cache.get();
    if (kid != null) {
      return keys.get(kid);
    }
    // A token without a kid is unambiguous only when the set has one key.
    return keys.size() == 1 ? keys.values().iterator().next() : null;
  }

  private void refresh() throws CustosException {
    JwkSet set = Jwks.setParser().build().parse(fetch());
    Map<String, Key> keys = new HashMap<>();
    for (Jwk<?> jwk : set.getKeys()) {
      Key key = jwk.toKey();
      if (key instanceof RSAPublicKey) {
        keys.put(jwk.getId(), key);
      }
    }
    if (keys.isEmpty()) {
      throw new CustosException("JWKS at " + jwksUri
          + " contains no RSA public keys.");
    }
    LOG.info("Loaded {} RSA signing key(s) from JWKS {}", keys.size(), jwksUri);
    cache.set(Collections.unmodifiableMap(keys));
  }

  /**
   * Fetch the raw JWKS document. Overridable so tests can supply a document
   * without a network call.
   */
  protected String fetch() throws CustosException {
    try {
      return OidcHttp.get(jwksUri, TIMEOUT_MS);
    } catch (IOException e) {
      throw new CustosException("Failed to fetch JWKS from " + jwksUri, e);
    }
  }
}
