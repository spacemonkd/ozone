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

import org.apache.hadoop.ozone.custos.CredentialType;
import org.apache.hadoop.ozone.custos.CustosCredential;
import org.apache.hadoop.ozone.custos.CustosException;
import org.apache.hadoop.ozone.custos.KerberosCredentials;
import org.apache.hadoop.ozone.custos.server.CustosConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Kerberos authentication provider: validates a client SPNEGO token and returns
 * the authenticated subject. The Ozone Java client mints the SPNEGO token from
 * its {@link org.apache.hadoop.security.UserGroupInformation} via
 * {@link KerberosCredentials} and sends the Custos service principal in the
 * credential attributes. Group resolution and token binding are handled by
 * {@link org.apache.hadoop.ozone.custos.server.identity.KerberosIdentityProvider}
 * through {@link org.apache.hadoop.ozone.custos.server.CustosAuthService}.
 */
public class KerberosProvider extends AbstractCustosProvider {

  private static final Logger LOG =
      LoggerFactory.getLogger(KerberosProvider.class);

  private String keytabPath;

  @Override
  public CredentialType supportedType() {
    return CredentialType.SPNEGO;
  }

  @Override
  protected void init() {
    keytabPath = getConf().get(CustosConfig.Keys.KERBEROS_KEYTAB, "");
    LOG.info("Kerberos provider configured with keytab '{}'", keytabPath);
  }

  @Override
  public String validateSubject(CustosCredential credential)
      throws CustosException {
    if (credential.getType() != CredentialType.SPNEGO) {
      throw new CustosException("KerberosProvider cannot handle credential type "
          + credential.getType());
    }
    String servicePrincipal =
        credential.getAttribute(KerberosCredentials.SERVICE_PRINCIPAL_ATTRIBUTE);
    if (servicePrincipal == null || servicePrincipal.isEmpty()) {
      throw new CustosException("SPNEGO credential is missing "
          + KerberosCredentials.SERVICE_PRINCIPAL_ATTRIBUTE
          + "; the Ozone client must send the Custos service principal.");
    }
    return SpnegoValidator.validate(getConf(), servicePrincipal, keytabPath,
        credential.getMaterial());
  }
}
