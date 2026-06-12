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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos.BatchedStateTransitions;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos.OMResponse;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos.ReplicatedStateTransition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Accumulates planned {@link ReplicatedStateTransition} entries and flushes
 * them as a single {@link BatchedStateTransitions} Ratis log entry.
 *
 * <p>Multiple RPC handler threads submit planned transitions concurrently.
 * A pool of batcher threads drains the queue, assembles batches, and
 * submits them to Ratis. Each original request holds a
 * {@link CompletableFuture} that completes when the batch is committed.
 *
 * <p>Flush triggers: max batch size OR max wait time, whichever comes first.
 */
public final class TransitionBatcher {

  private static final Logger LOG = LoggerFactory.getLogger(TransitionBatcher.class);

  private final BlockingQueue<PendingTransition> queue;
  private final ExecutorService batcherPool;
  private final BatchSubmitter submitter;
  private final int maxBatchSize;
  private final long flushIntervalMs;
  private final AtomicBoolean running = new AtomicBoolean(true);

  public TransitionBatcher(BatchSubmitter submitter, int threadCount,
      int maxBatchSize, long flushIntervalMs) {
    this.submitter = submitter;
    this.maxBatchSize = maxBatchSize;
    this.flushIntervalMs = flushIntervalMs;
    this.queue = new LinkedBlockingQueue<>();
    this.batcherPool = Executors.newFixedThreadPool(threadCount,
        new ThreadFactoryBuilder()
            .setNameFormat("om-transition-batcher-%d")
            .setDaemon(true)
            .build());
    for (int i = 0; i < threadCount; i++) {
      batcherPool.submit(this::batcherLoop);
    }
  }

  /**
   * Submits a planned transition for batched Ratis replication.
   * Returns a future that completes with the OMResponse once the
   * batch containing this transition is committed.
   */
  public CompletableFuture<OMResponse> submit(ReplicatedStateTransition transition) {
    CompletableFuture<OMResponse> future = new CompletableFuture<>();
    queue.add(new PendingTransition(transition, future));
    return future;
  }

  private void batcherLoop() {
    List<PendingTransition> batch = new ArrayList<>(maxBatchSize);
    while (running.get()) {
      try {
        PendingTransition first = queue.poll(flushIntervalMs, TimeUnit.MILLISECONDS);
        if (first == null) {
          continue;
        }
        batch.add(first);
        queue.drainTo(batch, maxBatchSize - 1);
        flush(batch);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      } catch (Exception e) {
        LOG.error("Batcher loop error, failing batch of {} entries", batch.size(), e);
        for (PendingTransition pt : batch) {
          pt.future.completeExceptionally(e);
        }
      } finally {
        batch.clear();
      }
    }
  }

  private void flush(List<PendingTransition> batch) {
    BatchedStateTransitions.Builder builder = BatchedStateTransitions.newBuilder();
    for (PendingTransition pt : batch) {
      builder.addTransitions(pt.transition);
    }
    BatchedStateTransitions batched = builder.build();

    try {
      submitter.submitBatch(batched, batch);
    } catch (Exception e) {
      LOG.error("Failed to submit batch of {} transitions to Ratis", batch.size(), e);
      for (PendingTransition pt : batch) {
        pt.future.completeExceptionally(e);
      }
    }
  }

  public void shutdown() {
    running.set(false);
    batcherPool.shutdownNow();
    try {
      batcherPool.awaitTermination(5, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  /**
   * Callback interface for submitting a batched payload to Ratis.
   * The implementation is responsible for completing the futures in
   * each PendingTransition once the batch is committed.
   */
  @FunctionalInterface
  public interface BatchSubmitter {
    void submitBatch(BatchedStateTransitions batched, List<PendingTransition> pending) throws Exception;
  }

  /**
   * Holds a single transition and its completion future.
   */
  public static final class PendingTransition {
    private final ReplicatedStateTransition transition;
    private final CompletableFuture<OMResponse> future;

    public PendingTransition(ReplicatedStateTransition transition,
        CompletableFuture<OMResponse> future) {
      this.transition = transition;
      this.future = future;
    }

    public ReplicatedStateTransition getTransition() {
      return transition;
    }

    public CompletableFuture<OMResponse> getFuture() {
      return future;
    }
  }
}
