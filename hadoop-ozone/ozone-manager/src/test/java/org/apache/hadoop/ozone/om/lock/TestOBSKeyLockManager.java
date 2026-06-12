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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.hadoop.hdds.conf.OzoneConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link OBSKeyLockManager}.
 */
class TestOBSKeyLockManager {

  private OBSKeyLockManager lockManager;

  @BeforeEach
  void setup() {
    OzoneConfiguration conf = new OzoneConfiguration();
    OzoneManagerLock omLock = new OzoneManagerLock(conf);
    lockManager = new OBSKeyLockManager(omLock);
  }

  @Test
  void testBucketReadLockAllowsConcurrentReaders() throws Exception {
    int numThreads = 10;
    CountDownLatch allAcquired = new CountDownLatch(numThreads);
    CountDownLatch release = new CountDownLatch(1);
    AtomicInteger acquired = new AtomicInteger(0);

    ExecutorService executor = Executors.newFixedThreadPool(numThreads);
    for (int i = 0; i < numThreads; i++) {
      executor.submit(() -> {
        lockManager.acquireBucketReadLock("vol", "bucket");
        try {
          acquired.incrementAndGet();
          allAcquired.countDown();
          release.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        } finally {
          lockManager.releaseBucketReadLock("vol", "bucket");
        }
      });
    }

    assertTrue(allAcquired.await(5, TimeUnit.SECONDS),
        "All threads should acquire read lock concurrently");
    assertEquals(numThreads, acquired.get());
    release.countDown();
    executor.shutdown();
    assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
  }

  @Test
  void testKeyWriteLockSerializesSameKey() throws Exception {
    CountDownLatch firstAcquired = new CountDownLatch(1);
    CountDownLatch firstRelease = new CountDownLatch(1);
    AtomicInteger concurrentHolders = new AtomicInteger(0);
    AtomicInteger maxConcurrent = new AtomicInteger(0);

    ExecutorService executor = Executors.newFixedThreadPool(2);

    executor.submit(() -> {
      lockManager.acquireKeyWriteLock("vol", "bucket", "key1");
      try {
        int cur = concurrentHolders.incrementAndGet();
        maxConcurrent.updateAndGet(m -> Math.max(m, cur));
        firstAcquired.countDown();
        firstRelease.await(5, TimeUnit.SECONDS);
        concurrentHolders.decrementAndGet();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      } finally {
        lockManager.releaseKeyWriteLock("vol", "bucket", "key1");
      }
    });

    assertTrue(firstAcquired.await(5, TimeUnit.SECONDS));

    executor.submit(() -> {
      lockManager.acquireKeyWriteLock("vol", "bucket", "key1");
      try {
        int cur = concurrentHolders.incrementAndGet();
        maxConcurrent.updateAndGet(m -> Math.max(m, cur));
        concurrentHolders.decrementAndGet();
      } finally {
        lockManager.releaseKeyWriteLock("vol", "bucket", "key1");
      }
    });

    Thread.sleep(100);
    assertEquals(1, concurrentHolders.get(),
        "Second thread should be blocked waiting for key lock");

    firstRelease.countDown();
    executor.shutdown();
    assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
    assertEquals(1, maxConcurrent.get(),
        "Key write lock should serialize access to same key");
  }

  @Test
  void testDifferentKeysCanProceedInParallel() throws Exception {
    CountDownLatch bothAcquired = new CountDownLatch(2);
    CountDownLatch release = new CountDownLatch(1);

    ExecutorService executor = Executors.newFixedThreadPool(2);

    for (int i = 0; i < 2; i++) {
      String keyName = "key" + i;
      executor.submit(() -> {
        lockManager.acquireKeyWriteLock("vol", "bucket", keyName);
        try {
          bothAcquired.countDown();
          release.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        } finally {
          lockManager.releaseKeyWriteLock("vol", "bucket", keyName);
        }
      });
    }

    assertTrue(bothAcquired.await(5, TimeUnit.SECONDS),
        "Different keys should be lockable concurrently");
    release.countDown();
    executor.shutdown();
    assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
  }

  @Test
  void testStripeCount() {
    assertEquals(1024, lockManager.getStripeCount());
    OzoneConfiguration conf = new OzoneConfiguration();
    OzoneManagerLock omLock = new OzoneManagerLock(conf);
    OBSKeyLockManager custom = new OBSKeyLockManager(omLock, 256);
    assertEquals(256, custom.getStripeCount());
  }
}
