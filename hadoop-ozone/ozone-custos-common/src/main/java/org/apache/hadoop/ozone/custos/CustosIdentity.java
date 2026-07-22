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

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * The verified identity a {@link CustosProvider} returns after authenticating
 * a credential.
 *
 * <p>Its fields map directly onto the {@code CustosToken} Custos issues:
 * {@code subject}, {@code groups}, {@code issuer} and {@code authMethod} become
 * the token's subject/groups/issuer/authProvider, and {@code authenticatedAt} /
 * {@code expiresAt} become issuedAt / expiry. Instances are immutable; build
 * them with {@link #newBuilder()}.
 */
public final class CustosIdentity {

  private final String subject;
  private final List<String> groups;
  private final List<String> roles;
  private final String issuer;
  private final String authMethod;
  private final Instant authenticatedAt;
  private final Instant expiresAt;

  private CustosIdentity(Builder b) {
    this.subject = Objects.requireNonNull(b.subject, "subject");
    this.groups = Collections.unmodifiableList(new ArrayList<>(b.groups));
    this.roles = Collections.unmodifiableList(new ArrayList<>(b.roles));
    this.issuer = b.issuer;
    this.authMethod = b.authMethod;
    this.authenticatedAt = b.authenticatedAt;
    this.expiresAt = b.expiresAt;
  }

  public String getSubject() {
    return subject;
  }

  public List<String> getGroups() {
    return groups;
  }

  public List<String> getRoles() {
    return roles;
  }

  public String getIssuer() {
    return issuer;
  }

  public String getAuthMethod() {
    return authMethod;
  }

  public Instant getAuthenticatedAt() {
    return authenticatedAt;
  }

  public Instant getExpiresAt() {
    return expiresAt;
  }

  @Override
  public String toString() {
    return "CustosIdentity{subject=" + subject
        + ", groups=" + groups
        + ", roles=" + roles
        + ", issuer=" + issuer
        + ", authMethod=" + authMethod
        + ", authenticatedAt=" + authenticatedAt
        + ", expiresAt=" + expiresAt + '}';
  }

  public static Builder newBuilder() {
    return new Builder();
  }

  /**
   * Builder for {@link CustosIdentity}. Only {@code subject} is required.
   */
  public static final class Builder {
    private String subject;
    private List<String> groups = new ArrayList<>();
    private List<String> roles = new ArrayList<>();
    private String issuer;
    private String authMethod;
    private Instant authenticatedAt;
    private Instant expiresAt;

    private Builder() {
    }

    public Builder setSubject(String value) {
      this.subject = value;
      return this;
    }

    public Builder setGroups(List<String> value) {
      this.groups = value == null ? new ArrayList<>() : new ArrayList<>(value);
      return this;
    }

    public Builder setRoles(List<String> value) {
      this.roles = value == null ? new ArrayList<>() : new ArrayList<>(value);
      return this;
    }

    public Builder setIssuer(String value) {
      this.issuer = value;
      return this;
    }

    public Builder setAuthMethod(String value) {
      this.authMethod = value;
      return this;
    }

    public Builder setAuthenticatedAt(Instant value) {
      this.authenticatedAt = value;
      return this;
    }

    public Builder setExpiresAt(Instant value) {
      this.expiresAt = value;
      return this;
    }

    public CustosIdentity build() {
      return new CustosIdentity(this);
    }
  }
}
