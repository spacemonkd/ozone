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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.google.protobuf.ByteString;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos.BatchedStateTransitions;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos.DBDelta;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos.DeltaType;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos.OMResponse;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos.ReplicatedStateTransition;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos.Status;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos.Type;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link TransitionBatcher}.
 */
class TestTransitionBatcher {

  private TransitionBatcher batcher;
  private AtomicInteger batchCount;
  private List<BatchedStateTransitions> submittedBatches;

  @BeforeEach
  void setup() {
    batchCount = new AtomicInteger(0);
    submittedBatches = new ArrayList<>();
  }

  @AfterEach
  void tearDown() {
    if (batcher != null) {
      batcher.shutdown();
    }
  }

  @Test
  void testSingleTransitionIsSubmitted() throws Exception {
    batcher = new TransitionBatcher(
        (batched, pending) -> {
          submittedBatches.add(batched);
          batchCount.incrementAndGet();
          for (TransitionBatcher.PendingTransition pt : pending) {
            pt.getFuture().complete(pt.getTransition().getResponse());
          }
        }, 1, 100, 5);

    ReplicatedStateTransition transition = createTransition(1, "key1");
    CompletableFuture<OMResponse> future = batcher.submit(transition);

    OMResponse response = future.get(5, TimeUnit.SECONDS);
    assertTrue(response.getSuccess());
    assertEquals(Status.OK, response.getStatus());
    assertEquals(1, batchCount.get());
  }

  @Test
  void testMultipleTransitionsAreBatched() throws Exception {
    batcher = new TransitionBatcher(
        (batched, pending) -> {
          submittedBatches.add(batched);
          batchCount.incrementAndGet();
          for (TransitionBatcher.PendingTransition pt : pending) {
            pt.getFuture().complete(pt.getTransition().getResponse());
          }
        }, 1, 100, 50);

    int numTransitions = 10;
    List<CompletableFuture<OMResponse>> futures = new ArrayList<>();
    for (int i = 0; i < numTransitions; i++) {
      futures.add(batcher.submit(createTransition(i, "key" + i)));
    }

    for (CompletableFuture<OMResponse> f : futures) {
      OMResponse resp = f.get(5, TimeUnit.SECONDS);
      assertTrue(resp.getSuccess());
    }

    assertTrue(batchCount.get() >= 1, "At least one batch should be submitted");
    int totalTransitions = 0;
    for (BatchedStateTransitions b : submittedBatches) {
      totalTransitions += b.getTransitionsCount();
    }
    assertEquals(numTransitions, totalTransitions,
        "All transitions should be submitted across batches");
  }

  @Test
  void testBatchSizeLimitTriggers() throws Exception {
    int maxBatchSize = 3;
    batcher = new TransitionBatcher(
        (batched, pending) -> {
          submittedBatches.add(batched);
          batchCount.incrementAndGet();
          for (TransitionBatcher.PendingTransition pt : pending) {
            pt.getFuture().complete(pt.getTransition().getResponse());
          }
        }, 1, maxBatchSize, 5000);

    List<CompletableFuture<OMResponse>> futures = new ArrayList<>();
    for (int i = 0; i < 6; i++) {
      futures.add(batcher.submit(createTransition(i, "key" + i)));
    }

    for (CompletableFuture<OMResponse> f : futures) {
      f.get(5, TimeUnit.SECONDS);
    }

    assertTrue(batchCount.get() >= 2,
        "Should have at least 2 batches with max size 3 and 6 transitions");
  }

  @Test
  void testSubmitterFailureCompletesExceptionally() throws Exception {
    batcher = new TransitionBatcher(
        (batched, pending) -> {
          throw new RuntimeException("Simulated Ratis failure");
        }, 1, 100, 5);

    CompletableFuture<OMResponse> future = batcher.submit(createTransition(1, "key1"));

    assertTrue(future.isCompletedExceptionally() || waitForException(future));
  }

  private boolean waitForException(CompletableFuture<OMResponse> future) {
    try {
      future.get(5, TimeUnit.SECONDS);
      return false;
    } catch (Exception e) {
      return true;
    }
  }

  private static ReplicatedStateTransition createTransition(long index, String key) {
    DBDelta delta = DBDelta.newBuilder()
        .setTableName("keyTable")
        .setKey(ByteString.copyFromUtf8(key))
        .setValue(ByteString.copyFromUtf8("value-" + key))
        .setType(DeltaType.PUT)
        .build();

    OMResponse response = OMResponse.newBuilder()
        .setCmdType(Type.CommitKey)
        .setStatus(Status.OK)
        .setSuccess(true)
        .build();

    return ReplicatedStateTransition.newBuilder()
        .setManagedIndex(index)
        .addDeltas(delta)
        .setResponse(response)
        .setCmdType(Type.CommitKey)
        .build();
  }
}
