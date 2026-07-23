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
import org.apache.hadoop.ozone.custos.CustosCredential;
import org.apache.hadoop.ozone.custos.CustosException;
import org.apache.hadoop.ozone.custos.client.CustosProtoHelper;
import org.apache.hadoop.ozone.custos.proto.CustosServiceGrpc;
import org.apache.hadoop.ozone.custos.proto.CustosServiceProtos.GetSessionTokenRequest;
import org.apache.hadoop.ozone.custos.proto.CustosServiceProtos.GetSessionTokenResponse;
import org.apache.hadoop.ozone.custos.proto.CustosServiceProtos.RenewSessionTokenRequest;
import org.apache.hadoop.ozone.custos.proto.CustosServiceProtos.RenewSessionTokenResponse;
import org.apache.hadoop.ozone.custos.proto.CustosTokenProtos.CustosTokenProto;

/**
 * gRPC service implementation delegating to {@link CustosAuthService}.
 */
public class CustosServiceImpl extends CustosServiceGrpc.CustosServiceImplBase {

  private final CustosAuthService authService;

  public CustosServiceImpl(CustosAuthService authService) {
    this.authService = authService;
  }

  @Override
  public void getSessionToken(GetSessionTokenRequest request,
      StreamObserver<GetSessionTokenResponse> responseObserver) {
    try {
      CustosCredential credential = CustosProtoHelper.fromProto(
          request.getCredential());
      CustosTokenProto token = authService.getSessionToken(credential,
          request.getAudience(), request.getRequestedTtlMs());
      responseObserver.onNext(GetSessionTokenResponse.newBuilder()
          .setToken(token)
          .build());
      responseObserver.onCompleted();
    } catch (CustosException e) {
      responseObserver.onError(Status.UNAUTHENTICATED
          .withDescription(e.getMessage())
          .asRuntimeException());
    }
  }

  @Override
  public void renewSessionToken(RenewSessionTokenRequest request,
      StreamObserver<RenewSessionTokenResponse> responseObserver) {
    try {
      CustosTokenProto token = authService.renewSessionToken(
          request.getToken(), request.getRequestedTtlMs());
      responseObserver.onNext(RenewSessionTokenResponse.newBuilder()
          .setToken(token)
          .build());
      responseObserver.onCompleted();
    } catch (CustosException e) {
      responseObserver.onError(Status.UNAUTHENTICATED
          .withDescription(e.getMessage())
          .asRuntimeException());
    }
  }
}
