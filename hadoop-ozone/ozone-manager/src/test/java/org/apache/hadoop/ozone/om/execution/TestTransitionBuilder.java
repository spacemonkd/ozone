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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;

import org.apache.hadoop.hdds.utils.db.StringCodec;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos.DBDelta;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos.DeltaType;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos.OMResponse;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos.ReplicatedStateTransition;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos.Status;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos.Type;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link TransitionBuilder}.
 */
class TestTransitionBuilder {

  @Test
  void testPutWithCodec() throws IOException {
    TransitionBuilder builder = new TransitionBuilder();
    builder.put("volumeTable", "/vol1", "volumeArgs", StringCodec.get());
    builder.setResponse(okResponse(Type.CreateVolume));

    ReplicatedStateTransition result = builder.build(42, Type.CreateVolume);

    assertEquals(42, result.getManagedIndex());
    assertEquals(Type.CreateVolume, result.getCmdType());
    assertEquals(1, result.getDeltasCount());

    DBDelta delta = result.getDeltas(0);
    assertEquals("volumeTable", delta.getTableName());
    assertEquals(DeltaType.PUT, delta.getType());
    assertArrayEquals(StringCodec.get().toPersistedFormat("/vol1"),
        delta.getKey().toByteArray());
    assertArrayEquals(StringCodec.get().toPersistedFormat("volumeArgs"),
        delta.getValue().toByteArray());
  }

  @Test
  void testDeleteWithStringKey() throws IOException {
    TransitionBuilder builder = new TransitionBuilder();
    builder.delete("openKeyTable", "/vol/bucket/key#123");
    builder.setResponse(okResponse(Type.CommitKey));

    ReplicatedStateTransition result = builder.build(10, Type.CommitKey);

    assertEquals(1, result.getDeltasCount());
    DBDelta delta = result.getDeltas(0);
    assertEquals("openKeyTable", delta.getTableName());
    assertEquals(DeltaType.DELETE, delta.getType());
    assertArrayEquals(StringCodec.get().toPersistedFormat("/vol/bucket/key#123"),
        delta.getKey().toByteArray());
    assertEquals(false, delta.hasValue());
  }

  @Test
  void testMultipleDeltas() throws IOException {
    TransitionBuilder builder = new TransitionBuilder();
    builder.put("keyTable", "/vol/bucket/key", "keyInfo", StringCodec.get());
    builder.delete("openKeyTable", "/vol/bucket/key#sess");
    builder.put("bucketTable", "/vol/bucket", "bucketInfo", StringCodec.get());
    builder.setResponse(okResponse(Type.CommitKey));

    ReplicatedStateTransition result = builder.build(5, Type.CommitKey);
    assertEquals(3, result.getDeltasCount());
    assertEquals(3, builder.size());
  }

  @Test
  void testRawPutAndDelete() {
    TransitionBuilder builder = new TransitionBuilder();
    byte[] key = new byte[]{1, 2, 3};
    byte[] value = new byte[]{4, 5, 6};
    builder.putRaw("someTable", key, value);
    builder.deleteRaw("otherTable", new byte[]{7, 8});
    builder.setResponse(okResponse(Type.CreateKey));

    ReplicatedStateTransition result = builder.build(1, Type.CreateKey);
    assertEquals(2, result.getDeltasCount());
    assertArrayEquals(key, result.getDeltas(0).getKey().toByteArray());
    assertArrayEquals(value, result.getDeltas(0).getValue().toByteArray());
  }

  @Test
  void testBuildWithoutResponseThrows() throws IOException {
    TransitionBuilder builder = new TransitionBuilder();
    builder.put("table", "key", "value", StringCodec.get());
    assertThrows(IllegalStateException.class,
        () -> builder.build(1, Type.CreateKey));
  }

  @Test
  void testEmptyDeltasAllowed() {
    TransitionBuilder builder = new TransitionBuilder();
    builder.setResponse(errorResponse(Type.CreateVolume));

    ReplicatedStateTransition result = builder.build(99, Type.CreateVolume);
    assertEquals(0, result.getDeltasCount());
    assertEquals(Status.VOLUME_ALREADY_EXISTS, result.getResponse().getStatus());
  }

  private static OMResponse okResponse(Type cmdType) {
    return OMResponse.newBuilder()
        .setCmdType(cmdType)
        .setStatus(Status.OK)
        .setSuccess(true)
        .build();
  }

  private static OMResponse errorResponse(Type cmdType) {
    return OMResponse.newBuilder()
        .setCmdType(cmdType)
        .setStatus(Status.VOLUME_ALREADY_EXISTS)
        .setSuccess(false)
        .setMessage("Volume already exists")
        .build();
  }
}
