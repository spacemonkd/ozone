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

/**
 * The backend that resolves an authenticated subject into the identity bound
 * to a {@code CustosToken}.
 *
 * <p>Authentication providers ({@link org.apache.hadoop.ozone.custos.CustosProvider})
 * validate raw credentials. Identity providers take the authenticated subject
 * (and any claims already extracted) and produce the full
 * {@link org.apache.hadoop.ozone.custos.CustosIdentity} that is associated with
 * the token — for example OIDC claim mapping, an LDAP group lookup, or the
 * local Hadoop group mapping for Kerberos.
 */
public enum IdentityProviderType {

  /** OpenID Connect: groups and roles come from token claims and/or userinfo. */
  OIDC,

  /** LDAP / Active Directory: groups resolved from directory membership. */
  LDAP,

  /** Kerberos: groups resolved via the configured Hadoop group mapping. */
  KERBEROS,

  /** Local Hadoop group mapping only; no external directory. */
  LOCAL
}
