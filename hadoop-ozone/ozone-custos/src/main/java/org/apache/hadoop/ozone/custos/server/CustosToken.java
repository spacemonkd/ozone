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

import java.util.Objects;
import org.apache.hadoop.ozone.custos.CustosIdentity;

/**
 * A signed session token Custos issues after a provider authenticates a
 * credential. OM verifies {@link #getSerialized()} locally.
 *
 * <p>PoC placeholder until {@code CustosTokenProto} and HMAC signing land.
 * The serialized bytes must never be logged.
 */
public final class CustosToken {

  private final String tokenId;
  private final CustosIdentity identity;
  private final byte[] serialized;

  public CustosToken(String tokenId, CustosIdentity identity, byte[] serialized) {
    this.tokenId = Objects.requireNonNull(tokenId, "tokenId");
    this.identity = Objects.requireNonNull(identity, "identity");
    this.serialized = serialized == null ? new byte[0] : serialized.clone();
  }

  public String getTokenId() {
    return tokenId;
  }

  public CustosIdentity getIdentity() {
    return identity;
  }

  /**
   * @return the token bytes the client attaches to {@code OMRequest.custosToken}.
   */
  public byte[] getSerialized() {
    return serialized.clone();
  }

  @Override
  public String toString() {
    return "CustosToken{tokenId=" + tokenId
        + ", subject=" + identity.getSubject()
        + ", serializedLength=" + serialized.length + '}';
  }
}
