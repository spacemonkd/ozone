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
import io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.NettyChannelBuilder;
import io.netty.handler.ssl.SslContext;
import java.io.Closeable;
import java.io.IOException;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLException;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.ozone.custos.CustosCredential;
import org.apache.hadoop.ozone.custos.CustosException;
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
    this(host, port, deadlineMs, shutdownTimeoutMs, null);
  }

  /**
   * @param caCerts CA certificate(s) to trust for TLS to Custos (the SCM root,
   *     obtained out of band). When null/empty the channel is plaintext
   *     (development only).
   */
  public CustosGrpcClient(String host, int port, long deadlineMs,
      long shutdownTimeoutMs, List<X509Certificate> caCerts) {
    this.shutdownTimeoutMs = shutdownTimeoutMs;
    this.channel = buildChannel(host, port, caCerts);
    this.blockingStub = CustosServiceGrpc.newBlockingStub(channel)
        .withDeadlineAfter(deadlineMs, TimeUnit.MILLISECONDS);
  }

  private static ManagedChannel buildChannel(String host, int port,
      List<X509Certificate> caCerts) {
    NettyChannelBuilder builder = NettyChannelBuilder.forAddress(host, port);
    if (caCerts != null && !caCerts.isEmpty()) {
      try {
        SslContext sslContext = GrpcSslContexts.forClient()
            .trustManager(caCerts)
            .build();
        builder.useTransportSecurity().sslContext(sslContext);
        LOG.info("Custos gRPC client using TLS, trusting {} CA certificate(s)",
            caCerts.size());
      } catch (SSLException e) {
        throw new IllegalStateException(
            "Failed to configure TLS for the Custos gRPC client", e);
      }
    } else {
      builder.usePlaintext();
    }
    return builder.build();
  }

  /**
   * Create a plaintext client from cluster configuration (TLS disabled).
   */
  public static CustosGrpcClient fromConfiguration(Configuration conf) {
    return fromConfiguration(conf, null);
  }

  /**
   * Create a client from cluster configuration. When {@code caCerts} is non-null
   * and non-empty the channel uses TLS trusting those CA certificate(s) (the
   * cluster CA, e.g. from OM's {@code getServiceInfo}); otherwise it is plaintext.
   */
  public static CustosGrpcClient fromConfiguration(Configuration conf,
      List<X509Certificate> caCerts) {
    String host = conf.get(CustosClientConfig.GRPC_HOST,
        CustosClientConfig.GRPC_HOST_DEFAULT);
    int port = conf.getInt(CustosClientConfig.GRPC_PORT,
        CustosClientConfig.GRPC_PORT_DEFAULT);
    long deadlineMs = conf.getLong(CustosClientConfig.GRPC_DEADLINE_MS,
        CustosClientConfig.GRPC_DEADLINE_MS_DEFAULT);
    return new CustosGrpcClient(host, port, deadlineMs, 5_000L, caCerts);
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

  /**
   * Discover the OM endpoint(s) and token audience from Custos, so a client that
   * only knows the Custos endpoint can reach OM without being told its address.
   */
  public ClusterInfo getClusterInfo() throws CustosException {
    try {
      GetClusterInfoResponse response = blockingStub.getClusterInfo(
          GetClusterInfoRequest.newBuilder().build());
      return new ClusterInfo(response.getOmGrpcAddressList(),
          response.getAudience());
    } catch (StatusRuntimeException e) {
      throw new CustosException("GetClusterInfo failed: "
          + e.getStatus().getDescription(), e);
    }
  }

  /**
   * OM endpoints and token audience returned by {@link #getClusterInfo()}.
   */
  public static final class ClusterInfo {
    private final List<String> omGrpcAddresses;
    private final String audience;

    ClusterInfo(List<String> omGrpcAddresses, String audience) {
      this.omGrpcAddresses = omGrpcAddresses;
      this.audience = audience;
    }

    public List<String> getOmGrpcAddresses() {
      return omGrpcAddresses;
    }

    public String getAudience() {
      return audience;
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
