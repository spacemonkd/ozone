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

/**
 * The Custos authentication plugin contract.
 *
 * <p>Each credential kind is validated by one provider. Custos loads providers
 * by class name from {@code ozone.custos.providers} and routes an incoming
 * {@link CustosCredential} to the provider whose {@link #supportedType()}
 * matches. Adding a new credential kind is implementing this one interface and
 * listing the class in configuration -- no change to Custos itself.
 *
 * <p>Implementations must have a public no-argument constructor. A provider
 * that needs cluster configuration may also implement
 * {@link org.apache.hadoop.conf.Configurable}; the loader will inject the
 * {@code OzoneConfiguration} before use.
 */
public interface CustosProvider {

  /**
   * @return the credential kind this provider validates. Custos uses this to
   *     route incoming credentials.
   */
  CredentialType supportedType();

  /**
   * Validate the credential and return the identity it proves.
   *
   * @param credential the client-presented credential
   * @return the verified identity
   * @throws CustosException if the credential is invalid, expired, or cannot
   *     be validated. Implementations must not include raw credential material
   *     in the exception message.
   */
  CustosIdentity authenticate(CustosCredential credential)
      throws CustosException;
}
