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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import java.io.IOException;

import org.apache.hadoop.hdds.utils.db.StringCodec;
import org.apache.hadoop.ozone.om.OzoneManager;
import org.apache.hadoop.ozone.om.exceptions.OMException;
import org.apache.hadoop.ozone.om.request.PlannedRequest;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos.OMRequest;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos.OMResponse;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos.ReplicatedStateTransition;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos.Status;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos.Type;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/**
 * Tests for {@link LeaderPlanner}.
 */
class TestLeaderPlanner {

  private OzoneManager ozoneManager;
  private ManagedIndexService indexService;
  private LeaderPlanner planner;

  @BeforeEach
  void setup() {
    ozoneManager = mock(OzoneManager.class);
    indexService = new ManagedIndexService(0);
    planner = new LeaderPlanner(ozoneManager, indexService);
  }

  private static OMRequest makeRequest(Type type) {
    return OMRequest.newBuilder()
        .setCmdType(type)
        .setClientId("test-client")
        .build();
  }

  @Test
  void testSuccessfulPlan() {
    PlannedRequest request = new PlannedRequest(makeRequest(Type.CreateVolume)) {
      @Override
      public void plan(OzoneManager om, TransitionBuilder builder) throws IOException {
        builder.put("volumeTable", "/vol1", "args", StringCodec.get());
        builder.setResponse(OMResponse.newBuilder()
            .setCmdType(Type.CreateVolume)
            .setStatus(Status.OK)
            .setSuccess(true)
            .build());
      }
    };

    ReplicatedStateTransition result = planner.plan(request);

    assertEquals(1, result.getManagedIndex());
    assertEquals(Type.CreateVolume, result.getCmdType());
    assertEquals(1, result.getDeltasCount());
    assertTrue(result.getResponse().getSuccess());
    assertEquals(Status.OK, result.getResponse().getStatus());
  }

  @Test
  void testPlanningFailureProducesErrorTransition() {
    PlannedRequest request = new PlannedRequest(makeRequest(Type.CreateVolume)) {
      @Override
      public void plan(OzoneManager om, TransitionBuilder builder) throws IOException {
        throw new OMException("Volume exists", OMException.ResultCodes.VOLUME_ALREADY_EXISTS);
      }
    };

    ReplicatedStateTransition result = planner.plan(request);

    assertEquals(1, result.getManagedIndex());
    assertEquals(0, result.getDeltasCount());
    assertFalse(result.getResponse().getSuccess());
    assertEquals(Status.VOLUME_ALREADY_EXISTS, result.getResponse().getStatus());
  }

  @Test
  void testLocksReleasedOnException() throws Exception {
    PlannedRequest request = spy(new PlannedRequest(makeRequest(Type.CreateKey)) {
      @Override
      public void plan(OzoneManager om, TransitionBuilder builder) throws IOException {
        throw new IOException("DB error");
      }
    });

    ReplicatedStateTransition result = planner.plan(request);

    verify(request).acquireLocks(ozoneManager);
    verify(request).releaseLocks(ozoneManager);
    assertFalse(result.getResponse().getSuccess());
  }

  @Test
  void testLocksReleasedOnAuthorizeFailure() throws Exception {
    PlannedRequest request = spy(new PlannedRequest(makeRequest(Type.CreateKey)) {
      @Override
      public void authorize(OzoneManager om) throws IOException {
        throw new OMException("Access denied", OMException.ResultCodes.ACCESS_DENIED);
      }

      @Override
      public void plan(OzoneManager om, TransitionBuilder builder) {
      }
    });

    ReplicatedStateTransition result = planner.plan(request);

    assertFalse(result.getResponse().getSuccess());
    assertEquals(Status.ACCESS_DENIED, result.getResponse().getStatus());
  }

  @Test
  void testManagedIndexIncrements() {
    PlannedRequest request = new PlannedRequest(makeRequest(Type.CreateVolume)) {
      @Override
      public void plan(OzoneManager om, TransitionBuilder builder) {
        builder.setResponse(OMResponse.newBuilder()
            .setCmdType(Type.CreateVolume)
            .setStatus(Status.OK)
            .setSuccess(true)
            .build());
      }
    };

    ReplicatedStateTransition r1 = planner.plan(request);
    ReplicatedStateTransition r2 = planner.plan(request);
    ReplicatedStateTransition r3 = planner.plan(request);

    assertEquals(1, r1.getManagedIndex());
    assertEquals(2, r2.getManagedIndex());
    assertEquals(3, r3.getManagedIndex());
  }
}
