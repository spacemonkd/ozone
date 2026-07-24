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

import com.google.protobuf.ByteString;
import java.util.UUID;
import org.apache.hadoop.hdds.security.exception.SCMSecurityException;
import org.apache.hadoop.hdds.security.symmetric.ManagedSecretKey;
import org.apache.hadoop.hdds.security.symmetric.SecretKeyClient;
import org.apache.hadoop.ozone.custos.CustosException;
import org.apache.hadoop.ozone.custos.proto.CustosTokenProtos.CustosTokenProto;
import org.apache.hadoop.ozone.custos.token.CustosTokenSigner;

/**
 * {@link CustosTokenSigner} backed by the SCM-managed symmetric keys that
 * delegation tokens already use.
 *
 * <p>Signing uses the current SCM secret key and stamps its id into
 * {@code signingKeyId}; verification looks that key up from SCM by id. Custos
 * and OM therefore share SCM's key distribution and rotation with no key
 * material in configuration.
 */
public class SecretKeySignedTokenSigner implements CustosTokenSigner {

  private final SecretKeyClient secretKeyClient;

  public SecretKeySignedTokenSigner(SecretKeyClient secretKeyClient) {
    this.secretKeyClient = secretKeyClient;
  }

  @Override
  public CustosTokenProto sign(CustosTokenProto token) throws CustosException {
    ManagedSecretKey key = secretKeyClient.getCurrentSecretKey();
    if (key == null) {
      throw new CustosException("No current SCM secret key available to sign "
          + "the Custos token.");
    }
    byte[] signature = key.sign(CustosTokenSigner.computeSigningInput(token));
    return token.toBuilder()
        .setSignature(ByteString.copyFrom(signature))
        .setSignatureAlgorithm(key.getSecretKey().getAlgorithm())
        .setSigningKeyId(key.getId().toString())
        .build();
  }

  @Override
  public boolean verify(CustosTokenProto token) throws CustosException {
    if (!token.hasSigningKeyId() || !token.hasSignature()
        || token.getSignature().isEmpty()) {
      return false;
    }
    try {
      ManagedSecretKey key =
          secretKeyClient.getSecretKey(UUID.fromString(token.getSigningKeyId()));
      if (key == null) {
        return false;
      }
      return key.isValidSignature(CustosTokenSigner.computeSigningInput(token),
          token.getSignature().toByteArray());
    } catch (SCMSecurityException e) {
      throw new CustosException("Failed to look up SCM secret key "
          + token.getSigningKeyId() + " to verify Custos token", e);
    }
  }
}
