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

import static org.apache.hadoop.ozone.custos.server.CustosConfig.Keys.PROVIDERS;

import java.util.Collection;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hdds.conf.OzoneConfiguration;
import org.apache.hadoop.ozone.custos.CredentialType;
import org.apache.hadoop.ozone.custos.CustosCredential;
import org.apache.hadoop.ozone.custos.CustosException;
import org.apache.hadoop.ozone.custos.CustosProvider;
import org.apache.hadoop.util.ReflectionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Loads {@link CustosProvider} implementations named in
 * {@code ozone.custos.providers} and routes an incoming {@link CustosCredential}
 * to the provider that declares support for its {@link CredentialType}.
 *
 * <p>Loading is config-driven so a new credential kind is added by configuration
 * alone. A misconfigured or unloadable provider class, or two providers claiming
 * the same credential type, fails construction with a clear error.
 */
public class CustosProviderRegistry {

  private static final Logger LOG =
      LoggerFactory.getLogger(CustosProviderRegistry.class);

  private final Map<CredentialType, CustosProvider> providers;

  public CustosProviderRegistry(OzoneConfiguration conf) {
    this.providers = loadProviders(conf);
    LOG.info("Custos loaded {} provider(s) for credential type(s) {}",
        providers.size(), providers.keySet());
  }

  private static Map<CredentialType, CustosProvider> loadProviders(
      OzoneConfiguration conf) {
    Map<CredentialType, CustosProvider> loaded =
        new EnumMap<>(CredentialType.class);
    Collection<String> classNames =
        conf.getTrimmedStringCollection(PROVIDERS);
    for (String className : classNames) {
      CustosProvider provider = instantiate(className, conf);
      CredentialType type = provider.supportedType();
      CustosProvider previous = loaded.put(type, provider);
      if (previous != null) {
        throw new IllegalStateException("Two Custos providers claim credential"
            + " type " + type + ": " + previous.getClass().getName() + " and "
            + provider.getClass().getName() + ". Each credential type must map"
            + " to exactly one provider in " + PROVIDERS + ".");
      }
    }
    return loaded;
  }

  private static CustosProvider instantiate(String className,
      OzoneConfiguration conf) {
    final Class<?> clazz;
    try {
      clazz = conf.getClassByName(className);
    } catch (ClassNotFoundException e) {
      throw new IllegalArgumentException("Custos provider class not found: "
          + className + ". Check " + PROVIDERS + ".", e);
    }
    if (!CustosProvider.class.isAssignableFrom(clazz)) {
      throw new IllegalArgumentException("Custos provider class " + className
          + " does not implement " + CustosProvider.class.getName() + ".");
    }
    // ReflectionUtils injects the configuration when the provider implements
    // Configurable, matching how Ozone loads other pluggable classes.
    return (CustosProvider) ReflectionUtils.newInstance(clazz,
        (Configuration) conf);
  }

  /**
   * Validate the credential and return the authenticated subject.
   *
   * @throws CustosException if no provider handles the credential type or the
   *     provider rejects the credential.
   */
  public String validateSubject(CustosCredential credential)
      throws CustosException {
    CustosProvider provider = providers.get(credential.getType());
    if (provider == null) {
      throw new CustosException("No Custos provider is configured for"
          + " credential type " + credential.getType() + ".");
    }
    return provider.validateSubject(credential);
  }

  /**
   * @return the credential types this registry can authenticate.
   */
  public Set<CredentialType> supportedTypes() {
    return providers.keySet();
  }
}
