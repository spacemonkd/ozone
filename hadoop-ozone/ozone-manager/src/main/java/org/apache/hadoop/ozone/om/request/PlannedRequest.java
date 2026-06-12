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

package org.apache.hadoop.ozone.om.request;

import java.io.IOException;

import org.apache.hadoop.ozone.om.OzoneManager;
import org.apache.hadoop.ozone.om.execution.TransitionBuilder;
import org.apache.hadoop.ozone.om.exceptions.OMException;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos.OMRequest;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos.OMResponse;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos.Status;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos.Type;

/**
 * Abstract base class for request handlers using the leader-planned
 * execution path. Subclasses implement the execution template:
 * <ol>
 *   <li>{@link #preProcess} — validate format, normalize keys</li>
 *   <li>{@link #authorize} — check ACLs</li>
 *   <li>{@link #acquireLocks} — acquire granular locks</li>
 *   <li>{@link #plan} — read state and declare DB deltas</li>
 *   <li>{@link #releaseLocks} — release locks</li>
 * </ol>
 *
 * <p>Unlike {@link OMClientRequest}, this class never touches the
 * in-memory table cache and never writes to the DB directly. All
 * mutations are recorded via {@link TransitionBuilder} and applied
 * atomically after Ratis consensus.
 */
public abstract class PlannedRequest {

  private final OMRequest omRequest;

  protected PlannedRequest(OMRequest omRequest) {
    this.omRequest = omRequest;
  }

  public OMRequest getOmRequest() {
    return omRequest;
  }

  /**
   * Returns the command type for this request. Used for barrier
   * detection and routing.
   */
  public Type getCmdType() {
    return omRequest.getCmdType();
  }

  /**
   * Basic request validation and normalization. May set default
   * values, validate key formats, resolve bucket links.
   * Called before locks are acquired.
   */
  public void preProcess(OzoneManager om) throws IOException {
  }

  /**
   * ACL authorization check. Called before locks are acquired.
   * External calls (e.g., Ranger) are allowed here since this
   * runs only on the leader.
   */
  public void authorize(OzoneManager om) throws IOException {
  }

  /**
   * Acquire locks needed for the planning phase. The lock is held
   * during {@link #plan} and released in {@link #releaseLocks}.
   */
  public void acquireLocks(OzoneManager om) throws IOException {
  }

  /**
   * Release locks acquired by {@link #acquireLocks}.
   * Always called in a finally block by the orchestrator.
   */
  public void releaseLocks(OzoneManager om) {
  }

  /**
   * Core business logic: read current DB state and declare all
   * DB mutations via the {@link TransitionBuilder}. Must also set
   * the response on the builder.
   *
   * <p>This method MUST be deterministic with respect to the DB
   * state visible under the acquired locks. It must NOT perform
   * any side effects beyond populating the builder.
   */
  public abstract void plan(OzoneManager om, TransitionBuilder builder) throws IOException;

  /**
   * Builds an error OMResponse for the given exception.
   */
  public OMResponse buildErrorResponse(Exception ex) {
    Status status = Status.INTERNAL_ERROR;
    if (ex instanceof OMException) {
      status = Status.valueOf(((OMException) ex).getResult().name());
    }
    return OMResponse.newBuilder()
        .setCmdType(getCmdType())
        .setStatus(status)
        .setSuccess(false)
        .setMessage(ex.getMessage() != null ? ex.getMessage() : ex.getClass().getName())
        .build();
  }
}
