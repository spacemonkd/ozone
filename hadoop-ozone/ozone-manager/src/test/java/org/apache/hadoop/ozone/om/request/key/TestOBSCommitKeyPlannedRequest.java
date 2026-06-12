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

package org.apache.hadoop.ozone.om.request.key;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Collections;
import java.util.UUID;

import org.apache.hadoop.hdds.client.RatisReplicationConfig;
import org.apache.hadoop.hdds.client.ReplicationConfig;
import org.apache.hadoop.hdds.conf.OzoneConfiguration;
import org.apache.hadoop.hdds.protocol.proto.HddsProtos;
import org.apache.hadoop.hdds.utils.db.Table;
import org.apache.hadoop.ozone.om.OMConfigKeys;
import org.apache.hadoop.ozone.om.OMMetadataManager;
import org.apache.hadoop.ozone.om.OmConfig;
import org.apache.hadoop.ozone.om.OmMetadataManagerImpl;
import org.apache.hadoop.ozone.om.OzoneManager;
import org.apache.hadoop.ozone.om.execution.LeaderPlanner;
import org.apache.hadoop.ozone.om.execution.ManagedIndexService;
import org.apache.hadoop.ozone.om.helpers.OmBucketInfo;
import org.apache.hadoop.ozone.om.helpers.OmKeyInfo;
import org.apache.hadoop.ozone.om.helpers.OmKeyLocationInfoGroup;
import org.apache.hadoop.ozone.om.helpers.OmVolumeArgs;
import org.apache.hadoop.ozone.om.ratis.StateTransitionApplyEngine;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos.CommitKeyRequest;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos.KeyArgs;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos.OMRequest;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos.ReplicatedStateTransition;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos.Status;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos.Type;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos.UserInfo;
import org.apache.ratis.server.protocol.TermIndex;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link OBSCommitKeyPlannedRequest}.
 */
class TestOBSCommitKeyPlannedRequest {

  @TempDir
  private Path folder;

  private OzoneManager ozoneManager;
  private OMMetadataManager metadataManager;
  private ManagedIndexService indexService;
  private LeaderPlanner planner;
  private StateTransitionApplyEngine applyEngine;

  private static final String VOLUME = "testVolume";
  private static final String BUCKET = "testBucket";
  private static final ReplicationConfig RATIS_THREE = RatisReplicationConfig.getInstance(
      HddsProtos.ReplicationFactor.THREE);

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
    when(ozoneManager.getConfiguration()).thenReturn(conf);
    when(ozoneManager.getConfig()).thenReturn(conf.getObject(OmConfig.class));
    when(ozoneManager.isGrpcBlockTokenEnabled()).thenReturn(false);
    when(ozoneManager.getObjectIdFromTxId(anyLong())).thenAnswer(inv -> inv.getArgument(0));

    org.apache.hadoop.ozone.om.lock.OBSKeyLockManager keyLockManager =
        new org.apache.hadoop.ozone.om.lock.OBSKeyLockManager(metadataManager.getLock());
    when(ozoneManager.getKeyLockManager()).thenReturn(keyLockManager);

    indexService = new ManagedIndexService(0);
    planner = new LeaderPlanner(ozoneManager, indexService);
    applyEngine = new StateTransitionApplyEngine(metadataManager, indexService);

    createVolumeAndBucket();
  }

  @AfterEach
  void tearDown() throws Exception {
    if (metadataManager != null) {
      metadataManager.stop();
    }
  }

  @Test
  void testSuccessfulKeyCommit() throws Exception {
    String keyName = "testKey-" + UUID.randomUUID();
    long clientID = 12345L;

    createOpenKey(keyName, clientID, 1024L);

    OMRequest omRequest = createCommitKeyRequest(keyName, clientID, 1024L);
    OBSCommitKeyPlannedRequest request = new OBSCommitKeyPlannedRequest(omRequest);
    ReplicatedStateTransition transition = planner.plan(request);

    assertEquals(Status.OK, transition.getResponse().getStatus(),
        "Planning failed with message: " + transition.getResponse().getMessage());
    assertTrue(transition.getResponse().getSuccess());
    assertTrue(transition.getDeltasCount() >= 3,
        "Expected >= 3 deltas, got " + transition.getDeltasCount());

    applyEngine.apply(transition, TermIndex.valueOf(1, 1));

    String dbOzoneKey = metadataManager.getOzoneKey(VOLUME, BUCKET, keyName);
    Table<byte[], byte[]> rawKeyTable = metadataManager.getStore().getTable("keyTable");
    byte[] rawValue = rawKeyTable.get(dbOzoneKey.getBytes(StandardCharsets.UTF_8));
    assertNotNull(rawValue, "Key should exist in keyTable after commit");

    OmKeyInfo committedKey = OmKeyInfo.getCodec().fromPersistedFormat(rawValue);
    assertEquals(keyName, committedKey.getKeyName());
    assertEquals(VOLUME, committedKey.getVolumeName());
    assertEquals(BUCKET, committedKey.getBucketName());
    assertEquals(1024L, committedKey.getDataSize());

    String dbOpenKey = metadataManager.getOpenKey(VOLUME, BUCKET, keyName, String.valueOf(clientID));
    Table<byte[], byte[]> rawOpenKeyTable = metadataManager.getStore().getTable("openKeyTable");
    assertNull(rawOpenKeyTable.get(dbOpenKey.getBytes(StandardCharsets.UTF_8)),
        "Open key should be deleted after commit");
  }

  @Test
  void testKeyNotFoundInOpenTableFails() throws Exception {
    String keyName = "nonExistentKey";
    long clientID = 99999L;

    OMRequest omRequest = createCommitKeyRequest(keyName, clientID, 0);
    OBSCommitKeyPlannedRequest request = new OBSCommitKeyPlannedRequest(omRequest);
    ReplicatedStateTransition transition = planner.plan(request);

    assertFalse(transition.getResponse().getSuccess());
    assertEquals(Status.KEY_NOT_FOUND, transition.getResponse().getStatus());
    assertEquals(0, transition.getDeltasCount());
  }

  @Test
  void testOverwriteMovesOldKeyToDeletedTable() throws Exception {
    String keyName = "overwriteKey-" + UUID.randomUUID();
    long clientID1 = 111L;
    long clientID2 = 222L;

    createOpenKey(keyName, clientID1, 512L);
    OMRequest req1 = createCommitKeyRequest(keyName, clientID1, 512L);
    when(ozoneManager.getObjectIdFromTxId(1L)).thenReturn(1L);
    ReplicatedStateTransition t1 = planner.plan(new OBSCommitKeyPlannedRequest(req1));
    assertTrue(t1.getResponse().getSuccess());
    applyEngine.apply(t1, TermIndex.valueOf(1, 1));

    createOpenKey(keyName, clientID2, 1024L);
    when(ozoneManager.getObjectIdFromTxId(2L)).thenReturn(2L);
    OMRequest req2 = createCommitKeyRequest(keyName, clientID2, 1024L);
    ReplicatedStateTransition t2 = planner.plan(new OBSCommitKeyPlannedRequest(req2));
    assertTrue(t2.getResponse().getSuccess());
    applyEngine.apply(t2, TermIndex.valueOf(1, 2));

    String dbOzoneKey = metadataManager.getOzoneKey(VOLUME, BUCKET, keyName);
    Table<byte[], byte[]> rawKeyTable = metadataManager.getStore().getTable("keyTable");
    byte[] rawValue = rawKeyTable.get(dbOzoneKey.getBytes(StandardCharsets.UTF_8));
    assertNotNull(rawValue);
    OmKeyInfo committedKey = OmKeyInfo.getCodec().fromPersistedFormat(rawValue);
    assertEquals(1024L, committedKey.getDataSize());

    Table<byte[], byte[]> rawDeletedTable = metadataManager.getStore().getTable("deletedTable");
    boolean foundDeleted = false;
    try (Table.KeyValueIterator<byte[], byte[]> iter = rawDeletedTable.iterator()) {
      while (iter.hasNext()) {
        Table.KeyValue<byte[], byte[]> entry = iter.next();
        String key = new String(entry.getKey(), StandardCharsets.UTF_8);
        if (key.contains(keyName)) {
          foundDeleted = true;
          break;
        }
      }
    }
    assertTrue(foundDeleted, "Old key version should be in deletedTable");
  }

  @Test
  void testManagedIndexIncrements() throws Exception {
    String key1 = "key1-" + UUID.randomUUID();
    String key2 = "key2-" + UUID.randomUUID();
    createOpenKey(key1, 1L, 0);
    createOpenKey(key2, 2L, 0);

    ReplicatedStateTransition t1 = planner.plan(
        new OBSCommitKeyPlannedRequest(createCommitKeyRequest(key1, 1L, 0)));
    ReplicatedStateTransition t2 = planner.plan(
        new OBSCommitKeyPlannedRequest(createCommitKeyRequest(key2, 2L, 0)));

    assertEquals(1, t1.getManagedIndex());
    assertEquals(2, t2.getManagedIndex());
    assertTrue(t1.getResponse().getSuccess());
    assertTrue(t2.getResponse().getSuccess());
  }

  private void createVolumeAndBucket() throws Exception {
    OmVolumeArgs volumeArgs = OmVolumeArgs.newBuilder()
        .setVolume(VOLUME)
        .setOwnerName("testOwner")
        .setAdminName("admin")
        .setObjectID(1L)
        .setUpdateID(1L)
        .build();
    String dbVolumeKey = metadataManager.getVolumeKey(VOLUME);
    Table<byte[], byte[]> rawVolumeTable = metadataManager.getStore().getTable("volumeTable");
    rawVolumeTable.put(dbVolumeKey.getBytes(StandardCharsets.UTF_8),
        OmVolumeArgs.getCodec().toPersistedFormat(volumeArgs));

    OmBucketInfo bucketInfo = OmBucketInfo.newBuilder()
        .setVolumeName(VOLUME)
        .setBucketName(BUCKET)
        .setObjectID(100L)
        .setUpdateID(100L)
        .build();
    String dbBucketKey = metadataManager.getBucketKey(VOLUME, BUCKET);
    Table<byte[], byte[]> rawBucketTable = metadataManager.getStore().getTable("bucketTable");
    rawBucketTable.put(dbBucketKey.getBytes(StandardCharsets.UTF_8),
        OmBucketInfo.getCodec().toPersistedFormat(bucketInfo));
  }

  private void createOpenKey(String keyName, long clientID, long dataSize) throws Exception {
    OmKeyInfo openKeyInfo = new OmKeyInfo.Builder()
        .setVolumeName(VOLUME)
        .setBucketName(BUCKET)
        .setKeyName(keyName)
        .setOmKeyLocationInfos(Collections.singletonList(
            new OmKeyLocationInfoGroup(0, Collections.emptyList())))
        .setCreationTime(System.currentTimeMillis())
        .setModificationTime(System.currentTimeMillis())
        .setDataSize(dataSize)
        .setReplicationConfig(RATIS_THREE)
        .setObjectID(clientID)
        .setUpdateID(0L)
        .build();

    String dbOpenKey = metadataManager.getOpenKey(VOLUME, BUCKET, keyName, String.valueOf(clientID));
    byte[] keyBytes = org.apache.hadoop.hdds.utils.db.StringCodec.get().toPersistedFormat(dbOpenKey);
    Table<byte[], byte[]> rawOpenKeyTable = metadataManager.getStore().getTable("openKeyTable");
    rawOpenKeyTable.put(keyBytes, OmKeyInfo.getCodec().toPersistedFormat(openKeyInfo));
  }

  private static OMRequest createCommitKeyRequest(String keyName, long clientID, long dataSize) {
    KeyArgs keyArgs = KeyArgs.newBuilder()
        .setVolumeName(VOLUME)
        .setBucketName(BUCKET)
        .setKeyName(keyName)
        .setDataSize(dataSize)
        .build();
    return OMRequest.newBuilder()
        .setClientId(UUID.randomUUID().toString())
        .setCmdType(Type.CommitKey)
        .setCommitKeyRequest(CommitKeyRequest.newBuilder()
            .setKeyArgs(keyArgs)
            .setClientID(clientID))
        .setUserInfo(UserInfo.newBuilder()
            .setUserName("testUser")
            .setRemoteAddress("127.0.0.1")
            .setHostName("localhost"))
        .build();
  }
}
