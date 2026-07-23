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

package org.apache.hadoop.ozone.custos.server.identity;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import org.apache.hadoop.conf.Configurable;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hdds.conf.OzoneConfiguration;
import org.apache.hadoop.ozone.custos.CustosException;
import org.apache.hadoop.ozone.custos.identity.AbstractIdentityProvider;
import org.apache.hadoop.ozone.custos.identity.IdentityContext;
import org.apache.hadoop.ozone.custos.identity.IdentityProviderType;
import org.apache.hadoop.security.Groups;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Resolves group membership through the configured Hadoop
 * {@code GroupMappingServiceProvider} for Kerberos-authenticated subjects.
 */
public class KerberosIdentityProvider extends AbstractIdentityProvider
    implements Configurable {

  private static final Logger LOG =
      LoggerFactory.getLogger(KerberosIdentityProvider.class);

  private OzoneConfiguration conf;

  @Override
  public void setConf(Configuration configuration) {
    this.conf = configuration instanceof OzoneConfiguration
        ? (OzoneConfiguration) configuration
        : new OzoneConfiguration(configuration);
  }

  @Override
  public OzoneConfiguration getConf() {
    return conf;
  }

  @Override
  public IdentityProviderType supportedType() {
    return IdentityProviderType.KERBEROS;
  }

  @Override
  protected List<String> resolveGroups(IdentityContext context)
      throws CustosException {
    if (context.getClaimGroups() != null && !context.getClaimGroups().isEmpty()) {
      return context.getClaimGroups();
    }
    try {
      return Groups.getUserToGroupsMappingService(conf)
          .getGroups(context.getSubject());
    } catch (IOException e) {
      LOG.warn("Could not resolve groups for user {}", context.getSubject(), e);
      return Collections.emptyList();
    }
  }
}
