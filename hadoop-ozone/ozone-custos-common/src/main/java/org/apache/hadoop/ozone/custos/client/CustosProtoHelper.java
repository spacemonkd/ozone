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

import org.apache.hadoop.ozone.custos.CredentialType;
import org.apache.hadoop.ozone.custos.CustosCredential;
import org.apache.hadoop.ozone.custos.CustosException;
import org.apache.hadoop.ozone.custos.proto.CustosTokenProtos.CredentialTypeProto;
import org.apache.hadoop.ozone.custos.proto.CustosTokenProtos.CustosCredentialProto;

/**
 * Translates between Java Custos types and protobuf wire messages.
 */
public final class CustosProtoHelper {

  private CustosProtoHelper() {
  }

  public static CustosCredentialProto toProto(CustosCredential credential) {
    CustosCredentialProto.Builder builder = CustosCredentialProto.newBuilder()
        .setType(toProto(credential.getType()))
        .setMaterial(com.google.protobuf.ByteString.copyFrom(credential.getMaterial()));
    builder.putAllAttributes(credential.getAttributes());
    return builder.build();
  }

  public static CustosCredential fromProto(CustosCredentialProto proto)
      throws CustosException {
    if (proto == null) {
      throw new CustosException("Credential must not be null.");
    }
    return new CustosCredential(fromProto(proto.getType()),
        proto.getMaterial().toByteArray(), proto.getAttributesMap());
  }

  public static CredentialTypeProto toProto(CredentialType type) {
    switch (type) {
    case OIDC_JWT:
      return CredentialTypeProto.OIDC_JWT;
    case SPNEGO:
      return CredentialTypeProto.SPNEGO;
    case S3_SIGV4:
      return CredentialTypeProto.S3_SIGV4;
    case DELEGATION_TOKEN:
      return CredentialTypeProto.DELEGATION_TOKEN;
    default:
      return CredentialTypeProto.CREDENTIAL_TYPE_UNKNOWN;
    }
  }

  public static CredentialType fromProto(CredentialTypeProto type)
      throws CustosException {
    switch (type) {
    case OIDC_JWT:
      return CredentialType.OIDC_JWT;
    case SPNEGO:
      return CredentialType.SPNEGO;
    case S3_SIGV4:
      return CredentialType.S3_SIGV4;
    case DELEGATION_TOKEN:
      return CredentialType.DELEGATION_TOKEN;
    case CREDENTIAL_TYPE_UNKNOWN:
    case UNRECOGNIZED:
    default:
      throw new CustosException("Unsupported credential type: " + type);
    }
  }
}
