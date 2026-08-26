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

import static org.apache.hadoop.hdds.security.x509.exception.CertificateException.ErrorCode.CSR_ERROR;

import java.io.IOException;
import java.net.InetAddress;
import java.security.KeyPair;
import java.util.function.Consumer;
import org.apache.hadoop.hdds.protocol.proto.HddsProtos;
import org.apache.hadoop.hdds.protocol.proto.SCMSecurityProtocolProtos.SCMGetCertResponseProto;
import org.apache.hadoop.hdds.protocolPB.SCMSecurityProtocolClientSideTranslatorPB;
import org.apache.hadoop.hdds.security.SecurityConfig;
import org.apache.hadoop.hdds.security.exception.SCMSecurityException;
import org.apache.hadoop.hdds.security.x509.certificate.client.DefaultCertificateClient;
import org.apache.hadoop.hdds.security.x509.certificate.utils.CertificateSignRequest;
import org.apache.hadoop.hdds.security.x509.exception.CertificateException;
import org.apache.hadoop.security.UserGroupInformation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Certificate client for the Custos service. Obtains an SCM-issued certificate
 * through the generic {@code getCertificateChain} RPC with {@code NodeType.CUSTOS}
 * (the same path Recon uses), so the certificate chains to the SCM root that
 * clients already trust. Used to serve the Custos gRPC endpoint over TLS.
 */
public class CustosCertificateClient extends DefaultCertificateClient {

  private static final Logger LOG =
      LoggerFactory.getLogger(CustosCertificateClient.class);

  public static final String COMPONENT_NAME = "custos";

  private final String clusterID;
  private final String custosID;

  public CustosCertificateClient(
      SecurityConfig config,
      SCMSecurityProtocolClientSideTranslatorPB scmSecurityClient,
      CustosStorageConfig storage,
      Consumer<String> saveCertIdCallback,
      Runnable shutdownCallback) {
    super(config, scmSecurityClient, LOG, storage.getCustosCertSerialId(),
        COMPONENT_NAME, "", saveCertIdCallback, shutdownCallback);
    this.clusterID = storage.getClusterID();
    this.custosID = storage.getCustosId();
  }

  @Override
  public CertificateSignRequest.Builder configureCSRBuilder()
      throws SCMSecurityException {
    LOG.info("Creating CSR for Custos.");
    try {
      CertificateSignRequest.Builder builder = super.configureCSRBuilder();
      String hostname = InetAddress.getLocalHost().getCanonicalHostName();
      String subject = UserGroupInformation.getCurrentUser()
          .getShortUserName() + "@" + hostname;

      // addInetAddresses (called by super) uses DomainValidator which rejects
      // single-label names like "custos" used in Docker compose deployments.
      // Add the DNS SAN directly so TLS hostname verification passes.
      builder.addDnsName(hostname);

      builder.setCA(false)
          .setKey(new KeyPair(getPublicKey(), getPrivateKey()))
          .setConfiguration(getSecurityConfig())
          .setSubject(subject);

      return builder;
    } catch (Exception e) {
      LOG.error("Failed to get hostname or current user", e);
      throw new CertificateException("Failed to get hostname or current user",
          e, CSR_ERROR);
    }
  }

  @Override
  protected SCMGetCertResponseProto sign(CertificateSignRequest request)
      throws IOException {
    HddsProtos.NodeDetailsProto.Builder custosDetailsProtoBuilder =
        HddsProtos.NodeDetailsProto.newBuilder()
            .setHostName(InetAddress.getLocalHost().getHostName())
            .setClusterId(clusterID)
            .setUuid(custosID)
            .setNodeType(HddsProtos.NodeType.CUSTOS);
    return getScmSecureClient().getCertificateChain(
        custosDetailsProtoBuilder.build(), request.toEncodedFormat());
  }

  @Override
  public Logger getLogger() {
    return LOG;
  }
}
