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

import com.google.protobuf.ServiceException;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.security.PrivilegedExceptionAction;
import java.security.cert.X509Certificate;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import javax.naming.InvalidNameException;
import javax.naming.ldap.LdapName;
import javax.naming.ldap.Rdn;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.apache.hadoop.ozone.ClientVersion;
import org.apache.hadoop.ozone.OzoneConsts;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos.OMRequest;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos.OMResponse;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos.Status;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos.Type;
import org.apache.hadoop.ozone.protocolPB.OMPBHelper;
import org.apache.hadoop.ozone.security.OzoneTokenIdentifier;
import org.apache.hadoop.ozone.security.proto.SecurityProtos.CancelDelegationTokenRequestProto;
import org.apache.hadoop.ozone.security.proto.SecurityProtos.GetDelegationTokenRequestProto;
import org.apache.hadoop.ozone.security.proto.SecurityProtos.RenewDelegationTokenRequestProto;
import org.apache.hadoop.security.Credentials;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.security.UserGroupInformation.AuthenticationMethod;
import org.apache.hadoop.security.authorize.AuthorizationException;
import org.apache.hadoop.security.authorize.ProxyUsers;
import org.apache.hadoop.security.token.Token;
import org.apache.hadoop.security.token.TokenIdentifier;
import org.apache.ratis.protocol.ClientId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * REST endpoint that issues, renews, and cancels Ozone delegation tokens over
 * HTTPS so an external gateway (for example Apache Knox) can bootstrap a token
 * for a client without Kerberos.
 * <p>
 * The gateway authenticates with its SCM-issued client certificate (mutual TLS,
 * enabled via {@code ozone.https.client.want-auth}); the certificate common
 * name must be listed in
 * {@code ozone.om.credential.rest.allowed.principals}. The gateway names the
 * end user with the {@code doas} query parameter and must be configured as a
 * Hadoop proxy user ({@code hadoop.proxyuser.<gateway>.*}). The minted token is
 * owned by that end user.
 * <p>
 * Operations are selected by HTTP method on {@code /credential/token}:
 * <ul>
 *   <li>{@code GET ?renewer=&lt;r&gt;&amp;doas=&lt;user&gt;} — issue; returns a
 *   serialized {@link Credentials} blob (same format as {@code ozone token get
 *   -t}) that the client can point {@code HADOOP_TOKEN_FILE_LOCATION} at.</li>
 *   <li>{@code POST} — renew; request body is the serialized {@link Credentials}
 *   blob; returns the new expiry time (epoch millis) as text.</li>
 *   <li>{@code DELETE} — cancel; request body is the serialized
 *   {@link Credentials} blob.</li>
 * </ul>
 * This servlet is registered as an internal servlet (no SPNEGO filter) and
 * performs its own certificate and proxy-user authorization.
 */
public class OMDelegationTokenServlet extends HttpServlet {

  private static final long serialVersionUID = 1L;
  private static final Logger LOG =
      LoggerFactory.getLogger(OMDelegationTokenServlet.class);
  private static final ClientId CLIENT_ID = ClientId.randomId();

  @Override
  protected void doGet(HttpServletRequest request,
      HttpServletResponse response) throws ServletException, IOException {
    UserGroupInformation proxyUgi = authenticate(request, response);
    if (proxyUgi == null) {
      return;
    }
    String renewer = request.getParameter("renewer");
    if (renewer == null) {
      renewer = proxyUgi.getShortUserName();
    }
    final String renewerName = renewer;
    try {
      OMResponse omResponse = submit(proxyUgi, OMRequest.newBuilder()
          .setCmdType(Type.GetDelegationToken)
          .setVersion(ClientVersion.CURRENT_VERSION)
          .setClientId(CLIENT_ID.toString())
          .setGetDelegationTokenRequest(GetDelegationTokenRequestProto
              .newBuilder().setRenewer(renewerName).build())
          .build());
      if (!isSuccess(omResponse, response)) {
        return;
      }
      if (!omResponse.getGetDelegationTokenResponse().getResponse().hasToken()) {
        response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
            "OM returned no delegation token");
        return;
      }
      Token<OzoneTokenIdentifier> token = OMPBHelper.tokenFromProto(
          omResponse.getGetDelegationTokenResponse().getResponse().getToken());
      writeCredentials(token, response);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
          "Interrupted while issuing token");
    }
  }

  @Override
  protected void doPost(HttpServletRequest request,
      HttpServletResponse response) throws ServletException, IOException {
    UserGroupInformation proxyUgi = authenticate(request, response);
    if (proxyUgi == null) {
      return;
    }
    Token<OzoneTokenIdentifier> token = readToken(request, response);
    if (token == null) {
      return;
    }
    try {
      OMResponse omResponse = submit(proxyUgi, OMRequest.newBuilder()
          .setCmdType(Type.RenewDelegationToken)
          .setVersion(ClientVersion.CURRENT_VERSION)
          .setClientId(CLIENT_ID.toString())
          .setRenewDelegationTokenRequest(RenewDelegationTokenRequestProto
              .newBuilder().setToken(OMPBHelper.protoFromToken(token)).build())
          .build());
      if (!isSuccess(omResponse, response)) {
        return;
      }
      long newExpiry = omResponse.getRenewDelegationTokenResponse()
          .getResponse().getNewExpiryTime();
      response.setContentType("text/plain");
      response.getWriter().write(Long.toString(newExpiry));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
          "Interrupted while renewing token");
    }
  }

  @Override
  protected void doDelete(HttpServletRequest request,
      HttpServletResponse response) throws ServletException, IOException {
    UserGroupInformation proxyUgi = authenticate(request, response);
    if (proxyUgi == null) {
      return;
    }
    Token<OzoneTokenIdentifier> token = readToken(request, response);
    if (token == null) {
      return;
    }
    try {
      OMResponse omResponse = submit(proxyUgi, OMRequest.newBuilder()
          .setCmdType(Type.CancelDelegationToken)
          .setVersion(ClientVersion.CURRENT_VERSION)
          .setClientId(CLIENT_ID.toString())
          .setCancelDelegationTokenRequest(CancelDelegationTokenRequestProto
              .newBuilder().setToken(OMPBHelper.protoFromToken(token)).build())
          .build());
      if (!isSuccess(omResponse, response)) {
        return;
      }
      response.setStatus(HttpServletResponse.SC_OK);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
          "Interrupted while cancelling token");
    }
  }

  /**
   * Authenticate the caller (the gateway) and build the effective UGI for the
   * requested end user. In "spnego" mode the caller is the SPNEGO-authenticated
   * principal; in "mtls" mode it is the TLS client certificate whose CN is in
   * the allow-list. The end user is taken from the {@code doas} parameter and
   * defaults to the caller. Writes an error to {@code response} and returns
   * {@code null} when authentication or authorization fails.
   */
  private UserGroupInformation authenticate(HttpServletRequest request,
      HttpServletResponse response) throws IOException {
    UserGroupInformation realUgi = spnegoMode()
        ? realUserFromSpnego(request, response)
        : realUserFromCertificate(request, response);
    if (realUgi == null) {
      return null;
    }
    String doasUser = request.getParameter("doas");
    if (doasUser == null || doasUser.isEmpty()
        || doasUser.equals(realUgi.getShortUserName())) {
      // No impersonation requested: mint the token for the caller itself.
      return realUgi;
    }
    UserGroupInformation proxyUgi =
        UserGroupInformation.createProxyUser(doasUser, realUgi);
    try {
      ProxyUsers.authorize(proxyUgi, request.getRemoteAddr());
    } catch (AuthorizationException e) {
      LOG.warn("Proxy user authorization failed for real user {} acting as {}",
          realUgi.getShortUserName(), doasUser, e);
      response.sendError(HttpServletResponse.SC_FORBIDDEN,
          "Not allowed to impersonate user " + doasUser);
      return null;
    }
    return proxyUgi;
  }

  private UserGroupInformation realUserFromSpnego(HttpServletRequest request,
      HttpServletResponse response) throws IOException {
    if (request.getUserPrincipal() == null) {
      response.sendError(HttpServletResponse.SC_UNAUTHORIZED,
          "SPNEGO authentication is required");
      return null;
    }
    UserGroupInformation realUgi = UserGroupInformation.createRemoteUser(
        request.getUserPrincipal().getName());
    realUgi.setAuthenticationMethod(AuthenticationMethod.KERBEROS);
    return realUgi;
  }

  private UserGroupInformation realUserFromCertificate(
      HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    X509Certificate[] certs = (X509Certificate[]) request.getAttribute(
        "javax.servlet.request.X509Certificate");
    if (certs == null || certs.length == 0) {
      response.sendError(HttpServletResponse.SC_UNAUTHORIZED,
          "A client certificate is required");
      return null;
    }
    String cn = extractCn(certs[0]);
    if (cn == null || !getAllowedPrincipals().contains(cn)) {
      response.sendError(HttpServletResponse.SC_FORBIDDEN,
          "Client certificate is not allowed to request delegation tokens");
      return null;
    }
    UserGroupInformation realUgi = UserGroupInformation.createRemoteUser(cn);
    realUgi.setAuthenticationMethod(AuthenticationMethod.CERTIFICATE);
    return realUgi;
  }

  private boolean spnegoMode() {
    OzoneManager om = (OzoneManager) getServletContext()
        .getAttribute(OzoneConsts.OM_CONTEXT_ATTRIBUTE);
    return "spnego".equalsIgnoreCase(om.getConfiguration().get(
        OMConfigKeys.OZONE_OM_CREDENTIAL_REST_AUTH_KEY,
        OMConfigKeys.OZONE_OM_CREDENTIAL_REST_AUTH_DEFAULT));
  }

  private OMResponse submit(UserGroupInformation proxyUgi, OMRequest omRequest)
      throws IOException, InterruptedException {
    OzoneManager om = (OzoneManager) getServletContext()
        .getAttribute(OzoneConsts.OM_CONTEXT_ATTRIBUTE);
    return proxyUgi.doAs((PrivilegedExceptionAction<OMResponse>) () -> {
      try {
        return om.getOmServerProtocol().processRequest(omRequest);
      } catch (ServiceException e) {
        throw new IOException(e.getMessage(), e);
      }
    });
  }

  private boolean isSuccess(OMResponse omResponse,
      HttpServletResponse response) throws IOException {
    if (omResponse.getSuccess() && omResponse.getStatus() == Status.OK) {
      return true;
    }
    int code = omResponse.getStatus() == Status.ACCESS_DENIED
        ? HttpServletResponse.SC_FORBIDDEN
        : HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
    response.sendError(code, "OM request failed: " + omResponse.getStatus()
        + (omResponse.hasMessage() ? " " + omResponse.getMessage() : ""));
    return false;
  }

  private void writeCredentials(Token<OzoneTokenIdentifier> token,
      HttpServletResponse response) throws IOException {
    Credentials credentials = new Credentials();
    credentials.addToken(token.getService(), token);
    response.setContentType("application/octet-stream");
    try (DataOutputStream out =
        new DataOutputStream(response.getOutputStream())) {
      credentials.writeTokenStorageToStream(out);
    }
  }

  @SuppressWarnings("unchecked")
  private Token<OzoneTokenIdentifier> readToken(HttpServletRequest request,
      HttpServletResponse response) throws IOException {
    Credentials credentials = new Credentials();
    try (DataInputStream in = new DataInputStream(request.getInputStream())) {
      credentials.readTokenStorageStream(in);
    } catch (IOException e) {
      response.sendError(HttpServletResponse.SC_BAD_REQUEST,
          "Could not read the token from the request body");
      return null;
    }
    for (Token<? extends TokenIdentifier> token : credentials.getAllTokens()) {
      if (OzoneTokenIdentifier.KIND_NAME.equals(token.getKind())) {
        return (Token<OzoneTokenIdentifier>) token;
      }
    }
    response.sendError(HttpServletResponse.SC_BAD_REQUEST,
        "No Ozone delegation token found in the request body");
    return null;
  }

  private Set<String> getAllowedPrincipals() {
    OzoneManager om = (OzoneManager) getServletContext()
        .getAttribute(OzoneConsts.OM_CONTEXT_ATTRIBUTE);
    Collection<String> principals = om.getConfiguration()
        .getTrimmedStringCollection(
            OMConfigKeys.OZONE_OM_CREDENTIAL_REST_ALLOWED_PRINCIPALS_KEY);
    return new HashSet<>(principals);
  }

  private static String extractCn(X509Certificate cert) {
    try {
      LdapName name =
          new LdapName(cert.getSubjectX500Principal().getName());
      for (Rdn rdn : name.getRdns()) {
        if ("CN".equalsIgnoreCase(rdn.getType())) {
          return rdn.getValue().toString();
        }
      }
    } catch (InvalidNameException e) {
      LOG.warn("Could not parse client certificate subject", e);
    }
    return null;
  }
}
