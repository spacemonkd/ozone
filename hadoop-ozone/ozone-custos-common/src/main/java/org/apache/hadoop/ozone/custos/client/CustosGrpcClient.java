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

package org.apache.hadoop.ozone.custos.client;

import io.grpc.ManagedChannel;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.NettyChannelBuilder;
import java.io.Closeable;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.ozone.custos.CustosCredential;
import org.apache.hadoop.ozone.custos.CustosException;
import org.apache.hadoop.ozone.custos.proto.CustosServiceGrpc;
import org.apache.hadoop.ozone.custos.proto.CustosServiceProtos.GetSessionTokenRequest;
import org.apache.hadoop.ozone.custos.proto.CustosServiceProtos.GetSessionTokenResponse;
import org.apache.hadoop.ozone.custos.proto.CustosServiceProtos.RenewSessionTokenRequest;
import org.apache.hadoop.ozone.custos.proto.CustosServiceProtos.RenewSessionTokenResponse;
import org.apache.hadoop.ozone.custos.proto.CustosTokenProtos.CustosTokenProto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * gRPC client for the Custos authentication service.
 *
 * <p>OzoneFS / Hadoop-RPC clients use this for {@code GetSessionToken} and
 * {@code RenewSessionToken} only. All namespace and data operations continue
 * over Hadoop RPC to OM, carrying the returned {@link CustosTokenProto} on
 * {@code OMRequest.custosToken}.
 */
public final class CustosGrpcClient implements Closeable {

  private static final Logger LOG = LoggerFactory.getLogger(CustosGrpcClient.class);

  private final ManagedChannel channel;
  private final CustosServiceGrpc.CustosServiceBlockingStub blockingStub;
  private final long shutdownTimeoutMs;

  public CustosGrpcClient(String host, int port, long deadlineMs) {
    this(host, port, deadlineMs, 5_000L);
  }

  public CustosGrpcClient(String host, int port, long deadlineMs,
      long shutdownTimeoutMs) {
    this.shutdownTimeoutMs = shutdownTimeoutMs;
    this.channel = NettyChannelBuilder.forAddress(host, port)
        .usePlaintext()
        .build();
    this.blockingStub = CustosServiceGrpc.newBlockingStub(channel)
        .withDeadlineAfter(deadlineMs, TimeUnit.MILLISECONDS);
  }

  /**
   * Create a client from cluster configuration.
   */
  public static CustosGrpcClient fromConfiguration(Configuration conf) {
    String host = conf.get(CustosClientConfig.GRPC_HOST,
        CustosClientConfig.GRPC_HOST_DEFAULT);
    int port = conf.getInt(CustosClientConfig.GRPC_PORT,
        CustosClientConfig.GRPC_PORT_DEFAULT);
    long deadlineMs = conf.getLong(CustosClientConfig.GRPC_DEADLINE_MS,
        CustosClientConfig.GRPC_DEADLINE_MS_DEFAULT);
    return new CustosGrpcClient(host, port, deadlineMs);
  }

  /**
   * Exchange a credential for a session token.
   */
  public CustosTokenProto getSessionToken(CustosCredential credential,
      String audience, long requestedTtlMs) throws CustosException {
    GetSessionTokenRequest request = GetSessionTokenRequest.newBuilder()
        .setCredential(CustosProtoHelper.toProto(credential))
        .setAudience(audience == null ? "" : audience)
        .setRequestedTtlMs(requestedTtlMs)
        .build();
    try {
      GetSessionTokenResponse response = blockingStub.getSessionToken(request);
      if (!response.hasToken()) {
        throw new CustosException("Custos returned an empty session token.");
      }
      return response.getToken();
    } catch (StatusRuntimeException e) {
      throw new CustosException("GetSessionToken failed: "
          + e.getStatus().getDescription(), e);
    }
  }

  /**
   * Renew an existing session token.
   */
  public CustosTokenProto renewSessionToken(CustosTokenProto token,
      long requestedTtlMs) throws CustosException {
    RenewSessionTokenRequest request = RenewSessionTokenRequest.newBuilder()
        .setToken(token)
        .setRequestedTtlMs(requestedTtlMs)
        .build();
    try {
      RenewSessionTokenResponse response =
          blockingStub.renewSessionToken(request);
      if (!response.hasToken()) {
        throw new CustosException("Custos returned an empty renewed token.");
      }
      return response.getToken();
    } catch (StatusRuntimeException e) {
      throw new CustosException("RenewSessionToken failed: "
          + e.getStatus().getDescription(), e);
    }
  }

  @Override
  public void close() throws IOException {
    channel.shutdown();
    try {
      if (!channel.awaitTermination(shutdownTimeoutMs, TimeUnit.MILLISECONDS)) {
        LOG.warn("Custos gRPC channel did not shut down in {} ms; forcing.",
            shutdownTimeoutMs);
        channel.shutdownNow();
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      channel.shutdownNow();
      throw new IOException("Interrupted while shutting down Custos gRPC client", e);
    }
  }
}
