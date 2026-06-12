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

package org.apache.hadoop.ozone.om.execution;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import com.google.protobuf.ByteString;
import org.apache.hadoop.hdds.utils.db.Codec;
import org.apache.hadoop.hdds.utils.db.StringCodec;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos.DBDelta;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos.DeltaType;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos.OMResponse;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos.ReplicatedStateTransition;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos.Type;

/**
 * Builds a {@link ReplicatedStateTransition} by accumulating DB
 * delta operations (puts and deletes) and a response. Used by
 * {@link PlannedRequest} implementations during the planning phase
 * to declare their DB mutations in a deterministic, serializable form.
 *
 * <p>This class is request-scoped and not thread-safe. Each request
 * gets its own builder instance.
 */
public final class TransitionBuilder {

  private final long managedIndex;
  private final List<DBDelta> deltas = new ArrayList<>();
  private OMResponse response;

  public TransitionBuilder() {
    this(0);
  }

  public TransitionBuilder(long managedIndex) {
    this.managedIndex = managedIndex;
  }

  /**
   * Returns the managed index assigned to this request.
   * Used by request handlers for object ID generation.
   */
  public long getManagedIndex() {
    return managedIndex;
  }

  /**
   * Records a PUT operation with typed key and value.
   * The key is serialized via {@link StringCodec} and value via
   * the provided codec.
   */
  public <V> void put(String tableName, String key, V value, Codec<V> valueCodec) throws IOException {
    byte[] keyBytes = StringCodec.get().toPersistedFormat(key);
    byte[] valueBytes = valueCodec.toPersistedFormat(value);
    putRaw(tableName, keyBytes, valueBytes);
  }

  /**
   * Records a DELETE operation with a typed key.
   */
  public void delete(String tableName, String key) throws IOException {
    byte[] keyBytes = StringCodec.get().toPersistedFormat(key);
    deleteRaw(tableName, keyBytes);
  }

  /**
   * Records a PUT operation with pre-serialized key and value bytes.
   */
  public void putRaw(String tableName, byte[] key, byte[] value) {
    deltas.add(DBDelta.newBuilder()
        .setTableName(tableName)
        .setKey(ByteString.copyFrom(key))
        .setValue(ByteString.copyFrom(value))
        .setType(DeltaType.PUT)
        .build());
  }

  /**
   * Records a DELETE operation with a pre-serialized key.
   */
  public void deleteRaw(String tableName, byte[] key) {
    deltas.add(DBDelta.newBuilder()
        .setTableName(tableName)
        .setKey(ByteString.copyFrom(key))
        .setType(DeltaType.DELETE)
        .build());
  }

  /**
   * Sets the OMResponse to be returned to the client.
   */
  public void setResponse(OMResponse omResponse) {
    this.response = omResponse;
  }

  /**
   * Returns the OMResponse previously set, or null if not set.
   */
  public OMResponse getResponse() {
    return response;
  }

  /**
   * Returns the number of delta operations accumulated.
   */
  public int size() {
    return deltas.size();
  }

  /**
   * Builds the {@link ReplicatedStateTransition} proto from the
   * accumulated deltas and response.
   *
   * @param managedIndex the unique index assigned to this request
   * @param cmdType the command type for barrier detection
   */
  public ReplicatedStateTransition build(long managedIndex, Type cmdType) {
    if (response == null) {
      throw new IllegalStateException("Response must be set before building");
    }
    return ReplicatedStateTransition.newBuilder()
        .setManagedIndex(managedIndex)
        .addAllDeltas(deltas)
        .setResponse(response)
        .setCmdType(cmdType)
        .build();
  }
}
