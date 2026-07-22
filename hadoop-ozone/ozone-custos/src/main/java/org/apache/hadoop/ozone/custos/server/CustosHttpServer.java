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

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A minimal HTTP endpoint that reports liveness for the Custos service.
 *
 * <p>This is deliberately lightweight scaffolding: it makes the service
 * reachable and answerable while the gRPC token-issuance endpoints and TLS via
 * SCM's {@code CertificateClient} are built out in later work. It serves a
 * single {@code GET /health} returning {@code 200 OK}.
 */
public class CustosHttpServer {

  private static final Logger LOG =
      LoggerFactory.getLogger(CustosHttpServer.class);

  private static final String HEALTH_PATH = "/health";
  private static final byte[] HEALTH_BODY =
      "OK\n".getBytes(StandardCharsets.UTF_8);

  private final InetSocketAddress bindAddress;
  private HttpServer httpServer;

  public CustosHttpServer(InetSocketAddress bindAddress) {
    this.bindAddress = bindAddress;
  }

  public synchronized void start() throws IOException {
    if (httpServer != null) {
      return;
    }
    HttpServer server = HttpServer.create(bindAddress, 0);
    server.createContext(HEALTH_PATH, CustosHttpServer::handleHealth);
    server.setExecutor(null);
    server.start();
    httpServer = server;
    LOG.info("Custos HTTP server started on {}, health endpoint {}",
        getListenAddress(), HEALTH_PATH);
  }

  public synchronized void stop() {
    if (httpServer != null) {
      httpServer.stop(0);
      httpServer = null;
      LOG.info("Custos HTTP server stopped");
    }
  }

  /**
   * @return the actual bound address, resolving the ephemeral port when the
   *     configured port was 0.
   */
  public synchronized InetSocketAddress getListenAddress() {
    return httpServer == null ? bindAddress : httpServer.getAddress();
  }

  private static void handleHealth(HttpExchange exchange) throws IOException {
    try {
      if (!"GET".equals(exchange.getRequestMethod())) {
        exchange.sendResponseHeaders(405, -1);
        return;
      }
      exchange.getResponseHeaders().set("Content-Type", "text/plain");
      exchange.sendResponseHeaders(200, HEALTH_BODY.length);
      try (OutputStream out = exchange.getResponseBody()) {
        out.write(HEALTH_BODY);
      }
    } finally {
      exchange.close();
    }
  }
}
