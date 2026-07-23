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

import java.util.List;
import org.apache.hadoop.ozone.custos.CustosException;
import org.apache.hadoop.ozone.custos.identity.AbstractIdentityProvider;
import org.apache.hadoop.ozone.custos.identity.IdentityContext;
import org.apache.hadoop.ozone.custos.identity.IdentityProviderType;

/**
 * Resolves identity from OIDC token claims and userinfo. Groups and roles are
 * taken from the {@link IdentityContext} claim fields populated during JWT
 * validation; a future implementation may also call the OIDC userinfo endpoint.
 */
public class OidcIdentityProvider extends AbstractIdentityProvider {

  @Override
  public IdentityProviderType supportedType() {
    return IdentityProviderType.OIDC;
  }

  @Override
  protected List<String> resolveGroups(IdentityContext context)
      throws CustosException {
    // Claim groups are set by the OIDC auth step; userinfo lookup is TODO.
    return super.resolveGroups(context);
  }
}
