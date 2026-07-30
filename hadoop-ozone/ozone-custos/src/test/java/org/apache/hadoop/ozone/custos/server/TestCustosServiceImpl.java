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

import static org.apache.hadoop.ozone.custos.server.CustosConfig.Keys.IDENTITY_PROVIDERS;
import static org.apache.hadoop.ozone.custos.server.CustosConfig.Keys.PROVIDERS;
import static org.assertj.core.api.Assertions.assertThat;

import io.grpc.stub.StreamObserver;
import java.util.Collections;
import org.apache.hadoop.hdds.conf.OzoneConfiguration;
import org.apache.hadoop.ozone.custos.CredentialType;
import org.apache.hadoop.ozone.custos.CustosCredential;
import org.apache.hadoop.ozone.custos.client.CustosProtoHelper;
import org.apache.hadoop.ozone.custos.proto.CustosServiceProtos.GetSessionTokenRequest;
import org.apache.hadoop.ozone.custos.proto.CustosServiceProtos.GetSessionTokenResponse;
import org.apache.hadoop.ozone.custos.server.identity.OidcIdentityProvider;
import org.junit.jupiter.api.Test;

/**
 * Unit test for {@link CustosServiceImpl}: a successful {@code GetSessionToken}
 * returns the token and the cluster CA bundle, so the CA is delivered only to
 * an authenticated caller.
 */
class TestCustosServiceImpl {

  private static final String CA_PEM =
      "-----BEGIN CERTIFICATE-----\nMIIB-test-ca\n-----END CERTIFICATE-----";

  @Test
  void getSessionTokenReturnsTokenAndCaBundle() throws Exception {
    OzoneConfiguration conf = new OzoneConfiguration();
    conf.set(PROVIDERS, StubOidcProvider.class.getName());
    conf.set(IDENTITY_PROVIDERS, OidcIdentityProvider.class.getName());

    CustosAuthService authService = new CustosAuthService(
        new CustosProviderRegistry(conf), new IdentityProviderRegistry(conf));
    CustosServiceImpl service =
        new CustosServiceImpl(authService, Collections.singletonList(CA_PEM));

    CustosCredential credential = new CustosCredential(CredentialType.OIDC_JWT,
        new byte[] {1}, Collections.singletonMap("subject", "alice"));
    GetSessionTokenRequest request = GetSessionTokenRequest.newBuilder()
        .setCredential(CustosProtoHelper.toProto(credential))
        .setAudience("om-service-1")
        .setRequestedTtlMs(60_000L)
        .build();

    CapturingObserver observer = new CapturingObserver();
    service.getSessionToken(request, observer);

    assertThat(observer.error).isNull();
    assertThat(observer.value.getToken().getSubject()).isEqualTo("alice");
    assertThat(observer.value.getCaCertPemList())
        .containsExactly(CA_PEM);
    assertThat(observer.value.getCaUnchanged()).isFalse();
    assertThat(observer.value.getCaFingerprint()).isNotEmpty();
  }

  @Test
  void omitsCaBundleWhenClientFingerprintMatches() throws Exception {
    OzoneConfiguration conf = new OzoneConfiguration();
    conf.set(PROVIDERS, StubOidcProvider.class.getName());
    conf.set(IDENTITY_PROVIDERS, OidcIdentityProvider.class.getName());

    CustosAuthService authService = new CustosAuthService(
        new CustosProviderRegistry(conf), new IdentityProviderRegistry(conf));
    CustosServiceImpl service =
        new CustosServiceImpl(authService, Collections.singletonList(CA_PEM));

    CustosCredential credential = new CustosCredential(CredentialType.OIDC_JWT,
        new byte[] {1}, Collections.singletonMap("subject", "alice"));

    // First call: no known fingerprint, so the bundle is returned.
    CapturingObserver first = new CapturingObserver();
    service.getSessionToken(GetSessionTokenRequest.newBuilder()
        .setCredential(CustosProtoHelper.toProto(credential))
        .build(), first);
    String fingerprint = first.value.getCaFingerprint();
    assertThat(fingerprint).isNotEmpty();
    assertThat(first.value.getCaCertPemList()).containsExactly(CA_PEM);

    // Second call echoing the fingerprint: bundle omitted, ca_unchanged set.
    CapturingObserver second = new CapturingObserver();
    service.getSessionToken(GetSessionTokenRequest.newBuilder()
        .setCredential(CustosProtoHelper.toProto(credential))
        .setKnownCaFingerprint(fingerprint)
        .build(), second);
    assertThat(second.error).isNull();
    assertThat(second.value.getCaUnchanged()).isTrue();
    assertThat(second.value.getCaCertPemList()).isEmpty();
    assertThat(second.value.getCaFingerprint()).isEqualTo(fingerprint);
  }

  @Test
  void caBundleMayBeEmpty() throws Exception {
    OzoneConfiguration conf = new OzoneConfiguration();
    conf.set(PROVIDERS, StubOidcProvider.class.getName());
    conf.set(IDENTITY_PROVIDERS, OidcIdentityProvider.class.getName());

    CustosAuthService authService = new CustosAuthService(
        new CustosProviderRegistry(conf), new IdentityProviderRegistry(conf));
    CustosServiceImpl service =
        new CustosServiceImpl(authService, Collections.emptyList());

    CustosCredential credential = new CustosCredential(CredentialType.OIDC_JWT,
        new byte[] {1}, Collections.singletonMap("subject", "bob"));
    GetSessionTokenRequest request = GetSessionTokenRequest.newBuilder()
        .setCredential(CustosProtoHelper.toProto(credential))
        .build();

    CapturingObserver observer = new CapturingObserver();
    service.getSessionToken(request, observer);

    assertThat(observer.error).isNull();
    assertThat(observer.value.getToken().getSubject()).isEqualTo("bob");
    assertThat(observer.value.getCaCertPemList()).isEmpty();
  }

  private static final class CapturingObserver
      implements StreamObserver<GetSessionTokenResponse> {
    private GetSessionTokenResponse value;
    private Throwable error;

    @Override
    public void onNext(GetSessionTokenResponse response) {
      this.value = response;
    }

    @Override
    public void onError(Throwable t) {
      this.error = t;
    }

    @Override
    public void onCompleted() {
    }
  }
}
