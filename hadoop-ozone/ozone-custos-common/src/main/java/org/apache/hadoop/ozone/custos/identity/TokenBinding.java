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

import java.util.Objects;

/**
 * Metadata applied when an {@link IdentityProvider} binds a
 * {@link org.apache.hadoop.ozone.custos.CustosIdentity} to a token.
 */
public final class TokenBinding {

  private final String tokenId;
  private final String audience;
  private final long issuedAtMs;
  private final long expiryMs;
  private final long maxLifetimeMs;
  private final boolean multiUse;

  public TokenBinding(String tokenId, String audience, long issuedAtMs,
      long expiryMs, long maxLifetimeMs, boolean multiUse) {
    this.tokenId = Objects.requireNonNull(tokenId, "tokenId");
    this.audience = audience;
    this.issuedAtMs = issuedAtMs;
    this.expiryMs = expiryMs;
    this.maxLifetimeMs = maxLifetimeMs;
    this.multiUse = multiUse;
  }

  public String getTokenId() {
    return tokenId;
  }

  public String getAudience() {
    return audience;
  }

  public long getIssuedAtMs() {
    return issuedAtMs;
  }

  public long getExpiryMs() {
    return expiryMs;
  }

  public long getMaxLifetimeMs() {
    return maxLifetimeMs;
  }

  public boolean isMultiUse() {
    return multiUse;
  }
}
