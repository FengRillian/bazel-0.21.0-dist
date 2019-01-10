package com.google.devtools.remoteexecution.v1test;

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
 * The action cache API is used to query whether a given action has already been
 * performed and, if so, retrieve its result. Unlike the
 * [ContentAddressableStorage][google.devtools.remoteexecution.v1test.ContentAddressableStorage],
 * which addresses blobs by their own content, the action cache addresses the
 * [ActionResult][google.devtools.remoteexecution.v1test.ActionResult] by a
 * digest of the encoded [Action][google.devtools.remoteexecution.v1test.Action]
 * which produced them.
 * The lifetime of entries in the action cache is implementation-specific, but
 * the server SHOULD assume that more recently used entries are more likely to
 * be used again. Additionally, action cache implementations SHOULD ensure that
 * any blobs referenced in the
 * [ContentAddressableStorage][google.devtools.remoteexecution.v1test.ContentAddressableStorage]
 * are still valid when returning a result.
 * As with other services in the Remote Execution API, any call may return an
 * error with a [RetryInfo][google.rpc.RetryInfo] error detail providing
 * information about when the client should retry the request; clients SHOULD
 * respect the information provided.
 * </pre>
 */
@javax.annotation.Generated(
    value = "by gRPC proto compiler",
    comments = "Source: google/devtools/remoteexecution/v1test/remote_execution.proto")
public final class ActionCacheGrpc {

  private ActionCacheGrpc() {}

  public static final String SERVICE_NAME = "google.devtools.remoteexecution.v1test.ActionCache";

  // Static method descriptors that strictly reflect the proto.
  @io.grpc.ExperimentalApi("https://github.com/grpc/grpc-java/issues/1901")
  @java.lang.Deprecated // Use {@link #getGetActionResultMethod()} instead. 
  public static final io.grpc.MethodDescriptor<com.google.devtools.remoteexecution.v1test.GetActionResultRequest,
      com.google.devtools.remoteexecution.v1test.ActionResult> METHOD_GET_ACTION_RESULT = getGetActionResultMethodHelper();

  private static volatile io.grpc.MethodDescriptor<com.google.devtools.remoteexecution.v1test.GetActionResultRequest,
      com.google.devtools.remoteexecution.v1test.ActionResult> getGetActionResultMethod;

  @io.grpc.ExperimentalApi("https://github.com/grpc/grpc-java/issues/1901")
  public static io.grpc.MethodDescriptor<com.google.devtools.remoteexecution.v1test.GetActionResultRequest,
      com.google.devtools.remoteexecution.v1test.ActionResult> getGetActionResultMethod() {
    return getGetActionResultMethodHelper();
  }

  private static io.grpc.MethodDescriptor<com.google.devtools.remoteexecution.v1test.GetActionResultRequest,
      com.google.devtools.remoteexecution.v1test.ActionResult> getGetActionResultMethodHelper() {
    io.grpc.MethodDescriptor<com.google.devtools.remoteexecution.v1test.GetActionResultRequest, com.google.devtools.remoteexecution.v1test.ActionResult> getGetActionResultMethod;
    if ((getGetActionResultMethod = ActionCacheGrpc.getGetActionResultMethod) == null) {
      synchronized (ActionCacheGrpc.class) {
        if ((getGetActionResultMethod = ActionCacheGrpc.getGetActionResultMethod) == null) {
          ActionCacheGrpc.getGetActionResultMethod = getGetActionResultMethod = 
              io.grpc.MethodDescriptor.<com.google.devtools.remoteexecution.v1test.GetActionResultRequest, com.google.devtools.remoteexecution.v1test.ActionResult>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(
                  "google.devtools.remoteexecution.v1test.ActionCache", "GetActionResult"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.google.devtools.remoteexecution.v1test.GetActionResultRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.google.devtools.remoteexecution.v1test.ActionResult.getDefaultInstance()))
                  .setSchemaDescriptor(new ActionCacheMethodDescriptorSupplier("GetActionResult"))
                  .build();
          }
        }
     }
     return getGetActionResultMethod;
  }
  @io.grpc.ExperimentalApi("https://github.com/grpc/grpc-java/issues/1901")
  @java.lang.Deprecated // Use {@link #getUpdateActionResultMethod()} instead. 
  public static final io.grpc.MethodDescriptor<com.google.devtools.remoteexecution.v1test.UpdateActionResultRequest,
      com.google.devtools.remoteexecution.v1test.ActionResult> METHOD_UPDATE_ACTION_RESULT = getUpdateActionResultMethodHelper();

  private static volatile io.grpc.MethodDescriptor<com.google.devtools.remoteexecution.v1test.UpdateActionResultRequest,
      com.google.devtools.remoteexecution.v1test.ActionResult> getUpdateActionResultMethod;

  @io.grpc.ExperimentalApi("https://github.com/grpc/grpc-java/issues/1901")
  public static io.grpc.MethodDescriptor<com.google.devtools.remoteexecution.v1test.UpdateActionResultRequest,
      com.google.devtools.remoteexecution.v1test.ActionResult> getUpdateActionResultMethod() {
    return getUpdateActionResultMethodHelper();
  }

  private static io.grpc.MethodDescriptor<com.google.devtools.remoteexecution.v1test.UpdateActionResultRequest,
      com.google.devtools.remoteexecution.v1test.ActionResult> getUpdateActionResultMethodHelper() {
    io.grpc.MethodDescriptor<com.google.devtools.remoteexecution.v1test.UpdateActionResultRequest, com.google.devtools.remoteexecution.v1test.ActionResult> getUpdateActionResultMethod;
    if ((getUpdateActionResultMethod = ActionCacheGrpc.getUpdateActionResultMethod) == null) {
      synchronized (ActionCacheGrpc.class) {
        if ((getUpdateActionResultMethod = ActionCacheGrpc.getUpdateActionResultMethod) == null) {
          ActionCacheGrpc.getUpdateActionResultMethod = getUpdateActionResultMethod = 
              io.grpc.MethodDescriptor.<com.google.devtools.remoteexecution.v1test.UpdateActionResultRequest, com.google.devtools.remoteexecution.v1test.ActionResult>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(
                  "google.devtools.remoteexecution.v1test.ActionCache", "UpdateActionResult"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.google.devtools.remoteexecution.v1test.UpdateActionResultRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.google.devtools.remoteexecution.v1test.ActionResult.getDefaultInstance()))
                  .setSchemaDescriptor(new ActionCacheMethodDescriptorSupplier("UpdateActionResult"))
                  .build();
          }
        }
     }
     return getUpdateActionResultMethod;
  }

  /**
   * Creates a new async stub that supports all call types for the service
   */
  public static ActionCacheStub newStub(io.grpc.Channel channel) {
    return new ActionCacheStub(channel);
  }

  /**
   * Creates a new blocking-style stub that supports unary and streaming output calls on the service
   */
  public static ActionCacheBlockingStub newBlockingStub(
      io.grpc.Channel channel) {
    return new ActionCacheBlockingStub(channel);
  }

  /**
   * Creates a new ListenableFuture-style stub that supports unary calls on the service
   */
  public static ActionCacheFutureStub newFutureStub(
      io.grpc.Channel channel) {
    return new ActionCacheFutureStub(channel);
  }

  /**
   * <pre>
   * The action cache API is used to query whether a given action has already been
   * performed and, if so, retrieve its result. Unlike the
   * [ContentAddressableStorage][google.devtools.remoteexecution.v1test.ContentAddressableStorage],
   * which addresses blobs by their own content, the action cache addresses the
   * [ActionResult][google.devtools.remoteexecution.v1test.ActionResult] by a
   * digest of the encoded [Action][google.devtools.remoteexecution.v1test.Action]
   * which produced them.
   * The lifetime of entries in the action cache is implementation-specific, but
   * the server SHOULD assume that more recently used entries are more likely to
   * be used again. Additionally, action cache implementations SHOULD ensure that
   * any blobs referenced in the
   * [ContentAddressableStorage][google.devtools.remoteexecution.v1test.ContentAddressableStorage]
   * are still valid when returning a result.
   * As with other services in the Remote Execution API, any call may return an
   * error with a [RetryInfo][google.rpc.RetryInfo] error detail providing
   * information about when the client should retry the request; clients SHOULD
   * respect the information provided.
   * </pre>
   */
  public static abstract class ActionCacheImplBase implements io.grpc.BindableService {

    /**
     * <pre>
     * Retrieve a cached execution result.
     * Errors:
     * * `NOT_FOUND`: The requested `ActionResult` is not in the cache.
     * </pre>
     */
    public void getActionResult(com.google.devtools.remoteexecution.v1test.GetActionResultRequest request,
        io.grpc.stub.StreamObserver<com.google.devtools.remoteexecution.v1test.ActionResult> responseObserver) {
      asyncUnimplementedUnaryCall(getGetActionResultMethodHelper(), responseObserver);
    }

    /**
     * <pre>
     * Upload a new execution result.
     * This method is intended for servers which implement the distributed cache
     * independently of the
     * [Execution][google.devtools.remoteexecution.v1test.Execution] API. As a
     * result, it is OPTIONAL for servers to implement.
     * Errors:
     * * `NOT_IMPLEMENTED`: This method is not supported by the server.
     * * `RESOURCE_EXHAUSTED`: There is insufficient storage space to add the
     *   entry to the cache.
     * </pre>
     */
    public void updateActionResult(com.google.devtools.remoteexecution.v1test.UpdateActionResultRequest request,
        io.grpc.stub.StreamObserver<com.google.devtools.remoteexecution.v1test.ActionResult> responseObserver) {
      asyncUnimplementedUnaryCall(getUpdateActionResultMethodHelper(), responseObserver);
    }

    @java.lang.Override public final io.grpc.ServerServiceDefinition bindService() {
      return io.grpc.ServerServiceDefinition.builder(getServiceDescriptor())
          .addMethod(
            getGetActionResultMethodHelper(),
            asyncUnaryCall(
              new MethodHandlers<
                com.google.devtools.remoteexecution.v1test.GetActionResultRequest,
                com.google.devtools.remoteexecution.v1test.ActionResult>(
                  this, METHODID_GET_ACTION_RESULT)))
          .addMethod(
            getUpdateActionResultMethodHelper(),
            asyncUnaryCall(
              new MethodHandlers<
                com.google.devtools.remoteexecution.v1test.UpdateActionResultRequest,
                com.google.devtools.remoteexecution.v1test.ActionResult>(
                  this, METHODID_UPDATE_ACTION_RESULT)))
          .build();
    }
  }

  /**
   * <pre>
   * The action cache API is used to query whether a given action has already been
   * performed and, if so, retrieve its result. Unlike the
   * [ContentAddressableStorage][google.devtools.remoteexecution.v1test.ContentAddressableStorage],
   * which addresses blobs by their own content, the action cache addresses the
   * [ActionResult][google.devtools.remoteexecution.v1test.ActionResult] by a
   * digest of the encoded [Action][google.devtools.remoteexecution.v1test.Action]
   * which produced them.
   * The lifetime of entries in the action cache is implementation-specific, but
   * the server SHOULD assume that more recently used entries are more likely to
   * be used again. Additionally, action cache implementations SHOULD ensure that
   * any blobs referenced in the
   * [ContentAddressableStorage][google.devtools.remoteexecution.v1test.ContentAddressableStorage]
   * are still valid when returning a result.
   * As with other services in the Remote Execution API, any call may return an
   * error with a [RetryInfo][google.rpc.RetryInfo] error detail providing
   * information about when the client should retry the request; clients SHOULD
   * respect the information provided.
   * </pre>
   */
  public static final class ActionCacheStub extends io.grpc.stub.AbstractStub<ActionCacheStub> {
    private ActionCacheStub(io.grpc.Channel channel) {
      super(channel);
    }

    private ActionCacheStub(io.grpc.Channel channel,
        io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ActionCacheStub build(io.grpc.Channel channel,
        io.grpc.CallOptions callOptions) {
      return new ActionCacheStub(channel, callOptions);
    }

    /**
     * <pre>
     * Retrieve a cached execution result.
     * Errors:
     * * `NOT_FOUND`: The requested `ActionResult` is not in the cache.
     * </pre>
     */
    public void getActionResult(com.google.devtools.remoteexecution.v1test.GetActionResultRequest request,
        io.grpc.stub.StreamObserver<com.google.devtools.remoteexecution.v1test.ActionResult> responseObserver) {
      asyncUnaryCall(
          getChannel().newCall(getGetActionResultMethodHelper(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * Upload a new execution result.
     * This method is intended for servers which implement the distributed cache
     * independently of the
     * [Execution][google.devtools.remoteexecution.v1test.Execution] API. As a
     * result, it is OPTIONAL for servers to implement.
     * Errors:
     * * `NOT_IMPLEMENTED`: This method is not supported by the server.
     * * `RESOURCE_EXHAUSTED`: There is insufficient storage space to add the
     *   entry to the cache.
     * </pre>
     */
    public void updateActionResult(com.google.devtools.remoteexecution.v1test.UpdateActionResultRequest request,
        io.grpc.stub.StreamObserver<com.google.devtools.remoteexecution.v1test.ActionResult> responseObserver) {
      asyncUnaryCall(
          getChannel().newCall(getUpdateActionResultMethodHelper(), getCallOptions()), request, responseObserver);
    }
  }

  /**
   * <pre>
   * The action cache API is used to query whether a given action has already been
   * performed and, if so, retrieve its result. Unlike the
   * [ContentAddressableStorage][google.devtools.remoteexecution.v1test.ContentAddressableStorage],
   * which addresses blobs by their own content, the action cache addresses the
   * [ActionResult][google.devtools.remoteexecution.v1test.ActionResult] by a
   * digest of the encoded [Action][google.devtools.remoteexecution.v1test.Action]
   * which produced them.
   * The lifetime of entries in the action cache is implementation-specific, but
   * the server SHOULD assume that more recently used entries are more likely to
   * be used again. Additionally, action cache implementations SHOULD ensure that
   * any blobs referenced in the
   * [ContentAddressableStorage][google.devtools.remoteexecution.v1test.ContentAddressableStorage]
   * are still valid when returning a result.
   * As with other services in the Remote Execution API, any call may return an
   * error with a [RetryInfo][google.rpc.RetryInfo] error detail providing
   * information about when the client should retry the request; clients SHOULD
   * respect the information provided.
   * </pre>
   */
  public static final class ActionCacheBlockingStub extends io.grpc.stub.AbstractStub<ActionCacheBlockingStub> {
    private ActionCacheBlockingStub(io.grpc.Channel channel) {
      super(channel);
    }

    private ActionCacheBlockingStub(io.grpc.Channel channel,
        io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ActionCacheBlockingStub build(io.grpc.Channel channel,
        io.grpc.CallOptions callOptions) {
      return new ActionCacheBlockingStub(channel, callOptions);
    }

    /**
     * <pre>
     * Retrieve a cached execution result.
     * Errors:
     * * `NOT_FOUND`: The requested `ActionResult` is not in the cache.
     * </pre>
     */
    public com.google.devtools.remoteexecution.v1test.ActionResult getActionResult(com.google.devtools.remoteexecution.v1test.GetActionResultRequest request) {
      return blockingUnaryCall(
          getChannel(), getGetActionResultMethodHelper(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Upload a new execution result.
     * This method is intended for servers which implement the distributed cache
     * independently of the
     * [Execution][google.devtools.remoteexecution.v1test.Execution] API. As a
     * result, it is OPTIONAL for servers to implement.
     * Errors:
     * * `NOT_IMPLEMENTED`: This method is not supported by the server.
     * * `RESOURCE_EXHAUSTED`: There is insufficient storage space to add the
     *   entry to the cache.
     * </pre>
     */
    public com.google.devtools.remoteexecution.v1test.ActionResult updateActionResult(com.google.devtools.remoteexecution.v1test.UpdateActionResultRequest request) {
      return blockingUnaryCall(
          getChannel(), getUpdateActionResultMethodHelper(), getCallOptions(), request);
    }
  }

  /**
   * <pre>
   * The action cache API is used to query whether a given action has already been
   * performed and, if so, retrieve its result. Unlike the
   * [ContentAddressableStorage][google.devtools.remoteexecution.v1test.ContentAddressableStorage],
   * which addresses blobs by their own content, the action cache addresses the
   * [ActionResult][google.devtools.remoteexecution.v1test.ActionResult] by a
   * digest of the encoded [Action][google.devtools.remoteexecution.v1test.Action]
   * which produced them.
   * The lifetime of entries in the action cache is implementation-specific, but
   * the server SHOULD assume that more recently used entries are more likely to
   * be used again. Additionally, action cache implementations SHOULD ensure that
   * any blobs referenced in the
   * [ContentAddressableStorage][google.devtools.remoteexecution.v1test.ContentAddressableStorage]
   * are still valid when returning a result.
   * As with other services in the Remote Execution API, any call may return an
   * error with a [RetryInfo][google.rpc.RetryInfo] error detail providing
   * information about when the client should retry the request; clients SHOULD
   * respect the information provided.
   * </pre>
   */
  public static final class ActionCacheFutureStub extends io.grpc.stub.AbstractStub<ActionCacheFutureStub> {
    private ActionCacheFutureStub(io.grpc.Channel channel) {
      super(channel);
    }

    private ActionCacheFutureStub(io.grpc.Channel channel,
        io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ActionCacheFutureStub build(io.grpc.Channel channel,
        io.grpc.CallOptions callOptions) {
      return new ActionCacheFutureStub(channel, callOptions);
    }

    /**
     * <pre>
     * Retrieve a cached execution result.
     * Errors:
     * * `NOT_FOUND`: The requested `ActionResult` is not in the cache.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<com.google.devtools.remoteexecution.v1test.ActionResult> getActionResult(
        com.google.devtools.remoteexecution.v1test.GetActionResultRequest request) {
      return futureUnaryCall(
          getChannel().newCall(getGetActionResultMethodHelper(), getCallOptions()), request);
    }

    /**
     * <pre>
     * Upload a new execution result.
     * This method is intended for servers which implement the distributed cache
     * independently of the
     * [Execution][google.devtools.remoteexecution.v1test.Execution] API. As a
     * result, it is OPTIONAL for servers to implement.
     * Errors:
     * * `NOT_IMPLEMENTED`: This method is not supported by the server.
     * * `RESOURCE_EXHAUSTED`: There is insufficient storage space to add the
     *   entry to the cache.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<com.google.devtools.remoteexecution.v1test.ActionResult> updateActionResult(
        com.google.devtools.remoteexecution.v1test.UpdateActionResultRequest request) {
      return futureUnaryCall(
          getChannel().newCall(getUpdateActionResultMethodHelper(), getCallOptions()), request);
    }
  }

  private static final int METHODID_GET_ACTION_RESULT = 0;
  private static final int METHODID_UPDATE_ACTION_RESULT = 1;

  private static final class MethodHandlers<Req, Resp> implements
      io.grpc.stub.ServerCalls.UnaryMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.ServerStreamingMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.ClientStreamingMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.BidiStreamingMethod<Req, Resp> {
    private final ActionCacheImplBase serviceImpl;
    private final int methodId;

    MethodHandlers(ActionCacheImplBase serviceImpl, int methodId) {
      this.serviceImpl = serviceImpl;
      this.methodId = methodId;
    }

    @java.lang.Override
    @java.lang.SuppressWarnings("unchecked")
    public void invoke(Req request, io.grpc.stub.StreamObserver<Resp> responseObserver) {
      switch (methodId) {
        case METHODID_GET_ACTION_RESULT:
          serviceImpl.getActionResult((com.google.devtools.remoteexecution.v1test.GetActionResultRequest) request,
              (io.grpc.stub.StreamObserver<com.google.devtools.remoteexecution.v1test.ActionResult>) responseObserver);
          break;
        case METHODID_UPDATE_ACTION_RESULT:
          serviceImpl.updateActionResult((com.google.devtools.remoteexecution.v1test.UpdateActionResultRequest) request,
              (io.grpc.stub.StreamObserver<com.google.devtools.remoteexecution.v1test.ActionResult>) responseObserver);
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

  private static abstract class ActionCacheBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoFileDescriptorSupplier, io.grpc.protobuf.ProtoServiceDescriptorSupplier {
    ActionCacheBaseDescriptorSupplier() {}

    @java.lang.Override
    public com.google.protobuf.Descriptors.FileDescriptor getFileDescriptor() {
      return com.google.devtools.remoteexecution.v1test.RemoteExecutionProto.getDescriptor();
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.ServiceDescriptor getServiceDescriptor() {
      return getFileDescriptor().findServiceByName("ActionCache");
    }
  }

  private static final class ActionCacheFileDescriptorSupplier
      extends ActionCacheBaseDescriptorSupplier {
    ActionCacheFileDescriptorSupplier() {}
  }

  private static final class ActionCacheMethodDescriptorSupplier
      extends ActionCacheBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoMethodDescriptorSupplier {
    private final String methodName;

    ActionCacheMethodDescriptorSupplier(String methodName) {
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
      synchronized (ActionCacheGrpc.class) {
        result = serviceDescriptor;
        if (result == null) {
          serviceDescriptor = result = io.grpc.ServiceDescriptor.newBuilder(SERVICE_NAME)
              .setSchemaDescriptor(new ActionCacheFileDescriptorSupplier())
              .addMethod(getGetActionResultMethodHelper())
              .addMethod(getUpdateActionResultMethodHelper())
              .build();
        }
      }
    }
    return result;
  }
}
