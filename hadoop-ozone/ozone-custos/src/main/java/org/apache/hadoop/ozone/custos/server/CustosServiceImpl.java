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

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import org.apache.hadoop.ozone.custos.CustosCredential;
import org.apache.hadoop.ozone.custos.CustosException;
import org.apache.hadoop.ozone.custos.client.CustosProtoHelper;
import org.apache.hadoop.ozone.custos.proto.CustosServiceGrpc;
import org.apache.hadoop.ozone.custos.proto.CustosServiceProtos.GetSessionTokenRequest;
import org.apache.hadoop.ozone.custos.proto.CustosServiceProtos.GetSessionTokenResponse;
import org.apache.hadoop.ozone.custos.proto.CustosServiceProtos.RenewSessionTokenRequest;
import org.apache.hadoop.ozone.custos.proto.CustosServiceProtos.RenewSessionTokenResponse;
import org.apache.hadoop.ozone.custos.proto.CustosTokenProtos.CustosTokenProto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * gRPC service implementation delegating to {@link CustosAuthService}. This is
 * the interception point for the PoC: each incoming call is logged at INFO with
 * the credential type (never the credential material) so the flow can be traced.
 */
public class CustosServiceImpl extends CustosServiceGrpc.CustosServiceImplBase {

  private static final Logger LOG =
      LoggerFactory.getLogger(CustosServiceImpl.class);

  private final CustosAuthService authService;
  private final List<String> caCertificates;
  private final String caFingerprint;

  public CustosServiceImpl(CustosAuthService authService,
      List<String> caCertificates) {
    this.authService = authService;
    this.caCertificates = caCertificates;
    // The CA bundle is fixed for the process lifetime (fetched once at startup),
    // so its fingerprint can be computed once here.
    this.caFingerprint = fingerprint(caCertificates);
  }

  @Override
  public void getSessionToken(GetSessionTokenRequest request,
      StreamObserver<GetSessionTokenResponse> responseObserver) {
    LOG.info("GetSessionToken intercepted: credentialType={}, audience='{}', "
        + "requestedTtlMs={}", request.getCredential().getType(),
        request.getAudience(), request.getRequestedTtlMs());
    try {
      CustosCredential credential = CustosProtoHelper.fromProto(
          request.getCredential());
      CustosTokenProto token = authService.getSessionToken(credential,
          request.getAudience(), request.getRequestedTtlMs());
      LOG.info("GetSessionToken succeeded: tokenId={}, subject={}",
          token.getTokenId(), token.getSubject());
      GetSessionTokenResponse.Builder response = GetSessionTokenResponse
          .newBuilder()
          .setToken(token)
          .setCaFingerprint(caFingerprint);
      if (!caCertificates.isEmpty()
          && caFingerprint.equals(request.getKnownCaFingerprint())) {
        // Client already holds this exact bundle; skip re-sending it.
        response.setCaUnchanged(true);
        LOG.info("GetSessionToken: client CA fingerprint matched; omitting CA "
            + "bundle (tokenId={})", token.getTokenId());
      } else {
        response.addAllCaCertPem(caCertificates);
      }
      responseObserver.onNext(response.build());
      responseObserver.onCompleted();
    } catch (CustosException e) {
      LOG.warn("GetSessionToken rejected (credentialType={}): {}",
          request.getCredential().getType(), e.getMessage());
      responseObserver.onError(Status.UNAUTHENTICATED
          .withDescription(e.getMessage())
          .asRuntimeException());
    }
  }

  @Override
  public void renewSessionToken(RenewSessionTokenRequest request,
      StreamObserver<RenewSessionTokenResponse> responseObserver) {
    LOG.info("RenewSessionToken intercepted: tokenId={}, requestedTtlMs={}",
        request.getToken().getTokenId(), request.getRequestedTtlMs());
    try {
      CustosTokenProto token = authService.renewSessionToken(
          request.getToken(), request.getRequestedTtlMs());
      LOG.info("RenewSessionToken succeeded: tokenId={}, newExpiryMs={}",
          token.getTokenId(), token.getExpiryMs());
      responseObserver.onNext(RenewSessionTokenResponse.newBuilder()
          .setToken(token)
          .build());
      responseObserver.onCompleted();
    } catch (CustosException e) {
      LOG.warn("RenewSessionToken rejected (tokenId={}): {}",
          request.getToken().getTokenId(), e.getMessage());
      responseObserver.onError(Status.UNAUTHENTICATED
          .withDescription(e.getMessage())
          .asRuntimeException());
    }
  }

  /**
   * Fingerprint of the CA bundle: lowercase-hex SHA-256 over the trimmed PEM
   * entries joined by {@code '\n'} in order. Opaque to the client, which only
   * echoes it back; empty when there is no CA.
   */
  private static String fingerprint(List<String> caCertificates) {
    if (caCertificates.isEmpty()) {
      return "";
    }
    StringBuilder joined = new StringBuilder();
    for (int i = 0; i < caCertificates.size(); i++) {
      if (i > 0) {
        joined.append('\n');
      }
      joined.append(caCertificates.get(i).trim());
    }
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256")
          .digest(joined.toString().getBytes(StandardCharsets.UTF_8));
      StringBuilder hex = new StringBuilder(digest.length * 2);
      for (byte b : digest) {
        hex.append(Character.forDigit((b >> 4) & 0xF, 16));
        hex.append(Character.forDigit(b & 0xF, 16));
      }
      return hex.toString();
    } catch (NoSuchAlgorithmException e) {
      // SHA-256 is guaranteed by the platform; no fingerprint means Custos
      // always re-sends the bundle, which is safe.
      LOG.warn("SHA-256 unavailable; CA bundle will always be re-sent", e);
      return "";
    }
  }
}
