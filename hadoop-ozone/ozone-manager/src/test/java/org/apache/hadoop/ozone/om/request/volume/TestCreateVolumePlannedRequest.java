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

package org.apache.hadoop.ozone.om.request.volume;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.UUID;

import org.apache.hadoop.hdds.conf.OzoneConfiguration;
import org.apache.hadoop.ozone.om.OMConfigKeys;
import org.apache.hadoop.ozone.om.OMMetadataManager;
import org.apache.hadoop.ozone.om.OmConfig;
import org.apache.hadoop.ozone.om.OmMetadataManagerImpl;
import org.apache.hadoop.ozone.om.OzoneManager;
import org.apache.hadoop.ozone.om.execution.LeaderPlanner;
import org.apache.hadoop.ozone.om.execution.ManagedIndexService;
import org.apache.hadoop.hdds.utils.db.Table;
import org.apache.hadoop.ozone.om.helpers.OmVolumeArgs;
import org.apache.hadoop.ozone.om.ratis.StateTransitionApplyEngine;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos.CreateVolumeRequest;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos.OMRequest;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos.ReplicatedStateTransition;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos.Status;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos.Type;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos.VolumeInfo;
import org.apache.hadoop.ozone.storage.proto.OzoneManagerStorageProtos.PersistedUserVolumeInfo;
import org.apache.ratis.server.protocol.TermIndex;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link CreateVolumePlannedRequest}.
 */
class TestCreateVolumePlannedRequest {

  @TempDir
  private Path folder;

  private OzoneManager ozoneManager;
  private OMMetadataManager metadataManager;
  private ManagedIndexService indexService;
  private LeaderPlanner planner;
  private StateTransitionApplyEngine applyEngine;

  @BeforeEach
  void setup() throws Exception {
    OzoneConfiguration conf = new OzoneConfiguration();
    conf.set(OMConfigKeys.OZONE_OM_DB_DIRS, folder.toAbsolutePath().toString());

    ozoneManager = mock(OzoneManager.class);
    metadataManager = new OmMetadataManagerImpl(conf, ozoneManager);
    when(ozoneManager.getMetadataManager()).thenReturn(metadataManager);
    when(ozoneManager.getMaxUserVolumeCount()).thenReturn(10L);
    when(ozoneManager.getAclsEnabled()).thenReturn(false);
    when(ozoneManager.isStrictS3()).thenReturn(false);
    when(ozoneManager.getObjectIdFromTxId(1L)).thenReturn(1L);
    when(ozoneManager.getObjectIdFromTxId(2L)).thenReturn(2L);
    when(ozoneManager.getConfiguration()).thenReturn(conf);
    when(ozoneManager.getConfig()).thenReturn(conf.getObject(OmConfig.class));

    indexService = new ManagedIndexService(0);
    planner = new LeaderPlanner(ozoneManager, indexService);
    applyEngine = new StateTransitionApplyEngine(metadataManager, indexService);
  }

  @AfterEach
  void tearDown() throws Exception {
    if (metadataManager != null) {
      metadataManager.stop();
    }
  }

  @Test
  void testSuccessfulVolumeCreation() throws Exception {
    String volumeName = UUID.randomUUID().toString();
    String owner = "testOwner";
    OMRequest omRequest = createVolumeRequest(volumeName, "admin", owner);

    CreateVolumePlannedRequest request = new CreateVolumePlannedRequest(omRequest);
    ReplicatedStateTransition transition = planner.plan(request);

    assertEquals(1, transition.getManagedIndex());
    assertEquals(Type.CreateVolume, transition.getCmdType());
    assertTrue(transition.getResponse().getSuccess());
    assertEquals(Status.OK, transition.getResponse().getStatus());
    assertTrue(transition.getDeltasCount() >= 2);

    // Apply and verify DB state via raw table (FULL_CACHE typed tables
    // don't see raw writes until cache is refreshed)
    applyEngine.apply(transition, TermIndex.valueOf(1, 1));

    String dbVolumeKey = metadataManager.getVolumeKey(volumeName);
    Table<byte[], byte[]> rawVolumeTable = metadataManager.getStore().getTable("volumeTable");
    byte[] rawValue = rawVolumeTable.get(dbVolumeKey.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    assertNotNull(rawValue);

    OmVolumeArgs volumeArgs = OmVolumeArgs.getCodec().fromPersistedFormat(rawValue);
    assertEquals(volumeName, volumeArgs.getVolume());
    assertEquals(owner, volumeArgs.getOwnerName());
    assertTrue(volumeArgs.getCreationTime() > 0);
    assertTrue(volumeArgs.getObjectID() > 0);

    Table<byte[], byte[]> rawUserTable = metadataManager.getStore().getTable("userTable");
    String dbUserKey = metadataManager.getUserKey(owner);
    byte[] rawUserValue = rawUserTable.get(dbUserKey.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    assertNotNull(rawUserValue);
    PersistedUserVolumeInfo userVolumeInfo = PersistedUserVolumeInfo.parseFrom(rawUserValue);
    assertTrue(userVolumeInfo.getVolumeNamesList().contains(volumeName));
  }

  @Test
  void testDuplicateVolumeCreationFails() throws Exception {
    String volumeName = UUID.randomUUID().toString();
    OMRequest omRequest = createVolumeRequest(volumeName, "admin", "owner");

    // First creation succeeds
    CreateVolumePlannedRequest request1 = new CreateVolumePlannedRequest(omRequest);
    ReplicatedStateTransition t1 = planner.plan(request1);
    assertTrue(t1.getResponse().getSuccess());
    applyEngine.apply(t1, TermIndex.valueOf(1, 1));

    // Second creation fails with VOLUME_ALREADY_EXISTS
    CreateVolumePlannedRequest request2 = new CreateVolumePlannedRequest(omRequest);
    ReplicatedStateTransition t2 = planner.plan(request2);
    assertFalse(t2.getResponse().getSuccess());
    assertEquals(Status.VOLUME_ALREADY_EXISTS, t2.getResponse().getStatus());
    assertEquals(0, t2.getDeltasCount());
  }

  @Test
  void testUserVolumeLimitExceeded() throws Exception {
    when(ozoneManager.getMaxUserVolumeCount()).thenReturn(2L);
    String owner = "limitedOwner";

    // Create 2 volumes to reach the limit
    for (int i = 0; i < 2; i++) {
      String volName = "vol-" + i;
      when(ozoneManager.getObjectIdFromTxId(indexService.current() + 1)).thenReturn((long) (i + 1));
      OMRequest req = createVolumeRequest(volName, "admin", owner);
      CreateVolumePlannedRequest request = new CreateVolumePlannedRequest(req);
      ReplicatedStateTransition t = planner.plan(request);
      assertTrue(t.getResponse().getSuccess(), "Volume " + volName + " should succeed");
      applyEngine.apply(t, TermIndex.valueOf(1, i + 1));
    }

    // Third volume should fail
    when(ozoneManager.getObjectIdFromTxId(indexService.current() + 1)).thenReturn(3L);
    OMRequest req = createVolumeRequest("vol-excess", "admin", owner);
    CreateVolumePlannedRequest request = new CreateVolumePlannedRequest(req);
    ReplicatedStateTransition t = planner.plan(request);
    assertFalse(t.getResponse().getSuccess());
    assertEquals(Status.USER_TOO_MANY_VOLUMES, t.getResponse().getStatus());
  }

  @Test
  void testManagedIndexIncrements() {
    String owner = "owner";
    OMRequest req1 = createVolumeRequest("vol1", "admin", owner);
    OMRequest req2 = createVolumeRequest("vol2", "admin", owner);

    ReplicatedStateTransition t1 = planner.plan(new CreateVolumePlannedRequest(req1));
    ReplicatedStateTransition t2 = planner.plan(new CreateVolumePlannedRequest(req2));

    assertEquals(1, t1.getManagedIndex());
    assertEquals(2, t2.getManagedIndex());
  }

  private static OMRequest createVolumeRequest(String volumeName, String adminName, String ownerName) {
    VolumeInfo volumeInfo = VolumeInfo.newBuilder()
        .setVolume(volumeName)
        .setAdminName(adminName)
        .setOwnerName(ownerName)
        .build();
    return OMRequest.newBuilder()
        .setClientId(UUID.randomUUID().toString())
        .setCmdType(Type.CreateVolume)
        .setCreateVolumeRequest(CreateVolumeRequest.newBuilder().setVolumeInfo(volumeInfo))
        .build();
  }
}
