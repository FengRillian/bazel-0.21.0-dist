// Generated by the protocol buffer compiler.  DO NOT EDIT!
// source: build/bazel/remote/execution/v2/remote_execution.proto

package build.bazel.remote.execution.v2;

public interface OutputDirectoryOrBuilder extends
    // @@protoc_insertion_point(interface_extends:build.bazel.remote.execution.v2.OutputDirectory)
    com.google.protobuf.MessageOrBuilder {

  /**
   * <pre>
   * The full path of the directory relative to the working directory. The path
   * separator is a forward slash `/`. Since this is a relative path, it MUST
   * NOT begin with a leading forward slash. The empty string value is allowed,
   * and it denotes the entire working directory.
   * </pre>
   *
   * <code>string path = 1;</code>
   */
  java.lang.String getPath();
  /**
   * <pre>
   * The full path of the directory relative to the working directory. The path
   * separator is a forward slash `/`. Since this is a relative path, it MUST
   * NOT begin with a leading forward slash. The empty string value is allowed,
   * and it denotes the entire working directory.
   * </pre>
   *
   * <code>string path = 1;</code>
   */
  com.google.protobuf.ByteString
      getPathBytes();

  /**
   * <pre>
   * The digest of the encoded
   * [Tree][build.bazel.remote.execution.v2.Tree] proto containing the
   * directory's contents.
   * </pre>
   *
   * <code>.build.bazel.remote.execution.v2.Digest tree_digest = 3;</code>
   */
  boolean hasTreeDigest();
  /**
   * <pre>
   * The digest of the encoded
   * [Tree][build.bazel.remote.execution.v2.Tree] proto containing the
   * directory's contents.
   * </pre>
   *
   * <code>.build.bazel.remote.execution.v2.Digest tree_digest = 3;</code>
   */
  build.bazel.remote.execution.v2.Digest getTreeDigest();
  /**
   * <pre>
   * The digest of the encoded
   * [Tree][build.bazel.remote.execution.v2.Tree] proto containing the
   * directory's contents.
   * </pre>
   *
   * <code>.build.bazel.remote.execution.v2.Digest tree_digest = 3;</code>
   */
  build.bazel.remote.execution.v2.DigestOrBuilder getTreeDigestOrBuilder();
}