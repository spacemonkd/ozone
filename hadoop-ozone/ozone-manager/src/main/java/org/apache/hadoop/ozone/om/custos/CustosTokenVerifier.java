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

package org.apache.hadoop.ozone.om.custos;

import java.util.UUID;
import org.apache.hadoop.hdds.security.exception.SCMSecurityException;
import org.apache.hadoop.hdds.security.symmetric.ManagedSecretKey;
import org.apache.hadoop.hdds.security.symmetric.SecretKeyClient;
import org.apache.hadoop.ozone.custos.proto.CustosTokenProtos.CustosTokenProto;
import org.apache.hadoop.ozone.custos.token.CustosTokenSigner;
import org.apache.hadoop.ozone.om.exceptions.OMException;
import org.apache.hadoop.ozone.om.exceptions.OMException.ResultCodes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Verifies a {@link CustosTokenProto} on the OM side using the SCM-managed
 * secret keys shared with the Custos auth service.
 *
 * <p>Verification is local: the signing key is looked up from SCM by
 * {@code signingKeyId} (the same path delegation tokens use) and the HMAC is
 * checked over {@link CustosTokenSigner#computeSigningInput}. No call is made
 * to the Custos service.
 */
public final class CustosTokenVerifier {

  private static final Logger LOG =
      LoggerFactory.getLogger(CustosTokenVerifier.class);

  private final SecretKeyClient secretKeyClient;

  public CustosTokenVerifier(SecretKeyClient secretKeyClient) {
    this.secretKeyClient = secretKeyClient;
  }

  /**
   * Verify the signature and expiry of a Custos token.
   *
   * @throws OMException with {@link ResultCodes#INVALID_CUSTOS_TOKEN} if the
   *     token is unsigned, its signature does not match, its signing key is
   *     unknown, or it has expired.
   */
  public void verify(CustosTokenProto token) throws OMException {
    if (secretKeyClient == null) {
      throw new OMException("Cannot verify Custos token: no SCM secret key "
          + "client is available (is security enabled?)",
          ResultCodes.INVALID_CUSTOS_TOKEN);
    }
    if (!token.hasSigningKeyId() || !token.hasSignature()
        || token.getSignature().isEmpty()) {
      throw new OMException("Custos token " + token.getTokenId()
          + " is not signed", ResultCodes.INVALID_CUSTOS_TOKEN);
    }

    ManagedSecretKey signingKey;
    try {
      signingKey = secretKeyClient.getSecretKey(
          UUID.fromString(token.getSigningKeyId()));
    } catch (SCMSecurityException e) {
      throw new OMException("Failed to look up Custos token signing key "
          + token.getSigningKeyId(), e, ResultCodes.INVALID_CUSTOS_TOKEN);
    }
    if (signingKey == null || !signingKey.isValidSignature(
        CustosTokenSigner.computeSigningInput(token),
        token.getSignature().toByteArray())) {
      throw new OMException("Custos token " + token.getTokenId()
          + " has an invalid signature", ResultCodes.INVALID_CUSTOS_TOKEN);
    }

    if (token.hasExpiryMs()
        && token.getExpiryMs() < System.currentTimeMillis()) {
      throw new OMException("Custos token " + token.getTokenId()
          + " has expired", ResultCodes.INVALID_CUSTOS_TOKEN);
    }

    LOG.debug("Verified Custos token {} for subject {} (groups={})",
        token.getTokenId(), token.getSubject(), token.getGroupsList());
  }
}
