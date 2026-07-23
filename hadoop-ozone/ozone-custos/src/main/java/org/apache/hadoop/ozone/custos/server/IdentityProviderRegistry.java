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

import static org.apache.hadoop.ozone.custos.server.CustosConfig.Keys.IDENTITY_PROVIDERS;

import java.util.Collection;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hdds.conf.OzoneConfiguration;
import org.apache.hadoop.ozone.custos.CustosException;
import org.apache.hadoop.ozone.custos.CustosIdentity;
import org.apache.hadoop.ozone.custos.identity.IdentityContext;
import org.apache.hadoop.ozone.custos.identity.IdentityProvider;
import org.apache.hadoop.ozone.custos.identity.IdentityProviderType;
import org.apache.hadoop.ozone.custos.identity.TokenBinding;
import org.apache.hadoop.ozone.custos.proto.CustosTokenProtos.CustosTokenProto;
import org.apache.hadoop.util.ReflectionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Loads {@link IdentityProvider} implementations named in
 * {@code ozone.custos.identity.providers} and routes a resolved subject to the
 * provider for its {@link IdentityProviderType}.
 */
public class IdentityProviderRegistry {

  private static final Logger LOG =
      LoggerFactory.getLogger(IdentityProviderRegistry.class);

  private final Map<IdentityProviderType, IdentityProvider> providers;

  public IdentityProviderRegistry(OzoneConfiguration conf) {
    this.providers = loadProviders(conf);
    LOG.info("Custos loaded {} identity provider(s) for type(s) {}",
        providers.size(), providers.keySet());
  }

  private static Map<IdentityProviderType, IdentityProvider> loadProviders(
      OzoneConfiguration conf) {
    Map<IdentityProviderType, IdentityProvider> loaded =
        new EnumMap<>(IdentityProviderType.class);
    Collection<String> classNames =
        conf.getTrimmedStringCollection(IDENTITY_PROVIDERS);
    for (String className : classNames) {
      IdentityProvider provider = instantiate(className, conf);
      IdentityProviderType type = provider.supportedType();
      IdentityProvider previous = loaded.put(type, provider);
      if (previous != null) {
        throw new IllegalStateException("Two identity providers claim type "
            + type + ": " + previous.getClass().getName() + " and "
            + provider.getClass().getName() + ". Each identity provider type"
            + " must map to exactly one provider in " + IDENTITY_PROVIDERS
            + ".");
      }
    }
    return loaded;
  }

  private static IdentityProvider instantiate(String className,
      OzoneConfiguration conf) {
    final Class<?> clazz;
    try {
      clazz = conf.getClassByName(className);
    } catch (ClassNotFoundException e) {
      throw new IllegalArgumentException("Identity provider class not found: "
          + className + ". Check " + IDENTITY_PROVIDERS + ".", e);
    }
    if (!IdentityProvider.class.isAssignableFrom(clazz)) {
      throw new IllegalArgumentException("Identity provider class " + className
          + " does not implement " + IdentityProvider.class.getName() + ".");
    }
    return (IdentityProvider) ReflectionUtils.newInstance(clazz,
        (Configuration) conf);
  }

  public CustosIdentity resolveIdentity(IdentityContext context)
      throws CustosException {
    IdentityProvider provider = providers.get(context.getProviderType());
    if (provider == null) {
      throw new CustosException("No identity provider is configured for type "
          + context.getProviderType() + ".");
    }
    return provider.resolveIdentity(context);
  }

  public CustosTokenProto.Builder associateToToken(CustosIdentity identity,
      IdentityProviderType type, TokenBinding binding) throws CustosException {
    IdentityProvider provider = providers.get(type);
    if (provider == null) {
      throw new CustosException("No identity provider is configured for type "
          + type + ".");
    }
    return provider.associateToToken(identity, binding);
  }

  public Set<IdentityProviderType> supportedTypes() {
    return providers.keySet();
  }
}
