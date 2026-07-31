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

package org.apache.hadoop.ozone.custos.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.nio.file.Path;
import org.apache.hadoop.hdds.conf.OzoneConfiguration;
import org.apache.hadoop.ozone.common.Storage.StorageState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit test for {@link CustosStorageConfig}: the cert serial id, cluster id, and
 * Custos uuid survive a restart via the VERSION file, so Custos reuses its
 * certificate across restarts.
 */
class TestCustosStorageConfig {

  @Test
  void persistsCertSerialAndIdentityAcrossRestart(@TempDir Path metaDir)
      throws Exception {
    OzoneConfiguration conf = new OzoneConfiguration();
    conf.set("ozone.metadata.dirs", metaDir.toFile().getAbsolutePath());

    CustosStorageConfig storage = new CustosStorageConfig(conf);
    assertThat(storage.getState()).isNotEqualTo(StorageState.INITIALIZED);
    String clusterId = storage.getClusterID();
    String custosId = storage.getCustosId();
    assertThat(custosId).isNotEmpty();

    storage.initialize();
    storage.setCustosCertSerialId("cert-serial-123");
    storage.persistCurrentState();

    // Re-open: the VERSION file should be read back with the same values.
    CustosStorageConfig reopened = new CustosStorageConfig(conf);
    assertThat(reopened.getState()).isEqualTo(StorageState.INITIALIZED);
    assertThat(reopened.getClusterID()).isEqualTo(clusterId);
    assertThat(reopened.getCustosId()).isEqualTo(custosId);
    assertThat(reopened.getCustosCertSerialId()).isEqualTo("cert-serial-123");

    assertThat(new File(metaDir.toFile(), CustosStorageConfig.STORAGE_DIR))
        .exists();
  }
}
