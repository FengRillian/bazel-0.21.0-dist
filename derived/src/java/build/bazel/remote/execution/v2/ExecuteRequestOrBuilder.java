// Generated by the protocol buffer compiler.  DO NOT EDIT!
// source: build/bazel/remote/execution/v2/remote_execution.proto

package build.bazel.remote.execution.v2;

public interface ExecuteRequestOrBuilder extends
    // @@protoc_insertion_point(interface_extends:build.bazel.remote.execution.v2.ExecuteRequest)
    com.google.protobuf.MessageOrBuilder {

  /**
   * <pre>
   * The instance of the execution system to operate against. A server may
   * support multiple instances of the execution system (with their own workers,
   * storage, caches, etc.). The server MAY require use of this field to select
   * between them in an implementation-defined fashion, otherwise it can be
   * omitted.
   * </pre>
   *
   * <code>string instance_name = 1;</code>
   */
  java.lang.String getInstanceName();
  /**
   * <pre>
   * The instance of the execution system to operate against. A server may
   * support multiple instances of the execution system (with their own workers,
   * storage, caches, etc.). The server MAY require use of this field to select
   * between them in an implementation-defined fashion, otherwise it can be
   * omitted.
   * </pre>
   *
   * <code>string instance_name = 1;</code>
   */
  com.google.protobuf.ByteString
      getInstanceNameBytes();

  /**
   * <pre>
   * If true, the action will be executed anew even if its result was already
   * present in the cache. If false, the result may be served from the
   * [ActionCache][build.bazel.remote.execution.v2.ActionCache].
   * </pre>
   *
   * <code>bool skip_cache_lookup = 3;</code>
   */
  boolean getSkipCacheLookup();

  /**
   * <pre>
   * The digest of the [Action][build.bazel.remote.execution.v2.Action] to
   * execute.
   * </pre>
   *
   * <code>.build.bazel.remote.execution.v2.Digest action_digest = 6;</code>
   */
  boolean hasActionDigest();
  /**
   * <pre>
   * The digest of the [Action][build.bazel.remote.execution.v2.Action] to
   * execute.
   * </pre>
   *
   * <code>.build.bazel.remote.execution.v2.Digest action_digest = 6;</code>
   */
  build.bazel.remote.execution.v2.Digest getActionDigest();
  /**
   * <pre>
   * The digest of the [Action][build.bazel.remote.execution.v2.Action] to
   * execute.
   * </pre>
   *
   * <code>.build.bazel.remote.execution.v2.Digest action_digest = 6;</code>
   */
  build.bazel.remote.execution.v2.DigestOrBuilder getActionDigestOrBuilder();

  /**
   * <pre>
   * An optional policy for execution of the action.
   * The server will have a default policy if this is not provided.
   * </pre>
   *
   * <code>.build.bazel.remote.execution.v2.ExecutionPolicy execution_policy = 7;</code>
   */
  boolean hasExecutionPolicy();
  /**
   * <pre>
   * An optional policy for execution of the action.
   * The server will have a default policy if this is not provided.
   * </pre>
   *
   * <code>.build.bazel.remote.execution.v2.ExecutionPolicy execution_policy = 7;</code>
   */
  build.bazel.remote.execution.v2.ExecutionPolicy getExecutionPolicy();
  /**
   * <pre>
   * An optional policy for execution of the action.
   * The server will have a default policy if this is not provided.
   * </pre>
   *
   * <code>.build.bazel.remote.execution.v2.ExecutionPolicy execution_policy = 7;</code>
   */
  build.bazel.remote.execution.v2.ExecutionPolicyOrBuilder getExecutionPolicyOrBuilder();

  /**
   * <pre>
   * An optional policy for the results of this execution in the remote cache.
   * The server will have a default policy if this is not provided.
   * This may be applied to both the ActionResult and the associated blobs.
   * </pre>
   *
   * <code>.build.bazel.remote.execution.v2.ResultsCachePolicy results_cache_policy = 8;</code>
   */
  boolean hasResultsCachePolicy();
  /**
   * <pre>
   * An optional policy for the results of this execution in the remote cache.
   * The server will have a default policy if this is not provided.
   * This may be applied to both the ActionResult and the associated blobs.
   * </pre>
   *
   * <code>.build.bazel.remote.execution.v2.ResultsCachePolicy results_cache_policy = 8;</code>
   */
  build.bazel.remote.execution.v2.ResultsCachePolicy getResultsCachePolicy();
  /**
   * <pre>
   * An optional policy for the results of this execution in the remote cache.
   * The server will have a default policy if this is not provided.
   * This may be applied to both the ActionResult and the associated blobs.
   * </pre>
   *
   * <code>.build.bazel.remote.execution.v2.ResultsCachePolicy results_cache_policy = 8;</code>
   */
  build.bazel.remote.execution.v2.ResultsCachePolicyOrBuilder getResultsCachePolicyOrBuilder();
}