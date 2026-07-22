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

/**
 * Configuration keys for the Custos auth service.
 */
public final class CustosConfigKeys {

  /**
   * Master switch for the Custos feature. When {@code false}, no client or OM
   * path uses Custos and Ozone behaves exactly as it does today.
   */
  public static final String OZONE_CUSTOS_ENABLED = "ozone.custos.enabled";
  public static final boolean OZONE_CUSTOS_ENABLED_DEFAULT = false;

  /**
   * Comma-separated list of {@code CustosProvider} implementation class names
   * to load, mirroring how {@code ozone.acl.authorizer.class} is configured.
   */
  public static final String OZONE_CUSTOS_PROVIDERS = "ozone.custos.providers";

  /** Host the Custos HTTP server binds to. */
  public static final String OZONE_CUSTOS_HTTP_BIND_HOST =
      "ozone.custos.http-bind-host";
  public static final String OZONE_CUSTOS_HTTP_BIND_HOST_DEFAULT = "0.0.0.0";

  /** Port the Custos HTTP server (health endpoint) listens on. */
  public static final String OZONE_CUSTOS_HTTP_BIND_PORT =
      "ozone.custos.http-bind-port";
  public static final int OZONE_CUSTOS_HTTP_BIND_PORT_DEFAULT = 9893;

  /**
   * Never constructed.
   */
  private CustosConfigKeys() {
  }
}
