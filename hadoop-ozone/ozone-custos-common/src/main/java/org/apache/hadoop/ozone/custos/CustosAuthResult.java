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

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Result of {@link CustosProvider#authenticate(CustosCredential)}.
 *
 * <p>Carries the authenticated subject plus any claims the provider extracted
 * while validating the credential (for example OIDC groups, roles, and issuer).
 * These claims flow into the {@code IdentityContext} so they can be bound into
 * the issued token. Providers that only authenticate a subject, and rely on a
 * separate identity provider for groups, return {@link #of(String)}.
 */
public final class CustosAuthResult {

  private final String subject;
  private final List<String> claimGroups;
  private final List<String> claimRoles;
  private final String issuer;
  private final Map<String, String> attributes;

  private CustosAuthResult(Builder b) {
    this.subject = Objects.requireNonNull(b.subject, "subject");
    this.claimGroups = Collections.unmodifiableList(b.claimGroups);
    this.claimRoles = Collections.unmodifiableList(b.claimRoles);
    this.issuer = b.issuer;
    this.attributes = Collections.unmodifiableMap(new HashMap<>(b.attributes));
  }

  /**
   * A result carrying only the subject, with no extracted claims.
   */
  public static CustosAuthResult of(String subject) {
    return newBuilder().setSubject(subject).build();
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

  public static Builder newBuilder() {
    return new Builder();
  }

  /**
   * Builder for {@link CustosAuthResult}.
   */
  public static final class Builder {
    private String subject;
    private List<String> claimGroups = Collections.emptyList();
    private List<String> claimRoles = Collections.emptyList();
    private String issuer;
    private Map<String, String> attributes = Collections.emptyMap();

    private Builder() {
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

    public CustosAuthResult build() {
      return new CustosAuthResult(this);
    }
  }
}
