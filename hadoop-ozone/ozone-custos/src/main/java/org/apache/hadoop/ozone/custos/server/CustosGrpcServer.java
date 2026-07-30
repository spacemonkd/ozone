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

import io.grpc.Server;
import io.grpc.netty.NettyServerBuilder;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * gRPC server exposing {@link org.apache.hadoop.ozone.custos.proto.CustosServiceGrpc}.
 */
public class CustosGrpcServer {

  private static final Logger LOG = LoggerFactory.getLogger(CustosGrpcServer.class);

  private final Server server;
  private final InetSocketAddress listenAddress;

  public CustosGrpcServer(InetSocketAddress bindAddress,
      CustosAuthService authService, List<String> caCertificates) {
    this.server = NettyServerBuilder.forAddress(bindAddress)
        .addService(new CustosServiceImpl(authService, caCertificates))
        .build();
    this.listenAddress = bindAddress;
  }

  public void start() throws IOException {
    server.start();
    LOG.info("Custos gRPC server started on {}", getListenAddress());
  }

  public void stop() throws InterruptedException {
    if (server != null) {
      server.shutdown();
      if (!server.awaitTermination(5, TimeUnit.SECONDS)) {
        server.shutdownNow();
      }
    }
  }

  public InetSocketAddress getListenAddress() {
    if (server != null && server.getListenSockets() != null
        && !server.getListenSockets().isEmpty()) {
      return (InetSocketAddress) server.getListenSockets().iterator().next();
    }
    return listenAddress;
  }
}
