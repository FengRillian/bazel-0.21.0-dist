// Copyright 2018 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.remote;

import static com.google.common.truth.Truth.assertThat;

import build.bazel.remote.execution.v2.ActionCacheUpdateCapabilities;
import build.bazel.remote.execution.v2.CacheCapabilities;
import build.bazel.remote.execution.v2.CapabilitiesGrpc.CapabilitiesImplBase;
import build.bazel.remote.execution.v2.DigestFunction;
import build.bazel.remote.execution.v2.ExecutionCapabilities;
import build.bazel.remote.execution.v2.GetCapabilitiesRequest;
import build.bazel.remote.execution.v2.RequestMetadata;
import build.bazel.remote.execution.v2.ServerCapabilities;
import com.google.common.util.concurrent.ListeningScheduledExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.devtools.build.lib.analysis.BlazeVersionInfo;
import com.google.devtools.build.lib.authandtls.AuthAndTLSOptions;
import com.google.devtools.build.lib.authandtls.GoogleAuthUtils;
import com.google.devtools.build.lib.remote.util.TracingMetadataUtils;
import com.google.devtools.common.options.Options;
import io.grpc.CallCredentials;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import io.grpc.util.MutableHandlerRegistry;
import java.util.concurrent.Executors;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link RemoteServerCapabilities}. */
@RunWith(JUnit4.class)
public class RemoteServerCapabilitiesTest {

  private final MutableHandlerRegistry serviceRegistry = new MutableHandlerRegistry();
  private final String fakeServerName = "fake server for " + getClass();
  private Server fakeServer;
  private static ListeningScheduledExecutorService retryService;

  @BeforeClass
  public static void beforeEverything() {
    retryService = MoreExecutors.listeningDecorator(Executors.newScheduledThreadPool(1));
  }

  @Before
  public final void setUp() throws Exception {
    fakeServer =
        InProcessServerBuilder.forName(fakeServerName)
            .fallbackHandlerRegistry(serviceRegistry)
            .directExecutor()
            .build()
            .start();
  }

  @After
  public void tearDown() throws Exception {
    fakeServer.shutdownNow();
    fakeServer.awaitTermination();
  }

  @AfterClass
  public static void afterEverything() {
    retryService.shutdownNow();
  }

  /** Capture the request headers from a client. Useful for testing metadata propagation. */
  private static class RequestHeadersValidator implements ServerInterceptor {
    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
        ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
      RequestMetadata meta = headers.get(TracingMetadataUtils.METADATA_KEY);
      assertThat(meta.getCorrelatedInvocationsId()).isEqualTo("build-req-id");
      assertThat(meta.getToolInvocationId()).isEqualTo("command-id");
      assertThat(meta.getActionId()).isNotEmpty();
      assertThat(meta.getToolDetails().getToolName()).isEqualTo("bazel");
      assertThat(meta.getToolDetails().getToolVersion())
          .isEqualTo(BlazeVersionInfo.instance().getVersion());
      return next.startCall(call, headers);
    }
  }

  @Test
  public void testGetCapabilitiesWithRetries() throws Exception {
    ServerCapabilities caps =
        ServerCapabilities.newBuilder()
            .setExecutionCapabilities(
                ExecutionCapabilities.newBuilder().setExecEnabled(true).build())
            .build();
    serviceRegistry.addService(
        ServerInterceptors.intercept(
            new CapabilitiesImplBase() {
              private int numErrors = 0;
              private static final int MAX_ERRORS = 3;

              @Override
              public void getCapabilities(
                  GetCapabilitiesRequest request,
                  StreamObserver<ServerCapabilities> responseObserver) {
                if (numErrors < MAX_ERRORS) {
                  numErrors++;
                  responseObserver.onError(
                      Status.UNAVAILABLE.asRuntimeException()); // Retriable error.
                } else {
                  responseObserver.onNext(caps);
                  responseObserver.onCompleted();
                }
              }
            },
            new RequestHeadersValidator()));

    RemoteOptions remoteOptions = Options.getDefaults(RemoteOptions.class);
    RemoteRetrier retrier =
        new RemoteRetrier(
            remoteOptions,
            RemoteRetrier.RETRIABLE_GRPC_ERRORS,
            retryService,
            Retrier.ALLOW_ALL_CALLS);
    ReferenceCountedChannel channel =
        new ReferenceCountedChannel(
            InProcessChannelBuilder.forName(fakeServerName).directExecutor().build());
    CallCredentials creds =
        GoogleAuthUtils.newCallCredentials(Options.getDefaults(AuthAndTLSOptions.class));
    RemoteServerCapabilities client =
        new RemoteServerCapabilities("instance", channel.retain(), creds, 3, retrier);

    assertThat(client.get("build-req-id", "command-id")).isEqualTo(caps);
  }

  @Test
  public void testCheckClientServerCompatibility_NoChecks() throws Exception {
    RemoteServerCapabilities.ClientServerCompatibilityStatus st =
        RemoteServerCapabilities.checkClientServerCompatibility(
            ServerCapabilities.getDefaultInstance(),
            Options.getDefaults(RemoteOptions.class),
            DigestFunction.SHA256);
    assertThat(st.isOk()).isTrue();
  }

  @Test
  public void testCheckClientServerCompatibility_ApiVersionDeprecated() throws Exception {
    ServerCapabilities caps =
        ServerCapabilities.newBuilder()
            .setDeprecatedApiVersion(ApiVersion.current.toSemVer())
            .setLowApiVersion(new ApiVersion(100, 0, 0, "").toSemVer())
            .setHighApiVersion(new ApiVersion(100, 0, 0, "").toSemVer())
            .setCacheCapabilities(
                CacheCapabilities.newBuilder()
                    .addDigestFunction(DigestFunction.SHA256)
                    .setActionCacheUpdateCapabilities(
                        ActionCacheUpdateCapabilities.newBuilder().setUpdateEnabled(true).build())
                    .build())
            .build();
    RemoteOptions remoteOptions = Options.getDefaults(RemoteOptions.class);
    remoteOptions.remoteCache = "server:port";
    RemoteServerCapabilities.ClientServerCompatibilityStatus st =
        RemoteServerCapabilities.checkClientServerCompatibility(
            caps, remoteOptions, DigestFunction.SHA256);
    assertThat(st.getErrors()).isEmpty();
    assertThat(st.getWarnings()).hasSize(1);
    assertThat(st.getWarnings().get(0)).containsMatch("API.*deprecated.*100.0");
  }

  @Test
  public void testCheckClientServerCompatibility_ApiVersionUnsupported() throws Exception {
    ServerCapabilities caps =
        ServerCapabilities.newBuilder()
            .setLowApiVersion(new ApiVersion(100, 0, 0, "").toSemVer())
            .setHighApiVersion(new ApiVersion(100, 0, 0, "").toSemVer())
            .setCacheCapabilities(
                CacheCapabilities.newBuilder()
                    .addDigestFunction(DigestFunction.SHA256)
                    .setActionCacheUpdateCapabilities(
                        ActionCacheUpdateCapabilities.newBuilder().setUpdateEnabled(true).build())
                    .build())
            .build();
    RemoteOptions remoteOptions = Options.getDefaults(RemoteOptions.class);
    remoteOptions.remoteCache = "server:port";
    RemoteServerCapabilities.ClientServerCompatibilityStatus st =
        RemoteServerCapabilities.checkClientServerCompatibility(
            caps, remoteOptions, DigestFunction.SHA256);
    assertThat(st.getErrors()).hasSize(1);
    assertThat(st.getErrors().get(0)).containsMatch("API.*not supported.*100.0");
  }

  @Test
  public void testCheckClientServerCompatibility_RemoteCacheDoesNotSupportDigestFunction()
      throws Exception {
    ServerCapabilities caps =
        ServerCapabilities.newBuilder()
            .setLowApiVersion(ApiVersion.current.toSemVer())
            .setHighApiVersion(ApiVersion.current.toSemVer())
            .setCacheCapabilities(
                CacheCapabilities.newBuilder()
                    .addDigestFunction(DigestFunction.MD5)
                    .setActionCacheUpdateCapabilities(
                        ActionCacheUpdateCapabilities.newBuilder().setUpdateEnabled(true).build())
                    .build())
            .build();
    RemoteOptions remoteOptions = Options.getDefaults(RemoteOptions.class);
    remoteOptions.remoteCache = "server:port";
    RemoteServerCapabilities.ClientServerCompatibilityStatus st =
        RemoteServerCapabilities.checkClientServerCompatibility(
            caps, remoteOptions, DigestFunction.SHA256);
    assertThat(st.getErrors()).hasSize(1);
    assertThat(st.getErrors().get(0)).containsMatch("Cannot use hash function");
  }

  @Test
  public void testCheckClientServerCompatibility_RemoteCacheDoesNotSupportUpdate()
      throws Exception {
    ServerCapabilities caps =
        ServerCapabilities.newBuilder()
            .setLowApiVersion(ApiVersion.current.toSemVer())
            .setHighApiVersion(ApiVersion.current.toSemVer())
            .setCacheCapabilities(
                CacheCapabilities.newBuilder().addDigestFunction(DigestFunction.SHA256).build())
            .build();
    RemoteOptions remoteOptions = Options.getDefaults(RemoteOptions.class);
    remoteOptions.remoteCache = "server:port";
    RemoteServerCapabilities.ClientServerCompatibilityStatus st =
        RemoteServerCapabilities.checkClientServerCompatibility(
            caps, remoteOptions, DigestFunction.SHA256);
    assertThat(st.getErrors()).hasSize(1);
    assertThat(st.getErrors().get(0))
        .containsMatch("remote server prohibits writing local results");

    // Ignored when no local upload.
    remoteOptions.remoteUploadLocalResults = false;
    st =
        RemoteServerCapabilities.checkClientServerCompatibility(
            caps, remoteOptions, DigestFunction.SHA256);
    assertThat(st.isOk()).isTrue();
  }

  @Test
  public void testCheckClientServerCompatibility_RemoteExecutionIsDisabled() throws Exception {
    ServerCapabilities caps =
        ServerCapabilities.newBuilder()
            .setLowApiVersion(ApiVersion.current.toSemVer())
            .setHighApiVersion(ApiVersion.current.toSemVer())
            .setCacheCapabilities(
                CacheCapabilities.newBuilder()
                    .addDigestFunction(DigestFunction.SHA256)
                    .setActionCacheUpdateCapabilities(
                        ActionCacheUpdateCapabilities.newBuilder().setUpdateEnabled(true).build())
                    .build())
            .setExecutionCapabilities(
                ExecutionCapabilities.newBuilder().setDigestFunction(DigestFunction.SHA256).build())
            .build();
    RemoteOptions remoteOptions = Options.getDefaults(RemoteOptions.class);
    remoteOptions.remoteExecutor = "server:port";
    RemoteServerCapabilities.ClientServerCompatibilityStatus st =
        RemoteServerCapabilities.checkClientServerCompatibility(
            caps, remoteOptions, DigestFunction.SHA256);
    assertThat(st.getErrors()).hasSize(1);
    assertThat(st.getErrors().get(0)).containsMatch("Remote execution is not supported");
  }

  @Test
  public void testCheckClientServerCompatibility_RemoteExecutionDoesNotSupportDigestFunction()
      throws Exception {
    ServerCapabilities caps =
        ServerCapabilities.newBuilder()
            .setLowApiVersion(ApiVersion.current.toSemVer())
            .setHighApiVersion(ApiVersion.current.toSemVer())
            .setCacheCapabilities(
                CacheCapabilities.newBuilder()
                    .addDigestFunction(DigestFunction.SHA256)
                    .setActionCacheUpdateCapabilities(
                        ActionCacheUpdateCapabilities.newBuilder().setUpdateEnabled(true).build())
                    .build())
            .setExecutionCapabilities(
                ExecutionCapabilities.newBuilder()
                    .setDigestFunction(DigestFunction.MD5)
                    .setExecEnabled(true)
                    .build())
            .build();
    RemoteOptions remoteOptions = Options.getDefaults(RemoteOptions.class);
    remoteOptions.remoteExecutor = "server:port";
    RemoteServerCapabilities.ClientServerCompatibilityStatus st =
        RemoteServerCapabilities.checkClientServerCompatibility(
            caps, remoteOptions, DigestFunction.SHA256);
    assertThat(st.getErrors()).hasSize(1);
    assertThat(st.getErrors().get(0)).containsMatch("Cannot use hash function");
  }

  @Test
  public void testCheckClientServerCompatibility_LocalFallbackNoRemoteCacheUpdate()
      throws Exception {
    ServerCapabilities caps =
        ServerCapabilities.newBuilder()
            .setLowApiVersion(ApiVersion.current.toSemVer())
            .setHighApiVersion(ApiVersion.current.toSemVer())
            .setCacheCapabilities(
                CacheCapabilities.newBuilder().addDigestFunction(DigestFunction.SHA256).build())
            .setExecutionCapabilities(
                ExecutionCapabilities.newBuilder()
                    .setDigestFunction(DigestFunction.SHA256)
                    .setExecEnabled(true)
                    .build())
            .build();
    RemoteOptions remoteOptions = Options.getDefaults(RemoteOptions.class);
    remoteOptions.remoteExecutor = "server:port";
    remoteOptions.remoteLocalFallback = true;
    RemoteServerCapabilities.ClientServerCompatibilityStatus st =
        RemoteServerCapabilities.checkClientServerCompatibility(
            caps, remoteOptions, DigestFunction.SHA256);
    assertThat(st.getErrors()).hasSize(1);
    assertThat(st.getErrors().get(0))
        .containsMatch("remote server prohibits writing local results");

    // Ignored when no fallback.
    remoteOptions.remoteLocalFallback = false;
    st =
        RemoteServerCapabilities.checkClientServerCompatibility(
            caps, remoteOptions, DigestFunction.SHA256);
    assertThat(st.isOk()).isTrue();

    // Ignored when no uploading local results.
    remoteOptions.remoteLocalFallback = true;
    remoteOptions.remoteUploadLocalResults = false;
    st =
        RemoteServerCapabilities.checkClientServerCompatibility(
            caps, remoteOptions, DigestFunction.SHA256);
    assertThat(st.isOk()).isTrue();
  }
}
