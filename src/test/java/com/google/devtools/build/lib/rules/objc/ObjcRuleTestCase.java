// Copyright 2017 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.rules.objc;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static com.google.devtools.build.lib.actions.util.ActionsTestUtil.getFirstArtifactEndingWith;
import static com.google.devtools.build.lib.rules.objc.CompilationSupport.AUTOMATIC_SDK_FRAMEWORKS;
import static com.google.devtools.build.lib.rules.objc.ObjcProvider.HEADER;
import static com.google.devtools.build.lib.rules.objc.ObjcProvider.INCLUDE;
import static com.google.devtools.build.lib.rules.objc.ObjcProvider.MODULE_MAP;
import static com.google.devtools.build.lib.rules.objc.ObjcProvider.STORYBOARD;
import static com.google.devtools.build.lib.rules.objc.ObjcRuleClasses.BundlingRule.FAMILIES_ATTR;
import static com.google.devtools.build.lib.rules.objc.ObjcRuleClasses.BundlingRule.INFOPLIST_ATTR;
import static com.google.devtools.build.lib.rules.objc.ObjcRuleClasses.LIPO;
import static com.google.devtools.build.lib.rules.objc.ObjcRuleClasses.SRCS_TYPE;

import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.devtools.build.lib.actions.Action;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.CommandAction;
import com.google.devtools.build.lib.actions.ExecutionInfoSpecifier;
import com.google.devtools.build.lib.actions.ExecutionRequirements;
import com.google.devtools.build.lib.actions.util.ActionsTestUtil;
import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.OutputGroupInfo;
import com.google.devtools.build.lib.analysis.actions.BinaryFileWriteAction;
import com.google.devtools.build.lib.analysis.actions.CustomCommandLine;
import com.google.devtools.build.lib.analysis.actions.CustomCommandLine.VectorArg;
import com.google.devtools.build.lib.analysis.actions.SpawnAction;
import com.google.devtools.build.lib.analysis.config.BuildConfiguration;
import com.google.devtools.build.lib.analysis.config.BuildOptions;
import com.google.devtools.build.lib.analysis.config.CompilationMode;
import com.google.devtools.build.lib.analysis.config.transitions.SplitTransition;
import com.google.devtools.build.lib.analysis.util.BuildViewTestCase;
import com.google.devtools.build.lib.analysis.util.ScratchAttributeWriter;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.lib.packages.util.MockObjcSupport;
import com.google.devtools.build.lib.packages.util.MockProtoSupport;
import com.google.devtools.build.lib.rules.apple.AppleCommandLineOptions;
import com.google.devtools.build.lib.rules.apple.AppleConfiguration.ConfigurationDistinguisher;
import com.google.devtools.build.lib.rules.apple.ApplePlatform;
import com.google.devtools.build.lib.rules.apple.ApplePlatform.PlatformType;
import com.google.devtools.build.lib.rules.apple.AppleToolchain;
import com.google.devtools.build.lib.rules.apple.DottedVersion;
import com.google.devtools.build.lib.rules.cpp.CppLinkAction;
import com.google.devtools.build.lib.rules.objc.CompilationSupport.ExtraLinkArgs;
import com.google.devtools.build.lib.testutil.TestConstants;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.xcode.plmerge.proto.PlMergeProtos;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * Superclass for all Obj-C rule tests.
 *
 * <p>TODO(matvore): split this up into more helper classes, especially the check... methods, which
 * are many and not shared by all objc_ rules.
 * <p>TODO(matvore): find a more concise way to repeat common tests (in particular, those which
 * simply call a check... method) across several rule types.
 */
public abstract class ObjcRuleTestCase extends BuildViewTestCase {
  protected static final String MOCK_ACTOOLWRAPPER_PATH =
      toolsRepoExecPath("tools/objc/actoolwrapper");
  protected static final String MOCK_IBTOOLWRAPPER_PATH =
      toolsRepoExecPath("tools/objc/ibtoolwrapper");
  protected static final String MOCK_XCRUNWRAPPER_PATH =
      toolsRepoExecPath("tools/objc/xcrunwrapper.sh");
  protected static final String MOCK_XCRUNWRAPPER_EXECUTABLE_PATH =
      toolExecutable("tools/objc/xcrunwrapper");
  protected static final ImmutableList<String> FASTBUILD_COPTS = ImmutableList.of("-O0", "-DDEBUG");

  protected static final DottedVersion DEFAULT_IOS_SDK_VERSION =
      DottedVersion.fromString(AppleCommandLineOptions.DEFAULT_IOS_SDK_VERSION);


  /**
   * Returns the configuration obtained by applying the apple crosstool configuration transtion to
   * this {@code BuildViewTestCase}'s target configuration.
   */
  protected BuildConfiguration getAppleCrosstoolConfiguration() throws InterruptedException {
    return getConfiguration(targetConfig, AppleCrosstoolTransition.APPLE_CROSSTOOL_TRANSITION);
  }

  /** Specification of code coverage behavior. */
  public enum CodeCoverageMode {
    // No code coverage information.
    NONE,
    // Code coverage in gcov format.
    GCOV,
    // Code coverage in llvm-covmap format.
    LLVMCOV;
  }

  /**
   * Returns the bin dir for artifacts built for a given Apple architecture (as set by a
   * configuration transition) and configuration distinguisher but the global
   * default for {@code --cpu} and the platform default for minimum OS.
   *
   * @param arch the given Apple architecture which artifacts are built under this configuration.
   *     Note this will likely be different than the value of {@code --cpu}
   * @param configurationDistinguisher the configuration distinguisher used to describe the a
   *     configuration transition
   */
  protected String configurationBin(
      String arch, ConfigurationDistinguisher configurationDistinguisher) {
    return configurationBin(arch, configurationDistinguisher, null, CompilationMode.FASTBUILD);
  }

  /**
   * Returns the bin dir for artifacts built for a given Apple architecture and minimum OS version
   * (as set by a configuration transition) and configuration distinguisher but the global default
   * for {@code --cpu}.
   *
   * @param arch the given Apple architecture which artifacts are built under this configuration.
   *     Note this will likely be different than the value of {@code --cpu}.
   * @param configurationDistinguisher the configuration distinguisher used to describe the a
   *     configuration transition
   * @param minOsVersion the minimum os version for which to compile artifacts in the configuration
   * @param compilationMode the compilation mode used during the build
   */
  protected String configurationBin(
      String arch,
      ConfigurationDistinguisher configurationDistinguisher,
      DottedVersion minOsVersion,
      CompilationMode compilationMode) {
    return configurationDir(arch, configurationDistinguisher, minOsVersion, compilationMode)
        + "bin/";
  }

   /**
   * Returns the genfiles dir for artifacts built for a given Apple architecture and minimum OS
   * version (as set by a configuration transition) and configuration distinguisher but the global
   * default for {@code --cpu}.
   *
   * @param arch the given Apple architecture which artifacts are built under this configuration.
   *     Note this will likely be different than the value of {@code --cpu}.
   * @param configurationDistinguisher the configuration distinguisher used to describe the
   *     a configuration transition
   * @param minOsVersion the minimum os version for which to compile artifacts in the
   *     configuration
   */
  protected String configurationGenfiles(
      String arch, ConfigurationDistinguisher configurationDistinguisher,
      DottedVersion minOsVersion) {
    return configurationDir(
            arch, configurationDistinguisher, minOsVersion, CompilationMode.FASTBUILD)
        + getTargetConfiguration()
            .getGenfilesDirectory(RepositoryName.MAIN)
            .getExecPath()
            .getBaseName();
  }

  private static String toolExecutable(String toolSrcPath) {
    return String.format("%s-out/host/bin/%s", TestConstants.PRODUCT_NAME,
        TestConstants.TOOLS_REPOSITORY_PATH_PREFIX + toolSrcPath);
  }

  private String configurationDir(
      String arch,
      ConfigurationDistinguisher configurationDistinguisher,
      DottedVersion minOsVersion,
      CompilationMode compilationMode) {
    String minOsSegment = minOsVersion == null ? "" : "-min" + minOsVersion;
    String modeSegment = compilationModeFlag(compilationMode);
    switch (configurationDistinguisher) {
      case UNKNOWN:
        return String.format("%s-out/ios_%s-%s/", TestConstants.PRODUCT_NAME, arch, modeSegment);
      case APPLE_CROSSTOOL:
        return String.format("%1$s-out/apl-ios_%2$s%3$s-%4$s/",
            TestConstants.PRODUCT_NAME,
            arch,
            minOsSegment,
            modeSegment);
      case APPLEBIN_IOS:
        return String.format(
            "%1$s-out/ios-%2$s%4$s-%3$s-ios_%2$s-%5$s/",
            TestConstants.PRODUCT_NAME,
            arch,
            configurationDistinguisher.toString().toLowerCase(Locale.US),
            minOsSegment,
            modeSegment);
      case APPLEBIN_WATCHOS:
        return String.format(
            "%1$s-out/watchos-%2$s%4$s-%3$s-watchos_%2$s-%5$s/",
            TestConstants.PRODUCT_NAME,
            arch,
            configurationDistinguisher.toString().toLowerCase(Locale.US),
            minOsSegment,
            modeSegment);
      default:
        throw new AssertionError();
    }
  }

  protected String execPathEndingWith(Iterable<Artifact> artifacts, String suffix) {
    return getFirstArtifactEndingWith(artifacts, suffix).getExecPathString();
  }

  @Override
  public void initializeMockClient() throws IOException {
    super.initializeMockClient();
    MockObjcSupport.setup(mockToolsConfig);
    MockProtoSupport.setup(mockToolsConfig);
  }

  protected static String frameworkDir(ApplePlatform platform) {
    return AppleToolchain.platformDir(
        platform.getNameInPlist()) + AppleToolchain.DEVELOPER_FRAMEWORK_PATH;
  }

  /**
   * Creates an {@code objc_library} target writer for the label indicated by the given String.
   */
  protected ScratchAttributeWriter createLibraryTargetWriter(String labelString) {
    return ScratchAttributeWriter.fromLabelString(this, "objc_library", labelString);
  }

  /** Creates an {@code apple_binary} target writer for the label indicated by the given String. */
  protected ScratchAttributeWriter createBinaryTargetWriter(String labelString) {
    return ScratchAttributeWriter.fromLabelString(this, "apple_binary", labelString)
        .set("platform_type", "'ios'");
  }

  private static String compilationModeFlag(CompilationMode mode) {
    switch (mode) {
      case DBG:
        return "dbg";
      case OPT:
        return "opt";
      case FASTBUILD:
        return "fastbuild";
    }
    throw new AssertionError();
  }

  private static List<String> compilationModeCopts(CompilationMode mode) {
    switch (mode) {
      case DBG:
        return ImmutableList.<String>builder()
            .addAll(ObjcConfiguration.DBG_COPTS)
            .build();
      case OPT:
        return ObjcConfiguration.OPT_COPTS;
      case FASTBUILD:
        return FASTBUILD_COPTS;
    }
    throw new AssertionError();
  }

  @Override
  protected void useConfiguration(String... args) throws Exception {
    ImmutableList<String> extraArgs = MockObjcSupport.requiredObjcCrosstoolFlags();
    args = Arrays.copyOf(args, args.length + extraArgs.size());
    for (int i = 0; i < extraArgs.size(); i++) {
      args[(args.length - extraArgs.size()) + i] = extraArgs.get(i);
    }

    super.useConfiguration(args);
  }

  /**
   * Returns the arguments to pass to clang for specifying module map artifact location and
   * module name.
   *
   * @param packagePath the path to the package this target is in
   * @param targetName the name of the target
   */
  protected List<String> moduleMapArtifactArguments(String packagePath, String targetName) {
    Artifact moduleMapArtifact =
        getGenfilesArtifact(
            targetName + ".modulemaps/module.modulemap", packagePath + ":" + targetName);
    String moduleName =
        (packagePath + "_" + targetName)
            .replace("//", "")
            .replace("@", "")
            .replace("-", "_")
            .replace("/", "_")
            .replace(":", "_");

    return ImmutableList.of("-iquote",
        moduleMapArtifact.getExecPath().getParentDirectory().toString(),
        "-fmodule-name=" + moduleName);
  }

  /**
   * Returns all child configurations resulting from a given split transition on a given
   * configuration.
   */
  protected List<BuildConfiguration> getSplitConfigurations(BuildConfiguration configuration,
      SplitTransition splitTransition) throws InterruptedException {
    ImmutableList.Builder<BuildConfiguration> splitConfigs = ImmutableList.builder();

    for (BuildOptions splitOptions : splitTransition.split(configuration.getOptions())) {
      splitConfigs.add(getSkyframeExecutor().getConfigurationForTesting(
          reporter, configuration.fragmentClasses(), splitOptions));
    }

    return splitConfigs.build();
  }

  /**
   * Verifies a {@code -filelist} file's contents.
   *
   * @param originalAction the action which uses the filelist artifact
   * @param inputArchives path suffixes of the expected contents of the filelist
   */
  protected void verifyObjlist(Action originalAction, String... inputArchives) throws Exception {
    ImmutableList.Builder<String> execPaths = ImmutableList.builder();
    for (String inputArchive : inputArchives) {
      execPaths.add(execPathEndingWith(originalAction.getInputs(), inputArchive));
    }
    assertThat(paramFileArgsForAction(originalAction)).containsExactlyElementsIn(execPaths.build());
  }

  /**
   * Verifies a link action is registered correctly.
   *
   * @param binArtifact the output artifact which a link action should be registered to generate
   * @param filelistArtifact the input filelist artifact
   * @param arch the architecture (for example, "i386") which the binary is to be created for
   * @param inputArchives the suffixes (basenames or relative paths with basenames) of the input
   *     archive files for the link action
   * @param importedFrameworks custom framework path fragments
   * @param extraLinkArgs extra link arguments expected on the link action
   */
  protected void verifyLinkAction(
      Artifact binArtifact,
      Artifact filelistArtifact,
      String arch,
      List<String> inputArchives,
      List<PathFragment> importedFrameworks,
      ExtraLinkArgs extraLinkArgs)
      throws Exception {
    final CommandAction binAction = (CommandAction) getGeneratingAction(binArtifact);

    for (String inputArchive : inputArchives) {
      // Verify each input archive is present in the action inputs.
      getFirstArtifactEndingWith(binAction.getInputs(), inputArchive);
    }
    ImmutableList.Builder<String> frameworkPathFragmentParents = ImmutableList.builder();
    ImmutableList.Builder<String> frameworkPathBaseNames = ImmutableList.builder();
    for (PathFragment importedFramework : importedFrameworks) {
      frameworkPathFragmentParents.add(importedFramework.getParentDirectory().toString());
      frameworkPathBaseNames.add(importedFramework.getBaseName());
    }

    ImmutableList<String> expectedCommandLineFragments =
        ImmutableList.<String>builder()
            .add("-mios-simulator-version-min=" + DEFAULT_IOS_SDK_VERSION)
            .add("-arch " + arch)
            .add("-isysroot " + AppleToolchain.sdkDir())
            .add(AppleToolchain.sdkDir() + AppleToolchain.DEVELOPER_FRAMEWORK_PATH)
            .add(frameworkDir(ApplePlatform.forTarget(PlatformType.IOS, arch)))
            .addAll(frameworkPathFragmentParents.build())
            .add("-Xlinker -objc_abi_version -Xlinker 2")
            .add("-Xlinker -rpath -Xlinker @executable_path/Frameworks")
            .add("-fobjc-link-runtime")
            .add("-ObjC")
            .addAll(
                Interspersing.beforeEach(
                    "-framework", SdkFramework.names(AUTOMATIC_SDK_FRAMEWORKS)))
            .addAll(Interspersing.beforeEach("-framework", frameworkPathBaseNames.build()))
            .add("-filelist")
            .add(filelistArtifact.getExecPathString())
            .add("-o")
            .addAll(Artifact.toExecPaths(binAction.getOutputs()))
            .addAll(extraLinkArgs)
            .build();

    String linkArgs = Joiner.on(" ").join(binAction.getArguments());
    for (String expectedCommandLineFragment : expectedCommandLineFragments) {
      assertThat(linkArgs).contains(expectedCommandLineFragment);
    }
  }

  protected void assertAppleSdkVersionEnv(Map<String, String> env) throws Exception {
    assertAppleSdkVersionEnv(env, DEFAULT_IOS_SDK_VERSION);
  }

  protected void assertAppleSdkVersionEnv(Map<String, String> env, DottedVersion versionNumber) {
    assertThat(env).containsEntry("APPLE_SDK_VERSION_OVERRIDE", versionNumber.toString());
  }

  protected void assertAppleSdkVersionEnv(CommandAction action) throws Exception {
    assertAppleSdkVersionEnv(action, DEFAULT_IOS_SDK_VERSION.toString());
  }

  protected void assertAppleSdkVersionEnv(CommandAction action, String versionString) {
    assertThat(action.getIncompleteEnvironmentForTesting())
        .containsEntry("APPLE_SDK_VERSION_OVERRIDE", versionString);
  }

  protected void assertAppleSdkPlatformEnv(CommandAction action, String platformName) {
    assertThat(action.getIncompleteEnvironmentForTesting()).containsEntry("APPLE_SDK_PLATFORM", platformName);
  }

  protected void assertXcodeVersionEnv(CommandAction action, String versionNumber) {
    assertThat(action.getIncompleteEnvironmentForTesting()).containsEntry("XCODE_VERSION_OVERRIDE", versionNumber);
  }

  protected ObjcProvider providerForTarget(String label) throws Exception {
    ObjcProvider objcProvider = getConfiguredTarget(label).get(ObjcProvider.SKYLARK_CONSTRUCTOR);
    if (objcProvider != null) {
      return objcProvider;
    }
    AppleExecutableBinaryInfo executableProvider =
        getConfiguredTarget(label).get(AppleExecutableBinaryInfo.SKYLARK_CONSTRUCTOR);
    if (executableProvider != null) {
      return executableProvider.getDepsObjcProvider();
    }
    AppleDylibBinaryInfo dylibProvider =
        getConfiguredTarget(label).get(AppleDylibBinaryInfo.SKYLARK_CONSTRUCTOR);
    if (dylibProvider != null) {
      return dylibProvider.getDepsObjcProvider();
    }
    AppleLoadableBundleBinaryInfo loadableBundleProvider =
        getConfiguredTarget(label).get(AppleLoadableBundleBinaryInfo.SKYLARK_CONSTRUCTOR);
    if (loadableBundleProvider != null) {
      return loadableBundleProvider.getDepsObjcProvider();
    }
    return null;
  }

  protected CommandAction archiveAction(String label) throws Exception {
    ConfiguredTarget target = getConfiguredTarget(label);
    return (CommandAction)
        getGeneratingAction(getBinArtifact("lib" + target.getLabel().getName() + ".a", target));
  }

  protected Iterable<Artifact> inputsEndingWith(Action action, final String suffix) {
    return Iterables.filter(
        action.getInputs(), artifact -> artifact.getExecPathString().endsWith(suffix));
  }

  /**
   * Asserts that the given action can specify execution requirements, and requires execution on
   * darwin.
   */
  protected void assertRequiresDarwin(ExecutionInfoSpecifier action) {
    assertThat(action.getExecutionInfo()).containsKey(ExecutionRequirements.REQUIRES_DARWIN);
  }

  protected ConfiguredTarget addBinWithTransitiveDepOnFrameworkImport() throws Exception {
    ConfiguredTarget lib = addLibWithDepOnFrameworkImport();
    return createBinaryTargetWriter("//bin:bin")
        .setList("deps", lib.getLabel().toString())
        .write();

  }

  protected ConfiguredTarget addLibWithDepOnFrameworkImport() throws Exception {
    scratch.file("fx/fx1.framework/a");
    scratch.file("fx/fx1.framework/b");
    scratch.file("fx/fx2.framework/c");
    scratch.file("fx/fx2.framework/d");
    scratch.file("fx/BUILD",
        "objc_framework(",
        "    name = 'fx',",
        "    framework_imports = glob(['fx1.framework/*', 'fx2.framework/*']),",
        "    sdk_frameworks = ['CoreLocation'],",
        "    weak_sdk_frameworks = ['MediaAccessibility'],",
        "    sdk_dylibs = ['libdy1'],",
        ")");
    return createLibraryTargetWriter("//lib:lib")
        .setAndCreateFiles("srcs", "a.m", "b.m", "private.h")
        .setList("deps", "//fx:fx")
        .write();
  }

  protected CommandAction compileAction(String ownerLabel, String objFileName) throws Exception {
    Action archiveAction = archiveAction(ownerLabel);
    return (CommandAction)
        getGeneratingAction(
            getFirstArtifactEndingWith(archiveAction.getInputs(), "/" + objFileName));
  }

  /**
   * Verifies simply that some rule type creates the {@link CompilationArtifacts} object
   * successfully; in particular, makes sure it is not ignoring attributes. If the scope of
   * {@link CompilationArtifacts} expands, make sure this method tests it properly.
   *
   * <p>This test only makes sure the attributes are not being ignored - it does not test any
   * other functionality in depth, which is covered by other unit tests.
   */
  protected void checkPopulatesCompilationArtifacts(RuleType ruleType) throws Exception {
    scratch.file("x/a.m");
    scratch.file("x/b.m");
    scratch.file("x/c.pch");
    ruleType.scratchTarget(scratch,
        "srcs", "['a.m']",
        "non_arc_srcs", "['b.m']",
        "pch", "'c.pch'");
    ImmutableList<String> includeFlags = ImmutableList.of("-include", "x/c.pch");
    assertContainsSublist(compileAction("//x:x", "a.o").getArguments(), includeFlags);
    assertContainsSublist(compileAction("//x:x", "b.o").getArguments(), includeFlags);
  }

  protected void checkProvidesHdrsAndIncludes(RuleType ruleType) throws Exception {
    scratch.file("x/a.h");
    ruleType.scratchTarget(scratch,
        "hdrs", "['a.h']",
        "includes", "['incdir']");
    ObjcProvider provider =
        getConfiguredTarget("//x:x", getAppleCrosstoolConfiguration())
            .get(ObjcProvider.SKYLARK_CONSTRUCTOR);
    assertThat(provider.get(HEADER)).containsExactly(getSourceArtifact("x/a.h"));
    assertThat(provider.get(INCLUDE))
        .containsExactly(
            PathFragment.create("x/incdir"),
            getAppleCrosstoolConfiguration().getGenfilesFragment().getRelative("x/incdir"));
  }

  protected void checkCompilesWithHdrs(RuleType ruleType) throws Exception {
    scratch.file("x/a.m");
    scratch.file("x/a.h");
    ruleType.scratchTarget(scratch,
        "srcs", "['a.m']",
        "hdrs", "['a.h']");
    assertThat(compileAction("//x:x", "a.o").getPossibleInputsForTesting())
        .contains(getSourceArtifact("x/a.h"));
  }

  // This checks that the proto bundling and grouping behavior works as expected. Grouping is based
  // on the proto_library targets, given that each proto_library is complete in its closure (all
  // the required deps are captured inside a proto_library).
  //
  // This particular tests sets up 3 proto groups, defined as [A, B], [B, C], [A, C, D]. The proto
  // grouping support detects that, for example, since A doesn't appear in all groups with B or C,
  // then it doesn't need any dependencies other than itself to be built. The same applies for B and
  // C, The same cannot be said about D, which only appears with A and C, so we have to assume that
  // D depends on A and C.
  //
  // These dependencies describe what the inputs will be to each of the generation/compilation
  // actions. Denoting {[in] -> [out]} as an action with "in" being the required inputs, and "out"
  // being the expected outputs, given the layout of the groups for this test, the actions should
  // be:
  //
  // {[A]       -> [A]}
  // {[B]       -> [B]}
  // {[C]       -> [C]}
  // {[A, C, D] -> [D]}
  //
  // This test ensures that, for example, to generate DataA.pbobjc.{h,m}, only data_a.proto should
  // be provided as an input, while the inputs to generate DataD.pbobjc.{h,m} should be
  // data_a.proto, data_c.proto and data_d.proto. The same applies for the compilation actions,
  // where the inputs are interpreted as .pbobjc.h files, and the output is a .pbobjc.o file.
  protected void checkProtoBundlingAndLinking(RuleType ruleType) throws Exception {
    scratch.file(
        "protos/BUILD",
        "proto_library(",
        "    name = 'protos_1',",
        "    srcs = ['data_a.proto', 'data_b.proto'],",
        ")",
        "proto_library(",
        "    name = 'protos_2',",
        "    srcs = ['data_b.proto', 'data_c.proto'],",
        ")",
        "proto_library(",
        "    name = 'protos_3',",
        "    srcs = ['data_c.proto', 'data_a.proto', 'data_d.proto'],",
        ")",
        "objc_proto_library(",
        "    name = 'objc_protos_a',",
        "    portable_proto_filters = ['filter_a.pbascii'],",
        "    deps = [':protos_1'],",
        ")",
        "objc_proto_library(",
        "    name = 'objc_protos_b',",
        "    portable_proto_filters = ['filter_b.pbascii'],",
        "    deps = [':protos_2', ':protos_3'],",
        ")");
    scratch.file(
        "libs/BUILD",
        "objc_library(",
        "    name = 'objc_lib',",
        "    srcs = ['a.m'],",
        "    deps = ['//protos:objc_protos_a', '//protos:objc_protos_b'],",
        "    defines = ['SHOULDNOTBEINPROTOS'],",
        "    copts = ['-ISHOULDNOTBEINPROTOS']",
        ")");

    ruleType.scratchTarget(
        scratch,
        "deps", "['//libs:objc_lib']");

    BuildConfiguration childConfig =
        Iterables.getOnlyElement(
            getSplitConfigurations(
                targetConfig,
                new MultiArchSplitTransitionProvider.AppleBinaryTransition(
                    PlatformType.IOS, Optional.absent())));

    ConfiguredTarget topTarget = getConfiguredTarget("//x:x", childConfig);

    assertObjcProtoProviderArtifactsArePropagated(topTarget);
    assertBundledGenerationActionsAreDifferent(topTarget);
    assertOnlyRequiredInputsArePresentForBundledGeneration(topTarget);
    assertCoptsAndDefinesNotPropagatedToProtos(topTarget);
    assertBundledGroupsGetCreatedAndLinked(topTarget);
  }

  protected ImmutableList<Artifact> getAllObjectFilesLinkedInBin(Artifact bin) {
    ImmutableList.Builder<Artifact> objects = ImmutableList.builder();
    CommandAction binAction = (CommandAction) getGeneratingAction(bin);
    for (Artifact binActionArtifact : binAction.getInputs()) {
      if (binActionArtifact.getRootRelativePath().getPathString().endsWith(".a")) {
        CommandAction linkAction = (CommandAction) getGeneratingAction(binActionArtifact);
        for (Artifact linkActionArtifact : linkAction.getInputs()) {
          if (linkActionArtifact.getRootRelativePath().getPathString().endsWith(".o")) {
            objects.add(linkActionArtifact);
          }
        }
      }
    }
    return objects.build();
  }

  private void assertObjcProtoProviderArtifactsArePropagated(ConfiguredTarget topTarget)
      throws Exception {
    ConfiguredTarget libTarget =
        view.getPrerequisiteConfiguredTargetForTesting(
            reporter, topTarget, Label.parseAbsoluteUnchecked("//libs:objc_lib"), masterConfig);

    ObjcProtoProvider protoProvider = libTarget.get(ObjcProtoProvider.SKYLARK_CONSTRUCTOR);
    assertThat(protoProvider).isNotNull();
    assertThat(protoProvider.getProtoGroups().toSet()).hasSize(3);
    assertThat(
            Artifact.toExecPaths(
                ImmutableSet.copyOf(Iterables.concat(protoProvider.getProtoGroups()))))
        .containsExactly(
            "protos/data_a.proto",
            "protos/data_b.proto",
            "protos/data_c.proto",
            "protos/data_d.proto");
    assertThat(Artifact.toExecPaths(protoProvider.getPortableProtoFilters()))
        .containsExactly("protos/filter_a.pbascii", "protos/filter_b.pbascii");
  }

  private void assertBundledGenerationActionsAreDifferent(ConfiguredTarget topTarget) {
    Artifact protoHeaderA = getBinArtifact("_generated_protos/x/protos/DataA.pbobjc.h", topTarget);
    Artifact protoHeaderB = getBinArtifact("_generated_protos/x/protos/DataB.pbobjc.h", topTarget);
    Artifact protoHeaderC = getBinArtifact("_generated_protos/x/protos/DataC.pbobjc.h", topTarget);
    Artifact protoHeaderD = getBinArtifact("_generated_protos/x/protos/DataD.pbobjc.h", topTarget);
    CommandAction protoActionA = (CommandAction) getGeneratingAction(protoHeaderA);
    CommandAction protoActionB = (CommandAction) getGeneratingAction(protoHeaderB);
    CommandAction protoActionC = (CommandAction) getGeneratingAction(protoHeaderC);
    CommandAction protoActionD = (CommandAction) getGeneratingAction(protoHeaderD);
    assertThat(protoActionA).isNotNull();
    assertThat(protoActionB).isNotNull();
    assertThat(protoActionC).isNotNull();
    assertThat(protoActionD).isNotNull();
    assertThat(protoActionA).isNotEqualTo(protoActionB);
    assertThat(protoActionB).isNotEqualTo(protoActionC);
    assertThat(protoActionC).isNotEqualTo(protoActionD);
  }

  private void assertOnlyRequiredInputsArePresentForBundledGeneration(ConfiguredTarget topTarget)
      throws Exception {
    ConfiguredTarget libTarget =
        view.getPrerequisiteConfiguredTargetForTesting(
            reporter, topTarget, Label.parseAbsoluteUnchecked("//libs:objc_lib"), masterConfig);
    ObjcProtoProvider protoProvider = libTarget.get(ObjcProtoProvider.SKYLARK_CONSTRUCTOR);

    Artifact protoHeaderA = getBinArtifact("_generated_protos/x/protos/DataA.pbobjc.h", topTarget);
    Artifact protoHeaderB = getBinArtifact("_generated_protos/x/protos/DataB.pbobjc.h", topTarget);
    Artifact protoHeaderC = getBinArtifact("_generated_protos/x/protos/DataC.pbobjc.h", topTarget);
    Artifact protoHeaderD = getBinArtifact("_generated_protos/x/protos/DataD.pbobjc.h", topTarget);

    CommandAction protoActionA = (CommandAction) getGeneratingAction(protoHeaderA);
    CommandAction protoActionB = (CommandAction) getGeneratingAction(protoHeaderB);
    CommandAction protoActionC = (CommandAction) getGeneratingAction(protoHeaderC);
    CommandAction protoActionD = (CommandAction) getGeneratingAction(protoHeaderD);

    assertThat(protoActionA.getInputs()).containsAllIn(protoProvider.getPortableProtoFilters());
    assertThat(protoActionB.getInputs()).containsAllIn(protoProvider.getPortableProtoFilters());
    assertThat(protoActionC.getInputs()).containsAllIn(protoProvider.getPortableProtoFilters());
    assertThat(protoActionD.getInputs()).containsAllIn(protoProvider.getPortableProtoFilters());

    assertThat(Artifact.toExecPaths(protoActionA.getInputs())).contains("protos/data_a.proto");
    assertThat(Artifact.toExecPaths(protoActionA.getInputs()))
        .containsNoneOf("protos/data_b.proto", "protos/data_c.proto", "protos/data_d.proto");

    assertThat(Artifact.toExecPaths(protoActionB.getInputs())).contains("protos/data_b.proto");
    assertThat(Artifact.toExecPaths(protoActionB.getInputs()))
        .containsNoneOf("protos/data_a.proto", "protos/data_c.proto", "protos/data_d.proto");

    assertThat(Artifact.toExecPaths(protoActionC.getInputs())).contains("protos/data_c.proto");
    assertThat(Artifact.toExecPaths(protoActionC.getInputs()))
        .containsNoneOf("protos/data_a.proto", "protos/data_b.proto", "protos/data_d.proto");

    assertThat(Artifact.toExecPaths(protoActionD.getInputs())).contains("protos/data_d.proto");
    assertThat(Artifact.toExecPaths(protoActionD.getInputs()))
        .containsAllOf("protos/data_a.proto", "protos/data_c.proto");
    assertThat(Artifact.toExecPaths(protoActionD.getInputs()))
        .doesNotContain("protos/data_b.proto");
  }

  /**
   * Ensures that all middleman artifacts in the action input are expanded so that the real inputs
   * are also included.
   */
  protected Iterable<Artifact> getExpandedActionInputs(Action action) {
    List<Artifact> containedArtifacts = new ArrayList<>();
    for (Artifact input : action.getInputs()) {
      if (input.isMiddlemanArtifact()) {
        Action middlemanAction = getGeneratingAction(input);
        Iterables.addAll(containedArtifacts, getExpandedActionInputs(middlemanAction));
      }
      containedArtifacts.add(input);
    }
    return containedArtifacts;
  }

  private void assertCoptsAndDefinesNotPropagatedToProtos(ConfiguredTarget topTarget)
      throws Exception {
    Artifact protoObject =
        getBinArtifact("_objs/x/non_arc/DataA.pbobjc.o", topTarget);
    CommandAction protoObjectAction = (CommandAction) getGeneratingAction(protoObject);
    assertThat(protoObjectAction).isNotNull();
    assertThat(protoObjectAction.getArguments())
        .containsNoneOf("-DSHOULDNOTBEINPROTOS", "-ISHOULDNOTBEINPROTOS");
  }

  private void assertBundledGroupsGetCreatedAndLinked(ConfiguredTarget topTarget) {
    Artifact protosGroupLib = getBinArtifact("libx_BundledProtos.a", topTarget);

    CommandAction protosLibAction = (CommandAction) getGeneratingAction(protosGroupLib);
    assertThat(protosLibAction).isNotNull();

    Artifact bin = getBinArtifact("x_bin", topTarget);
    CommandAction binAction = (CommandAction) getGeneratingAction(bin);
    assertThat(binAction.getInputs()).contains(protosGroupLib);
  }

  protected void checkProtoBundlingDoesNotHappen(RuleType ruleType) throws Exception {
    scratch.file(
        "protos/BUILD",
        "proto_library(",
        "    name = 'protos',",
        "    srcs = ['data_a.proto'],",
        ")",
        "objc_proto_library(",
        "    name = 'objc_protos',",
        "    portable_proto_filters = ['filter_b.pbascii'],",
        "    deps = [':protos'],",
        ")");
    scratch.file(
        "libs/BUILD",
        "objc_library(",
        "    name = 'objc_lib',",
        "    srcs = ['a.m'],",
        "    deps = ['//protos:objc_protos']",
        ")");

    ruleType.scratchTarget(
        scratch,
        "deps", "['//libs:objc_lib']");

    ConfiguredTarget topTarget = getConfiguredTarget("//x:x");
    Artifact protoHeader = getBinArtifact("_generated_protos/x/protos/DataA.pbobjc.h", topTarget);
    CommandAction protoAction = (CommandAction) getGeneratingAction(protoHeader);
    assertThat(protoAction).isNull();
  }

  protected void checkProtoBundlingWithTargetsWithNoDeps(RuleType ruleType) throws Exception {
    scratch.file(
        "protos/BUILD",
        "proto_library(",
        "    name = 'protos_a',",
        "    srcs = ['data_a.proto'],",
        ")",
        "objc_proto_library(",
        "    name = 'objc_protos_a',",
        "    portable_proto_filters = ['filter_a.pbascii'],",
        "    deps = [':protos_a'],",
        ")");
    scratch.file(
        "libs/BUILD",
        "objc_library(",
        "    name = 'objc_lib',",
        "    srcs = ['a.m'],",
        "    deps = ['//protos:objc_protos_a', ':no_deps_target'],",
        ")",
        "objc_framework(",
        "    name = 'no_deps_target',",
        "    framework_imports = ['x.framework'],",
        ")");

    ruleType.scratchTarget(scratch, "deps", "['//libs:objc_lib']");

    ConfiguredTarget topTarget = getConfiguredTarget("//x:x");

    ConfiguredTarget libTarget =
        view.getPrerequisiteConfiguredTargetForTesting(
            reporter, topTarget, Label.parseAbsoluteUnchecked("//libs:objc_lib"), masterConfig);

    ObjcProtoProvider protoProvider = libTarget.get(ObjcProtoProvider.SKYLARK_CONSTRUCTOR);
    assertThat(protoProvider).isNotNull();
  }

  protected void checkFrameworkDepLinkFlags(RuleType ruleType,
      ExtraLinkArgs extraLinkArgs) throws Exception {
    scratch.file(
        "libs/BUILD",
        "objc_library(",
        "    name = 'objc_lib',",
        "    srcs = ['a.m'],",
        "    deps = [':my_framework'],",
        ")",
        "objc_framework(",
        "    name = 'my_framework',",
        "    framework_imports = ['buzzbuzz.framework'],",
        ")");

    ruleType.scratchTarget(scratch, "deps", "['//libs:objc_lib']");

    CommandAction linkAction = linkAction("//x:x");
    Artifact binArtifact = getFirstArtifactEndingWith(linkAction.getOutputs(), "x_bin");
    Artifact objList = getFirstArtifactEndingWith(linkAction.getInputs(), "x-linker.objlist");

    verifyLinkAction(
        binArtifact,
        objList,
        "x86_64",
        ImmutableList.of("x/libx.a", "libobjc_lib.a"),
        ImmutableList.of(PathFragment.create("libs/buzzbuzz")),
        extraLinkArgs);
  }

  protected void checkBundleLoaderIsCorrectlyPassedToTheLinker(RuleType ruleType) throws Exception {
    scratch.file("bin/BUILD",
        "objc_library(",
        "    name = 'lib',",
        "    srcs = ['a.m'],",
        ")",
        "apple_binary(",
        "    name = 'bin',",
        "    deps = [':lib'],",
        "    platform_type = 'ios',",
        ")");

    ruleType.scratchTarget(scratch, "binary_type", "'loadable_bundle'", "bundle_loader",
        "'//bin:bin'");
    ConfiguredTarget binTarget = getConfiguredTarget("//bin:bin");

    CommandAction linkAction = linkAction("//x:x");
    assertThat(Joiner.on(" ").join(linkAction.getArguments()))
        .contains("-bundle_loader " + getBinArtifact("bin_lipobin", binTarget).getExecPath());
    assertThat(Joiner.on(" ").join(linkAction.getArguments()))
        .contains("-Xlinker -rpath -Xlinker @loader_path/Frameworks");
  }

  protected Action lipoLibAction(String libLabel) throws Exception {
    return actionProducingArtifact(libLabel, "_lipo.a");
  }

  protected Action lipoBinAction(String binLabel) throws Exception {
    return actionProducingArtifact(binLabel, "_lipobin");
  }

  protected CommandAction linkAction(String binLabel) throws Exception {
    CommandAction linkAction = (CommandAction) actionProducingArtifact(binLabel, "_bin");
    if (linkAction == null) {
      // For multi-architecture rules, the link action is not in the target configuration, but
      // across a configuration transition.
      Action lipoAction = lipoBinAction(binLabel);
      if (lipoAction != null) {
        Artifact binArtifact = getFirstArtifactEndingWith(lipoAction.getInputs(), "_bin");
        linkAction = (CommandAction) getGeneratingAction(binArtifact);
      }
    }
    return linkAction;
  }

  protected CommandAction linkLibAction(String libLabel) throws Exception {
    CommandAction linkAction = (CommandAction) actionProducingArtifact(libLabel, "-fl.a");

    if (linkAction == null) {
      // For multi-architecture rules, the link action is not in the target configuration, but
      // across a configuration transition.
      Action lipoAction = lipoLibAction(libLabel);
      if (lipoAction != null) {
        Artifact binArtifact = getFirstArtifactEndingWith(lipoAction.getInputs(), "-fl.a");
        linkAction = (CommandAction) getGeneratingAction(binArtifact);
      }
    }
    return linkAction;
  }

  protected Action actionProducingArtifact(String targetLabel,
      String artifactSuffix) throws Exception {
    ConfiguredTarget libraryTarget = getConfiguredTarget(targetLabel);
    Label parsedLabel = Label.parseAbsolute(targetLabel, ImmutableMap.of());
    Artifact linkedLibrary = getBinArtifact(
        parsedLabel.getName() + artifactSuffix,
        libraryTarget);
    return getGeneratingAction(linkedLibrary);
  }

  protected void addTargetWithAssetCatalogs(RuleType ruleType) throws Exception {
    scratch.file("x/foo.xcassets/foo");
    scratch.file("x/bar.xcassets/bar");
    ruleType.scratchTarget(scratch,
        "asset_catalogs", "['foo.xcassets/foo', 'bar.xcassets/bar']");
  }

  /**
   * Checks that a target at {@code //x:x}, which is already created, registered a correct actool
   * action based on the given targetDevice and platform, setting certain arbitrary and default
   * values.
   */
  protected void checkActoolActionCorrectness(DottedVersion minimumOsVersion, String targetDevice,
      String platform) throws Exception {
    Artifact actoolZipOut = getBinArtifact("x" + artifactName(".actool.zip"),
        getConfiguredTarget("//x:x"));
    Artifact actoolPartialInfoplist =
        getBinArtifact(
            "x" + artifactName(".actool-PartialInfo.plist"), getConfiguredTarget("//x:x"));
    SpawnAction actoolZipAction = (SpawnAction) getGeneratingAction(actoolZipOut);
    assertThat(actoolZipAction.getArguments())
        .containsExactly(
            MOCK_ACTOOLWRAPPER_PATH,
            actoolZipOut.getExecPathString(),
            "--platform", platform,
            "--output-partial-info-plist", actoolPartialInfoplist.getExecPathString(),
            "--minimum-deployment-target", minimumOsVersion.toString(),
            "--target-device", targetDevice,
            "x/foo.xcassets", "x/bar.xcassets")
        .inOrder();
    assertRequiresDarwin(actoolZipAction);

    assertThat(Artifact.toExecPaths(actoolZipAction.getInputs()))
        .containsExactly(
            "x/foo.xcassets/foo",
            "x/bar.xcassets/bar",
            MOCK_ACTOOLWRAPPER_PATH);
    assertThat(Artifact.toExecPaths(actoolZipAction.getOutputs()))
        .containsExactly(
            actoolZipOut.getExecPathString(),
            actoolPartialInfoplist.getExecPathString());
  }

  /**
   * Checks that a target at {@code //x:x}, which is already created, registered a correct actool
   * action based on certain arbitrary and default values for iphone simulator.
   */
  protected void checkActoolActionCorrectness(DottedVersion minimumOsVersion) throws Exception {
    checkActoolActionCorrectness(minimumOsVersion, "iphone", "iphonesimulator");
  }

  /**
   * Verifies that targeted device family information is passed to ibtool for the given targeted
   * families.
   *
   * @param packageName where to place the rule during testing - this should be different every time
   *     the method is invoked
   * @param buildFileContents contents of the BUILD file for the {@code packageName} package
   * @param targetDevices the values to {@code --target-device} expected in the ibtool invocation
   */
  private void checkPassesFamiliesToIbtool(String packageName, String buildFileContents,
      String... targetDevices) throws Exception {
    scratch.file(String.format("%s/BUILD", packageName), buildFileContents);
    ConfiguredTarget target = getConfiguredTarget(String.format("//%s:x", packageName));

    Artifact storyboardZipOut = getBinArtifact("x/foo.storyboard.zip", target);
    SpawnAction storyboardZipAction = (SpawnAction) getGeneratingAction(storyboardZipOut);

    List<String> arguments = storyboardZipAction.getArguments();
    for (String targetDevice : targetDevices) {
      assertContainsSublist(arguments, ImmutableList.of("--target-device", targetDevice));
    }

    assertWithMessage("Incorrect number of --target-device flags in arguments [" + arguments + "]")
        .that(Collections.frequency(arguments, "--target-device"))
        .isEqualTo(targetDevices.length);
  }

  private void checkPassesFamiliesToIbtool(RuleType ruleType, String packageName,
      String families, String... targetDevices) throws Exception {
    String buildFileContents = ruleType.target(scratch, packageName, "x",
        FAMILIES_ATTR, families,
        "storyboards", "['foo.storyboard']");
    checkPassesFamiliesToIbtool(packageName, buildFileContents, targetDevices);
  }

  protected void checkPassesFamiliesToIbtool(RuleType ruleType) throws Exception {
    checkPassesFamiliesToIbtool(ruleType, "iphone", "['iphone']", "iphone");
    checkPassesFamiliesToIbtool(ruleType, "ipad", "['ipad']", "ipad");
    checkPassesFamiliesToIbtool(ruleType, "both", "['iphone', 'ipad']",
        "ipad", "iphone");
    checkPassesFamiliesToIbtool(ruleType, "both_reverse", "['ipad', 'iphone']",
        "ipad", "iphone");
  }

  protected ConfiguredTarget createTargetWithStoryboards(RuleType ruleType) throws Exception {
    scratch.file("x/1.storyboard");
    scratch.file("x/2.storyboard");
    scratch.file("x/subdir_for_no_reason/en.lproj/loc.storyboard");
    scratch.file("x/ja.lproj/loc.storyboard");
    ruleType.scratchTarget(scratch, "storyboards", "glob(['*.storyboard', '**/*.storyboard'])");
    return getConfiguredTarget("//x:x");
  }


  protected void checkProvidesStoryboardObjects(RuleType ruleType) throws Exception {
    useConfiguration();
    createTargetWithStoryboards(ruleType);
    ObjcProvider provider = providerForTarget("//x:x");
    ImmutableList<Artifact> storyboardInputs = ImmutableList.of(
        getSourceArtifact("x/1.storyboard"),
        getSourceArtifact("x/2.storyboard"),
        getSourceArtifact("x/subdir_for_no_reason/en.lproj/loc.storyboard"),
        getSourceArtifact("x/ja.lproj/loc.storyboard"));

    assertThat(provider.get(STORYBOARD))
        .containsExactlyElementsIn(storyboardInputs);
  }

  protected void checkRegistersStoryboardCompileActions(RuleType ruleType,
      String platformName) throws Exception {
    checkRegistersStoryboardCompileActions(
        createTargetWithStoryboards(ruleType), DEFAULT_IOS_SDK_VERSION,
        ImmutableList.of(platformName));
  }

  private void checkRegistersStoryboardCompileActions(
      ConfiguredTarget target, DottedVersion minimumOsVersion, ImmutableList<String> targetDevices)
      throws Exception {
    Artifact storyboardZip = getBinArtifact("x/1.storyboard.zip", target);
    CommandAction compileAction = (CommandAction) getGeneratingAction(storyboardZip);
    assertThat(Artifact.toExecPaths(compileAction.getInputs()))
        .containsExactly(MOCK_IBTOOLWRAPPER_PATH, "x/1.storyboard");
    String archiveRoot = targetDevices.contains("watch") ? "." : "1.storyboardc";
    assertThat(compileAction.getOutputs()).containsExactly(storyboardZip);
    assertThat(compileAction.getArguments())
        .containsExactlyElementsIn(
            new CustomCommandLine.Builder()
                .addDynamicString(MOCK_IBTOOLWRAPPER_PATH)
                .addExecPath(storyboardZip)
                .addDynamicString(archiveRoot) // archive root
                .add("--minimum-deployment-target", minimumOsVersion.toString())
                .add("--module")
                .add("x")
                .addAll(VectorArg.addBefore("--target-device").each(targetDevices))
                .add("x/1.storyboard")
                .build()
                .arguments())
        .inOrder();

    storyboardZip = getBinArtifact("x/ja.lproj/loc.storyboard.zip", target);
    compileAction = (CommandAction) getGeneratingAction(storyboardZip);
    assertThat(Artifact.toExecPaths(compileAction.getInputs()))
        .containsExactly(MOCK_IBTOOLWRAPPER_PATH, "x/ja.lproj/loc.storyboard");
    assertThat(compileAction.getOutputs()).containsExactly(storyboardZip);
    archiveRoot = targetDevices.contains("watch") ? "ja.lproj/" : "ja.lproj/loc.storyboardc";
    assertThat(compileAction.getArguments())
        .containsExactlyElementsIn(
            new CustomCommandLine.Builder()
                .addDynamicString(MOCK_IBTOOLWRAPPER_PATH)
                .addExecPath(storyboardZip)
                .addDynamicString(archiveRoot) // archive root
                .add("--minimum-deployment-target", minimumOsVersion.toString())
                .add("--module")
                .add("x")
                .addAll(VectorArg.addBefore("--target-device").each(targetDevices))
                .add("x/ja.lproj/loc.storyboard")
                .build()
                .arguments())
        .inOrder();
  }

  protected List<String> rootedIncludePaths(
      BuildConfiguration configuration, String... unrootedPaths) {
    ImmutableList.Builder<String> rootedPaths = new ImmutableList.Builder<>();
    for (String unrootedPath : unrootedPaths) {
      rootedPaths.add(unrootedPath)
          .add(configuration.getGenfilesFragment().getRelative(unrootedPath).getSafePathString());
    }
    return rootedPaths.build();
  }

  protected void checkErrorsWrongFileTypeForSrcsWhenCompiling(RuleType ruleType)
      throws Exception {
    scratch.file("fg/BUILD",
        "filegroup(",
        "    name = 'fg',",
        "    srcs = ['non_matching', 'matchingh.h', 'matchingc.c'],",
        ")");
    checkError("x1", "x1",
        "does not match expected type: " + SRCS_TYPE,
        ruleType.target(scratch, "x1", "x1",
            "srcs", "['//fg:fg']"));
  }

  protected void checkClangCoptsForCompilationMode(RuleType ruleType, CompilationMode mode,
      CodeCoverageMode codeCoverageMode) throws Exception {
    ImmutableList.Builder<String> allExpectedCoptsBuilder = ImmutableList.<String>builder()
        .addAll(CompilationSupport.DEFAULT_COMPILER_FLAGS)
        .addAll(compilationModeCopts(mode));

    switch (codeCoverageMode) {
      case NONE:
        useConfiguration("--compilation_mode=" + compilationModeFlag(mode));
        break;
      case GCOV:
        allExpectedCoptsBuilder.addAll(CompilationSupport.CLANG_GCOV_COVERAGE_FLAGS);
        useConfiguration("--collect_code_coverage",
            "--compilation_mode=" + compilationModeFlag(mode));
        break;
      case LLVMCOV:
        allExpectedCoptsBuilder.addAll(CompilationSupport.CLANG_LLVM_COVERAGE_FLAGS);
        useConfiguration("--collect_code_coverage", "--experimental_use_llvm_covmap",
            "--compilation_mode=" + compilationModeFlag(mode));
        break;
    }
    scratch.file("x/a.m");
    ruleType.scratchTarget(scratch,
        "srcs", "['a.m']");

    CommandAction compileActionA = compileAction("//x:x", "a.o");

    assertThat(compileActionA.getArguments())
        .containsAllIn(allExpectedCoptsBuilder.build());
  }

  protected void checkClangCoptsForDebugModeWithoutGlib(RuleType ruleType) throws Exception {
     ImmutableList.Builder<String> allExpectedCoptsBuilder = ImmutableList.<String>builder()
        .addAll(CompilationSupport.DEFAULT_COMPILER_FLAGS)
        .addAll(ObjcConfiguration.DBG_COPTS);

    useConfiguration("--compilation_mode=dbg", "--objc_debug_with_GLIBCXX=false");
    scratch.file("x/a.m");
    ruleType.scratchTarget(scratch,
        "srcs", "['a.m']");

    CommandAction compileActionA = compileAction("//x:x", "a.o");

    assertThat(compileActionA.getArguments())
        .containsAllIn(allExpectedCoptsBuilder.build()).inOrder();

  }

  private void addTransitiveDefinesUsage(RuleType topLevelRuleType) throws Exception {
    createLibraryTargetWriter("//lib1:lib1")
        .setAndCreateFiles("srcs", "a.m")
        .setList("defines", "A=foo", "B")
        .write();
    createLibraryTargetWriter("//lib2:lib2")
        .setAndCreateFiles("srcs", "a.m")
        .setList("deps", "//lib1:lib1")
        .setList("defines", "C=bar", "D")
        .write();

    topLevelRuleType.scratchTarget(scratch,
        "srcs", "['a.m']",
        "non_arc_srcs", "['b.m']",
        "deps", "['//lib2:lib2']",
        "defines", "['E=baz']",
        "copts", "['explicit_copt']");
  }

  protected void checkReceivesTransitivelyPropagatedDefines(RuleType ruleType) throws Exception {
    addTransitiveDefinesUsage(ruleType);
    List<String> expectedArgs =
        ImmutableList.of("-DA=foo", "-DB", "-DC=bar", "-DD", "-DE=baz", "explicit_copt");
    List<String> compileActionAArgs = compileAction("//x:x", "a.o").getArguments();
    List<String> compileActionBArgs = compileAction("//x:x", "b.o").getArguments();
    for (String expectedArg : expectedArgs) {
      assertThat(compileActionAArgs).contains(expectedArg);
      assertThat(compileActionBArgs).contains(expectedArg);
    }
  }

  protected void checkDefinesFromCcLibraryDep(RuleType ruleType) throws Exception {
    useConfiguration();
    ScratchAttributeWriter.fromLabelString(this, "cc_library", "//dep:lib")
        .setList("srcs", "a.cc")
        .setList("defines", "foo", "bar")
        .write();

    ScratchAttributeWriter.fromLabelString(this, ruleType.getRuleTypeName(), "//objc:x")
        .setList("srcs", "a.m")
        .setList("deps", "//dep:lib")
        .write();

    CommandAction compileAction = compileAction("//objc:x", "a.o");
    assertThat(compileAction.getArguments()).containsAllOf("-Dfoo", "-Dbar");
  }

  protected void checkSdkIncludesUsedInCompileAction(RuleType ruleType) throws Exception {
    ruleType.scratchTarget(scratch,
        "sdk_includes", "['foo', 'bar/baz']",
        "srcs", "['a.m', 'b.m']");
    String sdkIncludeDir = AppleToolchain.sdkDir() + "/usr/include";
    // we remove spaces, since the legacy rules put a space after "-I" in include paths.
    String compileActionACommandLine =
        Joiner.on(" ").join(compileAction("//x:x", "a.o").getArguments()).replace(" ", "");
    assertThat(compileActionACommandLine).contains("-I" + sdkIncludeDir + "/foo");
    assertThat(compileActionACommandLine).contains("-I" + sdkIncludeDir + "/bar/baz");

    String compileActionBCommandLine =
        Joiner.on(" ").join(compileAction("//x:x", "b.o").getArguments()).replace(" ", "");
    assertThat(compileActionBCommandLine).contains("-I" + sdkIncludeDir + "/foo");
    assertThat(compileActionBCommandLine).contains("-I" + sdkIncludeDir + "/bar/baz");
  }

  protected void checkSdkIncludesUsedInCompileActionsOfDependers(RuleType ruleType)
      throws Exception {
    ruleType.scratchTarget(scratch, "sdk_includes", "['foo', 'bar/baz']");
    // Add some dependers (including transitive depender //bin:bin) and make sure they use the flags
    // as well.
    createLibraryTargetWriter("//lib:lib")
        .setAndCreateFiles("srcs", "a.m")
        .setList("deps", "//x:x")
        .setList("sdk_includes", "from_lib")
        .write();
    createLibraryTargetWriter("//bin:main_lib")
        .setAndCreateFiles("srcs", "b.m")
        .setList("deps", "//lib:lib")
        .setList("sdk_includes", "from_bin")
        .write();
    String sdkIncludeDir = AppleToolchain.sdkDir() + "/usr/include";

    // We remove spaces because the crosstool case does not use spaces for include paths.
    String compileAArgs = Joiner.on("")
        .join(compileAction("//lib:lib", "a.o").getArguments())
        .replace(" ", "");
    assertThat(compileAArgs).contains("-I" + sdkIncludeDir + "/from_lib");
    assertThat(compileAArgs).contains("-I" + sdkIncludeDir + "/foo");
    assertThat(compileAArgs).contains("-I" + sdkIncludeDir + "/bar/baz");

    String compileBArgs = Joiner.on("")
        .join(compileAction("//bin:main_lib", "b.o").getArguments())
        .replace(" ", "");
    assertThat(compileBArgs).contains("-I" + sdkIncludeDir + "/from_bin");
    assertThat(compileBArgs).contains("-I" + sdkIncludeDir + "/from_lib");
    assertThat(compileBArgs).contains("-I" + sdkIncludeDir + "/foo");
    assertThat(compileBArgs).contains("-I" + sdkIncludeDir + "/bar/baz");
  }

  protected void checkCompileXibActions(RuleType ruleType) throws Exception {
    ruleType.scratchTarget(scratch, "xibs", "['foo.xib', 'es.lproj/bar.xib']");
    checkCompileXibActions(DEFAULT_IOS_SDK_VERSION, "iphone");
  }

  private void checkCompileXibActions(DottedVersion minimumOsVersion,
      String platformType) throws Exception {
    scratch.file("x/foo.xib");
    scratch.file("x/es.lproj/bar.xib");
    ConfiguredTarget target = getConfiguredTarget("//x:x");
    Artifact fooNibZip = getBinArtifact("x/x/foo.nib.zip", target);
    Artifact barNibZip = getBinArtifact("x/x/es.lproj/bar.nib.zip", target);
    CommandAction fooCompile = (CommandAction) getGeneratingAction(fooNibZip);
    CommandAction barCompile = (CommandAction) getGeneratingAction(barNibZip);

    assertThat(Artifact.toExecPaths(fooCompile.getInputs()))
        .containsExactly(MOCK_IBTOOLWRAPPER_PATH, "x/foo.xib");
    assertThat(Artifact.toExecPaths(barCompile.getInputs()))
        .containsExactly(MOCK_IBTOOLWRAPPER_PATH, "x/es.lproj/bar.xib");

    assertThat(fooCompile.getArguments())
        .containsExactly(
            MOCK_IBTOOLWRAPPER_PATH,
            fooNibZip.getExecPathString(),
            "foo.nib", // archive root
            "--minimum-deployment-target", minimumOsVersion.toString(),
            "--module", "x",
            "--target-device", platformType,
            "x/foo.xib")
        .inOrder();
    assertThat(barCompile.getArguments())
        .containsExactly(
            MOCK_IBTOOLWRAPPER_PATH,
            barNibZip.getExecPathString(),
            "es.lproj/bar.nib", // archive root
            "--minimum-deployment-target", minimumOsVersion.toString(),
            "--module", "x",
            "--target-device", platformType,
            "x/es.lproj/bar.xib")
        .inOrder();
  }

  public void checkAllowVariousNonBlacklistedTypesInHeaders(RuleType ruleType) throws Exception {
    ruleType.scratchTarget(scratch, "hdrs", "['foo.foo', 'NoExtension', 'bar.inc', 'baz.hpp']");
    assertThat(view.hasErrors(getConfiguredTarget("//x:x"))).isFalse();
  }

  public void checkWarningForBlacklistedTypesInHeaders(RuleType ruleType) throws Exception {
    checkWarning("x1", "x1",
        "file 'foo.a' from target '//x1:foo.a' is not allowed in hdrs",
        ruleType.target(scratch, "x1", "x1", "hdrs", "['foo.a']"));
    checkWarning("x2", "x2",
        "file 'bar.o' from target '//x2:bar.o' is not allowed in hdrs",
        ruleType.target(scratch, "x2", "x2", "hdrs", "['bar.o']"));
  }

  protected void checkTwoStringsOneBundlePath(RuleType ruleType) throws Exception {
    String targets = ruleType.target(scratch, "x", "bndl",
        "strings", "['Resources/en.lproj/foo.strings', 'FooBar/en.lproj/foo.strings']");
    checkTwoStringsOneBundlePath(targets, "bndl");
  }

  private void checkTwoStringsOneBundlePath(String targets, String errorTarget) throws Exception {
    checkError(
        "x",
        errorTarget,
        "Two files map to the same path [en.lproj/foo.strings] in this bundle but come from "
            + "different locations: //x:Resources/en.lproj/foo.strings and "
            + "//x:FooBar/en.lproj/foo.strings",
        targets);
  }

  protected void checkTwoResourcesOneBundlePath(RuleType ruleType) throws Exception {
    String targets = ruleType.target(scratch, "x", "bndl", "resources", "['baz/foo', 'bar/foo']");
    checkTwoResourcesOneBundlePath(targets, "bndl");
  }

  private void checkTwoResourcesOneBundlePath(String targets, String errorTarget) throws Exception {
    checkError(
        "x",
        errorTarget,
        "Two files map to the same path [foo] in this bundle but come from "
            + "different locations: //x:baz/foo and //x:bar/foo",
        targets);
  }

  protected void checkSameStringsTwice(RuleType ruleType) throws Exception {
    String targets =
        ruleType.target(
            scratch,
            "x",
            "bndl",
            "resources",
            "['Resources/en.lproj/foo.strings']",
            "strings",
            "['Resources/en.lproj/foo.strings']");
    checkSameStringsTwice(targets, "bndl");
  }

  private void checkSameStringsTwice(String targets, String errorTarget) throws Exception {
    checkError(
        "x",
        errorTarget,
        "The same file was included multiple times in this rule: x/Resources/en.lproj/foo.strings",
        targets);
  }

  protected void checkMultipleInfoPlists(RuleType ruleType) throws Exception {
    scratch.file("x/a.plist");
    scratch.file("x/b.plist");
    ruleType.scratchTarget(scratch, "infoplists", "['a.plist', 'b.plist']");

    String targetName = "//x:x";
    PlMergeProtos.Control control = plMergeControl(targetName);

    assertThat(control.getSourceFileList())
        .contains(getSourceArtifact("x/a.plist").getExecPathString());
    assertThat(control.getSourceFileList())
        .contains(getSourceArtifact("x/b.plist").getExecPathString());
  }

  protected void checkInfoplistAndInfoplistsTogether(RuleType ruleType) throws Exception {
    scratch.file("x/a.plist");
    scratch.file("x/b.plist");
    scratch.file("x/c.plist");
    ruleType.scratchTarget(scratch, "infoplists", "['a.plist', 'b.plist']", INFOPLIST_ATTR,
        "'c.plist'");

    String targetName = "//x:x";
    PlMergeProtos.Control control = plMergeControl(targetName);

    assertThat(control.getSourceFileList())
        .contains(getSourceArtifact("x/a.plist").getExecPathString());
    assertThat(control.getSourceFileList())
        .contains(getSourceArtifact("x/b.plist").getExecPathString());
    assertThat(control.getSourceFileList())
        .contains(getSourceArtifact("x/c.plist").getExecPathString());
  }

  private BinaryFileWriteAction plMergeAction(String binaryLabelString) throws Exception {
    Label binaryLabel = Label.parseAbsolute(binaryLabelString, ImmutableMap.of());
    ConfiguredTarget binary = getConfiguredTarget(binaryLabelString);
    return (BinaryFileWriteAction)
        getGeneratingAction(getBinArtifact(binaryLabel.getName()
            + artifactName(".plmerge-control"), binary));
  }

  protected PlMergeProtos.Control plMergeControl(String binaryLabelString) throws Exception {
    InputStream in = plMergeAction(binaryLabelString).getSource().openStream();
    return PlMergeProtos.Control.parseFrom(in);
  }

  private String artifactName(String artifactName) {
    return artifactName;
  }

  /**
   * Normalizes arguments to a bash action into a space-separated list.
   *
   * <p>Bash actions' arguments have two parts, the bash invocation ({@code "/bin/bash", "-c"}) and
   * the command executed in the bash shell, as a single string. This method merges all these
   * arguments and splits them on {@code ' '}.
   */
  protected List<String> normalizeBashArgs(List<String> args) {
    return Splitter.on(' ').splitToList(Joiner.on(' ').join(args));
  }

  /** Returns the directory where objc modules will be cached. */
  protected String getModulesCachePath() throws InterruptedException {
    return getAppleCrosstoolConfiguration().getGenfilesFragment()
        + "/"
        + CompilationSupport.OBJC_MODULE_CACHE_DIR_NAME;
  }

  /**
   * Verifies that the given rule supports the minimum_os attribute, and adds compile and link
   * args to set the minimum os appropriately, including compile args for dependencies.
   *
   * @param ruleType the rule to test
   */
  protected void checkMinimumOsLinkAndCompileArg(RuleType ruleType) throws Exception {
    ruleType.scratchTarget(scratch,
        "deps", "['//package:objcLib']",
        "minimum_os_version", "'5.4'");
    scratch.file("package/BUILD",
        "objc_library(name = 'objcLib', srcs = [ 'b.m' ])");
    useConfiguration("--xcode_version=5.8");

    CommandAction linkAction = linkAction("//x:x");
    CommandAction objcLibArchiveAction = (CommandAction) getGeneratingAction(
        getFirstArtifactEndingWith(linkAction.getInputs(), "libobjcLib.a"));
    CommandAction objcLibCompileAction = (CommandAction) getGeneratingAction(
        getFirstArtifactEndingWith(objcLibArchiveAction.getInputs(), "b.o"));

    String linkArgs = Joiner.on(" ").join(linkAction.getArguments());
    String compileArgs = Joiner.on(" ").join(objcLibCompileAction.getArguments());
    assertThat(linkArgs).contains("-mios-simulator-version-min=5.4");
    assertThat(compileArgs).contains("-mios-simulator-version-min=5.4");
  }

  /**
   * Verifies that the given rule supports the minimum_os attribute under the watchOS platform
   * type, and adds compile and link args to set the minimum os appropriately for watchos,
   * including compile args for dependencies.
   *
   * @param ruleType the rule to test
   */
  protected void checkMinimumOsLinkAndCompileArg_watchos(RuleType ruleType) throws Exception {
    ruleType.scratchTarget(scratch,
        "deps", "['//package:objcLib']",
        "platform_type", "'watchos'",
        "minimum_os_version", "'5.4'");
    scratch.file("package/BUILD",
        "objc_library(name = 'objcLib', srcs = [ 'b.m' ])");
    useConfiguration("--xcode_version=5.8");

    CommandAction linkAction = linkAction("//x:x");
    CommandAction objcLibArchiveAction = (CommandAction) getGeneratingAction(
        getFirstArtifactEndingWith(linkAction.getInputs(), "libobjcLib.a"));
    CommandAction objcLibCompileAction = (CommandAction) getGeneratingAction(
        getFirstArtifactEndingWith(objcLibArchiveAction.getInputs(), "b.o"));

    String linkArgs = Joiner.on(" ").join(linkAction.getArguments());
    String compileArgs = Joiner.on(" ").join(objcLibCompileAction.getArguments());
    assertThat(linkArgs).contains("-mwatchos-simulator-version-min=5.4");
    assertThat(compileArgs).contains("-mwatchos-simulator-version-min=5.4");
  }

  /**
   * Verifies that the given rule throws a sensible error if the minimum_os attribute has a bad
   * value.
   */
  protected void checkMinimumOs_invalid_nonVersion(RuleType ruleType) throws Exception {
    checkError("x", "x",
        String.format(
            MultiArchSplitTransitionProvider.INVALID_VERSION_STRING_ERROR_FORMAT,
            "foobar"),
        ruleType.target(scratch, "x", "x", "minimum_os_version", "'foobar'"));
  }

  /**
   * Verifies that the given rule throws a sensible error if the minimum_os attribute has a bad
   * value.
   */
  protected void checkMinimumOs_invalid_containsAlphabetic(RuleType ruleType) throws Exception {
    checkError("x", "x",
        String.format(
            MultiArchSplitTransitionProvider.INVALID_VERSION_STRING_ERROR_FORMAT,
            "4.3alpha"),
        ruleType.target(scratch, "x", "x", "minimum_os_version", "'4.3alpha'"));
  }

  /**
   * Verifies that the given rule throws a sensible error if the minimum_os attribute has a bad
   * value.
   */
  protected void checkMinimumOs_invalid_tooManyComponents(RuleType ruleType) throws Exception {
    checkError("x", "x",
        String.format(
            MultiArchSplitTransitionProvider.INVALID_VERSION_STRING_ERROR_FORMAT,
            "4.3.1"),
        ruleType.target(scratch, "x", "x", "minimum_os_version", "'4.3.1'"));
  }

  protected void checkDylibDependencies(RuleType ruleType,
      ExtraLinkArgs extraLinkArgs) throws Exception {
    ruleType.scratchTarget(scratch,
        "dylibs", "['//fx:framework_import']");

    scratch.file("fx/MyFramework.framework/MyFramework");
    scratch.file("fx/BUILD",
        "objc_framework(",
        "    name = 'framework_import',",
        "    framework_imports = glob(['MyFramework.framework/*']),",
        "    is_dynamic = 1,",
        ")");
    useConfiguration("--ios_multi_cpus=i386,x86_64");

    Action lipobinAction = lipoBinAction("//x:x");

    String i386Bin =
        configurationBin("i386", ConfigurationDistinguisher.APPLEBIN_IOS)
            + "x/x_bin";
    String i386Filelist =
        configurationBin("i386", ConfigurationDistinguisher.APPLEBIN_IOS)
            + "x/x-linker.objlist";
    String x8664Bin =
        configurationBin("x86_64", ConfigurationDistinguisher.APPLEBIN_IOS)
            + "x/x_bin";
    String x8664Filelist =
        configurationBin("x86_64", ConfigurationDistinguisher.APPLEBIN_IOS)
            + "x/x-linker.objlist";

    Artifact i386BinArtifact = getFirstArtifactEndingWith(lipobinAction.getInputs(), i386Bin);
    Artifact i386FilelistArtifact =
        getFirstArtifactEndingWith(getGeneratingAction(i386BinArtifact).getInputs(), i386Filelist);
    Artifact x8664BinArtifact = getFirstArtifactEndingWith(lipobinAction.getInputs(), x8664Bin);
    Artifact x8664FilelistArtifact =
        getFirstArtifactEndingWith(getGeneratingAction(x8664BinArtifact).getInputs(),
            x8664Filelist);

    ImmutableList<String> archiveNames =
        ImmutableList.of("x/libx.a", "lib1/liblib1.a", "lib2/liblib2.a");
    verifyLinkAction(i386BinArtifact, i386FilelistArtifact, "i386", archiveNames,
        ImmutableList.of(PathFragment.create("fx/MyFramework")), extraLinkArgs);
    verifyLinkAction(x8664BinArtifact, x8664FilelistArtifact,
        "x86_64", archiveNames,  ImmutableList.of(PathFragment.create("fx/MyFramework")),
        extraLinkArgs);
  }

  protected void checkLipoBinaryAction(RuleType ruleType) throws Exception {
    ruleType.scratchTarget(scratch);

    useConfiguration("--ios_multi_cpus=i386,x86_64");

    CommandAction action = (CommandAction) lipoBinAction("//x:x");
    String i386Bin =
        configurationBin("i386", ConfigurationDistinguisher.APPLEBIN_IOS) + "x/x_bin";
    String x8664Bin =
        configurationBin("x86_64", ConfigurationDistinguisher.APPLEBIN_IOS) + "x/x_bin";

    assertThat(Artifact.toExecPaths(action.getInputs()))
        .containsExactly(i386Bin, x8664Bin, MOCK_XCRUNWRAPPER_PATH,
            MOCK_XCRUNWRAPPER_EXECUTABLE_PATH);

    assertThat(action.getArguments())
        .containsExactly(MOCK_XCRUNWRAPPER_EXECUTABLE_PATH, LIPO,
            "-create", i386Bin, x8664Bin,
            "-o", execPathEndingWith(action.getOutputs(), "x_lipobin"))
        .inOrder();

    assertThat(Artifact.toRootRelativePaths(action.getOutputs()))
        .containsExactly("x/x_lipobin");
    assertRequiresDarwin(action);
  }

  protected void checkMultiarchCcDep(RuleType ruleType) throws Exception {
    ruleType.scratchTarget(scratch,
        "deps", "['//package:cclib']");
    scratch.file("package/BUILD",
        "cc_library(name = 'cclib', srcs = ['dep.c'])");

    useConfiguration("--ios_multi_cpus=i386,x86_64");

    Action appLipoAction = actionProducingArtifact("//x:x", "_lipobin");
    String i386Prefix =
        configurationBin("i386", ConfigurationDistinguisher.APPLEBIN_IOS);
    String x8664Prefix =
        configurationBin("x86_64", ConfigurationDistinguisher.APPLEBIN_IOS);

    CommandAction i386BinAction = (CommandAction) getGeneratingAction(
        getFirstArtifactEndingWith(appLipoAction.getInputs(), i386Prefix + "x/x_bin"));

    CommandAction x8664BinAction = (CommandAction) getGeneratingAction(
        getFirstArtifactEndingWith(appLipoAction.getInputs(), x8664Prefix + "x/x_bin"));

    verifyObjlist(i386BinAction, "package/libcclib.a");
    verifyObjlist(x8664BinAction, "package/libcclib.a");

    assertThat(Artifact.toExecPaths(i386BinAction.getInputs()))
        .containsAllOf(
            i386Prefix + "package/libcclib.a",
            i386Prefix + "x/x-linker.objlist");
    assertThat(Artifact.toExecPaths(x8664BinAction.getInputs()))
        .containsAllOf(
            x8664Prefix + "package/libcclib.a",
            x8664Prefix + "x/x-linker.objlist");
  }

  // Regression test for b/32310268.
  protected void checkAliasedLinkoptsThroughObjcLibrary(RuleType ruleType) throws Exception {
    useConfiguration("--cpu=ios_i386");

    scratch.file("bin/BUILD",
        "objc_library(",
        "    name = 'objclib',",
        "    srcs = ['objcdep.c'],",
        "    deps = ['cclib'],",
        ")",
        "alias(",
        "    name = 'cclib',",
        "    actual = 'cclib_real',",
        ")",
        "cc_library(",
        "    name = 'cclib_real',",
        "    srcs = ['ccdep.c'],",
        "    linkopts = ['-somelinkopt'],",
        ")");

    ruleType.scratchTarget(scratch,
        "deps", "['//bin:objclib']");

    // Frameworks should get placed together with no duplicates.
    assertThat(Joiner.on(" ").join(linkAction("//x").getArguments()))
        .contains("-somelinkopt");
  }

  protected void checkCcDependencyLinkoptsArePropagatedToLinkAction(
      RuleType ruleType) throws Exception {
    useConfiguration("--cpu=ios_i386");

    scratch.file("bin/BUILD",
        "cc_library(",
        "    name = 'cclib1',",
        "    srcs = ['dep1.c'],",
        "    linkopts = ['-framework F1', '-framework F2', '-Wl,--other-opt'],",
        ")",
        "cc_library(",
        "    name = 'cclib2',",
        "    srcs = ['dep2.c'],",
        "    linkopts = ['-another-opt', '-framework F2'],",
        "    deps = ['cclib1'],",
        ")",
        "cc_library(",
        "    name = 'cclib3',",
        "    srcs = ['dep2.c'],",
        "    linkopts = ['-one-more-opt', '-framework UIKit'],",
        "    deps = ['cclib1'],",
        ")");

    ruleType.scratchTarget(scratch,
        "deps", "['//bin:cclib2', '//bin:cclib3']");

    // Frameworks from the CROSSTOOL "apply_implicit_frameworks" feature should be present.
    assertThat(Joiner.on(" ").join(linkAction("//x").getArguments()))
        .contains("-framework Foundation -framework UIKit");
    // Frameworks included in linkopts by the user should get placed together with no duplicates.
    // (They may duplicate the ones inserted by the CROSSTOOL feature, but we don't test that here.)
    assertThat(Joiner.on(" ").join(linkAction("//x").getArguments()))
        .contains("-framework F2 -framework F1");
    // Linkopts should also be grouped together.
    assertThat(Joiner.on(" ").join(linkAction("//x").getArguments()))
        .contains("-another-opt -Wl,--other-opt -one-more-opt");
  }

  protected void checkObjcProviderLinkInputsInLinkAction(RuleType ruleType) throws Exception {
    useConfiguration("--cpu=ios_i386");

    scratch.file("bin/defs.bzl",
        "def _custom_rule_impl(ctx):",
        "  return struct(objc=apple_common.new_objc_provider(",
        "      link_inputs=depset(ctx.files.link_inputs)))",
        "custom_rule = rule(",
        "    _custom_rule_impl,",
        "    attrs={'link_inputs': attr.label_list(allow_files=True)},",
        ")");

    scratch.file("bin/input.txt");

    scratch.file("bin/BUILD",
        "load('//bin:defs.bzl', 'custom_rule')",
        "custom_rule(",
        "    name = 'custom',",
        "    link_inputs = ['input.txt'],",
        ")");

    ruleType.scratchTarget(scratch,
        "deps", "['//bin:custom']");

    Artifact inputFile = getSourceArtifact("bin/input.txt");
    assertThat(linkAction("//x").getInputs()).contains(inputFile);
  }

  protected void checkAppleSdkVersionEnv(RuleType ruleType) throws Exception {
    ruleType.scratchTarget(scratch);

    CommandAction action = linkAction("//x:x");

    assertAppleSdkVersionEnv(action);
  }

  protected void checkNonDefaultAppleSdkVersionEnv(RuleType ruleType) throws Exception {
    ruleType.scratchTarget(scratch);
    useConfiguration("--ios_sdk_version=8.1");

    CommandAction action = linkAction("//x:x");

    assertAppleSdkVersionEnv(action, "8.1");
  }

  protected void checkAppleSdkDefaultPlatformEnv(RuleType ruleType) throws Exception {
    ruleType.scratchTarget(scratch);
    CommandAction action = linkAction("//x:x");

    assertAppleSdkPlatformEnv(action, "iPhoneSimulator");
  }

  protected void checkAppleSdkIphoneosPlatformEnv(RuleType ruleType) throws Exception {
    ruleType.scratchTarget(scratch);
    useConfiguration(
        "--cpu=ios_arm64");

    CommandAction action = linkAction("//x:x");

    assertAppleSdkPlatformEnv(action, "iPhoneOS");
  }

  protected void checkAppleSdkWatchsimulatorPlatformEnv(RuleType ruleType) throws Exception {
    ruleType.scratchTarget(scratch,
        "platform_type", "'watchos'");
    useConfiguration("--watchos_cpus=i386");

    Action lipoAction = actionProducingArtifact("//x:x", "_lipobin");

    String i386Bin = configurationBin("i386", ConfigurationDistinguisher.APPLEBIN_WATCHOS)
        + "x/x_bin";
    Artifact binArtifact = getFirstArtifactEndingWith(lipoAction.getInputs(), i386Bin);
    CommandAction linkAction = (CommandAction) getGeneratingAction(binArtifact);

    assertAppleSdkPlatformEnv(linkAction, "WatchSimulator");
  }

  protected void checkAppleSdkWatchosPlatformEnv(RuleType ruleType) throws Exception {
    ruleType.scratchTarget(scratch,
        "platform_type", "'watchos'");
    useConfiguration("--watchos_cpus=armv7k");

    Action lipoAction = actionProducingArtifact("//x:x", "_lipobin");

    String armv7kBin =
        configurationBin("armv7k", ConfigurationDistinguisher.APPLEBIN_WATCHOS)
        + "x/x_bin";
    Artifact binArtifact = getFirstArtifactEndingWith(lipoAction.getInputs(), armv7kBin);
    CommandAction linkAction = (CommandAction) getGeneratingAction(binArtifact);

    assertAppleSdkPlatformEnv(linkAction, "WatchOS");
  }

  protected void checkAppleSdkTvsimulatorPlatformEnv(RuleType ruleType) throws Exception {
    ruleType.scratchTarget(scratch,
        "platform_type", "'tvos'");
    useConfiguration("--tvos_cpus=x86_64");

    CommandAction linkAction = linkAction("//x:x");

    assertAppleSdkPlatformEnv(linkAction, "AppleTVSimulator");
  }

  protected void checkAppleSdkTvosPlatformEnv(RuleType ruleType) throws Exception {
    ruleType.scratchTarget(scratch,
        "platform_type", "'tvos'");
    useConfiguration("--tvos_cpus=arm64");

    CommandAction linkAction = linkAction("//x:x");

    assertAppleSdkPlatformEnv(linkAction, "AppleTVOS");
  }

  protected void checkLinkMinimumOSVersion(String minOSVersionOption) throws Exception {
    CommandAction linkAction = linkAction("//x:x");

    assertThat(Joiner.on(" ").join(linkAction.getArguments())).contains(minOSVersionOption);
  }

  protected void checkWatchSimulatorDepCompile(RuleType ruleType) throws Exception {
    ruleType.scratchTarget(scratch,
        "deps", "['//package:objcLib']",
        "platform_type", "'watchos'");
    scratch.file("package/BUILD",
        "objc_library(name = 'objcLib', srcs = [ 'b.m' ])");

    Action lipoAction = actionProducingArtifact("//x:x", "_lipobin");

    String i386Bin = configurationBin("i386", ConfigurationDistinguisher.APPLEBIN_WATCHOS)
        + "x/x_bin";
    Artifact binArtifact = getFirstArtifactEndingWith(lipoAction.getInputs(), i386Bin);
    CommandAction linkAction = (CommandAction) getGeneratingAction(binArtifact);
    CommandAction objcLibCompileAction = (CommandAction) getGeneratingAction(
        getFirstArtifactEndingWith(linkAction.getInputs(), "libobjcLib.a"));

    assertAppleSdkPlatformEnv(objcLibCompileAction, "WatchSimulator");
    assertThat(objcLibCompileAction.getArguments()).containsAllOf("-arch_only", "i386").inOrder();
  }

  protected void checkWatchSimulatorLinkAction(RuleType ruleType) throws Exception {
    ruleType.scratchTarget(scratch,
        "deps", "['//package:objcLib']",
        "platform_type", "'watchos'");
    scratch.file("package/BUILD",
        "objc_library(name = 'objcLib', srcs = [ 'b.m' ])");

    // Tests that ios_multi_cpus and cpu are completely ignored.
    useConfiguration("--ios_multi_cpus=x86_64", "--cpu=ios_x86_64", "--watchos_cpus=i386");

    Action lipoAction = actionProducingArtifact("//x:x", "_lipobin");

    String i386Bin = configurationBin("i386", ConfigurationDistinguisher.APPLEBIN_WATCHOS)
        + "x/x_bin";
    Artifact binArtifact = getFirstArtifactEndingWith(lipoAction.getInputs(), i386Bin);
    CommandAction linkAction = (CommandAction) getGeneratingAction(binArtifact);

    assertAppleSdkPlatformEnv(linkAction, "WatchSimulator");
    assertThat(normalizeBashArgs(linkAction.getArguments()))
        .containsAllOf("-arch", "i386").inOrder();
  }

  protected void checkWatchSimulatorLipoAction(RuleType ruleType) throws Exception {
    ruleType.scratchTarget(scratch,
        "platform_type", "'watchos'");

    // Tests that ios_multi_cpus and cpu are completely ignored.
    useConfiguration("--ios_multi_cpus=x86_64", "--cpu=ios_x86_64", "--watchos_cpus=i386,armv7k");

    CommandAction action = (CommandAction) lipoBinAction("//x:x");
    String i386Bin = configurationBin("i386", ConfigurationDistinguisher.APPLEBIN_WATCHOS)
        + "x/x_bin";
    String armv7kBin = configurationBin("armv7k", ConfigurationDistinguisher.APPLEBIN_WATCHOS)
        + "x/x_bin";

    assertThat(Artifact.toExecPaths(action.getInputs()))
        .containsExactly(i386Bin, armv7kBin, MOCK_XCRUNWRAPPER_PATH,
            MOCK_XCRUNWRAPPER_EXECUTABLE_PATH);

    assertContainsSublist(action.getArguments(), ImmutableList.of(
        MOCK_XCRUNWRAPPER_EXECUTABLE_PATH, LIPO, "-create"));
    assertThat(action.getArguments()).containsAllOf(armv7kBin, i386Bin);
    assertContainsSublist(action.getArguments(), ImmutableList.of(
        "-o", execPathEndingWith(action.getOutputs(), "x_lipobin")));

    assertThat(Artifact.toRootRelativePaths(action.getOutputs()))
        .containsExactly("x/x_lipobin");
    assertAppleSdkPlatformEnv(action, "WatchOS");
    assertRequiresDarwin(action);
  }

  protected void checkXcodeVersionEnv(RuleType ruleType) throws Exception {
    ruleType.scratchTarget(scratch);
    useConfiguration("--xcode_version=5.8");

    CommandAction action = linkAction("//x:x");

    assertXcodeVersionEnv(action, "5.8");
  }

  public void checkLinkingRuleCanUseCrosstool_singleArch(RuleType ruleType) throws Exception {
    ruleType.scratchTarget(scratch);

    // If bin is indeed using the c++ backend, then its archive action should be a CppLinkAction.
    Action lipobinAction = lipoBinAction("//x:x");
    Artifact bin = getFirstArtifactEndingWith(lipobinAction.getInputs(), "_bin");
    Action linkAction = getGeneratingAction(bin);
    Artifact archive = getFirstArtifactEndingWith(linkAction.getInputs(), ".a");
    Action archiveAction = getGeneratingAction(archive);
    assertThat(archiveAction).isInstanceOf(CppLinkAction.class);
  }

  public void checkLinkingRuleCanUseCrosstool_multiArch(RuleType ruleType) throws Exception {
    useConfiguration("--ios_multi_cpus=i386,x86_64");
    ruleType.scratchTarget(scratch);

    // If bin is indeed using the c++ backend, then its archive action should be a CppLinkAction.
    Action lipobinAction = lipoBinAction("//x:x");
    Artifact bin = getFirstArtifactEndingWith(lipobinAction.getInputs(), "_bin");
    Action linkAction = getGeneratingAction(bin);
    Artifact archive = getFirstArtifactEndingWith(linkAction.getInputs(), ".a");
    Action archiveAction = getGeneratingAction(archive);
    assertThat(archiveAction).isInstanceOf(CppLinkAction.class);
  }

  protected void scratchFrameworkSkylarkStub(String bzlPath) throws Exception {
    PathFragment pathFragment = PathFragment.create(bzlPath);
    scratch.file(pathFragment.getParentDirectory() + "/BUILD");
    scratch.file(
        bzlPath,
        "def framework_stub_impl(ctx):",
        "  bin_provider = ctx.attr.binary[apple_common.AppleDylibBinary]",
        "  my_provider = apple_common.new_dynamic_framework_provider(",
        "      objc = bin_provider.objc,",
        "      binary = bin_provider.binary,",
        "      framework_files = depset([bin_provider.binary]),",
        "      framework_dirs = depset(['_frameworks/stubframework.framework']))",
        "  return struct(providers = [my_provider])",
        "framework_stub_rule = rule(",
        "    framework_stub_impl,",
        // Both 'binary' and 'deps' are needed because ObjcProtoAspect is applied transitively
        // along attribute 'deps' only.
        "    attrs = {'binary': attr.label(mandatory=True,",
        "                                  providers=[apple_common.AppleDylibBinary]),",
        "             'deps': attr.label_list(providers=[apple_common.AppleDylibBinary])},",
        "    fragments = ['apple', 'objc'],",
        ")");
  }

  private void assertAvoidDepsObjects(RuleType ruleType) throws Exception {
    /*
     * The target tree for ease of understanding:
     * x depends on "avoidLib" as a dylib and "objcLib" as a static dependency.
     *
     *               (    objcLib    )
     *              /              \
     *       (   avoidLib   )     (   baseLib   )
     *        /                    /           \
     * (avoidLibDep)              /            (baseLibDep)
     *        \                  /
     *        (   avoidLibDepTwo   )
     *
     * All libraries prefixed with "avoid" shouldn't be statically linked in the top level target.
     */
    ruleType.scratchTarget(scratch,
        "deps", "['//package:objcLib']",
        "dylibs", "['//package:avoidLib']");
    scratchFrameworkSkylarkStub("frameworkstub/framework_stub.bzl");
    scratch.file("package/BUILD",
        "load('//frameworkstub:framework_stub.bzl', 'framework_stub_rule')",
        "objc_library(name = 'objcLib', srcs = [ 'b.m' ],",
        "    deps = [':avoidLibDep', ':baseLib'])",
        "objc_library(name = 'baseLib', srcs = [ 'base.m' ],",
        "    deps = [':baseLibDep', ':avoidLibDepTwo'])",
        "objc_library(name = 'baseLibDep', srcs = [ 'basedep.m' ],",
        "    sdk_frameworks = ['BaseSDK'], resources = [':base.png'])",
        "framework_stub_rule(name = 'avoidLib', binary = ':avoidLibBinary')",
        "apple_binary(name = 'avoidLibBinary', binary_type = 'dylib',",
        "    platform_type = 'ios',",
        "    deps = [':avoidLibDep'])",
        "objc_library(name = 'avoidLibDep', srcs = [ 'd.m' ], deps = [':avoidLibDepTwo'])",
        "objc_library(name = 'avoidLibDepTwo', srcs = [ 'e.m' ],",
        "    sdk_frameworks = ['AvoidSDK'], resources = [':avoid.png'])");

    Action lipobinAction = lipoBinAction("//x:x");
    Artifact binArtifact = getFirstArtifactEndingWith(lipobinAction.getInputs(), "x/x_bin");

    Action action = getGeneratingAction(binArtifact);

    assertThat(getFirstArtifactEndingWith(action.getInputs(), "package/libobjcLib.a")).isNotNull();
    assertThat(getFirstArtifactEndingWith(action.getInputs(), "package/libbaseLib.a")).isNotNull();
    assertThat(getFirstArtifactEndingWith(action.getInputs(), "package/libbaseLibDep.a"))
        .isNotNull();
    assertThat(getFirstArtifactEndingWith(action.getInputs(), "package/libavoidLib.a")).isNull();
    assertThat(getFirstArtifactEndingWith(action.getInputs(), "package/libavoidLibDepTwo.a"))
        .isNull();
    assertThat(getFirstArtifactEndingWith(action.getInputs(), "package/libavoidLibDep.a")).isNull();
  }

  public void checkAvoidDepsObjectsWithCrosstool(RuleType ruleType) throws Exception {
    useConfiguration("--ios_multi_cpus=i386,x86_64");
    assertAvoidDepsObjects(ruleType);
  }

  public void checkAvoidDepsObjects(RuleType ruleType) throws Exception {
    useConfiguration("--ios_multi_cpus=i386,x86_64");
    assertAvoidDepsObjects(ruleType);
  }

  /**
   * Verifies that if apple_binary A depends on a dylib B1 which then depends on a dylib B2,
   * that the symbols from B2 are not present in A.
   */
  public void checkAvoidDepsThroughDylib(RuleType ruleType) throws Exception {
    ruleType.scratchTarget(scratch,
        "deps", "['//package:ObjcLib']",
        "dylibs", "['//package:dylib1']");
    scratchFrameworkSkylarkStub("frameworkstub/framework_stub.bzl");
    scratch.file("package/BUILD",
        "load('//frameworkstub:framework_stub.bzl', 'framework_stub_rule')",
        "objc_library(name = 'ObjcLib', srcs = [ 'ObjcLib.m' ],",
        "    deps = [':Dylib1Lib', ':Dylib2Lib'])",
        "objc_library(name = 'Dylib1Lib', srcs = [ 'Dylib1Lib.m' ])",
        "objc_library(name = 'Dylib2Lib', srcs = [ 'Dylib2Lib.m' ])",
        "framework_stub_rule(name = 'dylib1', binary = ':dylib1Binary')",
        "apple_binary(name = 'dylib1Binary', binary_type = 'dylib',",
        "    platform_type = 'ios',",
        "    deps = [':Dylib1Lib'], dylibs = ['//package:dylib2'])",
        "framework_stub_rule(name = 'dylib2', binary = ':dylib2Binary')",
        "apple_binary(name = 'dylib2Binary', binary_type = 'dylib',",
        "    platform_type = 'ios',",
        "    deps = [':Dylib2Lib'])",
        "apple_binary(name = 'alternate',",
        "    platform_type = 'ios',",
        "    deps = ['//package:ObjcLib'])");

    Action lipobinAction = lipoBinAction("//x:x");
    Artifact binArtifact = getFirstArtifactEndingWith(lipobinAction.getInputs(), "x/x_bin");

    Action linkAction = getGeneratingAction(binArtifact);

    assertThat(getFirstArtifactEndingWith(linkAction.getInputs(),
        "package/libObjcLib.a")).isNotNull();
    assertThat(getFirstArtifactEndingWith(linkAction.getInputs(),
        "package/libDylib1Lib.a")).isNull();
    assertThat(getFirstArtifactEndingWith(linkAction.getInputs(),
        "package/libDylib2Lib.a")).isNull();

    // Sanity check that the identical binary without dylibs would be fully linked.
    Action alternateLipobinAction = lipoBinAction("//package:alternate");
    Artifact alternateBinArtifact = getFirstArtifactEndingWith(alternateLipobinAction.getInputs(),
        "package/alternate_bin");
    Action alternateLinkAction = getGeneratingAction(alternateBinArtifact);

    assertThat(getFirstArtifactEndingWith(alternateLinkAction.getInputs(),
        "package/libObjcLib.a")).isNotNull();
    assertThat(getFirstArtifactEndingWith(alternateLinkAction.getInputs(),
        "package/libDylib1Lib.a")).isNotNull();
    assertThat(getFirstArtifactEndingWith(alternateLinkAction.getInputs(),
        "package/libDylib2Lib.a")).isNotNull();
  }

  /**
   * Tests that direct cc_library dependencies of a dylib (and their dependencies) are correctly
   * removed from the main binary.
   */
  // transitively avoided, even if it is not present in deps.
  public void checkAvoidDepsObjects_avoidViaCcLibrary(RuleType ruleType) throws Exception {
    ruleType.scratchTarget(scratch,
        "deps", "['//package:objcLib']",
        "dylibs", "['//package:avoidLib']");
    scratchFrameworkSkylarkStub("frameworkstub/framework_stub.bzl");
    scratch.file("package/BUILD",
        "load('//frameworkstub:framework_stub.bzl', 'framework_stub_rule')",
        "framework_stub_rule(name = 'avoidLib', binary = ':avoidLibBinary')",
        "apple_binary(name = 'avoidLibBinary', binary_type = 'dylib',",
        "    platform_type = 'ios',",
        "    deps = [':avoidCclib'])",
        "cc_library(name = 'avoidCclib', srcs = ['cclib.c'], deps = [':avoidObjcLib'])",
        "objc_library(name = 'objcLib', srcs = [ 'b.m' ], deps = [':avoidObjcLib'])",
        "objc_library(name = 'avoidObjcLib', srcs = [ 'c.m' ])");

    Action lipobinAction = actionProducingArtifact("//x:x", "_lipobin");
    Artifact binArtifact = getFirstArtifactEndingWith(lipobinAction.getInputs(), "x/x_bin");

    Action action = getGeneratingAction(binArtifact);

    assertThat(getFirstArtifactEndingWith(action.getInputs(), "package/libobjcLib.a")).isNotNull();
    assertThat(getFirstArtifactEndingWith(action.getInputs(), "package/libavoidObjcLib.a"))
        .isNull();
  }

  public void checkFilesToCompileOutputGroup(RuleType ruleType) throws Exception {
    ruleType.scratchTarget(scratch);
    ConfiguredTarget target = getConfiguredTarget("//x:x");
    assertThat(
            ActionsTestUtil.baseNamesOf(
                getOutputGroup(target, OutputGroupInfo.FILES_TO_COMPILE)))
        .isEqualTo("a.o");
  }

  protected void checkCustomModuleMapNotPropagatedByTargetUnderTest(
      RuleType ruleType) throws Exception {
    checkCustomModuleMap(ruleType, false);
  }

  protected void checkCustomModuleMapPropagatedByTargetUnderTest(
      RuleType ruleType) throws Exception {
    checkCustomModuleMap(ruleType, true);
  }

  private void checkCustomModuleMap(RuleType ruleType, boolean targetUnderTestShouldPropagate)
      throws Exception {
    useConfiguration(
        "--experimental_objc_enable_module_maps",
        "--incompatible_strict_objc_module_maps");
    ruleType.scratchTarget(scratch, "deps", "['//z:a']");
    scratch.file("z/a.m");
    scratch.file("z/a.h");
    scratch.file("z/b.m");
    scratch.file("z/b.h");
    scratch.file("y/module.modulemap", "module my_module_b { export *\n header b.h }");
    scratch.file(
        "z/BUILD",
        "objc_library(",
        "name = 'testModuleMap',",
        "hdrs = ['b.h'],",
        "srcs = ['b.m'],",
        "module_map = '//y:mm'",
        ")",
        "objc_library(",
        "name = 'a',",
        "hdrs = ['a.h'],",
        "srcs = ['a.m'],",
        "deps = [':testModuleMap']",
        ")");
    scratch.file("y/BUILD",
        "filegroup(",
            "name = 'mm',",
            "srcs = ['module.modulemap']",
        ")");

    CommandAction compileActionA = compileAction("//z:testModuleMap", "b.o");
    assertThat(compileActionA.getArguments()).doesNotContain("-fmodule-maps");
    assertThat(compileActionA.getArguments()).doesNotContain("-fmodule-name");

    String x8664Genfiles =
        configurationGenfiles("x86_64", ConfigurationDistinguisher.APPLE_CROSSTOOL, null);

    // The target with the module map should propagate it to its direct dependers...
    ObjcProvider provider = providerForTarget("//z:testModuleMap");
    assertThat(Artifact.toExecPaths(provider.get(MODULE_MAP)))
        .containsExactly("y/module.modulemap");

    // ...and the target depending on //z:testModuleMap will see it (as well as its own)...
    provider = providerForTarget("//z:a");
    assertThat(Artifact.toExecPaths(provider.get(MODULE_MAP)))
        .containsExactly(x8664Genfiles + "/z/a.modulemaps/module.modulemap", "y/module.modulemap");

    provider = providerForTarget("//x:x");
    if (targetUnderTestShouldPropagate) {
      // ...and //x:x should propagate //z:a but not //z:testModuleMap.
      assertThat(Artifact.toExecPaths(provider.get(MODULE_MAP)))
          .containsExactly(
              x8664Genfiles + "/x/x.modulemaps/module.modulemap",
              x8664Genfiles + "/z/a.modulemaps/module.modulemap");
    } else {
      // ...but //x:x should not see them.
      assertThat(Artifact.toExecPaths(provider.get(MODULE_MAP))).isEmpty();
    }
  }

  /**
   * Verifies that the given rule supports different minimum_os attribute values for two targets
   * in the same build, and adds compile args to set the minimum os appropriately for
   * dependencies of each.
   *
   * @param ruleType the rule to test
   * @param multiArchArtifactSuffix the suffix of the artifact that the rule-under-test produces
   * @param singleArchArtifactSuffix the suffix of the single-architecture artifact that is an
   *     input to the rule-under-test's generating action
   */
  protected void checkMinimumOsDifferentTargets(RuleType ruleType, String multiArchArtifactSuffix,
      String singleArchArtifactSuffix) throws Exception {
    ruleType.scratchTarget("nine", "nine", scratch,
        "deps", "['//package:objcLib']",
        "minimum_os_version", "'9.0'");
    ruleType.scratchTarget("eight", "eight", scratch,
        "deps", "['//package:objcLib']",
        "minimum_os_version", "'8.0'");
    scratch.file("package/BUILD",
        "genrule(name = 'root', srcs = ['//nine:nine', '//eight:eight'], outs = ['genout'],",
        "    cmd = 'touch genout')",
        "objc_library(name = 'objcLib', srcs = [ 'b.m' ])");

    ConfiguredTarget rootTarget = getConfiguredTarget("//package:root");
    Artifact rootArtifact = getGenfilesArtifact("genout", rootTarget);

    Action genruleAction = getGeneratingAction(rootArtifact);
    Action eightLipoAction = getGeneratingAction(
        getFirstArtifactEndingWith(genruleAction.getInputs(), "eight" + multiArchArtifactSuffix));
    Action nineLipoAction = getGeneratingAction(
        getFirstArtifactEndingWith(genruleAction.getInputs(), "nine" + multiArchArtifactSuffix));
    Artifact eightBin =
        getFirstArtifactEndingWith(eightLipoAction.getInputs(), singleArchArtifactSuffix);
    Artifact nineBin =
        getFirstArtifactEndingWith(nineLipoAction.getInputs(), singleArchArtifactSuffix);

    CommandAction eightLinkAction = (CommandAction) getGeneratingAction(eightBin);
    CommandAction nineLinkAction = (CommandAction) getGeneratingAction(nineBin);

    CommandAction eightObjcLibArchiveAction = (CommandAction) getGeneratingAction(
        getFirstArtifactEndingWith(eightLinkAction.getInputs(), "libobjcLib.a"));
    CommandAction eightObjcLibCompileAction = (CommandAction) getGeneratingAction(
        getFirstArtifactEndingWith(eightObjcLibArchiveAction.getInputs(), "b.o"));
    CommandAction nineObjcLibArchiveAction = (CommandAction) getGeneratingAction(
        getFirstArtifactEndingWith(nineLinkAction.getInputs(), "libobjcLib.a"));
    CommandAction nineObjcLibCompileAction = (CommandAction) getGeneratingAction(
        getFirstArtifactEndingWith(nineObjcLibArchiveAction.getInputs(), "b.o"));

    assertThat(Joiner.on(" ").join(eightObjcLibCompileAction.getArguments()))
        .contains("-mios-simulator-version-min=8.0");
    assertThat(Joiner.on(" ").join(nineObjcLibCompileAction.getArguments()))
        .contains("-mios-simulator-version-min=9.0");
  }

  protected void verifyDrops32BitArchitecture(RuleType ruleType) throws Exception {
    scratch.file("libs/BUILD",
        "objc_library(",
        "    name = 'objc_lib',",
        "    srcs = ['a.m'],",
        ")");

    ruleType.scratchTarget(
        scratch,
        "deps", "['//libs:objc_lib']",
        "platform_type", "'ios'",
        "minimum_os_version", "'11.0'"); // Does not support 32-bit architectures.

    useConfiguration("--ios_multi_cpus=armv7,arm64,i386,x86_64");

    Action lipoAction = actionProducingArtifact("//x:x", "_lipobin");

    getSingleArchBinary(lipoAction, "arm64");
    getSingleArchBinary(lipoAction, "x86_64");
    assertThat(getSingleArchBinaryIfAvailable(lipoAction, "armv7")).isNull();
    assertThat(getSingleArchBinaryIfAvailable(lipoAction, "i386")).isNull();
  }

  /**
   * Returns the full exec path string for exec paths of targets within the main tools repository.
   */
  protected static String toolsRepoExecPath(String execPath) {
    return TestConstants.TOOLS_REPOSITORY_PATH_PREFIX + execPath;
  }

  @Nullable
  protected Artifact getSingleArchBinaryIfAvailable(Action lipoAction, String arch) {
    for (Artifact archBinary : lipoAction.getInputs()) {
      String execPath = archBinary.getExecPathString();
      if (execPath.endsWith("_bin") && execPath.contains(arch)) {
        return archBinary;
      }
    }
    return null;
  }

  protected Artifact getSingleArchBinary(Action lipoAction, String arch) {
    Artifact result = getSingleArchBinaryIfAvailable(lipoAction, arch);
    if (result != null) {
      return result;
    } else {
      throw new AssertionError("Lipo action does not contain an input binary from arch " + arch);
    }
  }

  protected void scratchFeatureFlagTestLib() throws Exception {
    scratch.file(
        "lib/BUILD",
        "config_feature_flag(",
        "  name = 'flag1',",
        "  allowed_values = ['on', 'off'],",
        "  default_value = 'off',",
        ")",
        "config_setting(",
        "  name = 'flag1@on',",
        "  flag_values = {':flag1': 'on'},",
        "  transitive_configs = [':flag1'],",
        ")",
        "config_feature_flag(",
        "  name = 'flag2',",
        "  allowed_values = ['on', 'off'],",
        "  default_value = 'off',",
        ")",
        "config_setting(",
        "  name = 'flag2@on',",
        "  flag_values = {':flag2': 'on'},",
        "  transitive_configs = [':flag2'],",
        ")",
        "objc_library(",
        "  name = 'objcLib',",
        "  srcs = select({",
        "    ':flag1@on': ['flag1on.m'],",
        "    '//conditions:default': ['flag1off.m'],",
        "  }) + select({",
        "    ':flag2@on': ['flag2on.m'],",
        "    '//conditions:default': ['flag2off.m'],",
        "  }),",
        "  copts = select({",
        "    ':flag1@on': ['-FLAG_1_ON'],",
        "    '//conditions:default': ['-FLAG_1_OFF'],",
        "  }) + select({",
        "    ':flag2@on': ['-FLAG_2_ON'],",
        "    '//conditions:default': ['-FLAG_2_OFF'],",
        "  }),",
        "  transitive_configs = [':flag1', ':flag2'],",
        ")");
  }
}
