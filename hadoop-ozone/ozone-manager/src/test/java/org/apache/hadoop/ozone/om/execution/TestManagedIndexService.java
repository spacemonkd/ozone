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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.apache.hadoop.hdds.utils.db.BatchOperation;
import org.apache.hadoop.hdds.utils.db.Table;
import org.apache.hadoop.ozone.om.OMMetadataManager;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link ManagedIndexService}.
 */
class TestManagedIndexService {

  @Test
  void testMonotonicIncrement() {
    ManagedIndexService service = new ManagedIndexService(0);
    assertEquals(1, service.getAndIncrement());
    assertEquals(2, service.getAndIncrement());
    assertEquals(3, service.getAndIncrement());
    assertEquals(3, service.current());
  }

  @Test
  void testStartFromInitialValue() {
    ManagedIndexService service = new ManagedIndexService(100);
    assertEquals(101, service.getAndIncrement());
    assertEquals(102, service.getAndIncrement());
  }

  @Test
  void testOnBecomeLeaderWithHigherCommitIndex() {
    ManagedIndexService service = new ManagedIndexService(70);
    service.onBecomeLeader(80);
    assertEquals(80, service.current());
    assertEquals(81, service.getAndIncrement());
  }

  @Test
  void testOnBecomeLeaderWithLowerCommitIndex() {
    ManagedIndexService service = new ManagedIndexService(100);
    service.onBecomeLeader(80);
    assertEquals(100, service.current());
    assertEquals(101, service.getAndIncrement());
  }

  @Test
  void testUpdateCommitIndex() {
    ManagedIndexService service = new ManagedIndexService(50);
    service.updateCommitIndex(60);
    assertEquals(60, service.current());
    assertEquals(61, service.getAndIncrement());
  }

  @Test
  void testUpdateCommitIndexDoesNotDecrease() {
    ManagedIndexService service = new ManagedIndexService(100);
    service.updateCommitIndex(50);
    assertEquals(100, service.current());
  }

  @Test
  void testConcurrentGetAndIncrement() throws InterruptedException {
    ManagedIndexService service = new ManagedIndexService(0);
    int threadCount = 10;
    int incrementsPerThread = 1000;
    ExecutorService executor = Executors.newFixedThreadPool(threadCount);
    CountDownLatch latch = new CountDownLatch(threadCount);
    Set<Long> allIndexes = ConcurrentHashMap.newKeySet();

    for (int i = 0; i < threadCount; i++) {
      executor.submit(() -> {
        try {
          for (int j = 0; j < incrementsPerThread; j++) {
            allIndexes.add(service.getAndIncrement());
          }
        } finally {
          latch.countDown();
        }
      });
    }

    assertTrue(latch.await(10, TimeUnit.SECONDS));
    executor.shutdown();

    int expectedTotal = threadCount * incrementsPerThread;
    assertEquals(expectedTotal, allIndexes.size());
    assertEquals(expectedTotal, service.current());
  }

  @Test
  @SuppressWarnings("unchecked")
  void testRecover() throws IOException {
    OMMetadataManager metadataManager = mock(OMMetadataManager.class);
    Table<String, String> metaTable = mock(Table.class);
    when(metadataManager.getMetaTable()).thenReturn(metaTable);
    when(metaTable.get(ManagedIndexService.MANAGED_INDEX_KEY)).thenReturn("42");

    ManagedIndexService service = ManagedIndexService.recover(metadataManager);
    assertEquals(42, service.current());
    assertEquals(43, service.getAndIncrement());
  }

  @Test
  @SuppressWarnings("unchecked")
  void testRecoverWithNoPersistedValue() throws IOException {
    OMMetadataManager metadataManager = mock(OMMetadataManager.class);
    Table<String, String> metaTable = mock(Table.class);
    when(metadataManager.getMetaTable()).thenReturn(metaTable);
    when(metaTable.get(ManagedIndexService.MANAGED_INDEX_KEY)).thenReturn(null);

    ManagedIndexService service = ManagedIndexService.recover(metadataManager);
    assertEquals(0, service.current());
    assertEquals(1, service.getAndIncrement());
  }

  @Test
  @SuppressWarnings("unchecked")
  void testPersistWithBatch() throws IOException {
    OMMetadataManager metadataManager = mock(OMMetadataManager.class);
    Table<String, String> metaTable = mock(Table.class);
    when(metadataManager.getMetaTable()).thenReturn(metaTable);
    BatchOperation batchOperation = mock(BatchOperation.class);

    ManagedIndexService service = new ManagedIndexService(0);
    service.persistWithBatch(metadataManager, batchOperation, 55);

    verify(metaTable).putWithBatch(batchOperation,
        ManagedIndexService.MANAGED_INDEX_KEY, "55");
  }
}
