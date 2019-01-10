package build.bazel.remote.execution.v2;

import static io.grpc.MethodDescriptor.generateFullMethodName;
import static io.grpc.stub.ClientCalls.asyncBidiStreamingCall;
import static io.grpc.stub.ClientCalls.asyncClientStreamingCall;
import static io.grpc.stub.ClientCalls.asyncServerStreamingCall;
import static io.grpc.stub.ClientCalls.asyncUnaryCall;
import static io.grpc.stub.ClientCalls.blockingServerStreamingCall;
import static io.grpc.stub.ClientCalls.blockingUnaryCall;
import static io.grpc.stub.ClientCalls.futureUnaryCall;
import static io.grpc.stub.ServerCalls.asyncBidiStreamingCall;
import static io.grpc.stub.ServerCalls.asyncClientStreamingCall;
import static io.grpc.stub.ServerCalls.asyncServerStreamingCall;
import static io.grpc.stub.ServerCalls.asyncUnaryCall;
import static io.grpc.stub.ServerCalls.asyncUnimplementedStreamingCall;
import static io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall;

/**
 * <pre>
 * The Capabilities service may be used by remote execution clients to query
 * various server properties, in order to self-configure or return meaningful
 * error messages.
 * The query may include a particular `instance_name`, in which case the values
 * returned will pertain to that instance.
 * </pre>
 */
@javax.annotation.Generated(
    value = "by gRPC proto compiler",
    comments = "Source: build/bazel/remote/execution/v2/remote_execution.proto")
public final class CapabilitiesGrpc {

  private CapabilitiesGrpc() {}

  public static final String SERVICE_NAME = "build.bazel.remote.execution.v2.Capabilities";

  // Static method descriptors that strictly reflect the proto.
  @io.grpc.ExperimentalApi("https://github.com/grpc/grpc-java/issues/1901")
  @java.lang.Deprecated // Use {@link #getGetCapabilitiesMethod()} instead. 
  public static final io.grpc.MethodDescriptor<build.bazel.remote.execution.v2.GetCapabilitiesRequest,
      build.bazel.remote.execution.v2.ServerCapabilities> METHOD_GET_CAPABILITIES = getGetCapabilitiesMethodHelper();

  private static volatile io.grpc.MethodDescriptor<build.bazel.remote.execution.v2.GetCapabilitiesRequest,
      build.bazel.remote.execution.v2.ServerCapabilities> getGetCapabilitiesMethod;

  @io.grpc.ExperimentalApi("https://github.com/grpc/grpc-java/issues/1901")
  public static io.grpc.MethodDescriptor<build.bazel.remote.execution.v2.GetCapabilitiesRequest,
      build.bazel.remote.execution.v2.ServerCapabilities> getGetCapabilitiesMethod() {
    return getGetCapabilitiesMethodHelper();
  }

  private static io.grpc.MethodDescriptor<build.bazel.remote.execution.v2.GetCapabilitiesRequest,
      build.bazel.remote.execution.v2.ServerCapabilities> getGetCapabilitiesMethodHelper() {
    io.grpc.MethodDescriptor<build.bazel.remote.execution.v2.GetCapabilitiesRequest, build.bazel.remote.execution.v2.ServerCapabilities> getGetCapabilitiesMethod;
    if ((getGetCapabilitiesMethod = CapabilitiesGrpc.getGetCapabilitiesMethod) == null) {
      synchronized (CapabilitiesGrpc.class) {
        if ((getGetCapabilitiesMethod = CapabilitiesGrpc.getGetCapabilitiesMethod) == null) {
          CapabilitiesGrpc.getGetCapabilitiesMethod = getGetCapabilitiesMethod = 
              io.grpc.MethodDescriptor.<build.bazel.remote.execution.v2.GetCapabilitiesRequest, build.bazel.remote.execution.v2.ServerCapabilities>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(
                  "build.bazel.remote.execution.v2.Capabilities", "GetCapabilities"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  build.bazel.remote.execution.v2.GetCapabilitiesRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  build.bazel.remote.execution.v2.ServerCapabilities.getDefaultInstance()))
                  .setSchemaDescriptor(new CapabilitiesMethodDescriptorSupplier("GetCapabilities"))
                  .build();
          }
        }
     }
     return getGetCapabilitiesMethod;
  }

  /**
   * Creates a new async stub that supports all call types for the service
   */
  public static CapabilitiesStub newStub(io.grpc.Channel channel) {
    return new CapabilitiesStub(channel);
  }

  /**
   * Creates a new blocking-style stub that supports unary and streaming output calls on the service
   */
  public static CapabilitiesBlockingStub newBlockingStub(
      io.grpc.Channel channel) {
    return new CapabilitiesBlockingStub(channel);
  }

  /**
   * Creates a new ListenableFuture-style stub that supports unary calls on the service
   */
  public static CapabilitiesFutureStub newFutureStub(
      io.grpc.Channel channel) {
    return new CapabilitiesFutureStub(channel);
  }

  /**
   * <pre>
   * The Capabilities service may be used by remote execution clients to query
   * various server properties, in order to self-configure or return meaningful
   * error messages.
   * The query may include a particular `instance_name`, in which case the values
   * returned will pertain to that instance.
   * </pre>
   */
  public static abstract class CapabilitiesImplBase implements io.grpc.BindableService {

    /**
     * <pre>
     * GetCapabilities returns the server capabilities configuration.
     * </pre>
     */
    public void getCapabilities(build.bazel.remote.execution.v2.GetCapabilitiesRequest request,
        io.grpc.stub.StreamObserver<build.bazel.remote.execution.v2.ServerCapabilities> responseObserver) {
      asyncUnimplementedUnaryCall(getGetCapabilitiesMethodHelper(), responseObserver);
    }

    @java.lang.Override public final io.grpc.ServerServiceDefinition bindService() {
      return io.grpc.ServerServiceDefinition.builder(getServiceDescriptor())
          .addMethod(
            getGetCapabilitiesMethodHelper(),
            asyncUnaryCall(
              new MethodHandlers<
                build.bazel.remote.execution.v2.GetCapabilitiesRequest,
                build.bazel.remote.execution.v2.ServerCapabilities>(
                  this, METHODID_GET_CAPABILITIES)))
          .build();
    }
  }

  /**
   * <pre>
   * The Capabilities service may be used by remote execution clients to query
   * various server properties, in order to self-configure or return meaningful
   * error messages.
   * The query may include a particular `instance_name`, in which case the values
   * returned will pertain to that instance.
   * </pre>
   */
  public static final class CapabilitiesStub extends io.grpc.stub.AbstractStub<CapabilitiesStub> {
    private CapabilitiesStub(io.grpc.Channel channel) {
      super(channel);
    }

    private CapabilitiesStub(io.grpc.Channel channel,
        io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected CapabilitiesStub build(io.grpc.Channel channel,
        io.grpc.CallOptions callOptions) {
      return new CapabilitiesStub(channel, callOptions);
    }

    /**
     * <pre>
     * GetCapabilities returns the server capabilities configuration.
     * </pre>
     */
    public void getCapabilities(build.bazel.remote.execution.v2.GetCapabilitiesRequest request,
        io.grpc.stub.StreamObserver<build.bazel.remote.execution.v2.ServerCapabilities> responseObserver) {
      asyncUnaryCall(
          getChannel().newCall(getGetCapabilitiesMethodHelper(), getCallOptions()), request, responseObserver);
    }
  }

  /**
   * <pre>
   * The Capabilities service may be used by remote execution clients to query
   * various server properties, in order to self-configure or return meaningful
   * error messages.
   * The query may include a particular `instance_name`, in which case the values
   * returned will pertain to that instance.
   * </pre>
   */
  public static final class CapabilitiesBlockingStub extends io.grpc.stub.AbstractStub<CapabilitiesBlockingStub> {
    private CapabilitiesBlockingStub(io.grpc.Channel channel) {
      super(channel);
    }

    private CapabilitiesBlockingStub(io.grpc.Channel channel,
        io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected CapabilitiesBlockingStub build(io.grpc.Channel channel,
        io.grpc.CallOptions callOptions) {
      return new CapabilitiesBlockingStub(channel, callOptions);
    }

    /**
     * <pre>
     * GetCapabilities returns the server capabilities configuration.
     * </pre>
     */
    public build.bazel.remote.execution.v2.ServerCapabilities getCapabilities(build.bazel.remote.execution.v2.GetCapabilitiesRequest request) {
      return blockingUnaryCall(
          getChannel(), getGetCapabilitiesMethodHelper(), getCallOptions(), request);
    }
  }

  /**
   * <pre>
   * The Capabilities service may be used by remote execution clients to query
   * various server properties, in order to self-configure or return meaningful
   * error messages.
   * The query may include a particular `instance_name`, in which case the values
   * returned will pertain to that instance.
   * </pre>
   */
  public static final class CapabilitiesFutureStub extends io.grpc.stub.AbstractStub<CapabilitiesFutureStub> {
    private CapabilitiesFutureStub(io.grpc.Channel channel) {
      super(channel);
    }

    private CapabilitiesFutureStub(io.grpc.Channel channel,
        io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected CapabilitiesFutureStub build(io.grpc.Channel channel,
        io.grpc.CallOptions callOptions) {
      return new CapabilitiesFutureStub(channel, callOptions);
    }

    /**
     * <pre>
     * GetCapabilities returns the server capabilities configuration.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<build.bazel.remote.execution.v2.ServerCapabilities> getCapabilities(
        build.bazel.remote.execution.v2.GetCapabilitiesRequest request) {
      return futureUnaryCall(
          getChannel().newCall(getGetCapabilitiesMethodHelper(), getCallOptions()), request);
    }
  }

  private static final int METHODID_GET_CAPABILITIES = 0;

  private static final class MethodHandlers<Req, Resp> implements
      io.grpc.stub.ServerCalls.UnaryMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.ServerStreamingMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.ClientStreamingMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.BidiStreamingMethod<Req, Resp> {
    private final CapabilitiesImplBase serviceImpl;
    private final int methodId;

    MethodHandlers(CapabilitiesImplBase serviceImpl, int methodId) {
      this.serviceImpl = serviceImpl;
      this.methodId = methodId;
    }

    @java.lang.Override
    @java.lang.SuppressWarnings("unchecked")
    public void invoke(Req request, io.grpc.stub.StreamObserver<Resp> responseObserver) {
      switch (methodId) {
        case METHODID_GET_CAPABILITIES:
          serviceImpl.getCapabilities((build.bazel.remote.execution.v2.GetCapabilitiesRequest) request,
              (io.grpc.stub.StreamObserver<build.bazel.remote.execution.v2.ServerCapabilities>) responseObserver);
          break;
        default:
          throw new AssertionError();
      }
    }

    @java.lang.Override
    @java.lang.SuppressWarnings("unchecked")
    public io.grpc.stub.StreamObserver<Req> invoke(
        io.grpc.stub.StreamObserver<Resp> responseObserver) {
      switch (methodId) {
        default:
          throw new AssertionError();
      }
    }
  }

  private static abstract class CapabilitiesBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoFileDescriptorSupplier, io.grpc.protobuf.ProtoServiceDescriptorSupplier {
    CapabilitiesBaseDescriptorSupplier() {}

    @java.lang.Override
    public com.google.protobuf.Descriptors.FileDescriptor getFileDescriptor() {
      return build.bazel.remote.execution.v2.RemoteExecutionProto.getDescriptor();
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.ServiceDescriptor getServiceDescriptor() {
      return getFileDescriptor().findServiceByName("Capabilities");
    }
  }

  private static final class CapabilitiesFileDescriptorSupplier
      extends CapabilitiesBaseDescriptorSupplier {
    CapabilitiesFileDescriptorSupplier() {}
  }

  private static final class CapabilitiesMethodDescriptorSupplier
      extends CapabilitiesBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoMethodDescriptorSupplier {
    private final String methodName;

    CapabilitiesMethodDescriptorSupplier(String methodName) {
      this.methodName = methodName;
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.MethodDescriptor getMethodDescriptor() {
      return getServiceDescriptor().findMethodByName(methodName);
    }
  }

  private static volatile io.grpc.ServiceDescriptor serviceDescriptor;

  public static io.grpc.ServiceDescriptor getServiceDescriptor() {
    io.grpc.ServiceDescriptor result = serviceDescriptor;
    if (result == null) {
      synchronized (CapabilitiesGrpc.class) {
        result = serviceDescriptor;
        if (result == null) {
          serviceDescriptor = result = io.grpc.ServiceDescriptor.newBuilder(SERVICE_NAME)
              .setSchemaDescriptor(new CapabilitiesFileDescriptorSupplier())
              .addMethod(getGetCapabilitiesMethodHelper())
              .build();
        }
      }
    }
    return result;
  }
}
