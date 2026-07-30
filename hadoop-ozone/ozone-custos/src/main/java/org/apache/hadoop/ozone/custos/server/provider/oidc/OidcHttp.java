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

package org.apache.hadoop.ozone.custos.server.provider.oidc;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Minimal HTTP GET for fetching OIDC discovery and JWKS documents. Uses the
 * JDK {@link HttpURLConnection} to stay within the Java 8 target.
 */
public final class OidcHttp {

  private OidcHttp() {
  }

  public static String get(String url, int timeoutMs) throws IOException {
    HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
    try {
      conn.setRequestMethod("GET");
      conn.setConnectTimeout(timeoutMs);
      conn.setReadTimeout(timeoutMs);
      int code = conn.getResponseCode();
      if (code != HttpURLConnection.HTTP_OK) {
        throw new IOException("GET " + url + " returned HTTP " + code + ".");
      }
      try (InputStream in = conn.getInputStream()) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) != -1) {
          out.write(buf, 0, n);
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
      }
    } finally {
      conn.disconnect();
    }
  }
}
