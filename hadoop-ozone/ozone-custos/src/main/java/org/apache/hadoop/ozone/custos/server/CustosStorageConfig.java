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

import java.io.IOException;
import java.util.Properties;
import java.util.UUID;
import org.apache.hadoop.hdds.conf.OzoneConfiguration;
import org.apache.hadoop.hdds.protocol.proto.HddsProtos.NodeType;
import org.apache.hadoop.hdds.server.ServerUtils;
import org.apache.hadoop.hdds.upgrade.HDDSLayoutVersionManager;
import org.apache.hadoop.ozone.common.Storage;

/**
 * Persistent storage for the Custos service: a VERSION file under
 * {@code <ozone.metadata.dirs>/custos} holding the cluster id, the Custos node
 * uuid, and the serial id of the SCM-issued certificate. Persisting the serial
 * id lets Custos reuse its certificate across restarts instead of requesting a
 * new one every boot, mirroring {@code ReconStorageConfig}.
 */
public class CustosStorageConfig extends Storage {

  public static final String STORAGE_DIR = "custos";
  public static final String CUSTOS_CERT_SERIAL_ID = "custosCertSerialId";
  public static final String CUSTOS_ID = "uuid";

  public CustosStorageConfig(OzoneConfiguration conf) throws IOException {
    super(NodeType.CUSTOS, ServerUtils.getOzoneMetaDirPath(conf), STORAGE_DIR,
        HDDSLayoutVersionManager.maxLayoutVersion());
  }

  public void setCustosCertSerialId(String certSerialId) {
    getStorageInfo().setProperty(CUSTOS_CERT_SERIAL_ID, certSerialId);
  }

  public String getCustosCertSerialId() {
    return getStorageInfo().getProperty(CUSTOS_CERT_SERIAL_ID);
  }

  public void unsetCustosCertSerialId() {
    getStorageInfo().unsetProperty(CUSTOS_CERT_SERIAL_ID);
  }

  /**
   * @return the Custos node uuid recorded in the VERSION file.
   */
  public String getCustosId() {
    return getStorageInfo().getProperty(CUSTOS_ID);
  }

  @Override
  protected Properties getNodeProperties() {
    String custosId = getCustosId();
    if (custosId == null) {
      custosId = UUID.randomUUID().toString();
    }
    Properties custosProperties = new Properties();
    custosProperties.setProperty(CUSTOS_ID, custosId);
    if (getCustosCertSerialId() != null) {
      custosProperties.setProperty(CUSTOS_CERT_SERIAL_ID,
          getCustosCertSerialId());
    }
    return custosProperties;
  }
}
