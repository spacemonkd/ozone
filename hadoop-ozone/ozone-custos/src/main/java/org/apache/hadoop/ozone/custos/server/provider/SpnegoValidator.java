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

package org.apache.hadoop.ozone.custos.server.provider;

import java.security.PrivilegedExceptionAction;
import org.apache.hadoop.hdds.conf.OzoneConfiguration;
import org.apache.hadoop.ozone.custos.CustosException;
import org.apache.hadoop.security.UserGroupInformation;
import org.ietf.jgss.GSSContext;
import org.ietf.jgss.GSSCredential;
import org.ietf.jgss.GSSException;
import org.ietf.jgss.GSSManager;
import org.ietf.jgss.GSSName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Validates a client SPNEGO/Kerberos token against the Custos service keytab.
 * The Custos service principal is supplied by the Ozone client in the credential.
 */
public final class SpnegoValidator {

  private static final Logger LOG = LoggerFactory.getLogger(SpnegoValidator.class);

  private SpnegoValidator() {
  }

  /**
   * Accept the SPNEGO token and return the authenticated principal with realm
   * stripped.
   */
  public static String validate(OzoneConfiguration conf, String servicePrincipal,
      String keytabPath, byte[] spnegoToken) throws CustosException {
    if (servicePrincipal == null || servicePrincipal.isEmpty()) {
      throw new CustosException("SPNEGO credential is missing the Custos service"
          + " principal attribute.");
    }
    if (keytabPath == null || keytabPath.isEmpty()) {
      throw new CustosException("ozone.custos.kerberos.keytab is not set.");
    }
    if (spnegoToken == null || spnegoToken.length == 0) {
      throw new CustosException("SPNEGO token is empty.");
    }
    try {
      UserGroupInformation.setConfiguration(conf);
      UserGroupInformation serviceUgi =
          UserGroupInformation.loginUserFromKeytabAndReturnUGI(
              servicePrincipal, keytabPath);
      return serviceUgi.doAs((PrivilegedExceptionAction<String>) () ->
          acceptSecContext(spnegoToken));
    } catch (Exception e) {
      Throwable cause = e.getCause() != null ? e.getCause() : e;
      if (cause instanceof CustosException) {
        throw (CustosException) cause;
      }
      throw new CustosException("SPNEGO validation failed.", cause);
    }
  }

  private static String acceptSecContext(byte[] spnegoToken)
      throws GSSException, CustosException {
    GSSManager manager = GSSManager.getInstance();
    GSSContext context = manager.createContext((GSSCredential) null);
    try {
      context.acceptSecContext(spnegoToken, 0, spnegoToken.length);
      if (!context.isEstablished()) {
        throw new CustosException("SPNEGO handshake is incomplete.");
      }
      GSSName srcName = context.getSrcName();
      if (srcName == null) {
        throw new CustosException("SPNEGO accept produced no source principal.");
      }
      String principal = srcName.toString();
      LOG.info("SPNEGO handshake established; authenticated principal {}",
          stripRealm(principal));
      return stripRealm(principal);
    } finally {
      context.dispose();
    }
  }

  private static String stripRealm(String principal) {
    int at = principal.indexOf('@');
    return at < 0 ? principal : principal.substring(0, at);
  }
}
