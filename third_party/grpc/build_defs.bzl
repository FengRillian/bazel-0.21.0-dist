"""BUILD rules to generate gRPC service interfaces.
You need to load the rules in your BUILD file for use, like:
load("//third_party/grpc:build_defs.bzl", "java_grpc_library")
"""

def _path_ignoring_repository(f):
  if (len(f.owner.workspace_root) == 0):
    return f.short_path
  # If |f| is a generated file, it will have "bazel-out/*/genfiles" prefix
  # before "external/workspace", so we need to add the starting index of "external/workspace"
  return f.path[f.path.find(f.owner.workspace_root)+len(f.owner.workspace_root)+1:]

def _gensource_impl(ctx):
  if len(ctx.attr.srcs) > 1:
    fail("Only one src value supported", "srcs")
  for s in ctx.attr.srcs:
    if s.label.package != ctx.label.package:
      print(("in srcs attribute of {0}: Proto source with label {1} should be in "
             + "same package as consuming rule").format(ctx.label, s.label))
  srcdotjar = ctx.new_file(ctx.label.name + ".jar")
  srcs = [f for dep in ctx.attr.srcs for f in dep.proto.direct_sources]
  includes = [f for dep in ctx.attr.srcs for f in dep.proto.transitive_imports.to_list()]

  ctx.action(
      command = " ".join([
          ctx.executable._protoc.path,
          "--plugin=protoc-gen-grpc-java={0}".format(ctx.executable._java_plugin.path),
          "--grpc-java_out=enable_deprecated={0}:{1}".format(str(ctx.attr.enable_deprecated).lower(), srcdotjar.path)]
          + ["-I{0}={1}".format(_path_ignoring_repository(include), include.path) for include in includes]
          + [src.path for src in srcs]),
      inputs = [ctx.executable._java_plugin, ctx.executable._protoc] + srcs + includes,
      outputs = [srcdotjar],
      use_default_shell_env=True)

  ctx.action(
      command = "cp '%s' '%s'" % (srcdotjar.path, ctx.outputs.srcjar.path),
      inputs = [srcdotjar],
      outputs = [ctx.outputs.srcjar])

_java_grpc_gensource = rule(
    attrs = {
        "srcs": attr.label_list(
            mandatory = True,
            non_empty = True,
            providers = ["proto"],
        ),
        "enable_deprecated": attr.bool(
            default = False,
        ),
        "_protoc": attr.label(
            default = Label("@com_google_protobuf//:protoc"),
            executable = True,
            cfg = "host",
            allow_files = True,
            single_file = True,
        ),
        "_java_plugin": attr.label(
            default = Label("@io_bazel//third_party/grpc:grpc-java-plugin"),
            executable = True,
            cfg = "host",
        ),
    },
    outputs = {
        "srcjar": "%{name}.srcjar",
    },
    implementation = _gensource_impl,
)

def java_grpc_library(name, srcs, deps, enable_deprecated=None, visibility=None, constraints=None, **kwargs):
  """Generates and compiles gRPC Java sources for services defined in a proto
  file. This rule is compatible with proto_library with java_api_version,
  java_proto_library, and java_lite_proto_library.
  Do note that this rule only scans through the proto file for RPC services. It
  does not generate Java classes for proto messages. You will need a separate
  proto_library with java_api_version, java_proto_library, or
  java_lite_proto_library rule.
  Args:
    name: (str) A unique name for this rule. Required.
    srcs: (list) a single proto_library target that contains the schema of the
        service. Required.
    deps: (list) a single java_proto_library target for the proto_library in
        srcs.  Required.
    visibility: (list) the visibility list
    **kwargs: Passed through to generated targets
  """
  gensource_name = name + "_srcs"
  _java_grpc_gensource(
      name = gensource_name,
      srcs = srcs,
      enable_deprecated = enable_deprecated,
      visibility = ["//visibility:private"],
      tags = [
          "avoid_dep",
      ],
      **kwargs
  )
  native.java_library(
      name = name,
      srcs = [gensource_name],
      visibility = visibility,
      deps = [
          "@io_bazel//third_party:javax_annotations",
          "@io_bazel//third_party:jsr305",
          "@io_bazel//third_party/grpc:grpc-jar",
          "@io_bazel//third_party:guava",
          "@com_google_protobuf//:protobuf_java",
      ] + deps,
      **kwargs
  )
