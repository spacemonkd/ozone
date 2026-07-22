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

import static org.apache.hadoop.ozone.custos.server.CustosConfigKeys.OZONE_CUSTOS_ENABLED;
import static org.apache.hadoop.ozone.custos.server.CustosConfigKeys.OZONE_CUSTOS_ENABLED_DEFAULT;
import static org.apache.hadoop.ozone.custos.server.CustosConfigKeys.OZONE_CUSTOS_HTTP_BIND_HOST;
import static org.apache.hadoop.ozone.custos.server.CustosConfigKeys.OZONE_CUSTOS_HTTP_BIND_HOST_DEFAULT;
import static org.apache.hadoop.ozone.custos.server.CustosConfigKeys.OZONE_CUSTOS_HTTP_BIND_PORT;
import static org.apache.hadoop.ozone.custos.server.CustosConfigKeys.OZONE_CUSTOS_HTTP_BIND_PORT_DEFAULT;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.Callable;
import org.apache.hadoop.hdds.cli.GenericCli;
import org.apache.hadoop.hdds.cli.HddsVersionProvider;
import org.apache.hadoop.hdds.conf.OzoneConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.Command;

/**
 * Entry point for the standalone Custos auth service.
 *
 * <p>Custos validates client credentials through pluggable
 * {@link org.apache.hadoop.ozone.custos.CustosProvider}s and (in later work)
 * issues signed tokens that OM verifies locally. This process boots, loads the
 * configured providers, and serves a health endpoint. Token-issuance gRPC
 * endpoints and TLS via SCM's {@code CertificateClient} are built on top of
 * this scaffolding.
 */
@Command(name = "ozone custos",
    hidden = true,
    description = "Ozone Custos authentication service.",
    versionProvider = HddsVersionProvider.class,
    mixinStandardHelpOptions = true)
public class Custos extends GenericCli implements Callable<Void> {

  private static final Logger LOG = LoggerFactory.getLogger(Custos.class);

  private CustosProviderRegistry providerRegistry;
  private CustosHttpServer httpServer;

  public static void main(String[] args) {
    new Custos().run(args);
  }

  @Override
  public Void call() throws Exception {
    OzoneConfiguration conf = getOzoneConf();
    start(conf);
    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
      try {
        stop();
      } catch (Exception e) {
        LOG.error("Error while stopping Custos", e);
      }
    }, "custos-shutdown"));
    return null;
  }

  public void start(OzoneConfiguration conf) throws IOException {
    boolean enabled = conf.getBoolean(OZONE_CUSTOS_ENABLED,
        OZONE_CUSTOS_ENABLED_DEFAULT);
    LOG.info("Starting Ozone Custos auth service ({}={})",
        OZONE_CUSTOS_ENABLED, enabled);

    providerRegistry = new CustosProviderRegistry(conf);

    String bindHost = conf.get(OZONE_CUSTOS_HTTP_BIND_HOST,
        OZONE_CUSTOS_HTTP_BIND_HOST_DEFAULT);
    int bindPort = conf.getInt(OZONE_CUSTOS_HTTP_BIND_PORT,
        OZONE_CUSTOS_HTTP_BIND_PORT_DEFAULT);
    httpServer = new CustosHttpServer(new InetSocketAddress(bindHost, bindPort));
    httpServer.start();

    LOG.info("Ozone Custos auth service started on {}",
        httpServer.getListenAddress());
  }

  public void stop() {
    LOG.info("Stopping Ozone Custos auth service");
    if (httpServer != null) {
      httpServer.stop();
    }
  }

  public CustosProviderRegistry getProviderRegistry() {
    return providerRegistry;
  }

  public InetSocketAddress getHttpAddress() {
    return httpServer == null ? null : httpServer.getListenAddress();
  }
}
