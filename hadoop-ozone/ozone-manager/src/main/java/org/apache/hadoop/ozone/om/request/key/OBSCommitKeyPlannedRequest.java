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


import java.io.IOException;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

import org.apache.hadoop.ozone.OmUtils;
import org.apache.hadoop.ozone.OzoneConsts;
import org.apache.hadoop.ozone.om.OMMetadataManager;
import org.apache.hadoop.ozone.om.OzoneManager;
import org.apache.hadoop.ozone.om.exceptions.OMException;
import org.apache.hadoop.ozone.om.execution.TransitionBuilder;
import org.apache.hadoop.ozone.om.helpers.OmBucketInfo;
import org.apache.hadoop.ozone.om.helpers.OmKeyInfo;
import org.apache.hadoop.ozone.om.helpers.OmKeyLocationInfo;
import org.apache.hadoop.ozone.om.helpers.QuotaUtil;
import org.apache.hadoop.ozone.om.helpers.RepeatedOmKeyInfo;
import org.apache.hadoop.ozone.om.request.PlannedRequest;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos.CommitKeyRequest;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos.KeyArgs;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos.KeyLocation;
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
 * OBS (Object Store layout) CommitKey implementation for the leader-planned execution path.
 * Handles non-hsync, non-recovery key commit: removes from openKeyTable, puts in keyTable,
 * handles overwrite (old key → deletedTable), and updates bucket quota.
 */
public class OBSCommitKeyPlannedRequest extends PlannedRequest {

  private static final Logger LOG = LoggerFactory.getLogger(OBSCommitKeyPlannedRequest.class);

  private String volumeName;
  private String bucketName;
  private String keyName;
  private long clientID;

  public OBSCommitKeyPlannedRequest(OMRequest omRequest) {
    super(omRequest);
  }

  @Override
  public void preProcess(OzoneManager om) throws IOException {
    CommitKeyRequest commitKeyRequest = getOmRequest().getCommitKeyRequest();
    KeyArgs keyArgs = commitKeyRequest.getKeyArgs();

    this.volumeName = keyArgs.getVolumeName();
    this.bucketName = keyArgs.getBucketName();
    this.keyName = keyArgs.getKeyName();
    this.clientID = commitKeyRequest.getClientID();

    if (om.getConfig().isKeyNameCharacterCheckEnabled()) {
      OmUtils.validateKeyName(keyName);
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
          IAccessAuthorizer.ACLType.WRITE, volumeName, bucketName, keyName,
          ugi, remoteAddress, hostName, true, null);
    }
  }

  @Override
  public void acquireLocks(OzoneManager om) throws IOException {
    om.getKeyLockManager().acquireBucketReadLock(volumeName, bucketName);
    om.getKeyLockManager().acquireKeyWriteLock(volumeName, bucketName, keyName);
  }

  @Override
  public void releaseLocks(OzoneManager om) {
    om.getKeyLockManager().releaseKeyWriteLock(volumeName, bucketName, keyName);
    om.getKeyLockManager().releaseBucketReadLock(volumeName, bucketName);
  }

  @Override
  @SuppressWarnings("methodlength")
  public void plan(OzoneManager om, TransitionBuilder builder) throws IOException {
    OMMetadataManager metadataManager = om.getMetadataManager();
    CommitKeyRequest commitKeyRequest = getOmRequest().getCommitKeyRequest();
    KeyArgs commitKeyArgs = commitKeyRequest.getKeyArgs();

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

    String dbOpenKey = metadataManager.getOpenKey(volumeName, bucketName, keyName,
        String.valueOf(clientID));
    OmKeyInfo openKeyInfo = metadataManager.getOpenKeyTable(
        bucketInfo.getBucketLayout()).getSkipCache(dbOpenKey);
    if (openKeyInfo == null) {
      throw new OMException("Failed to commit key, as " + dbOpenKey
          + " entry is not found in the OpenKey table",
          OMException.ResultCodes.KEY_NOT_FOUND);
    }

    long managedIndex = builder.getManagedIndex();
    long now = Time.now();

    List<OmKeyLocationInfo> locationInfoList = new ArrayList<>();
    for (KeyLocation keyLocation : commitKeyArgs.getKeyLocationsList()) {
      OmKeyLocationInfo locationInfo = OmKeyLocationInfo.getFromProtobuf(keyLocation);
      if (om.isGrpcBlockTokenEnabled()) {
        locationInfo.setToken(null);
      }
      locationInfoList.add(locationInfo);
    }

    openKeyInfo.setModificationTime(commitKeyArgs.getModificationTime() > 0
        ? commitKeyArgs.getModificationTime() : now);

    OmKeyInfo committedKeyInfo = openKeyInfo.toBuilder()
        .setUpdateID(managedIndex)
        .setDataSize(commitKeyArgs.getDataSize())
        .build();

    List<OmKeyLocationInfo> uncommitted =
        committedKeyInfo.updateLocationInfoList(locationInfoList, false);

    String dbOzoneKey = metadataManager.getOzoneKey(volumeName, bucketName, keyName);
    OmKeyInfo keyToDelete = metadataManager.getKeyTable(
        bucketInfo.getBucketLayout()).getSkipCache(dbOzoneKey);

    long correctedSpace = committedKeyInfo.getReplicatedSize();

    if (keyToDelete != null && !bucketInfo.getIsVersionEnabled()) {
      RepeatedOmKeyInfo oldVerKeyInfo = OmUtils.prepareKeyForDelete(
          bucketInfo.getObjectID(), keyToDelete, managedIndex);

      long pseudoObjId = om.getObjectIdFromTxId(managedIndex);
      String delKeyName = metadataManager.getOzoneDeletePathKey(pseudoObjId, dbOzoneKey);

      long totalDeletedSize = 0;
      for (OmKeyInfo oldKey : oldVerKeyInfo.getOmKeyInfoList()) {
        totalDeletedSize += sumBlockLengths(oldKey);
      }
      correctedSpace -= totalDeletedSize;

      checkBucketQuotaInNamespace(bucketInfo, 1L);
      checkBucketQuotaInBytes(bucketInfo, correctedSpace);

      bucketInfo.decrUsedNamespace(1L, true);
      bucketInfo.decrUsedBytes(totalDeletedSize, true);

      builder.put("deletedTable", delKeyName, oldVerKeyInfo,
          RepeatedOmKeyInfo.getCodec(true));
    } else {
      checkBucketQuotaInNamespace(bucketInfo, 1L);
      checkBucketQuotaInBytes(bucketInfo, correctedSpace);
    }

    bucketInfo.incrUsedNamespace(1L);
    bucketInfo.incrUsedBytes(correctedSpace);

    if (!uncommitted.isEmpty() && committedKeyInfo.getDataSize() > 0) {
      OmKeyInfo pseudoKeyInfo = committedKeyInfo.toBuilder()
          .setObjectID(OzoneConsts.OBJECT_ID_RECLAIM_BLOCKS)
          .build();
      pseudoKeyInfo.setKeyLocationVersions(java.util.Collections.singletonList(
          new org.apache.hadoop.ozone.om.helpers.OmKeyLocationInfoGroup(0, uncommitted)));
      long pseudoObjId = om.getObjectIdFromTxId(managedIndex);
      String delKeyForUncommitted = metadataManager.getOzoneDeletePathKey(pseudoObjId,
          dbOzoneKey + "/uncommitted");
      RepeatedOmKeyInfo repeatedInfo = new RepeatedOmKeyInfo(bucketInfo.getObjectID());
      repeatedInfo.addOmKeyInfo(pseudoKeyInfo);
      builder.put("deletedTable", delKeyForUncommitted, repeatedInfo,
          RepeatedOmKeyInfo.getCodec(true));
    }

    committedKeyInfo = committedKeyInfo.withMetadataMutations(metadata -> {
      metadata.remove(OzoneConsts.HSYNC_CLIENT_ID);
    });

    builder.delete("openKeyTable", dbOpenKey);
    builder.put("keyTable", dbOzoneKey, committedKeyInfo, OmKeyInfo.getCodec());
    builder.put("bucketTable", dbBucketKey, bucketInfo.copyObject(), OmBucketInfo.getCodec());

    builder.setResponse(OMResponse.newBuilder()
        .setCmdType(Type.CommitKey)
        .setStatus(Status.OK)
        .setSuccess(true)
        .build());

    LOG.debug("Planned OBS CommitKey: {}/{}/{}", volumeName, bucketName, keyName);
  }

  private static long sumBlockLengths(OmKeyInfo omKeyInfo) {
    long bytesUsed = 0;
    for (org.apache.hadoop.ozone.om.helpers.OmKeyLocationInfoGroup group
        : omKeyInfo.getKeyLocationVersions()) {
      for (OmKeyLocationInfo locationInfo : group.getLocationList()) {
        bytesUsed += QuotaUtil.getReplicatedSize(
            locationInfo.getLength(), omKeyInfo.getReplicationConfig());
      }
    }
    return bytesUsed;
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
