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
import java.util.Map;
import java.util.Objects;

/**
 * The single input type every {@link CustosProvider} receives.
 *
 * <p>It carries the credential {@link CredentialType} and the raw credential
 * material (a bearer token, a SPNEGO token, or S3 SigV4 bytes). Structured
 * fields that do not fit a single byte array (for example an S3 access id or
 * string-to-sign) travel in {@link #getAttributes() attributes} so all
 * providers can share one input and Custos can route purely on type.
 *
 * <p>Instances are immutable. The raw material must never be logged.
 */
public final class CustosCredential {

  private final CredentialType type;
  private final byte[] material;
  private final Map<String, String> attributes;

  public CustosCredential(CredentialType type, byte[] material) {
    this(type, material, Collections.emptyMap());
  }

  public CustosCredential(CredentialType type, byte[] material,
      Map<String, String> attributes) {
    this.type = Objects.requireNonNull(type, "type");
    this.material = material == null ? new byte[0] : material.clone();
    this.attributes = Collections.unmodifiableMap(
        new HashMap<>(attributes == null ? Collections.emptyMap() : attributes));
  }

  public CredentialType getType() {
    return type;
  }

  /**
   * @return a copy of the raw credential material; never {@code null}.
   */
  public byte[] getMaterial() {
    return material.clone();
  }

  /**
   * @return additional, structured credential fields keyed by name.
   */
  public Map<String, String> getAttributes() {
    return attributes;
  }

  public String getAttribute(String name) {
    return attributes.get(name);
  }

  @Override
  public String toString() {
    // Deliberately omits material and attribute values to avoid leaking
    // credential bytes into logs.
    return "CustosCredential{type=" + type
        + ", materialLength=" + material.length
        + ", attributeKeys=" + attributes.keySet() + '}';
  }
}
