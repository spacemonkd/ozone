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

package org.apache.hadoop.ozone.custos;

import java.io.IOException;
import java.security.PrivilegedExceptionAction;
import java.util.Collections;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.security.SecurityUtil;
import org.apache.hadoop.security.UserGroupInformation;
import org.ietf.jgss.GSSContext;
import org.ietf.jgss.GSSException;
import org.ietf.jgss.GSSManager;
import org.ietf.jgss.GSSName;
import org.ietf.jgss.Oid;

/**
 * Builds a {@link CustosCredential} from the Ozone Java client's current
 * Kerberos login.
 *
 * <p>The client mints a SPNEGO token from its {@link UserGroupInformation}
 * (the same login used for OM RPC) and sends it to Custos. Application code does
 * not set a principal per call; the Custos client library resolves the service
 * principal from cluster config and the Custos host, following the same
 * {@code _HOST} substitution pattern as {@code ozone.om.kerberos.principal} for
 * OM RPC.
 */
public final class KerberosCredentials {

  /** Credential attribute carrying the resolved Custos service principal. */
  public static final String SERVICE_PRINCIPAL_ATTRIBUTE = "servicePrincipal";

  /**
   * Cluster config key for the Custos Kerberos service principal pattern, for
   * example {@code custos/_HOST@REALM}. Resolved with
   * {@link SecurityUtil#getServerPrincipal(String, String)} against the Custos
   * host the client is contacting.
   */
  public static final String KERBEROS_PRINCIPAL_KEY =
      "ozone.custos.kerberos.principal";

  private KerberosCredentials() {
  }

  /**
   * Resolve the Custos service principal from cluster config and host name.
   */
  public static String resolveServicePrincipal(Configuration conf,
      String custosHostname) throws CustosException, IOException {
    String principalPattern = conf.get(KERBEROS_PRINCIPAL_KEY, "");
    if (principalPattern.isEmpty()) {
      throw new CustosException(KERBEROS_PRINCIPAL_KEY
          + " must be set in the cluster configuration.");
    }
    return SecurityUtil.getServerPrincipal(principalPattern, custosHostname);
  }

  /**
   * Build a SPNEGO credential from the current UGI for the Custos instance at
   * {@code custosHostname}.
   */
  public static CustosCredential fromCurrentUser(Configuration conf,
      String custosHostname) throws CustosException, IOException {
    return fromResolvedServicePrincipal(conf,
        resolveServicePrincipal(conf, custosHostname));
  }

  /**
   * Build a SPNEGO credential from the current UGI for the given resolved
   * Custos service principal.
   */
  public static CustosCredential fromResolvedServicePrincipal(Configuration conf,
      String servicePrincipal) throws CustosException, IOException {
    if (servicePrincipal == null || servicePrincipal.isEmpty()) {
      throw new CustosException("Custos service principal must not be empty.");
    }
    UserGroupInformation.setConfiguration(conf);
    UserGroupInformation currentUser = UserGroupInformation.getCurrentUser();
    if (!currentUser.hasKerberosCredentials()) {
      throw new CustosException("Current user does not have Kerberos credentials.");
    }
    byte[] spnegoToken;
    try {
      spnegoToken = currentUser.doAs(
          (PrivilegedExceptionAction<byte[]>) () -> initSecContext(servicePrincipal));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new CustosException("Interrupted while minting SPNEGO token.", e);
    }
    return new CustosCredential(CredentialType.SPNEGO, spnegoToken,
        Collections.singletonMap(SERVICE_PRINCIPAL_ATTRIBUTE, servicePrincipal));
  }

  private static byte[] initSecContext(String servicePrincipal)
      throws GSSException, CustosException {
    GSSManager manager = GSSManager.getInstance();
    GSSName serverName =
        manager.createName(servicePrincipal, GSSName.NT_USER_NAME);
    GSSContext context = manager.createContext(serverName, spnegoMech(), null,
        GSSContext.DEFAULT_LIFETIME);
    context.requestMutualAuth(true);
    try {
      byte[] token = context.initSecContext(new byte[0], 0, 0);
      if (token == null || token.length == 0) {
        throw new CustosException("SPNEGO initSecContext produced no token.");
      }
      return token;
    } finally {
      context.dispose();
    }
  }

  /**
   * SPNEGO mechanism OID ({@code 1.3.6.1.5.5.14.1.2}). This is a fixed IETF identifier.
   */
  private static Oid spnegoMech() throws GSSException {
    return new Oid("1.3.6.1.5.5.14.1.2");
  }
}
