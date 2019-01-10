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
 * The Remote Execution API is used to execute an
 * [Action][google.devtools.remoteexecution.v1test.Action] on the remote
 * workers.
 * As with other services in the Remote Execution API, any call may return an
 * error with a [RetryInfo][google.rpc.RetryInfo] error detail providing
 * information about when the client should retry the request; clients SHOULD
 * respect the information provided.
 * </pre>
 */
@javax.annotation.Generated(
    value = "by gRPC proto compiler",
    comments = "Source: google/devtools/remoteexecution/v1test/remote_execution.proto")
public final class ExecutionGrpc {

  private ExecutionGrpc() {}

  public static final String SERVICE_NAME = "google.devtools.remoteexecution.v1test.Execution";

  // Static method descriptors that strictly reflect the proto.
  @io.grpc.ExperimentalApi("https://github.com/grpc/grpc-java/issues/1901")
  @java.lang.Deprecated // Use {@link #getExecuteMethod()} instead. 
  public static final io.grpc.MethodDescriptor<com.google.devtools.remoteexecution.v1test.ExecuteRequest,
      com.google.longrunning.Operation> METHOD_EXECUTE = getExecuteMethodHelper();

  private static volatile io.grpc.MethodDescriptor<com.google.devtools.remoteexecution.v1test.ExecuteRequest,
      com.google.longrunning.Operation> getExecuteMethod;

  @io.grpc.ExperimentalApi("https://github.com/grpc/grpc-java/issues/1901")
  public static io.grpc.MethodDescriptor<com.google.devtools.remoteexecution.v1test.ExecuteRequest,
      com.google.longrunning.Operation> getExecuteMethod() {
    return getExecuteMethodHelper();
  }

  private static io.grpc.MethodDescriptor<com.google.devtools.remoteexecution.v1test.ExecuteRequest,
      com.google.longrunning.Operation> getExecuteMethodHelper() {
    io.grpc.MethodDescriptor<com.google.devtools.remoteexecution.v1test.ExecuteRequest, com.google.longrunning.Operation> getExecuteMethod;
    if ((getExecuteMethod = ExecutionGrpc.getExecuteMethod) == null) {
      synchronized (ExecutionGrpc.class) {
        if ((getExecuteMethod = ExecutionGrpc.getExecuteMethod) == null) {
          ExecutionGrpc.getExecuteMethod = getExecuteMethod = 
              io.grpc.MethodDescriptor.<com.google.devtools.remoteexecution.v1test.ExecuteRequest, com.google.longrunning.Operation>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(
                  "google.devtools.remoteexecution.v1test.Execution", "Execute"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.google.devtools.remoteexecution.v1test.ExecuteRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.google.longrunning.Operation.getDefaultInstance()))
                  .setSchemaDescriptor(new ExecutionMethodDescriptorSupplier("Execute"))
                  .build();
          }
        }
     }
     return getExecuteMethod;
  }

  /**
   * Creates a new async stub that supports all call types for the service
   */
  public static ExecutionStub newStub(io.grpc.Channel channel) {
    return new ExecutionStub(channel);
  }

  /**
   * Creates a new blocking-style stub that supports unary and streaming output calls on the service
   */
  public static ExecutionBlockingStub newBlockingStub(
      io.grpc.Channel channel) {
    return new ExecutionBlockingStub(channel);
  }

  /**
   * Creates a new ListenableFuture-style stub that supports unary calls on the service
   */
  public static ExecutionFutureStub newFutureStub(
      io.grpc.Channel channel) {
    return new ExecutionFutureStub(channel);
  }

  /**
   * <pre>
   * The Remote Execution API is used to execute an
   * [Action][google.devtools.remoteexecution.v1test.Action] on the remote
   * workers.
   * As with other services in the Remote Execution API, any call may return an
   * error with a [RetryInfo][google.rpc.RetryInfo] error detail providing
   * information about when the client should retry the request; clients SHOULD
   * respect the information provided.
   * </pre>
   */
  public static abstract class ExecutionImplBase implements io.grpc.BindableService {

    /**
     * <pre>
     * Execute an action remotely.
     * In order to execute an action, the client must first upload all of the
     * inputs, as well as the
     * [Command][google.devtools.remoteexecution.v1test.Command] to run, into the
     * [ContentAddressableStorage][google.devtools.remoteexecution.v1test.ContentAddressableStorage].
     * It then calls `Execute` with an
     * [Action][google.devtools.remoteexecution.v1test.Action] referring to them.
     * The server will run the action and eventually return the result.
     * The input `Action`'s fields MUST meet the various canonicalization
     * requirements specified in the documentation for their types so that it has
     * the same digest as other logically equivalent `Action`s. The server MAY
     * enforce the requirements and return errors if a non-canonical input is
     * received. It MAY also proceed without verifying some or all of the
     * requirements, such as for performance reasons. If the server does not
     * verify the requirement, then it will treat the `Action` as distinct from
     * another logically equivalent action if they hash differently.
     * Returns a [google.longrunning.Operation][google.longrunning.Operation]
     * describing the resulting execution, with eventual `response`
     * [ExecuteResponse][google.devtools.remoteexecution.v1test.ExecuteResponse].
     * The `metadata` on the operation is of type
     * [ExecuteOperationMetadata][google.devtools.remoteexecution.v1test.ExecuteOperationMetadata].
     * To query the operation, you can use the
     * [Operations API][google.longrunning.Operations.GetOperation]. If you wish
     * to allow the server to stream operations updates, rather than requiring
     * client polling, you can use the
     * [Watcher API][google.watcher.v1.Watcher.Watch] with the Operation's `name`
     * as the `target`.
     * When using the Watcher API, the initial `data` will be the `Operation` at
     * the time of the request. Updates will be provided periodically by the
     * server until the `Operation` completes, at which point the response message
     * will (assuming no error) be at `data.response`.
     * The server NEED NOT implement other methods or functionality of the
     * Operation and Watcher APIs.
     * Errors discovered during creation of the `Operation` will be reported
     * as gRPC Status errors, while errors that occurred while running the
     * action will be reported in the `status` field of the `ExecuteResponse`. The
     * server MUST NOT set the `error` field of the `Operation` proto.
     * The possible errors include:
     * * `INVALID_ARGUMENT`: One or more arguments are invalid.
     * * `FAILED_PRECONDITION`: One or more errors occurred in setting up the
     *   action requested, such as a missing input or command or no worker being
     *   available. The client may be able to fix the errors and retry.
     * * `RESOURCE_EXHAUSTED`: There is insufficient quota of some resource to run
     *   the action.
     * * `UNAVAILABLE`: Due to a transient condition, such as all workers being
     *   occupied (and the server does not support a queue), the action could not
     *   be started. The client should retry.
     * * `INTERNAL`: An internal error occurred in the execution engine or the
     *   worker.
     * * `DEADLINE_EXCEEDED`: The execution timed out.
     * In the case of a missing input or command, the server SHOULD additionally
     * send a [PreconditionFailure][google.rpc.PreconditionFailure] error detail
     * where, for each requested blob not present in the CAS, there is a
     * `Violation` with a `type` of `MISSING` and a `subject` of
     * `"blobs/{hash}/{size}"` indicating the digest of the missing blob.
     * </pre>
     */
    public void execute(com.google.devtools.remoteexecution.v1test.ExecuteRequest request,
        io.grpc.stub.StreamObserver<com.google.longrunning.Operation> responseObserver) {
      asyncUnimplementedUnaryCall(getExecuteMethodHelper(), responseObserver);
    }

    @java.lang.Override public final io.grpc.ServerServiceDefinition bindService() {
      return io.grpc.ServerServiceDefinition.builder(getServiceDescriptor())
          .addMethod(
            getExecuteMethodHelper(),
            asyncUnaryCall(
              new MethodHandlers<
                com.google.devtools.remoteexecution.v1test.ExecuteRequest,
                com.google.longrunning.Operation>(
                  this, METHODID_EXECUTE)))
          .build();
    }
  }

  /**
   * <pre>
   * The Remote Execution API is used to execute an
   * [Action][google.devtools.remoteexecution.v1test.Action] on the remote
   * workers.
   * As with other services in the Remote Execution API, any call may return an
   * error with a [RetryInfo][google.rpc.RetryInfo] error detail providing
   * information about when the client should retry the request; clients SHOULD
   * respect the information provided.
   * </pre>
   */
  public static final class ExecutionStub extends io.grpc.stub.AbstractStub<ExecutionStub> {
    private ExecutionStub(io.grpc.Channel channel) {
      super(channel);
    }

    private ExecutionStub(io.grpc.Channel channel,
        io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ExecutionStub build(io.grpc.Channel channel,
        io.grpc.CallOptions callOptions) {
      return new ExecutionStub(channel, callOptions);
    }

    /**
     * <pre>
     * Execute an action remotely.
     * In order to execute an action, the client must first upload all of the
     * inputs, as well as the
     * [Command][google.devtools.remoteexecution.v1test.Command] to run, into the
     * [ContentAddressableStorage][google.devtools.remoteexecution.v1test.ContentAddressableStorage].
     * It then calls `Execute` with an
     * [Action][google.devtools.remoteexecution.v1test.Action] referring to them.
     * The server will run the action and eventually return the result.
     * The input `Action`'s fields MUST meet the various canonicalization
     * requirements specified in the documentation for their types so that it has
     * the same digest as other logically equivalent `Action`s. The server MAY
     * enforce the requirements and return errors if a non-canonical input is
     * received. It MAY also proceed without verifying some or all of the
     * requirements, such as for performance reasons. If the server does not
     * verify the requirement, then it will treat the `Action` as distinct from
     * another logically equivalent action if they hash differently.
     * Returns a [google.longrunning.Operation][google.longrunning.Operation]
     * describing the resulting execution, with eventual `response`
     * [ExecuteResponse][google.devtools.remoteexecution.v1test.ExecuteResponse].
     * The `metadata` on the operation is of type
     * [ExecuteOperationMetadata][google.devtools.remoteexecution.v1test.ExecuteOperationMetadata].
     * To query the operation, you can use the
     * [Operations API][google.longrunning.Operations.GetOperation]. If you wish
     * to allow the server to stream operations updates, rather than requiring
     * client polling, you can use the
     * [Watcher API][google.watcher.v1.Watcher.Watch] with the Operation's `name`
     * as the `target`.
     * When using the Watcher API, the initial `data` will be the `Operation` at
     * the time of the request. Updates will be provided periodically by the
     * server until the `Operation` completes, at which point the response message
     * will (assuming no error) be at `data.response`.
     * The server NEED NOT implement other methods or functionality of the
     * Operation and Watcher APIs.
     * Errors discovered during creation of the `Operation` will be reported
     * as gRPC Status errors, while errors that occurred while running the
     * action will be reported in the `status` field of the `ExecuteResponse`. The
     * server MUST NOT set the `error` field of the `Operation` proto.
     * The possible errors include:
     * * `INVALID_ARGUMENT`: One or more arguments are invalid.
     * * `FAILED_PRECONDITION`: One or more errors occurred in setting up the
     *   action requested, such as a missing input or command or no worker being
     *   available. The client may be able to fix the errors and retry.
     * * `RESOURCE_EXHAUSTED`: There is insufficient quota of some resource to run
     *   the action.
     * * `UNAVAILABLE`: Due to a transient condition, such as all workers being
     *   occupied (and the server does not support a queue), the action could not
     *   be started. The client should retry.
     * * `INTERNAL`: An internal error occurred in the execution engine or the
     *   worker.
     * * `DEADLINE_EXCEEDED`: The execution timed out.
     * In the case of a missing input or command, the server SHOULD additionally
     * send a [PreconditionFailure][google.rpc.PreconditionFailure] error detail
     * where, for each requested blob not present in the CAS, there is a
     * `Violation` with a `type` of `MISSING` and a `subject` of
     * `"blobs/{hash}/{size}"` indicating the digest of the missing blob.
     * </pre>
     */
    public void execute(com.google.devtools.remoteexecution.v1test.ExecuteRequest request,
        io.grpc.stub.StreamObserver<com.google.longrunning.Operation> responseObserver) {
      asyncUnaryCall(
          getChannel().newCall(getExecuteMethodHelper(), getCallOptions()), request, responseObserver);
    }
  }

  /**
   * <pre>
   * The Remote Execution API is used to execute an
   * [Action][google.devtools.remoteexecution.v1test.Action] on the remote
   * workers.
   * As with other services in the Remote Execution API, any call may return an
   * error with a [RetryInfo][google.rpc.RetryInfo] error detail providing
   * information about when the client should retry the request; clients SHOULD
   * respect the information provided.
   * </pre>
   */
  public static final class ExecutionBlockingStub extends io.grpc.stub.AbstractStub<ExecutionBlockingStub> {
    private ExecutionBlockingStub(io.grpc.Channel channel) {
      super(channel);
    }

    private ExecutionBlockingStub(io.grpc.Channel channel,
        io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ExecutionBlockingStub build(io.grpc.Channel channel,
        io.grpc.CallOptions callOptions) {
      return new ExecutionBlockingStub(channel, callOptions);
    }

    /**
     * <pre>
     * Execute an action remotely.
     * In order to execute an action, the client must first upload all of the
     * inputs, as well as the
     * [Command][google.devtools.remoteexecution.v1test.Command] to run, into the
     * [ContentAddressableStorage][google.devtools.remoteexecution.v1test.ContentAddressableStorage].
     * It then calls `Execute` with an
     * [Action][google.devtools.remoteexecution.v1test.Action] referring to them.
     * The server will run the action and eventually return the result.
     * The input `Action`'s fields MUST meet the various canonicalization
     * requirements specified in the documentation for their types so that it has
     * the same digest as other logically equivalent `Action`s. The server MAY
     * enforce the requirements and return errors if a non-canonical input is
     * received. It MAY also proceed without verifying some or all of the
     * requirements, such as for performance reasons. If the server does not
     * verify the requirement, then it will treat the `Action` as distinct from
     * another logically equivalent action if they hash differently.
     * Returns a [google.longrunning.Operation][google.longrunning.Operation]
     * describing the resulting execution, with eventual `response`
     * [ExecuteResponse][google.devtools.remoteexecution.v1test.ExecuteResponse].
     * The `metadata` on the operation is of type
     * [ExecuteOperationMetadata][google.devtools.remoteexecution.v1test.ExecuteOperationMetadata].
     * To query the operation, you can use the
     * [Operations API][google.longrunning.Operations.GetOperation]. If you wish
     * to allow the server to stream operations updates, rather than requiring
     * client polling, you can use the
     * [Watcher API][google.watcher.v1.Watcher.Watch] with the Operation's `name`
     * as the `target`.
     * When using the Watcher API, the initial `data` will be the `Operation` at
     * the time of the request. Updates will be provided periodically by the
     * server until the `Operation` completes, at which point the response message
     * will (assuming no error) be at `data.response`.
     * The server NEED NOT implement other methods or functionality of the
     * Operation and Watcher APIs.
     * Errors discovered during creation of the `Operation` will be reported
     * as gRPC Status errors, while errors that occurred while running the
     * action will be reported in the `status` field of the `ExecuteResponse`. The
     * server MUST NOT set the `error` field of the `Operation` proto.
     * The possible errors include:
     * * `INVALID_ARGUMENT`: One or more arguments are invalid.
     * * `FAILED_PRECONDITION`: One or more errors occurred in setting up the
     *   action requested, such as a missing input or command or no worker being
     *   available. The client may be able to fix the errors and retry.
     * * `RESOURCE_EXHAUSTED`: There is insufficient quota of some resource to run
     *   the action.
     * * `UNAVAILABLE`: Due to a transient condition, such as all workers being
     *   occupied (and the server does not support a queue), the action could not
     *   be started. The client should retry.
     * * `INTERNAL`: An internal error occurred in the execution engine or the
     *   worker.
     * * `DEADLINE_EXCEEDED`: The execution timed out.
     * In the case of a missing input or command, the server SHOULD additionally
     * send a [PreconditionFailure][google.rpc.PreconditionFailure] error detail
     * where, for each requested blob not present in the CAS, there is a
     * `Violation` with a `type` of `MISSING` and a `subject` of
     * `"blobs/{hash}/{size}"` indicating the digest of the missing blob.
     * </pre>
     */
    public com.google.longrunning.Operation execute(com.google.devtools.remoteexecution.v1test.ExecuteRequest request) {
      return blockingUnaryCall(
          getChannel(), getExecuteMethodHelper(), getCallOptions(), request);
    }
  }

  /**
   * <pre>
   * The Remote Execution API is used to execute an
   * [Action][google.devtools.remoteexecution.v1test.Action] on the remote
   * workers.
   * As with other services in the Remote Execution API, any call may return an
   * error with a [RetryInfo][google.rpc.RetryInfo] error detail providing
   * information about when the client should retry the request; clients SHOULD
   * respect the information provided.
   * </pre>
   */
  public static final class ExecutionFutureStub extends io.grpc.stub.AbstractStub<ExecutionFutureStub> {
    private ExecutionFutureStub(io.grpc.Channel channel) {
      super(channel);
    }

    private ExecutionFutureStub(io.grpc.Channel channel,
        io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ExecutionFutureStub build(io.grpc.Channel channel,
        io.grpc.CallOptions callOptions) {
      return new ExecutionFutureStub(channel, callOptions);
    }

    /**
     * <pre>
     * Execute an action remotely.
     * In order to execute an action, the client must first upload all of the
     * inputs, as well as the
     * [Command][google.devtools.remoteexecution.v1test.Command] to run, into the
     * [ContentAddressableStorage][google.devtools.remoteexecution.v1test.ContentAddressableStorage].
     * It then calls `Execute` with an
     * [Action][google.devtools.remoteexecution.v1test.Action] referring to them.
     * The server will run the action and eventually return the result.
     * The input `Action`'s fields MUST meet the various canonicalization
     * requirements specified in the documentation for their types so that it has
     * the same digest as other logically equivalent `Action`s. The server MAY
     * enforce the requirements and return errors if a non-canonical input is
     * received. It MAY also proceed without verifying some or all of the
     * requirements, such as for performance reasons. If the server does not
     * verify the requirement, then it will treat the `Action` as distinct from
     * another logically equivalent action if they hash differently.
     * Returns a [google.longrunning.Operation][google.longrunning.Operation]
     * describing the resulting execution, with eventual `response`
     * [ExecuteResponse][google.devtools.remoteexecution.v1test.ExecuteResponse].
     * The `metadata` on the operation is of type
     * [ExecuteOperationMetadata][google.devtools.remoteexecution.v1test.ExecuteOperationMetadata].
     * To query the operation, you can use the
     * [Operations API][google.longrunning.Operations.GetOperation]. If you wish
     * to allow the server to stream operations updates, rather than requiring
     * client polling, you can use the
     * [Watcher API][google.watcher.v1.Watcher.Watch] with the Operation's `name`
     * as the `target`.
     * When using the Watcher API, the initial `data` will be the `Operation` at
     * the time of the request. Updates will be provided periodically by the
     * server until the `Operation` completes, at which point the response message
     * will (assuming no error) be at `data.response`.
     * The server NEED NOT implement other methods or functionality of the
     * Operation and Watcher APIs.
     * Errors discovered during creation of the `Operation` will be reported
     * as gRPC Status errors, while errors that occurred while running the
     * action will be reported in the `status` field of the `ExecuteResponse`. The
     * server MUST NOT set the `error` field of the `Operation` proto.
     * The possible errors include:
     * * `INVALID_ARGUMENT`: One or more arguments are invalid.
     * * `FAILED_PRECONDITION`: One or more errors occurred in setting up the
     *   action requested, such as a missing input or command or no worker being
     *   available. The client may be able to fix the errors and retry.
     * * `RESOURCE_EXHAUSTED`: There is insufficient quota of some resource to run
     *   the action.
     * * `UNAVAILABLE`: Due to a transient condition, such as all workers being
     *   occupied (and the server does not support a queue), the action could not
     *   be started. The client should retry.
     * * `INTERNAL`: An internal error occurred in the execution engine or the
     *   worker.
     * * `DEADLINE_EXCEEDED`: The execution timed out.
     * In the case of a missing input or command, the server SHOULD additionally
     * send a [PreconditionFailure][google.rpc.PreconditionFailure] error detail
     * where, for each requested blob not present in the CAS, there is a
     * `Violation` with a `type` of `MISSING` and a `subject` of
     * `"blobs/{hash}/{size}"` indicating the digest of the missing blob.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<com.google.longrunning.Operation> execute(
        com.google.devtools.remoteexecution.v1test.ExecuteRequest request) {
      return futureUnaryCall(
          getChannel().newCall(getExecuteMethodHelper(), getCallOptions()), request);
    }
  }

  private static final int METHODID_EXECUTE = 0;

  private static final class MethodHandlers<Req, Resp> implements
      io.grpc.stub.ServerCalls.UnaryMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.ServerStreamingMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.ClientStreamingMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.BidiStreamingMethod<Req, Resp> {
    private final ExecutionImplBase serviceImpl;
    private final int methodId;

    MethodHandlers(ExecutionImplBase serviceImpl, int methodId) {
      this.serviceImpl = serviceImpl;
      this.methodId = methodId;
    }

    @java.lang.Override
    @java.lang.SuppressWarnings("unchecked")
    public void invoke(Req request, io.grpc.stub.StreamObserver<Resp> responseObserver) {
      switch (methodId) {
        case METHODID_EXECUTE:
          serviceImpl.execute((com.google.devtools.remoteexecution.v1test.ExecuteRequest) request,
              (io.grpc.stub.StreamObserver<com.google.longrunning.Operation>) responseObserver);
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

  private static abstract class ExecutionBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoFileDescriptorSupplier, io.grpc.protobuf.ProtoServiceDescriptorSupplier {
    ExecutionBaseDescriptorSupplier() {}

    @java.lang.Override
    public com.google.protobuf.Descriptors.FileDescriptor getFileDescriptor() {
      return com.google.devtools.remoteexecution.v1test.RemoteExecutionProto.getDescriptor();
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.ServiceDescriptor getServiceDescriptor() {
      return getFileDescriptor().findServiceByName("Execution");
    }
  }

  private static final class ExecutionFileDescriptorSupplier
      extends ExecutionBaseDescriptorSupplier {
    ExecutionFileDescriptorSupplier() {}
  }

  private static final class ExecutionMethodDescriptorSupplier
      extends ExecutionBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoMethodDescriptorSupplier {
    private final String methodName;

    ExecutionMethodDescriptorSupplier(String methodName) {
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
      synchronized (ExecutionGrpc.class) {
        result = serviceDescriptor;
        if (result == null) {
          serviceDescriptor = result = io.grpc.ServiceDescriptor.newBuilder(SERVICE_NAME)
              .setSchemaDescriptor(new ExecutionFileDescriptorSupplier())
              .addMethod(getExecuteMethodHelper())
              .build();
        }
      }
    }
    return result;
  }
}
