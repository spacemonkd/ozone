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
import java.util.List;
import org.apache.hadoop.ozone.custos.CustosCredential;
import org.apache.hadoop.ozone.custos.CustosException;
import org.apache.hadoop.ozone.custos.client.CustosProtoHelper;
import org.apache.hadoop.ozone.custos.proto.CustosServiceGrpc;
import org.apache.hadoop.ozone.custos.proto.CustosServiceProtos.GetClusterInfoRequest;
import org.apache.hadoop.ozone.custos.proto.CustosServiceProtos.GetClusterInfoResponse;
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
  private final List<String> omGrpcAddresses;
  private final String audience;

  public CustosServiceImpl(CustosAuthService authService,
      List<String> omGrpcAddresses, String audience) {
    this.authService = authService;
    this.omGrpcAddresses = omGrpcAddresses;
    this.audience = audience;
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
      // Let a client that discovered its endpoint via GetClusterInfo omit the
      // audience; fall back to the configured cluster audience when unset.
      String tokenAudience = request.getAudience().isEmpty()
          ? audience : request.getAudience();
      CustosTokenProto token = authService.getSessionToken(credential,
          tokenAudience, request.getRequestedTtlMs());
      LOG.info("GetSessionToken succeeded: tokenId={}, subject={}",
          token.getTokenId(), token.getSubject());
      responseObserver.onNext(GetSessionTokenResponse.newBuilder()
          .setToken(token)
          .build());
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

  @Override
  public void getClusterInfo(GetClusterInfoRequest request,
      StreamObserver<GetClusterInfoResponse> responseObserver) {
    LOG.info("GetClusterInfo intercepted: returning {} OM endpoint(s)",
        omGrpcAddresses.size());
    responseObserver.onNext(GetClusterInfoResponse.newBuilder()
        .addAllOmGrpcAddress(omGrpcAddresses)
        .setAudience(audience)
        .build());
    responseObserver.onCompleted();
  }
}
