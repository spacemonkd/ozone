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

package org.apache.hadoop.ozone.custos.identity;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Input to an {@link IdentityProvider} after a credential has been authenticated.
 *
 * <p>Carries the authenticated subject and any claims the auth step already
 * extracted (for example OIDC groups). Identity providers use this to resolve
 * the full identity that will be bound to a {@code CustosToken}.
 */
public final class IdentityContext {

  private final IdentityProviderType providerType;
  private final String subject;
  private final List<String> claimGroups;
  private final List<String> claimRoles;
  private final String issuer;
  private final Map<String, String> attributes;

  private IdentityContext(Builder b) {
    this.providerType = Objects.requireNonNull(b.providerType, "providerType");
    this.subject = Objects.requireNonNull(b.subject, "subject");
    this.claimGroups = Collections.unmodifiableList(b.claimGroups);
    this.claimRoles = Collections.unmodifiableList(b.claimRoles);
    this.issuer = b.issuer;
    this.attributes = Collections.unmodifiableMap(
        new HashMap<>(b.attributes));
  }

  public IdentityProviderType getProviderType() {
    return providerType;
  }

  public String getSubject() {
    return subject;
  }

  public List<String> getClaimGroups() {
    return claimGroups;
  }

  public List<String> getClaimRoles() {
    return claimRoles;
  }

  public String getIssuer() {
    return issuer;
  }

  public Map<String, String> getAttributes() {
    return attributes;
  }

  public String getAttribute(String name) {
    return attributes.get(name);
  }

  public static Builder newBuilder() {
    return new Builder();
  }

  /**
   * Builder for {@link IdentityContext}.
   */
  public static final class Builder {
    private IdentityProviderType providerType;
    private String subject;
    private List<String> claimGroups = Collections.emptyList();
    private List<String> claimRoles = Collections.emptyList();
    private String issuer;
    private Map<String, String> attributes = Collections.emptyMap();

    private Builder() {
    }

    public Builder setProviderType(IdentityProviderType value) {
      this.providerType = value;
      return this;
    }

    public Builder setSubject(String value) {
      this.subject = value;
      return this;
    }

    public Builder setClaimGroups(List<String> value) {
      this.claimGroups = value == null ? Collections.emptyList() : value;
      return this;
    }

    public Builder setClaimRoles(List<String> value) {
      this.claimRoles = value == null ? Collections.emptyList() : value;
      return this;
    }

    public Builder setIssuer(String value) {
      this.issuer = value;
      return this;
    }

    public Builder setAttributes(Map<String, String> value) {
      this.attributes = value == null ? Collections.emptyMap() : value;
      return this;
    }

    public IdentityContext build() {
      return new IdentityContext(this);
    }
  }
}
