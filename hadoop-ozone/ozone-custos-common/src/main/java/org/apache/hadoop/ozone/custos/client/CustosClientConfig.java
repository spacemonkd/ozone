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

package org.apache.hadoop.ozone.custos.client;

/**
 * Configuration keys for the Ozone Java client's Custos gRPC client.
 *
 * <p>Native Ozone clients continue to use Hadoop RPC for OM. Custos is reached
 * over gRPC only for {@code GetSessionToken} / {@code RenewSessionToken}.
 */
public final class CustosClientConfig {

  public static final String ENABLED = "ozone.custos.enabled";
  public static final String GRPC_HOST = "ozone.custos.grpc.host";
  public static final String GRPC_PORT = "ozone.custos.grpc.port";
  public static final String GRPC_DEADLINE_MS = "ozone.custos.grpc.deadline.ms";

  public static final String GRPC_HOST_DEFAULT = "localhost";
  public static final int GRPC_PORT_DEFAULT = 9894;
  public static final long GRPC_DEADLINE_MS_DEFAULT = 30_000L;

  private CustosClientConfig() {
  }
}
