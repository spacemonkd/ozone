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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import org.apache.hadoop.hdds.client.ContainerBlockID;
import org.apache.hadoop.hdds.client.RatisReplicationConfig;
import org.apache.hadoop.hdds.client.ReplicationConfig;
import org.apache.hadoop.hdds.conf.OzoneConfiguration;
import org.apache.hadoop.hdds.protocol.proto.HddsProtos;
import org.apache.hadoop.hdds.scm.container.common.helpers.AllocatedBlock;
import org.apache.hadoop.hdds.scm.container.common.helpers.ExcludeList;
import org.apache.hadoop.hdds.scm.protocol.ScmBlockLocationProtocol;
import org.apache.hadoop.ozone.om.ScmClient;
import org.apache.hadoop.hdds.scm.pipeline.Pipeline;
import org.apache.hadoop.hdds.scm.pipeline.PipelineID;
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
import org.apache.hadoop.ozone.om.helpers.OmVolumeArgs;
import org.apache.hadoop.ozone.om.ratis.StateTransitionApplyEngine;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos.CreateKeyRequest;
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
 * Tests for {@link OBSCreateKeyPlannedRequest}.
 */
class TestOBSCreateKeyPlannedRequest {

  @TempDir
  private Path folder;

  private OzoneManager ozoneManager;
  private OMMetadataManager metadataManager;
  private ManagedIndexService indexService;
  private LeaderPlanner planner;
  private StateTransitionApplyEngine applyEngine;
  private ScmClient scmClient;
  private ScmBlockLocationProtocol blockClient;

  private static final String VOLUME = "testVolume";
  private static final String BUCKET = "testBucket";

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
    when(ozoneManager.getScmBlockSize()).thenReturn(256L * 1024 * 1024);
    when(ozoneManager.getPreallocateBlocksMax()).thenReturn(64);
    when(ozoneManager.isGrpcBlockTokenEnabled()).thenReturn(false);
    when(ozoneManager.getOMServiceId()).thenReturn("om-service-1");
    when(ozoneManager.getObjectIdFromTxId(anyLong())).thenAnswer(inv -> inv.getArgument(0));

    ReplicationConfig defaultReplication = RatisReplicationConfig.getInstance(
        HddsProtos.ReplicationFactor.THREE);
    OmBucketInfo bucketInfo = OmBucketInfo.newBuilder()
        .setVolumeName(VOLUME)
        .setBucketName(BUCKET)
        .setObjectID(100L)
        .setUpdateID(100L)
        .build();
    when(ozoneManager.getBucketInfo(VOLUME, BUCKET)).thenReturn(bucketInfo);

    org.apache.hadoop.ozone.om.lock.OBSKeyLockManager keyLockManager =
        new org.apache.hadoop.ozone.om.lock.OBSKeyLockManager(metadataManager.getLock());
    when(ozoneManager.getKeyLockManager()).thenReturn(keyLockManager);

    scmClient = mock(ScmClient.class);
    blockClient = mock(ScmBlockLocationProtocol.class);
    when(ozoneManager.getScmClient()).thenReturn(scmClient);
    when(scmClient.getBlockClient()).thenReturn(blockClient);

    Pipeline pipeline = Pipeline.newBuilder()
        .setId(PipelineID.randomId())
        .setReplicationConfig(defaultReplication)
        .setState(Pipeline.PipelineState.OPEN)
        .setNodes(Collections.emptyList())
        .build();
    AllocatedBlock allocatedBlock = new AllocatedBlock.Builder()
        .setContainerBlockID(new ContainerBlockID(1, 1))
        .setPipeline(pipeline)
        .build();
    when(blockClient.allocateBlock(anyLong(), anyInt(), any(ReplicationConfig.class),
        anyString(), any(ExcludeList.class), anyString()))
        .thenReturn(Collections.singletonList(allocatedBlock));

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
  void testSuccessfulKeyCreation() throws Exception {
    String keyName = "testKey-" + UUID.randomUUID();
    OMRequest omRequest = createKeyRequest(keyName, 1024L);

    OBSCreateKeyPlannedRequest request = new OBSCreateKeyPlannedRequest(omRequest);
    ReplicatedStateTransition transition = planner.plan(request);

    assertTrue(transition.getResponse().getSuccess());
    assertEquals(Status.OK, transition.getResponse().getStatus());
    assertTrue(transition.getDeltasCount() >= 2);

    applyEngine.apply(transition, TermIndex.valueOf(1, 1));

    String dbOpenKeyPrefix = "/" + VOLUME + "/" + BUCKET + "/" + keyName + "/";
    Table<byte[], byte[]> rawOpenKeyTable = metadataManager.getStore().getTable("openKeyTable");
    boolean found = false;
    try (Table.KeyValueIterator<byte[], byte[]> iter = rawOpenKeyTable.iterator()) {
      while (iter.hasNext()) {
        Table.KeyValue<byte[], byte[]> entry = iter.next();
        String key = new String(entry.getKey(), StandardCharsets.UTF_8);
        if (key.contains(keyName)) {
          found = true;
          OmKeyInfo keyInfo = OmKeyInfo.getCodec().fromPersistedFormat(entry.getValue());
          assertEquals(keyName, keyInfo.getKeyName());
          assertEquals(VOLUME, keyInfo.getVolumeName());
          assertEquals(BUCKET, keyInfo.getBucketName());
          assertTrue(keyInfo.getObjectID() > 0);
          assertTrue(keyInfo.getCreationTime() > 0);
          break;
        }
      }
    }
    assertTrue(found, "Key should exist in openKeyTable after apply");
  }

  @Test
  void testEmptyKeyCreation() throws Exception {
    String keyName = "emptyKey-" + UUID.randomUUID();
    OMRequest omRequest = createKeyRequest(keyName, 0);

    OBSCreateKeyPlannedRequest request = new OBSCreateKeyPlannedRequest(omRequest);
    ReplicatedStateTransition transition = planner.plan(request);

    assertTrue(transition.getResponse().getSuccess());
    assertEquals(Status.OK, transition.getResponse().getStatus());

    applyEngine.apply(transition, TermIndex.valueOf(1, 1));

    Table<byte[], byte[]> rawOpenKeyTable = metadataManager.getStore().getTable("openKeyTable");
    boolean found = false;
    try (Table.KeyValueIterator<byte[], byte[]> iter = rawOpenKeyTable.iterator()) {
      while (iter.hasNext()) {
        Table.KeyValue<byte[], byte[]> entry = iter.next();
        String key = new String(entry.getKey(), StandardCharsets.UTF_8);
        if (key.contains(keyName)) {
          found = true;
          OmKeyInfo keyInfo = OmKeyInfo.getCodec().fromPersistedFormat(entry.getValue());
          assertEquals(0, keyInfo.getDataSize());
          break;
        }
      }
    }
    assertTrue(found, "Empty key should exist in openKeyTable");
  }

  @Test
  void testBucketNotFoundFails() throws Exception {
    String keyName = "testKey";
    OMRequest omRequest = createKeyRequest("nonExistentVolume", "nonExistentBucket", keyName, 1024L);

    when(ozoneManager.getBucketInfo("nonExistentVolume", "nonExistentBucket"))
        .thenReturn(OmBucketInfo.newBuilder()
            .setVolumeName("nonExistentVolume")
            .setBucketName("nonExistentBucket")
            .setObjectID(200L)
            .setUpdateID(200L)
            .build());

    OBSCreateKeyPlannedRequest request = new OBSCreateKeyPlannedRequest(omRequest);
    ReplicatedStateTransition transition = planner.plan(request);

    assertFalse(transition.getResponse().getSuccess());
    assertEquals(0, transition.getDeltasCount());
  }

  @Test
  void testQuotaExceededFails() throws Exception {
    OmBucketInfo quotaLimitedBucket = OmBucketInfo.newBuilder()
        .setVolumeName(VOLUME)
        .setBucketName(BUCKET)
        .setObjectID(100L)
        .setUpdateID(100L)
        .setQuotaInBytes(100L)
        .setUsedBytes(90L)
        .build();
    String bucketKey = metadataManager.getBucketKey(VOLUME, BUCKET);
    metadataManager.getBucketTable().putWithBatch(
        metadataManager.getStore().initBatchOperation(),
        bucketKey, quotaLimitedBucket);
    metadataManager.getStore().commitBatchOperation(
        metadataManager.getStore().initBatchOperation());

    Table<byte[], byte[]> rawBucketTable = metadataManager.getStore().getTable("bucketTable");
    rawBucketTable.put(bucketKey.getBytes(StandardCharsets.UTF_8),
        OmBucketInfo.getCodec().toPersistedFormat(quotaLimitedBucket));

    String keyName = "quotaKey-" + UUID.randomUUID();
    OMRequest omRequest = createKeyRequest(keyName, 1024L * 1024);

    OBSCreateKeyPlannedRequest request = new OBSCreateKeyPlannedRequest(omRequest);
    ReplicatedStateTransition transition = planner.plan(request);

    assertFalse(transition.getResponse().getSuccess());
    assertEquals(Status.QUOTA_EXCEEDED, transition.getResponse().getStatus());
    assertEquals(0, transition.getDeltasCount());
  }

  @Test
  void testManagedIndexIncrements() throws Exception {
    OMRequest req1 = createKeyRequest("key1", 0);
    OMRequest req2 = createKeyRequest("key2", 0);

    ReplicatedStateTransition t1 = planner.plan(new OBSCreateKeyPlannedRequest(req1));
    ReplicatedStateTransition t2 = planner.plan(new OBSCreateKeyPlannedRequest(req2));

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

  private OMRequest createKeyRequest(String keyName, long dataSize) {
    return createKeyRequest(VOLUME, BUCKET, keyName, dataSize);
  }

  private static OMRequest createKeyRequest(String volume, String bucket, String keyName, long dataSize) {
    KeyArgs.Builder keyArgs = KeyArgs.newBuilder()
        .setVolumeName(volume)
        .setBucketName(bucket)
        .setKeyName(keyName)
        .setType(HddsProtos.ReplicationType.RATIS)
        .setFactor(HddsProtos.ReplicationFactor.THREE);
    if (dataSize > 0) {
      keyArgs.setDataSize(dataSize);
    }
    return OMRequest.newBuilder()
        .setClientId(UUID.randomUUID().toString())
        .setCmdType(Type.CreateKey)
        .setCreateKeyRequest(CreateKeyRequest.newBuilder().setKeyArgs(keyArgs))
        .setUserInfo(UserInfo.newBuilder()
            .setUserName("testUser")
            .setRemoteAddress("127.0.0.1")
            .setHostName("localhost"))
        .build();
  }
}
