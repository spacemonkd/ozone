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

package org.apache.hadoop.ozone.om.lock;

import java.util.concurrent.locks.ReadWriteLock;

import com.google.common.util.concurrent.Striped;

/**
 * Provides granular locking for OBS key operations in the planned execution path.
 *
 * <p>Lock strategy:
 * <ul>
 *   <li>CreateKey: bucket read lock only — openKeyTable entries are unique per
 *       clientID so parallel creates to the same bucket don't conflict.</li>
 *   <li>CommitKey: bucket read lock + striped key write lock — concurrent commits
 *       to the same key must be serialized, but different keys can proceed in parallel.</li>
 * </ul>
 *
 * <p>The bucket read lock prevents bucket deletion/modification during key operations.
 * The striped key write lock uses a fixed number of stripes (configurable) to bound
 * memory while providing good parallelism across different keys.
 *
 * <p>Lock ordering: always acquire bucket read lock first, then key write lock.
 */
public final class OBSKeyLockManager {

  private static final int DEFAULT_STRIPE_COUNT = 1024;

  private final Striped<ReadWriteLock> keyStripedLocks;
  private final IOzoneManagerLock omLock;
  private final int stripeCount;

  public OBSKeyLockManager(IOzoneManagerLock omLock) {
    this(omLock, DEFAULT_STRIPE_COUNT);
  }

  public OBSKeyLockManager(IOzoneManagerLock omLock, int stripeCount) {
    this.omLock = omLock;
    this.stripeCount = stripeCount;
    this.keyStripedLocks = Striped.readWriteLock(stripeCount);
  }

  /**
   * Acquires a bucket read lock. Used by both CreateKey and CommitKey.
   * Prevents bucket deletion while key operations are in progress.
   */
  public void acquireBucketReadLock(String volumeName, String bucketName) {
    omLock.acquireReadLock(OzoneManagerLock.LeveledResource.BUCKET_LOCK,
        volumeName, bucketName);
  }

  /**
   * Releases the bucket read lock.
   */
  public void releaseBucketReadLock(String volumeName, String bucketName) {
    omLock.releaseReadLock(OzoneManagerLock.LeveledResource.BUCKET_LOCK,
        volumeName, bucketName);
  }

  /**
   * Acquires a striped write lock for the given key path.
   * Must be called after acquireBucketReadLock to maintain lock ordering.
   */
  public void acquireKeyWriteLock(String volumeName, String bucketName, String keyName) {
    String lockKey = volumeName + "/" + bucketName + "/" + keyName;
    keyStripedLocks.get(lockKey).writeLock().lock();
  }

  /**
   * Releases the striped write lock for the given key path.
   */
  public void releaseKeyWriteLock(String volumeName, String bucketName, String keyName) {
    String lockKey = volumeName + "/" + bucketName + "/" + keyName;
    keyStripedLocks.get(lockKey).writeLock().unlock();
  }

  /**
   * Returns the number of stripes configured for key-level locking.
   */
  public int getStripeCount() {
    return stripeCount;
  }
}
