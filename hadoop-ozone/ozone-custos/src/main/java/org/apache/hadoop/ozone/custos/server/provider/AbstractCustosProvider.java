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

import org.apache.hadoop.conf.Configurable;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hdds.conf.OzoneConfiguration;
import org.apache.hadoop.ozone.custos.CustosCredential;
import org.apache.hadoop.ozone.custos.CustosException;
import org.apache.hadoop.ozone.custos.CustosProvider;

/**
 * Base class for {@link CustosProvider} implementations.
 *
 * <p>Subclasses validate a credential and return the authenticated subject.
 * Identity resolution and token binding are handled by
 * {@link org.apache.hadoop.ozone.custos.identity.IdentityProvider} in
 * {@link org.apache.hadoop.ozone.custos.server.CustosAuthService}.
 */
public abstract class AbstractCustosProvider implements CustosProvider, Configurable {

  private OzoneConfiguration conf;

  @Override
  public void setConf(Configuration configuration) {
    this.conf = configuration instanceof OzoneConfiguration
        ? (OzoneConfiguration) configuration
        : new OzoneConfiguration(configuration);
    init();
  }

  @Override
  public OzoneConfiguration getConf() {
    return conf;
  }

  /**
   * Called once after the configuration is injected. Subclasses read their
   * settings here. The default is a no-op.
   */
  protected void init() {
  }

  @Override
  public abstract String validateSubject(CustosCredential credential)
      throws CustosException;
}
