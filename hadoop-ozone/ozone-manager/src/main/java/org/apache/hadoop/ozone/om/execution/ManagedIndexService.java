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

import java.io.IOException;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.hadoop.hdds.utils.db.BatchOperation;
import org.apache.hadoop.ozone.om.OMMetadataManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Provides a monotonically increasing index for object ID generation,
 * decoupled from the Ratis transaction index. This enables pre-Ratis
 * batching where multiple requests share a single Ratis log entry but
 * each needs a unique object identifier.
 *
 * <p>The index is persisted in the OM metaTable under the key
 * {@link #MANAGED_INDEX_KEY}. On leader election, the index is recovered
 * as {@code max(currentIndex, lastCommittedIndex)} to ensure monotonicity
 * even after switchovers where uncommitted indexes were issued.
 */
public final class ManagedIndexService {

  private static final Logger LOG = LoggerFactory.getLogger(ManagedIndexService.class);

  public static final String MANAGED_INDEX_KEY = "#MANAGED_INDEX";

  private final AtomicLong currentIndex;

  public ManagedIndexService(long initialIndex) {
    this.currentIndex = new AtomicLong(initialIndex);
  }

  /**
   * Creates a ManagedIndexService by recovering the last persisted index
   * from the meta table. If no persisted index exists, starts from 0.
   */
  public static ManagedIndexService recover(OMMetadataManager metadataManager) throws IOException {
    String persisted = metadataManager.getMetaTable().get(MANAGED_INDEX_KEY);
    long initial = persisted != null ? Long.parseLong(persisted) : 0L;
    LOG.info("Recovered managed index: {}", initial);
    return new ManagedIndexService(initial);
  }

  /**
   * Returns the next unique index and advances the counter.
   * Thread-safe for concurrent leader-side planning.
   */
  public long getAndIncrement() {
    return currentIndex.incrementAndGet();
  }

  /**
   * Returns the current index value without incrementing.
   */
  public long current() {
    return currentIndex.get();
  }

  /**
   * Called when a batch is committed (at all nodes during apply).
   * Updates the current index to the highest committed managed index
   * so that followers stay in sync.
   */
  public void updateCommitIndex(long committedIndex) {
    currentIndex.getAndUpdate(current -> Math.max(current, committedIndex));
  }

  /**
   * Called when this node becomes the Ratis leader.
   * Ensures the index is at least as high as the last committed value
   * to maintain monotonicity after switchovers.
   *
   * <p>Scenario: Node was leader, issued indexes up to 100, but only
   * 80 were committed. Node loses leadership, another node advances
   * committed to 95. When this node becomes leader again:
   * {@code currentIndex = max(100, 95) = 100} — preserving the gap
   * but maintaining monotonicity.
   */
  public void onBecomeLeader(long lastCommittedIndex) {
    long previous = currentIndex.getAndUpdate(
        current -> Math.max(current, lastCommittedIndex));
    LOG.info("Leader election: managed index {} -> {} (committedIndex={})",
        previous, currentIndex.get(), lastCommittedIndex);
  }

  /**
   * Writes the current managed index to the given batch operation
   * as part of an atomic DB commit.
   */
  public void persistWithBatch(OMMetadataManager metadataManager,
      BatchOperation batchOperation, long indexToCommit) throws IOException {
    metadataManager.getMetaTable().putWithBatch(
        batchOperation, MANAGED_INDEX_KEY, String.valueOf(indexToCommit));
  }
}
