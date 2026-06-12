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

import static org.apache.hadoop.ozone.om.lock.OzoneManagerLock.LeveledResource.USER_LOCK;
import static org.apache.hadoop.ozone.om.lock.OzoneManagerLock.LeveledResource.VOLUME_LOCK;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.InvalidPathException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.hadoop.hdds.utils.db.Proto2Codec;
import org.apache.hadoop.ozone.OmUtils;
import org.apache.hadoop.ozone.OzoneAcl;
import org.apache.hadoop.ozone.om.OMMetadataManager;
import org.apache.hadoop.ozone.om.OzoneManager;
import org.apache.hadoop.ozone.om.exceptions.OMException;
import org.apache.hadoop.ozone.om.execution.TransitionBuilder;
import org.apache.hadoop.ozone.om.helpers.OmVolumeArgs;
import org.apache.hadoop.ozone.om.helpers.OzoneAclUtil;
import org.apache.hadoop.ozone.om.request.PlannedRequest;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos.CreateVolumeRequest;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos.CreateVolumeResponse;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos.OMRequest;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos.OMResponse;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos.Status;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos.VolumeInfo;
import org.apache.hadoop.ozone.security.acl.IAccessAuthorizer;
import org.apache.hadoop.ozone.security.acl.OzoneObj;
import org.apache.hadoop.ozone.storage.proto.OzoneManagerStorageProtos.PersistedUserVolumeInfo;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.util.Time;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * CreateVolume implementation for the leader-planned execution path.
 */
public class CreateVolumePlannedRequest extends PlannedRequest {

  private static final Logger LOG = LoggerFactory.getLogger(CreateVolumePlannedRequest.class);

  private String volume;
  private String owner;

  public CreateVolumePlannedRequest(OMRequest omRequest) {
    super(omRequest);
  }

  @Override
  public void preProcess(OzoneManager om) throws IOException {
    VolumeInfo volumeInfo = getOmRequest().getCreateVolumeRequest().getVolumeInfo();
    try {
      OmUtils.validateVolumeName(volumeInfo.getVolume(), om.isStrictS3());
    } catch (InvalidPathException e) {
      throw new OMException("Invalid volume name: " + volumeInfo.getVolume(),
          OMException.ResultCodes.INVALID_VOLUME_NAME);
    }
    this.volume = volumeInfo.getVolume();
    this.owner = volumeInfo.getOwnerName();
  }

  @Override
  public void authorize(OzoneManager om) throws IOException {
    if (om.getAclsEnabled()) {
      UserGroupInformation ugi = UserGroupInformation.createRemoteUser(
          getOmRequest().getUserInfo().getUserName());
      InetAddress remoteAddress = InetAddress.getByName(
          getOmRequest().getUserInfo().getRemoteAddress());
      String hostName = getOmRequest().getUserInfo().getHostName();
      om.checkAcls(OzoneObj.ResourceType.VOLUME, OzoneObj.StoreType.OZONE,
          IAccessAuthorizer.ACLType.CREATE, volume, null, null,
          ugi, remoteAddress, hostName, true, null);
    }
  }

  @Override
  public void acquireLocks(OzoneManager om) throws IOException {
    OMMetadataManager metadataManager = om.getMetadataManager();
    metadataManager.getLock().acquireWriteLock(VOLUME_LOCK, volume);
    metadataManager.getLock().acquireWriteLock(USER_LOCK, owner);
  }

  @Override
  public void releaseLocks(OzoneManager om) {
    OMMetadataManager metadataManager = om.getMetadataManager();
    metadataManager.getLock().releaseWriteLock(USER_LOCK, owner);
    metadataManager.getLock().releaseWriteLock(VOLUME_LOCK, volume);
  }

  @Override
  public void plan(OzoneManager om, TransitionBuilder builder) throws IOException {
    CreateVolumeRequest createVolumeRequest = getOmRequest().getCreateVolumeRequest();
    VolumeInfo volumeInfo = createVolumeRequest.getVolumeInfo();
    OMMetadataManager metadataManager = om.getMetadataManager();

    String dbVolumeKey = metadataManager.getVolumeKey(volume);
    if (metadataManager.getVolumeTable().getSkipCache(dbVolumeKey) != null) {
      throw new OMException("Volume already exists",
          OMException.ResultCodes.VOLUME_ALREADY_EXISTS);
    }

    long managedIndex = builder.getManagedIndex();
    long objectId = om.getObjectIdFromTxId(managedIndex);

    long now = Time.now();
    VolumeInfo updatedVolumeInfo = volumeInfo.toBuilder()
        .setCreationTime(now)
        .setModificationTime(now)
        .build();

    // Add default ACLs
    List<OzoneAcl> defaultAclList = OzoneAclUtil.getDefaultAclList(
        UserGroupInformation.createRemoteUser(owner), om.getConfig());
    OmVolumeArgs.Builder volumeBuilder = OmVolumeArgs.builderFromProtobuf(updatedVolumeInfo)
        .setObjectID(objectId)
        .setUpdateID(managedIndex);
    if (om.getConfig().ignoreClientACLs()) {
      volumeBuilder.setAcls(defaultAclList);
    } else {
      volumeBuilder.acls().addAll(defaultAclList);
    }
    OmVolumeArgs omVolumeArgs = volumeBuilder.build();

    // Update user volume list
    String dbUserKey = metadataManager.getUserKey(owner);
    PersistedUserVolumeInfo existingList = metadataManager.getUserTable().get(dbUserKey);
    PersistedUserVolumeInfo updatedList = addVolumeToOwnerList(
        existingList, volume, owner, om.getMaxUserVolumeCount(), managedIndex);

    builder.put("volumeTable", dbVolumeKey, omVolumeArgs, OmVolumeArgs.getCodec());
    builder.put("userTable", dbUserKey, updatedList,
        Proto2Codec.get(PersistedUserVolumeInfo.getDefaultInstance()));

    builder.setResponse(OMResponse.newBuilder()
        .setCmdType(getCmdType())
        .setStatus(Status.OK)
        .setSuccess(true)
        .setCreateVolumeResponse(CreateVolumeResponse.newBuilder().build())
        .build());

    LOG.debug("Planned volume creation: {}", volume);
  }

  private static PersistedUserVolumeInfo addVolumeToOwnerList(
      PersistedUserVolumeInfo volumeList, String volume, String owner,
      long maxUserVolumeCount, long txID) throws OMException {
    if (volumeList != null && volumeList.getVolumeNamesList().size() >= maxUserVolumeCount) {
      throw new OMException("Too many volumes for user:" + owner,
          OMException.ResultCodes.USER_TOO_MANY_VOLUMES);
    }

    Set<String> volumeSet = new HashSet<>();
    long objectID = txID;
    if (volumeList != null) {
      volumeSet.addAll(volumeList.getVolumeNamesList());
      objectID = volumeList.getObjectID();
    }
    volumeSet.add(volume);

    return PersistedUserVolumeInfo.newBuilder()
        .setObjectID(objectID)
        .setUpdateID(txID)
        .addAllVolumeNames(volumeSet)
        .build();
  }
}
