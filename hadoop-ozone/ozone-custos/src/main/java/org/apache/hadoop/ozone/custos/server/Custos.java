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

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.concurrent.Callable;
import org.apache.hadoop.hdds.cli.GenericCli;
import org.apache.hadoop.hdds.cli.HddsVersionProvider;
import org.apache.hadoop.hdds.conf.OzoneConfiguration;
import org.apache.hadoop.hdds.protocol.SecretKeyProtocol;
import org.apache.hadoop.hdds.security.symmetric.DefaultSecretKeyClient;
import org.apache.hadoop.hdds.security.symmetric.SecretKeyClient;
import org.apache.hadoop.hdds.utils.HddsServerUtil;
import org.apache.hadoop.ozone.OzoneSecurityUtil;
import org.apache.hadoop.ozone.custos.KerberosCredentials;
import org.apache.hadoop.ozone.custos.token.CustosTokenSigner;
import org.apache.hadoop.security.SecurityUtil;
import org.apache.hadoop.security.UserGroupInformation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.Command;

/**
 * Entry point for the standalone Custos auth service.
 *
 * <p>Custos validates client credentials through pluggable
 * {@link org.apache.hadoop.ozone.custos.CustosProvider}s, resolves identity
 * through {@link org.apache.hadoop.ozone.custos.identity.IdentityProvider}s, and
 * issues session tokens via {@link CustosAuthService}. For Kerberos, the Ozone
 * Java client mints a SPNEGO token via {@link org.apache.hadoop.ozone.custos.KerberosCredentials}
 * and sends it to Custos; configure {@code ozone.custos.providers} (for example
 * {@code KerberosProvider}), {@code ozone.custos.identity.providers} (for example
 * {@code KerberosIdentityProvider}), and the server keytab
 * {@code ozone.custos.kerberos.keytab}.
 */
@Command(name = "ozone custos",
    hidden = true,
    description = "Ozone Custos authentication service.",
    versionProvider = HddsVersionProvider.class,
    mixinStandardHelpOptions = true)
public class Custos extends GenericCli implements Callable<Void> {

  private static final Logger LOG = LoggerFactory.getLogger(Custos.class);

  private CustosProviderRegistry providerRegistry;
  private IdentityProviderRegistry identityProviderRegistry;
  private CustosAuthService authService;
  private CustosHttpServer httpServer;
  private CustosGrpcServer grpcServer;
  private SecretKeyClient secretKeyClient;

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
    CustosConfig config = conf.getObject(CustosConfig.class);
    LOG.info("Starting Ozone Custos auth service ({}={})",
        CustosConfig.Keys.ENABLED, config.isEnabled());

    providerRegistry = new CustosProviderRegistry(conf);
    identityProviderRegistry = new IdentityProviderRegistry(conf);

    CustosTokenSigner signer = null;
    if (OzoneSecurityUtil.isSecurityEnabled(conf)) {
      loginCustosUser(conf);
      SecretKeyProtocol secretKeyProtocol =
          HddsServerUtil.getSecretKeyClientForCustos(conf);
      secretKeyClient = DefaultSecretKeyClient.create(conf, secretKeyProtocol,
          "custos");
      secretKeyClient.start(conf);
      signer = new SecretKeySignedTokenSigner(secretKeyClient);
      LOG.info("Custos token signing enabled via SCM-managed secret keys");
    } else {
      LOG.warn("Security is disabled; Custos will issue unsigned tokens");
    }
    authService = new CustosAuthService(providerRegistry,
        identityProviderRegistry, signer);

    httpServer = new CustosHttpServer(new InetSocketAddress(
        config.getHttpBindHost(), config.getHttpBindPort()));
    httpServer.start();

    grpcServer = new CustosGrpcServer(new InetSocketAddress(
        config.getGrpcBindHost(), config.getGrpcBindPort()), authService);
    grpcServer.start();

    LOG.info("Ozone Custos auth service started on {} (HTTP) and {} (gRPC)",
        httpServer.getListenAddress(), grpcServer.getListenAddress());
  }

  /**
   * Log in as the Custos service principal so RPCs to SCM (for secret keys)
   * are made under the Custos identity.
   */
  private void loginCustosUser(OzoneConfiguration conf) throws IOException {
    UserGroupInformation.setConfiguration(conf);
    String hostname = InetAddress.getLocalHost().getCanonicalHostName();
    SecurityUtil.login(conf, CustosConfig.Keys.KERBEROS_KEYTAB,
        KerberosCredentials.KERBEROS_PRINCIPAL_KEY, hostname);
    LOG.info("Custos logged in as {}",
        UserGroupInformation.getLoginUser().getUserName());
  }

  public void stop() {
    LOG.info("Stopping Ozone Custos auth service");
    if (secretKeyClient != null) {
      secretKeyClient.stop();
    }
    if (grpcServer != null) {
      try {
        grpcServer.stop();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        LOG.warn("Interrupted while stopping Custos gRPC server", e);
      }
    }
    if (httpServer != null) {
      httpServer.stop();
    }
  }

  public CustosProviderRegistry getProviderRegistry() {
    return providerRegistry;
  }

  public IdentityProviderRegistry getIdentityProviderRegistry() {
    return identityProviderRegistry;
  }

  public CustosAuthService getAuthService() {
    return authService;
  }

  public InetSocketAddress getHttpAddress() {
    return httpServer == null ? null : httpServer.getListenAddress();
  }

  public InetSocketAddress getGrpcAddress() {
    return grpcServer == null ? null : grpcServer.getListenAddress();
  }
}
