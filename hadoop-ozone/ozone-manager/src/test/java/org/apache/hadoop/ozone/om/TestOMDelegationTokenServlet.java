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

package org.apache.hadoop.ozone.om;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.security.cert.X509Certificate;
import javax.security.auth.x500.X500Principal;
import javax.servlet.ServletConfig;
import javax.servlet.ServletContext;
import javax.servlet.ServletOutputStream;
import javax.servlet.WriteListener;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.apache.hadoop.hdds.conf.OzoneConfiguration;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.ozone.OzoneConsts;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos.GetDelegationTokenResponseProto;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos.OMResponse;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos.Status;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos.Type;
import org.apache.hadoop.ozone.protocolPB.OMPBHelper;
import org.apache.hadoop.ozone.protocolPB.OzoneManagerProtocolServerSideTranslatorPB;
import org.apache.hadoop.ozone.security.OzoneTokenIdentifier;
import org.apache.hadoop.security.Credentials;
import org.apache.hadoop.security.authorize.ProxyUsers;
import org.apache.hadoop.security.token.Token;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link OMDelegationTokenServlet} covering certificate and
 * proxy-user authorization and the issue (GET) path with a mocked OM.
 */
public class TestOMDelegationTokenServlet {

  private static final String CERT_ATTR =
      "javax.servlet.request.X509Certificate";

  private OMDelegationTokenServlet servlet;
  private OzoneManager om;
  private OzoneManagerProtocolServerSideTranslatorPB protocol;
  private HttpServletRequest request;
  private HttpServletResponse response;
  private ByteArrayOutputStream body;

  @BeforeEach
  void setUp() throws Exception {
    OzoneConfiguration conf = new OzoneConfiguration();
    conf.set(OMConfigKeys.OZONE_OM_CREDENTIAL_REST_ALLOWED_PRINCIPALS_KEY,
        "knox");
    conf.set("hadoop.proxyuser.knox.users", "*");
    conf.set("hadoop.proxyuser.knox.hosts", "*");
    ProxyUsers.refreshSuperUserGroupsConfiguration(conf);

    protocol = mock(OzoneManagerProtocolServerSideTranslatorPB.class);
    om = mock(OzoneManager.class);
    when(om.getConfiguration()).thenReturn(conf);
    when(om.getOmServerProtocol()).thenReturn(protocol);

    ServletContext context = mock(ServletContext.class);
    when(context.getAttribute(OzoneConsts.OM_CONTEXT_ATTRIBUTE)).thenReturn(om);
    ServletConfig config = mock(ServletConfig.class);
    when(config.getServletContext()).thenReturn(context);

    servlet = new OMDelegationTokenServlet();
    servlet.init(config);

    request = mock(HttpServletRequest.class);
    response = mock(HttpServletResponse.class);
    body = new ByteArrayOutputStream();
    when(response.getOutputStream()).thenReturn(new ServletOutputStream() {
      @Override
      public void write(int b) {
        body.write(b);
      }

      @Override
      public boolean isReady() {
        return true;
      }

      @Override
      public void setWriteListener(WriteListener writeListener) {
      }
    });
  }

  @Test
  void getWithoutCertificateReturnsUnauthorized() throws Exception {
    when(request.getAttribute(CERT_ATTR)).thenReturn(null);

    servlet.doGet(request, response);

    verify(response).sendError(eq(HttpServletResponse.SC_UNAUTHORIZED),
        anyString());
  }

  @Test
  void getWithDisallowedCertificateReturnsForbidden() throws Exception {
    X509Certificate[] certs = {certWithCn("evil")};
    when(request.getAttribute(CERT_ATTR)).thenReturn(certs);

    servlet.doGet(request, response);

    verify(response).sendError(eq(HttpServletResponse.SC_FORBIDDEN),
        anyString());
  }

  @Test
  void getIssuesTokenForImpersonatedUser() throws Exception {
    X509Certificate[] certs = {certWithCn("knox")};
    when(request.getAttribute(CERT_ATTR)).thenReturn(certs);
    when(request.getParameter("doas")).thenReturn("alice");
    when(request.getRemoteAddr()).thenReturn("127.0.0.1");
    when(protocol.processRequest(any())).thenReturn(tokenResponse());

    servlet.doGet(request, response);

    verify(response).setContentType("application/octet-stream");
    Credentials credentials = new Credentials();
    credentials.readTokenStorageStream(
        new DataInputStream(new ByteArrayInputStream(body.toByteArray())));
    assertThat(credentials.getAllTokens()).anyMatch(
        t -> OzoneTokenIdentifier.KIND_NAME.equals(t.getKind()));
  }

  private static X509Certificate certWithCn(String cn) {
    X509Certificate cert = mock(X509Certificate.class);
    when(cert.getSubjectX500Principal())
        .thenReturn(new X500Principal("CN=" + cn + ",OU=scm,O=cluster"));
    return cert;
  }

  private static OMResponse tokenResponse() {
    OzoneTokenIdentifier id = new OzoneTokenIdentifier(
        new Text("alice"), new Text("alice"), new Text(""));
    Token<OzoneTokenIdentifier> token = new Token<>(id.getBytes(),
        "password".getBytes(UTF_8), OzoneTokenIdentifier.KIND_NAME,
        new Text("om-service"));
    return OMResponse.newBuilder()
        .setCmdType(Type.GetDelegationToken)
        .setSuccess(true)
        .setStatus(Status.OK)
        .setGetDelegationTokenResponse(GetDelegationTokenResponseProto
            .newBuilder()
            .setResponse(org.apache.hadoop.ozone.security.proto.SecurityProtos
                .GetDelegationTokenResponseProto.newBuilder()
                .setToken(OMPBHelper.protoFromToken(token))
                .build())
            .build())
        .build();
  }
}
