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

package org.apache.hadoop.ozone.om.ratis;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Queue;

import com.google.protobuf.ByteString;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos.BatchedStateTransitions;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos.DBDelta;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos.DeltaType;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos.OMResponse;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos.ReplicatedStateTransition;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos.Status;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos.Type;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link StateTransitionApplyEngine}.
 */
class TestStateTransitionApplyEngine {

  private static ReplicatedStateTransition makeTransition(long index, Type cmdType) {
    OMResponse response = OMResponse.newBuilder()
        .setCmdType(cmdType)
        .setStatus(Status.OK)
        .build();
    return ReplicatedStateTransition.newBuilder()
        .setManagedIndex(index)
        .setCmdType(cmdType)
        .setResponse(response)
        .addDeltas(DBDelta.newBuilder()
            .setTableName("keyTable")
            .setKey(ByteString.copyFromUtf8("key" + index))
            .setValue(ByteString.copyFromUtf8("value" + index))
            .setType(DeltaType.PUT)
            .build())
        .build();
  }

  @Test
  void testSplitAtBarriersEmpty() {
    BatchedStateTransitions batched = BatchedStateTransitions.newBuilder().build();
    List<Queue<ReplicatedStateTransition>> result =
        StateTransitionApplyEngine.splitAtBarriers(batched);
    assertEquals(0, result.size());
  }

  @Test
  void testSplitAtBarriersNoBarriers() {
    BatchedStateTransitions batched = BatchedStateTransitions.newBuilder()
        .addTransitions(makeTransition(1, Type.CreateKey))
        .addTransitions(makeTransition(2, Type.CommitKey))
        .addTransitions(makeTransition(3, Type.DeleteKey))
        .build();

    List<Queue<ReplicatedStateTransition>> result =
        StateTransitionApplyEngine.splitAtBarriers(batched);
    assertEquals(1, result.size());
    assertEquals(3, result.get(0).size());
  }

  @Test
  void testSplitAtCreateSnapshotBarrier() {
    BatchedStateTransitions batched = BatchedStateTransitions.newBuilder()
        .addTransitions(makeTransition(1, Type.CreateKey))
        .addTransitions(makeTransition(2, Type.CommitKey))
        .addTransitions(makeTransition(3, Type.CreateSnapshot))
        .addTransitions(makeTransition(4, Type.DeleteKey))
        .build();

    List<Queue<ReplicatedStateTransition>> result =
        StateTransitionApplyEngine.splitAtBarriers(batched);
    // [CreateKey, CommitKey], [CreateSnapshot], [DeleteKey]
    assertEquals(3, result.size());
    assertEquals(2, result.get(0).size());
    assertEquals(1, result.get(1).size());
    assertEquals(1, result.get(2).size());
  }

  @Test
  void testSplitAtSnapshotPurgeBarrier() {
    BatchedStateTransitions batched = BatchedStateTransitions.newBuilder()
        .addTransitions(makeTransition(1, Type.CreateKey))
        .addTransitions(makeTransition(2, Type.SnapshotPurge))
        .addTransitions(makeTransition(3, Type.CommitKey))
        .build();

    List<Queue<ReplicatedStateTransition>> result =
        StateTransitionApplyEngine.splitAtBarriers(batched);
    // [CreateKey], [SnapshotPurge], [CommitKey]
    assertEquals(3, result.size());
    assertEquals(1, result.get(0).size());
    assertEquals(1, result.get(1).size());
    assertEquals(1, result.get(2).size());
  }

  @Test
  void testSplitConsecutiveBarriers() {
    BatchedStateTransitions batched = BatchedStateTransitions.newBuilder()
        .addTransitions(makeTransition(1, Type.CreateSnapshot))
        .addTransitions(makeTransition(2, Type.SnapshotPurge))
        .addTransitions(makeTransition(3, Type.CreateKey))
        .build();

    List<Queue<ReplicatedStateTransition>> result =
        StateTransitionApplyEngine.splitAtBarriers(batched);
    // [CreateSnapshot], [SnapshotPurge], [CreateKey]
    assertEquals(3, result.size());
    assertEquals(1, result.get(0).size());
    assertEquals(1, result.get(1).size());
    assertEquals(1, result.get(2).size());
  }

  @Test
  void testSplitBarrierAtStart() {
    BatchedStateTransitions batched = BatchedStateTransitions.newBuilder()
        .addTransitions(makeTransition(1, Type.CreateSnapshot))
        .addTransitions(makeTransition(2, Type.CreateKey))
        .addTransitions(makeTransition(3, Type.CommitKey))
        .build();

    List<Queue<ReplicatedStateTransition>> result =
        StateTransitionApplyEngine.splitAtBarriers(batched);
    // [CreateSnapshot], [CreateKey, CommitKey]
    assertEquals(2, result.size());
    assertEquals(1, result.get(0).size());
    assertEquals(2, result.get(1).size());
  }

  @Test
  void testSplitBarrierAtEnd() {
    BatchedStateTransitions batched = BatchedStateTransitions.newBuilder()
        .addTransitions(makeTransition(1, Type.CreateKey))
        .addTransitions(makeTransition(2, Type.CommitKey))
        .addTransitions(makeTransition(3, Type.CreateSnapshot))
        .build();

    List<Queue<ReplicatedStateTransition>> result =
        StateTransitionApplyEngine.splitAtBarriers(batched);
    // [CreateKey, CommitKey], [CreateSnapshot]
    assertEquals(2, result.size());
    assertEquals(2, result.get(0).size());
    assertEquals(1, result.get(1).size());
  }
}
