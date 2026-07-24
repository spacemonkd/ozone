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

package org.apache.hadoop.ozone.custos.token;

import org.apache.hadoop.ozone.custos.CustosException;
import org.apache.hadoop.ozone.custos.proto.CustosTokenProtos.CustosTokenProto;

/**
 * Signs and verifies a {@link CustosTokenProto}.
 *
 * <p>Custos signs a token before returning it from
 * {@code GetSessionToken}; OM verifies the signature locally before trusting
 * the identity it carries. The signing input excludes the signature-related
 * fields (see {@link #computeSigningInput(CustosTokenProto)}) so signing and
 * verification operate over the same bytes.
 *
 * <p>The implementation is {@code SecretKeySignedTokenSigner}, which signs and
 * verifies with the HMAC keys managed and rotated by SCM and distributed
 * through the {@code SecretKeyClient} that OM and delegation tokens already
 * use, so no key is shared through configuration.
 */
public interface CustosTokenSigner {

  /**
   * Return a copy of {@code token} with the signature, signature algorithm, and
   * signing key id fields populated.
   */
  CustosTokenProto sign(CustosTokenProto token) throws CustosException;

  /**
   * Verify the signature on {@code token}. Returns {@code false} when the token
   * carries no signature or the signature does not match.
   */
  boolean verify(CustosTokenProto token) throws CustosException;

  /**
   * Serialize {@code token} with the signature-related fields cleared, so the
   * signer and verifier compute over identical bytes regardless of any
   * signature already present.
   */
  static byte[] computeSigningInput(CustosTokenProto token) {
    return token.toBuilder()
        .clearSignature()
        .clearSignatureAlgorithm()
        .clearSigningKeyId()
        .build()
        .toByteArray();
  }
}
