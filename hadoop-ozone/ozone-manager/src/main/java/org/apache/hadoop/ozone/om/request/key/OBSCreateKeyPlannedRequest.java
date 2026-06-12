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

import static org.apache.hadoop.hdds.protocol.proto.HddsProtos.BlockTokenSecretProto.AccessModeProto.READ;
import static org.apache.hadoop.hdds.protocol.proto.HddsProtos.BlockTokenSecretProto.AccessModeProto.WRITE;

import java.io.IOException;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;

import org.apache.hadoop.hdds.client.BlockID;
import org.apache.hadoop.hdds.client.ECReplicationConfig;
import org.apache.hadoop.hdds.client.ReplicationConfig;
import org.apache.hadoop.hdds.scm.container.common.helpers.AllocatedBlock;
import org.apache.hadoop.hdds.scm.container.common.helpers.ExcludeList;
import org.apache.hadoop.hdds.utils.UniqueId;
import org.apache.hadoop.ozone.OmUtils;
import org.apache.hadoop.ozone.OzoneAcl;
import org.apache.hadoop.ozone.OzoneConsts;
import org.apache.hadoop.ozone.om.OMMetadataManager;
import org.apache.hadoop.ozone.om.OzoneConfigUtil;
import org.apache.hadoop.ozone.om.OzoneManager;
import org.apache.hadoop.ozone.om.exceptions.OMException;
import org.apache.hadoop.ozone.om.execution.TransitionBuilder;
import org.apache.hadoop.ozone.om.helpers.OmBucketInfo;
import org.apache.hadoop.ozone.om.helpers.OmKeyInfo;
import org.apache.hadoop.ozone.om.helpers.OmKeyLocationInfo;
import org.apache.hadoop.ozone.om.helpers.OmKeyLocationInfoGroup;
import org.apache.hadoop.ozone.om.helpers.OzoneAclUtil;
import org.apache.hadoop.ozone.om.request.PlannedRequest;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos.CreateKeyRequest;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos.CreateKeyResponse;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos.KeyArgs;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos.OMRequest;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos.OMResponse;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos.Status;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos.Type;
import org.apache.hadoop.ozone.security.acl.IAccessAuthorizer;
import org.apache.hadoop.ozone.security.acl.OzoneObj;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.util.Time;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * OBS (Object Store layout) CreateKey implementation for the leader-planned execution path.
 * Handles non-multipart key creation: validates, allocates blocks from SCM,
 * writes openKeyTable entry and updates bucket quota.
 */
public class OBSCreateKeyPlannedRequest extends PlannedRequest {

  private static final Logger LOG = LoggerFactory.getLogger(OBSCreateKeyPlannedRequest.class);

  private String volumeName;
  private String bucketName;
  private String keyName;
  private long clientID;
  private List<OmKeyLocationInfo> allocatedLocations;
  private ReplicationConfig replicationConfig;

  public OBSCreateKeyPlannedRequest(OMRequest omRequest) {
    super(omRequest);
  }

  @Override
  public void preProcess(OzoneManager om) throws IOException {
    CreateKeyRequest createKeyRequest = getOmRequest().getCreateKeyRequest();
    KeyArgs keyArgs = createKeyRequest.getKeyArgs();

    this.volumeName = keyArgs.getVolumeName();
    this.bucketName = keyArgs.getBucketName();
    this.keyName = keyArgs.getKeyName();

    OmUtils.verifyKeyNameWithSnapshotReservedWord(keyName);
    if (om.getConfig().isKeyNameCharacterCheckEnabled()) {
      OmUtils.validateKeyName(keyName);
    }

    this.clientID = UniqueId.next();

    OmBucketInfo bucketInfo = om.getBucketInfo(volumeName, bucketName);
    this.replicationConfig = OzoneConfigUtil.resolveReplicationConfigPreference(
        keyArgs.getType(), keyArgs.getFactor(), keyArgs.getEcReplicationConfig(),
        bucketInfo.getDefaultReplicationConfig(), om);

    if (!keyArgs.getIsMultipartKey() && keyArgs.hasDataSize() && keyArgs.getDataSize() > 0) {
      long scmBlockSize = om.getScmBlockSize();
      long requestedSize = keyArgs.getDataSize();
      this.allocatedLocations = allocateBlocks(om, requestedSize, scmBlockSize);
    } else {
      this.allocatedLocations = Collections.emptyList();
    }
  }

  @Override
  public void authorize(OzoneManager om) throws IOException {
    if (om.getAclsEnabled()) {
      UserGroupInformation ugi = UserGroupInformation.createRemoteUser(
          getOmRequest().getUserInfo().getUserName());
      InetAddress remoteAddress = InetAddress.getByName(
          getOmRequest().getUserInfo().getRemoteAddress());
      String hostName = getOmRequest().getUserInfo().getHostName();
      om.checkAcls(OzoneObj.ResourceType.KEY, OzoneObj.StoreType.OZONE,
          IAccessAuthorizer.ACLType.CREATE, volumeName, bucketName, keyName,
          ugi, remoteAddress, hostName, true, null);
    }
  }

  @Override
  public void acquireLocks(OzoneManager om) throws IOException {
    om.getKeyLockManager().acquireBucketReadLock(volumeName, bucketName);
  }

  @Override
  public void releaseLocks(OzoneManager om) {
    om.getKeyLockManager().releaseBucketReadLock(volumeName, bucketName);
  }

  @Override
  public void plan(OzoneManager om, TransitionBuilder builder) throws IOException {
    OMMetadataManager metadataManager = om.getMetadataManager();

    String dbBucketKey = metadataManager.getBucketKey(volumeName, bucketName);
    OmBucketInfo bucketInfo = metadataManager.getBucketTable().getSkipCache(dbBucketKey);
    if (bucketInfo == null) {
      throw new OMException("Bucket not found: " + volumeName + "/" + bucketName,
          OMException.ResultCodes.BUCKET_NOT_FOUND);
    }

    String dbVolumeKey = metadataManager.getVolumeKey(volumeName);
    if (metadataManager.getVolumeTable().getSkipCache(dbVolumeKey) == null) {
      throw new OMException("Volume not found: " + volumeName,
          OMException.ResultCodes.VOLUME_NOT_FOUND);
    }

    long managedIndex = builder.getManagedIndex();
    long objectId = om.getObjectIdFromTxId(managedIndex);
    long now = Time.now();

    long preAllocatedSpace = (long) allocatedLocations.size()
        * om.getScmBlockSize() * replicationConfig.getRequiredNodes();
    checkBucketQuotaInBytes(bucketInfo, preAllocatedSpace);
    checkBucketQuotaInNamespace(bucketInfo, 1L);

    OmKeyInfo omKeyInfo = buildKeyInfo(objectId, managedIndex, now, om);

    String dbOpenKeyName = metadataManager.getOpenKey(volumeName, bucketName, keyName,
        String.valueOf(clientID));

    builder.put("openKeyTable", dbOpenKeyName, omKeyInfo, OmKeyInfo.getCodec());
    builder.put("bucketTable", dbBucketKey, bucketInfo.copyObject(), OmBucketInfo.getCodec());

    builder.setResponse(OMResponse.newBuilder()
        .setCmdType(Type.CreateKey)
        .setStatus(Status.OK)
        .setSuccess(true)
        .setCreateKeyResponse(CreateKeyResponse.newBuilder()
            .setKeyInfo(omKeyInfo.getNetworkProtobuf(getOmRequest().getVersion(), true))
            .setID(clientID)
            .setOpenVersion(omKeyInfo.getLatestVersionLocations().getVersion())
            .build())
        .build());

    LOG.debug("Planned OBS CreateKey: {}/{}/{}", volumeName, bucketName, keyName);
  }

  private OmKeyInfo buildKeyInfo(long objectId, long managedIndex, long now,
      OzoneManager om) throws OMException {
    KeyArgs keyArgs = getOmRequest().getCreateKeyRequest().getKeyArgs();
    long dataSize = (keyArgs.hasDataSize() && keyArgs.getDataSize() > 0)
        ? keyArgs.getDataSize() : 0;

    List<OzoneAcl> acls = OzoneAclUtil.getDefaultAclList(
        UserGroupInformation.createRemoteUser(getOmRequest().getUserInfo().getUserName()),
        om.getConfig());

    OmKeyInfo.Builder builder = new OmKeyInfo.Builder()
        .setVolumeName(volumeName)
        .setBucketName(bucketName)
        .setKeyName(keyName)
        .setOmKeyLocationInfos(Collections.singletonList(
            new OmKeyLocationInfoGroup(0, new ArrayList<>(allocatedLocations))))
        .setCreationTime(now)
        .setModificationTime(now)
        .setDataSize(dataSize)
        .setReplicationConfig(replicationConfig)
        .setAcls(acls)
        .setObjectID(objectId)
        .setUpdateID(managedIndex)
        .setOwnerName(getOmRequest().getUserInfo().getUserName())
        .setFile(true);

    return builder.build();
  }

  private List<OmKeyLocationInfo> allocateBlocks(OzoneManager om, long requestedSize,
      long scmBlockSize) throws IOException {
    int dataGroupSize = replicationConfig instanceof ECReplicationConfig
        ? ((ECReplicationConfig) replicationConfig).getData() : 1;
    int numBlocks = (int) Math.min(om.getPreallocateBlocksMax(),
        (requestedSize - 1) / (scmBlockSize * dataGroupSize) + 1);

    String clientMachine = "";
    if (getOmRequest().getUserInfo().hasRemoteAddress()) {
      clientMachine = getOmRequest().getUserInfo().getRemoteAddress();
    }

    List<AllocatedBlock> allocatedBlocks = om.getScmClient().getBlockClient()
        .allocateBlock(scmBlockSize, numBlocks, replicationConfig,
            om.getOMServiceId(), new ExcludeList(), clientMachine);

    String remoteUser = getOmRequest().getUserInfo().getUserName();
    List<OmKeyLocationInfo> locationInfos = new ArrayList<>(numBlocks);
    for (AllocatedBlock allocatedBlock : allocatedBlocks) {
      BlockID blockID = new BlockID(allocatedBlock.getBlockID());
      OmKeyLocationInfo.Builder locBuilder = new OmKeyLocationInfo.Builder()
          .setBlockID(blockID)
          .setLength(scmBlockSize)
          .setOffset(0)
          .setPipeline(allocatedBlock.getPipeline());
      if (om.isGrpcBlockTokenEnabled()) {
        locBuilder.setToken(om.getBlockTokenSecretManager().generateToken(
            remoteUser, blockID, EnumSet.of(READ, WRITE), scmBlockSize));
      }
      locationInfos.add(locBuilder.build());
    }
    return locationInfos;
  }

  private static void checkBucketQuotaInBytes(OmBucketInfo bucketInfo, long allocateSize) throws OMException {
    if (bucketInfo.getQuotaInBytes() > OzoneConsts.QUOTA_RESET) {
      long usedBytes = bucketInfo.getTotalBucketSpace();
      long quotaInBytes = bucketInfo.getQuotaInBytes();
      if (quotaInBytes - usedBytes < allocateSize) {
        throw new OMException("The DiskSpace quota of bucket:" + bucketInfo.getBucketName()
            + " exceeded quotaInBytes: " + quotaInBytes + " Bytes but diskspace consumed: "
            + (usedBytes + allocateSize) + " Bytes.",
            OMException.ResultCodes.QUOTA_EXCEEDED);
      }
    }
  }

  private static void checkBucketQuotaInNamespace(OmBucketInfo bucketInfo,
      long allocatedNamespace) throws OMException {
    if (bucketInfo.getQuotaInNamespace() > OzoneConsts.QUOTA_RESET) {
      long usedNamespace = bucketInfo.getTotalBucketNamespace();
      long quotaInNamespace = bucketInfo.getQuotaInNamespace();
      long toUseNamespaceInTotal = usedNamespace + allocatedNamespace;
      if (quotaInNamespace < toUseNamespaceInTotal) {
        throw new OMException("The namespace quota of Bucket:" + bucketInfo.getBucketName()
            + " exceeded: quotaInNamespace: " + quotaInNamespace
            + " but namespace consumed: " + toUseNamespaceInTotal + ".",
            OMException.ResultCodes.QUOTA_EXCEEDED);
      }
    }
  }
}
