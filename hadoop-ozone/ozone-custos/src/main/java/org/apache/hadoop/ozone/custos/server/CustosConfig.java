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

import org.apache.hadoop.hdds.conf.Config;
import org.apache.hadoop.hdds.conf.ConfigGroup;
import org.apache.hadoop.hdds.conf.ConfigTag;
import org.apache.hadoop.hdds.conf.ConfigType;

/**
 * Configuration for the Custos auth service.
 *
 * <p>Provider-specific settings that the Custos service owns (for example the
 * Kerberos service keytab) live here; a provider reads them through the
 * {@code OzoneConfiguration} injected into it. The Custos Kerberos service
 * principal is configured on the Ozone client and sent with the SPNEGO token.
 */
@ConfigGroup(prefix = "ozone.custos")
public class CustosConfig {

  @Config(key = "ozone.custos.enabled",
      defaultValue = "false",
      type = ConfigType.BOOLEAN,
      tags = {ConfigTag.SECURITY},
      description = "Master switch for the Custos auth service. When false, no "
          + "client or OM path uses Custos and Ozone behaves as it does today.")
  private boolean enabled = Defaults.ENABLED;

  @Config(key = "ozone.custos.providers",
      defaultValue = "",
      type = ConfigType.STRING,
      tags = {ConfigTag.SECURITY},
      description = "Comma-separated list of CustosProvider implementation class "
          + "names to load, mirroring how ozone.acl.authorizer.class is "
          + "configured. Each credential type must map to exactly one provider.")
  private String providers = Defaults.PROVIDERS;

  @Config(key = "ozone.custos.http-bind-host",
      defaultValue = "0.0.0.0",
      type = ConfigType.STRING,
      tags = {ConfigTag.SECURITY},
      description = "Host the Custos HTTP server binds to.")
  private String httpBindHost = Defaults.HTTP_BIND_HOST;

  @Config(key = "ozone.custos.http-bind-port",
      defaultValue = "9893",
      type = ConfigType.INT,
      tags = {ConfigTag.SECURITY},
      description = "Port the Custos HTTP server (health endpoint) listens on.")
  private int httpBindPort = Defaults.HTTP_BIND_PORT;

  @Config(key = "ozone.custos.grpc-bind-host",
      defaultValue = "0.0.0.0",
      type = ConfigType.STRING,
      tags = {ConfigTag.SECURITY},
      description = "Host the Custos gRPC server binds to.")
  private String grpcBindHost = Defaults.GRPC_BIND_HOST;

  @Config(key = "ozone.custos.grpc-bind-port",
      defaultValue = "9894",
      type = ConfigType.INT,
      tags = {ConfigTag.SECURITY},
      description = "Port the Custos gRPC server listens on.")
  private int grpcBindPort = Defaults.GRPC_BIND_PORT;

  @Config(key = "ozone.custos.kerberos.keytab",
      defaultValue = "",
      type = ConfigType.STRING,
      tags = {ConfigTag.SECURITY},
      description = "Path to the keytab holding the Custos Kerberos service "
          + "principal used to validate client SPNEGO tokens. The service "
          + "principal itself is sent by the Ozone client in the credential.")
  private String kerberosKeytab = Defaults.KERBEROS_KEYTAB;

  @Config(key = "ozone.custos.identity.providers",
      defaultValue = "",
      type = ConfigType.STRING,
      tags = {ConfigTag.SECURITY},
      description = "Comma-separated list of IdentityProvider implementation "
          + "class names to load (for example OIDC, LDAP). Each identity "
          + "provider type must map to exactly one implementation.")
  private String identityProviders = Defaults.IDENTITY_PROVIDERS;

  @Config(key = "ozone.custos.token.audience",
      defaultValue = "",
      type = ConfigType.STRING,
      tags = {ConfigTag.SECURITY},
      description = "Audience Custos stamps on issued tokens (the OM cluster / "
          + "service id) and returns from GetClusterInfo, so a client can omit "
          + "it. When empty, the OM service id is used if configured. This is "
          + "distinct from ozone.custos.oidc.audience (the expected JWT aud).")
  private String tokenAudience = Defaults.TOKEN_AUDIENCE;

  @Config(key = "ozone.custos.oidc.issuer",
      defaultValue = "",
      type = ConfigType.STRING,
      tags = {ConfigTag.SECURITY},
      description = "Expected OIDC token issuer (the iss claim). Also the base "
          + "for JWKS discovery when ozone.custos.oidc.jwks-uri is not set.")
  private String oidcIssuer = Defaults.OIDC_ISSUER;

  @Config(key = "ozone.custos.oidc.audience",
      defaultValue = "",
      type = ConfigType.STRING,
      tags = {ConfigTag.SECURITY},
      description = "Expected OIDC token audience (the aud claim).")
  private String oidcAudience = Defaults.OIDC_AUDIENCE;

  @Config(key = "ozone.custos.oidc.jwks-uri",
      defaultValue = "",
      type = ConfigType.STRING,
      tags = {ConfigTag.SECURITY},
      description = "Explicit JWKS URL for the OIDC issuer's signing keys. When "
          + "empty, it is discovered from the issuer's OpenID configuration.")
  private String oidcJwksUri = Defaults.OIDC_JWKS_URI;

  @Config(key = "ozone.custos.oidc.username-claim",
      defaultValue = "sub",
      type = ConfigType.STRING,
      tags = {ConfigTag.SECURITY},
      description = "JWT claim to use as the authenticated subject.")
  private String oidcUsernameClaim = Defaults.OIDC_USERNAME_CLAIM;

  @Config(key = "ozone.custos.oidc.groups-claim",
      defaultValue = "groups",
      type = ConfigType.STRING,
      tags = {ConfigTag.SECURITY},
      description = "JWT claim to read group membership from.")
  private String oidcGroupsClaim = Defaults.OIDC_GROUPS_CLAIM;

  @Config(key = "ozone.custos.oidc.roles-claim",
      defaultValue = "roles",
      type = ConfigType.STRING,
      tags = {ConfigTag.SECURITY},
      description = "JWT claim to read roles from.")
  private String oidcRolesClaim = Defaults.OIDC_ROLES_CLAIM;

  @Config(key = "ozone.custos.oidc.clock-skew-seconds",
      defaultValue = "30",
      type = ConfigType.LONG,
      tags = {ConfigTag.SECURITY},
      description = "Allowed clock skew, in seconds, when checking OIDC token "
          + "expiry and not-before times.")
  private long oidcClockSkewSeconds = Defaults.OIDC_CLOCK_SKEW_SECONDS;

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean value) {
    this.enabled = value;
  }

  public String getProviders() {
    return providers;
  }

  public void setProviders(String value) {
    this.providers = value;
  }

  public String getHttpBindHost() {
    return httpBindHost;
  }

  public void setHttpBindHost(String value) {
    this.httpBindHost = value;
  }

  public int getHttpBindPort() {
    return httpBindPort;
  }

  public void setHttpBindPort(int value) {
    this.httpBindPort = value;
  }

  public String getGrpcBindHost() {
    return grpcBindHost;
  }

  public void setGrpcBindHost(String value) {
    this.grpcBindHost = value;
  }

  public int getGrpcBindPort() {
    return grpcBindPort;
  }

  public void setGrpcBindPort(int value) {
    this.grpcBindPort = value;
  }

  public String getKerberosKeytab() {
    return kerberosKeytab;
  }

  public void setKerberosKeytab(String value) {
    this.kerberosKeytab = value;
  }

  public String getIdentityProviders() {
    return identityProviders;
  }

  public void setIdentityProviders(String value) {
    this.identityProviders = value;
  }

  public String getTokenAudience() {
    return tokenAudience;
  }

  public void setTokenAudience(String value) {
    this.tokenAudience = value;
  }

  public String getOidcIssuer() {
    return oidcIssuer;
  }

  public void setOidcIssuer(String value) {
    this.oidcIssuer = value;
  }

  public String getOidcAudience() {
    return oidcAudience;
  }

  public void setOidcAudience(String value) {
    this.oidcAudience = value;
  }

  public String getOidcJwksUri() {
    return oidcJwksUri;
  }

  public void setOidcJwksUri(String value) {
    this.oidcJwksUri = value;
  }

  public String getOidcUsernameClaim() {
    return oidcUsernameClaim;
  }

  public void setOidcUsernameClaim(String value) {
    this.oidcUsernameClaim = value;
  }

  public String getOidcGroupsClaim() {
    return oidcGroupsClaim;
  }

  public void setOidcGroupsClaim(String value) {
    this.oidcGroupsClaim = value;
  }

  public String getOidcRolesClaim() {
    return oidcRolesClaim;
  }

  public void setOidcRolesClaim(String value) {
    this.oidcRolesClaim = value;
  }

  public long getOidcClockSkewSeconds() {
    return oidcClockSkewSeconds;
  }

  public void setOidcClockSkewSeconds(long value) {
    this.oidcClockSkewSeconds = value;
  }

  /**
   * Full string keys, for tests and for callers (OM, clients) that set them.
   */
  public static final class Keys {
    public static final String ENABLED = "ozone.custos.enabled";
    public static final String PROVIDERS = "ozone.custos.providers";
    public static final String HTTP_BIND_HOST = "ozone.custos.http-bind-host";
    public static final String HTTP_BIND_PORT = "ozone.custos.http-bind-port";
    public static final String GRPC_BIND_HOST = "ozone.custos.grpc-bind-host";
    public static final String GRPC_BIND_PORT = "ozone.custos.grpc-bind-port";
    public static final String KERBEROS_KEYTAB =
        "ozone.custos.kerberos.keytab";
    public static final String IDENTITY_PROVIDERS =
        "ozone.custos.identity.providers";
    public static final String TOKEN_AUDIENCE = "ozone.custos.token.audience";
    public static final String OIDC_ISSUER = "ozone.custos.oidc.issuer";
    public static final String OIDC_AUDIENCE = "ozone.custos.oidc.audience";
    public static final String OIDC_JWKS_URI = "ozone.custos.oidc.jwks-uri";
    public static final String OIDC_USERNAME_CLAIM =
        "ozone.custos.oidc.username-claim";
    public static final String OIDC_GROUPS_CLAIM =
        "ozone.custos.oidc.groups-claim";
    public static final String OIDC_ROLES_CLAIM =
        "ozone.custos.oidc.roles-claim";
    public static final String OIDC_CLOCK_SKEW_SECONDS =
        "ozone.custos.oidc.clock-skew-seconds";

    private Keys() {
    }
  }

  /**
   * Default values, for tests.
   */
  public static final class Defaults {
    public static final boolean ENABLED = false;
    public static final String PROVIDERS = "";
    public static final String HTTP_BIND_HOST = "0.0.0.0";
    public static final int HTTP_BIND_PORT = 9893;
    public static final String GRPC_BIND_HOST = "0.0.0.0";
    public static final int GRPC_BIND_PORT = 9894;
    public static final String KERBEROS_KEYTAB = "";
    public static final String IDENTITY_PROVIDERS = "";
    public static final String TOKEN_AUDIENCE = "";
    public static final String OIDC_ISSUER = "";
    public static final String OIDC_AUDIENCE = "";
    public static final String OIDC_JWKS_URI = "";
    public static final String OIDC_USERNAME_CLAIM = "sub";
    public static final String OIDC_GROUPS_CLAIM = "groups";
    public static final String OIDC_ROLES_CLAIM = "roles";
    public static final long OIDC_CLOCK_SKEW_SECONDS = 30;

    private Defaults() {
    }
  }
}
