// Copyright 2015 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.rules.proto;

import static com.google.devtools.build.lib.analysis.configuredtargets.RuleConfiguredTarget.Mode.TARGET;
import static com.google.devtools.build.lib.collect.nestedset.Order.STABLE_ORDER;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.ArtifactRoot;
import com.google.devtools.build.lib.analysis.RuleContext;
import com.google.devtools.build.lib.analysis.Runfiles;
import com.google.devtools.build.lib.analysis.TransitiveInfoCollection;
import com.google.devtools.build.lib.analysis.config.BuildConfiguration;
import com.google.devtools.build.lib.analysis.configuredtargets.RuleConfiguredTarget.Mode;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.packages.BuildType;
import com.google.devtools.build.lib.syntax.Type;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.PathFragment;
import javax.annotation.Nullable;

/**
 * Utility functions for proto_library and proto aspect implementations.
 */
public class ProtoCommon {
  private ProtoCommon() {
    throw new UnsupportedOperationException();
  }

  /**
   * Gets the direct sources of a proto library. If protoSources is not empty, the value is just
   * protoSources. Otherwise, it's the combined sources of all direct dependencies of the given
   * RuleContext.
   *
   * @param ruleContext the proto library rule context.
   * @param protoSources the direct proto sources.
   * @return the direct sources of a proto library.
   */
  private static NestedSet<Artifact> computeStrictImportableProtosForDependents(
      RuleContext ruleContext, ImmutableList<Artifact> protoSources) {

    if (protoSources.isEmpty()) {
      /* a proxy/alias library, return the sources of the direct deps */
      NestedSetBuilder<Artifact> builder = NestedSetBuilder.stableOrder();
      for (TransitiveInfoCollection provider : ruleContext.getPrerequisites("deps", Mode.TARGET)) {
        ProtoInfo sources = provider.getProvider(ProtoInfo.class);
        if (sources != null) {
          builder.addTransitive(sources.getStrictImportableProtoSourcesForDependents());
        }
      }
      return builder.build();
    } else {
      return NestedSetBuilder.wrap(STABLE_ORDER, protoSources);
    }
  }

  /**
   * Collects all .proto files in this lib and its transitive dependencies.
   *
   * <p>Each import is a Artifact/Label pair.
   */
  private static NestedSet<Artifact> computeTransitiveProtoSources(
      RuleContext ruleContext, ImmutableList<Artifact> protoSources) {
    NestedSetBuilder<Artifact> result = NestedSetBuilder.naiveLinkOrder();

    result.addAll(protoSources);

    for (ProtoInfo dep : ruleContext.getPrerequisites("deps", Mode.TARGET, ProtoInfo.class)) {
      result.addTransitive(dep.getTransitiveProtoSources());
    }

    return result.build();
  }

  static NestedSet<Artifact> computeDependenciesDescriptorSets(RuleContext ruleContext) {
    NestedSetBuilder<Artifact> result = NestedSetBuilder.stableOrder();

    for (ProtoInfo provider : ruleContext.getPrerequisites("deps", Mode.TARGET, ProtoInfo.class)) {
      result.addTransitive(provider.getTransitiveDescriptorSets());
    }
    return result.build();
  }

  /**
   * Returns all proto source roots in this lib ({@code currentProtoSourceRoot}) and in its
   * transitive dependencies.
   *
   * <p>Assumes {@code currentProtoSourceRoot} is the same as the package name.
   */
  private static NestedSet<String> computeTransitiveProtoSourceRoots(
      RuleContext ruleContext, String currentProtoSourceRoot) {
    NestedSetBuilder<String> protoPath = NestedSetBuilder.stableOrder();

    protoPath.add(currentProtoSourceRoot);
    for (ProtoInfo provider : ruleContext.getPrerequisites("deps", Mode.TARGET, ProtoInfo.class)) {
      protoPath.addTransitive(provider.getTransitiveProtoSourceRoots());
    }

    return protoPath.build();
  }

  /**
   * Returns the source root of the currentl {@code proto_library} or "." if it's the source root.
   *
   * <p>Build will fail if the {@code proto_source_root} of the current library is neither the
   * package name nor the source root.
   */
  // TODO(lberki): This should really be a PathFragment. Unfortunately, it's on the Starlark API of
  // ProtoInfo so it's not an easy change :(
  private static String getProtoSourceRoot(RuleContext ruleContext) {
    String protoSourceRoot =
        ruleContext.attributes().get("proto_source_root", Type.STRING);
    if (!protoSourceRoot.isEmpty()) {
      checkProtoSourceRootIsTheSameAsPackage(protoSourceRoot, ruleContext);
    }

    // This is the same as getPackageIdentifier().getPathUnderExecRoot() due to the check above for
    // protoSourceRoot == package name, but it's a bit more future-proof.
    String result =
        ruleContext
            .getLabel()
            .getPackageIdentifier()
            .getRepository()
            .getPathUnderExecRoot()
            .getRelative(protoSourceRoot)
            .getPathString();
    return result.isEmpty() ? "." : result;
  }

  /**
   * Returns a set of the {@code proto_source_root} collected from the current library and the
   * specified attribute.
   *
   * <p>Assumes {@code currentProtoSourceRoot} is the same as the package name.
   */
  private static NestedSet<String> getProtoSourceRootsOfAttribute(
      RuleContext ruleContext, String currentProtoSourceRoot, String attributeName) {
    NestedSetBuilder<String> protoSourceRoots = NestedSetBuilder.stableOrder();
    protoSourceRoots.add(currentProtoSourceRoot);

    for (ProtoInfo provider :
        ruleContext.getPrerequisites(attributeName, Mode.TARGET, ProtoInfo.class)) {
      protoSourceRoots.add(provider.getDirectProtoSourceRoot());
    }

    return protoSourceRoots.build();
  }

  /**
   * Returns a set of the {@code proto_source_root} collected from the current library and the
   * direct dependencies.
   *
   * <p>Assumes {@code currentProtoSourceRoot} is the same as the package name.
   */
  private static NestedSet<String> computeStrictImportableProtoSourceRoots(
      RuleContext ruleContext, String currentProtoSourceRoot) {
    return getProtoSourceRootsOfAttribute(ruleContext, currentProtoSourceRoot, "deps");
  }

  /**
   * Returns a set of the {@code proto_source_root} collected from the current library and the
   * exported dependencies.
   *
   * <p>Assumes {@code currentProtoSourceRoot} is the same as the package name.
   */
  private static NestedSet<String> computeExportedProtoSourceRoots(
      RuleContext ruleContext, String currentProtoSourceRoot) {
    return getProtoSourceRootsOfAttribute(ruleContext, currentProtoSourceRoot, "exports");
  }

  private static void checkProtoSourceRootIsTheSameAsPackage(
      String protoSourceRoot, RuleContext ruleContext) {
    if (!ruleContext.getLabel().getPackageName().equals(protoSourceRoot)) {
      ruleContext.attributeError(
          "proto_source_root",
          "proto_source_root must be the same as the package name ("
              + ruleContext.getLabel().getPackageName() + ")."
              + " not '" + protoSourceRoot + "'."
      );
    }
  }

  /**
   * Check that .proto files in sources are from the same package. This is done to avoid clashes
   * with the generated sources.
   */
  private static void checkSourceFilesAreInSamePackage(RuleContext ruleContext) {
    // TODO(bazel-team): this does not work with filegroups that contain files
    // that are not in the package
    for (Label source : ruleContext.attributes().get("srcs", BuildType.LABEL_LIST)) {
      if (!isConfiguredTargetInSamePackage(ruleContext, source)) {
        ruleContext.attributeError(
            "srcs",
            "Proto source with label '" + source + "' must be in same package as consuming rule.");
      }
    }
  }

  private static boolean isConfiguredTargetInSamePackage(RuleContext ruleContext, Label source) {
    return ruleContext.getLabel().getPackageIdentifier().equals(source.getPackageIdentifier());
  }

  /**
   * Creates the {@link ProtoInfo} for the {@code proto_library} rule associated with {@code
   * ruleContext}.
   */
  public static ProtoInfo createProtoInfo(RuleContext ruleContext) {
    checkSourceFilesAreInSamePackage(ruleContext);
    ImmutableList<Artifact> directProtoSources =
        ruleContext.getPrerequisiteArtifacts("srcs", Mode.TARGET).list();
    String protoSourceRoot = ProtoCommon.getProtoSourceRoot(ruleContext);

    NestedSet<Artifact> transitiveProtoSources =
        computeTransitiveProtoSources(ruleContext, directProtoSources);
    NestedSet<String> transitiveProtoSourceRoots =
        computeTransitiveProtoSourceRoots(ruleContext, protoSourceRoot);

    NestedSet<Artifact> strictImportableProtosForDependents =
        computeStrictImportableProtosForDependents(ruleContext, directProtoSources);
    NestedSet<Artifact> strictImportableProtos = computeStrictImportableProtos(ruleContext);
    NestedSet<String> strictImportableProtoSourceRoots =
        computeStrictImportableProtoSourceRoots(ruleContext, protoSourceRoot);

    NestedSet<Artifact> exportedProtos = ProtoCommon.computeExportedProtos(ruleContext);
    NestedSet<String> exportedProtoSourceRoots =
        computeExportedProtoSourceRoots(ruleContext, protoSourceRoot);

    Artifact directDescriptorSet =
        ruleContext.getGenfilesArtifact(
            ruleContext.getLabel().getName() + "-descriptor-set.proto.bin");
    NestedSet<Artifact> dependenciesDescriptorSets =
        ProtoCommon.computeDependenciesDescriptorSets(ruleContext);
    NestedSet<Artifact> transitiveDescriptorSets =
        NestedSetBuilder.fromNestedSet(dependenciesDescriptorSets).add(directDescriptorSet).build();

    ProtoInfo protoInfo =
        ProtoInfo.create(
            directProtoSources,
            protoSourceRoot,
            transitiveProtoSources,
            transitiveProtoSourceRoots,
            strictImportableProtosForDependents,
            strictImportableProtos,
            strictImportableProtoSourceRoots,
            exportedProtos,
            exportedProtoSourceRoots,
            directDescriptorSet,
            transitiveDescriptorSets);

    return protoInfo;
  }

  public static Runfiles.Builder createDataRunfilesProvider(
      final NestedSet<Artifact> transitiveProtoSources, RuleContext ruleContext) {
    // We assume that the proto sources will not have conflicting artifacts
    // with the same root relative path
    return new Runfiles.Builder(
            ruleContext.getWorkspaceName(), ruleContext.getConfiguration().legacyExternalRunfiles())
        .addTransitiveArtifactsWrappedInStableOrder(transitiveProtoSources);
  }

  // =================================================================
  // Protocol compiler invocation stuff.

  /**
   * Each language-specific initialization method will call this to construct
   * Artifacts representing its protocol compiler outputs.
   *
   * @param extension Remove ".proto" and replace it with this to produce
   *                  the output file name, e.g. ".pb.cc".
   * @param pythonNames If true, replace hyphens in the file name
   *              with underscores, as required for Python modules.
   */
  public static ImmutableList<Artifact> getGeneratedOutputs(RuleContext ruleContext,
      ImmutableList<Artifact> protoSources, String extension, boolean pythonNames) {
    ImmutableList.Builder<Artifact> outputsBuilder = new ImmutableList.Builder<>();
    ArtifactRoot genfiles =
        ruleContext.getConfiguration().getGenfilesDirectory(ruleContext.getRule().getRepository());
    for (Artifact src : protoSources) {
      PathFragment srcPath = src.getRootRelativePath();
      if (pythonNames) {
        srcPath = srcPath.replaceName(srcPath.getBaseName().replace('-', '_'));
      }
      // Note that two proto_library rules can have the same source file, so this is actually a
      // shared action. NB: This can probably result in action conflicts if the proto_library rules
      // are not the same.
      outputsBuilder.add(
          ruleContext.getShareableArtifact(FileSystemUtils.replaceExtension(srcPath, extension),
              genfiles));
    }
    return outputsBuilder.build();
  }

  /**
   * Each language-specific initialization method will call this to construct
   * Artifacts representing its protocol compiler outputs.
   *
   * @param extension Remove ".proto" and replace it with this to produce
   *                  the output file name, e.g. ".pb.cc".
   */
  public static ImmutableList<Artifact> getGeneratedOutputs(RuleContext ruleContext,
      ImmutableList<Artifact> protoSources, String extension) {
    return getGeneratedOutputs(ruleContext, protoSources, extension, false);
  }

  public static ImmutableList<Artifact> getGeneratedTreeArtifactOutputs(
      RuleContext ruleContext, ImmutableList<Artifact> protoSources, PathFragment directory) {
    ImmutableList.Builder<Artifact> outputsBuilder = new ImmutableList.Builder<>();
    if (!protoSources.isEmpty()) {
      ArtifactRoot genfiles =
          ruleContext
              .getConfiguration()
              .getGenfilesDirectory(ruleContext.getRule().getRepository());
      outputsBuilder.add(ruleContext.getTreeArtifact(directory, genfiles));
    }
    return outputsBuilder.build();
  }

  /**
   * Returns the .proto files that are the direct srcs of the direct-dependencies of this rule. If
   * the current rule is an alias proto_library (=no srcs), we use the direct srcs of the
   * direct-dependencies of our direct-dependencies.
   */
  @Nullable
  private static NestedSet<Artifact> computeStrictImportableProtos(RuleContext ruleContext) {
    NestedSetBuilder<Artifact> result = NestedSetBuilder.stableOrder();
    ImmutableList<Artifact> srcs = ruleContext.getPrerequisiteArtifacts("srcs", TARGET).list();
    if (srcs.isEmpty()) {
      for (ProtoInfo provider : ruleContext.getPrerequisites("deps", TARGET, ProtoInfo.class)) {
        result.addTransitive(provider.getStrictImportableProtoSources());
      }
    } else {
      for (ProtoInfo provider : ruleContext.getPrerequisites("deps", TARGET, ProtoInfo.class)) {
        result.addTransitive(provider.getStrictImportableProtoSourcesForDependents());
      }
      result.addAll(srcs);
    }
    return result.build();
  }

  /**
   * Returns the .proto files that are the direct srcs of the exported dependencies of this rule.
   */
  private static NestedSet<Artifact> computeExportedProtos(RuleContext ruleContext) {
    NestedSetBuilder<Artifact> result = NestedSetBuilder.stableOrder();
    for (ProtoInfo provider : ruleContext.getPrerequisites("exports", TARGET, ProtoInfo.class)) {
      result.addTransitive(provider.getStrictImportableProtoSources());
    }
    return result.build();
  }

  /**
   * Decides whether this proto_library should check for strict proto deps.
   *
   * <p>Only takes into account command-line flags.
   */
  @VisibleForTesting
  public static boolean areDepsStrict(RuleContext ruleContext) {
    BuildConfiguration.StrictDepsMode flagValue =
        ruleContext.getFragment(ProtoConfiguration.class).strictProtoDeps();
    if (flagValue == BuildConfiguration.StrictDepsMode.OFF) {
      return false;
    }
    if (flagValue == BuildConfiguration.StrictDepsMode.ERROR
        || flagValue == BuildConfiguration.StrictDepsMode.WARN) {
      return true;
    }
    return (flagValue == BuildConfiguration.StrictDepsMode.STRICT);
  }
}
