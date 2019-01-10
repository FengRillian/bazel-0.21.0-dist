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

package com.google.devtools.build.lib.repository;

import static com.google.common.truth.Truth.assertThat;
import static com.google.devtools.build.skyframe.EvaluationResultSubjectFactory.assertThatEvaluationResult;

import com.google.auto.value.AutoValue;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.actions.FileStateValue;
import com.google.devtools.build.lib.actions.FileValue;
import com.google.devtools.build.lib.analysis.BlazeDirectories;
import com.google.devtools.build.lib.analysis.ServerDirectories;
import com.google.devtools.build.lib.analysis.util.AnalysisMock;
import com.google.devtools.build.lib.analysis.util.BuildViewTestCase;
import com.google.devtools.build.lib.cmdline.PackageIdentifier;
import com.google.devtools.build.lib.events.NullEventHandler;
import com.google.devtools.build.lib.packages.PackageFactory;
import com.google.devtools.build.lib.packages.PackageFactory.EnvironmentExtension;
import com.google.devtools.build.lib.packages.Rule;
import com.google.devtools.build.lib.packages.RuleClassProvider;
import com.google.devtools.build.lib.pkgcache.PathPackageLocator;
import com.google.devtools.build.lib.rules.repository.RepositoryDelegatorFunction;
import com.google.devtools.build.lib.skyframe.BazelSkyframeExecutorConstants;
import com.google.devtools.build.lib.skyframe.ExternalFilesHelper;
import com.google.devtools.build.lib.skyframe.ExternalFilesHelper.ExternalFileAction;
import com.google.devtools.build.lib.skyframe.ExternalPackageFunction;
import com.google.devtools.build.lib.skyframe.FileFunction;
import com.google.devtools.build.lib.skyframe.FileStateFunction;
import com.google.devtools.build.lib.skyframe.LocalRepositoryLookupFunction;
import com.google.devtools.build.lib.skyframe.PackageFunction;
import com.google.devtools.build.lib.skyframe.PackageLookupFunction;
import com.google.devtools.build.lib.skyframe.PackageLookupFunction.CrossRepositoryLabelViolationStrategy;
import com.google.devtools.build.lib.skyframe.PrecomputedValue;
import com.google.devtools.build.lib.skyframe.RegisteredExecutionPlatformsFunction;
import com.google.devtools.build.lib.skyframe.RegisteredToolchainsFunction;
import com.google.devtools.build.lib.skyframe.SkyFunctions;
import com.google.devtools.build.lib.skyframe.SkyframeExecutor;
import com.google.devtools.build.lib.skyframe.WorkspaceASTFunction;
import com.google.devtools.build.lib.skyframe.WorkspaceFileFunction;
import com.google.devtools.build.lib.syntax.SkylarkSemantics;
import com.google.devtools.build.lib.util.io.TimestampGranularityMonitor;
import com.google.devtools.build.lib.vfs.Root;
import com.google.devtools.build.lib.vfs.RootedPath;
import com.google.devtools.build.skyframe.AbstractSkyKey;
import com.google.devtools.build.skyframe.EvaluationContext;
import com.google.devtools.build.skyframe.EvaluationResult;
import com.google.devtools.build.skyframe.InMemoryMemoizingEvaluator;
import com.google.devtools.build.skyframe.MemoizingEvaluator;
import com.google.devtools.build.skyframe.RecordingDifferencer;
import com.google.devtools.build.skyframe.SequencedRecordingDifferencer;
import com.google.devtools.build.skyframe.SequentialBuildDriver;
import com.google.devtools.build.skyframe.SkyFunction;
import com.google.devtools.build.skyframe.SkyFunctionException;
import com.google.devtools.build.skyframe.SkyFunctionName;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nullable;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link ExternalPackageUtil}. */
@RunWith(JUnit4.class)
public class ExternalPackageUtilTest extends BuildViewTestCase {
  private static final EvaluationContext EVALUATION_OPTIONS =
      EvaluationContext.newBuilder()
          .setKeepGoing(false)
          .setNumThreads(SkyframeExecutor.DEFAULT_THREAD_COUNT)
          .setEventHander(NullEventHandler.INSTANCE)
          .build();

  private SequentialBuildDriver driver;

  @Before
  public void createEnvironment() {
    AnalysisMock analysisMock = AnalysisMock.get();
    AtomicReference<PathPackageLocator> pkgLocator =
        new AtomicReference<>(
            new PathPackageLocator(
                outputBase,
                ImmutableList.of(Root.fromPath(rootDirectory)),
                BazelSkyframeExecutorConstants.BUILD_FILES_BY_PRIORITY));
    AtomicReference<ImmutableSet<PackageIdentifier>> deletedPackages =
        new AtomicReference<>(ImmutableSet.<PackageIdentifier>of());
    BlazeDirectories directories =
        new BlazeDirectories(
            new ServerDirectories(rootDirectory, outputBase, rootDirectory),
            rootDirectory,
            /* defaultSystemJavabase= */ null,
            analysisMock.getProductName());
    ExternalFilesHelper externalFilesHelper =
        ExternalFilesHelper.createForTesting(
            pkgLocator,
            ExternalFileAction.DEPEND_ON_EXTERNAL_PKG_FOR_EXTERNAL_REPO_PATHS,
            directories);

    Map<SkyFunctionName, SkyFunction> skyFunctions = new HashMap<>();
    skyFunctions.put(
        SkyFunctions.PACKAGE_LOOKUP,
        new PackageLookupFunction(
            deletedPackages,
            CrossRepositoryLabelViolationStrategy.ERROR,
            BazelSkyframeExecutorConstants.BUILD_FILES_BY_PRIORITY));
    skyFunctions.put(
        FileStateValue.FILE_STATE,
        new FileStateFunction(
            new AtomicReference<TimestampGranularityMonitor>(), externalFilesHelper));
    skyFunctions.put(FileValue.FILE, new FileFunction(pkgLocator));
    RuleClassProvider ruleClassProvider = analysisMock.createRuleClassProvider();
    skyFunctions.put(SkyFunctions.WORKSPACE_AST, new WorkspaceASTFunction(ruleClassProvider));
    skyFunctions.put(
        SkyFunctions.WORKSPACE_FILE,
        new WorkspaceFileFunction(
            ruleClassProvider,
            analysisMock
                .getPackageFactoryBuilderForTesting(directories)
                .setEnvironmentExtensions(
                    ImmutableList.<EnvironmentExtension>of(
                        new PackageFactory.EmptyEnvironmentExtension()))
                .build(ruleClassProvider),
            directories));
    skyFunctions.put(
        SkyFunctions.PACKAGE, new PackageFunction(null, null, null, null, null, null, null));
    skyFunctions.put(SkyFunctions.EXTERNAL_PACKAGE, new ExternalPackageFunction());
    skyFunctions.put(SkyFunctions.LOCAL_REPOSITORY_LOOKUP, new LocalRepositoryLookupFunction());

    // Helper Skyfunctions to call ExternalPackageUtil.
    skyFunctions.put(GET_RULE_BY_NAME_FUNCTION, new GetRuleByNameFunction());
    skyFunctions.put(
        GET_REGISTERED_EXECUTION_PLATFORMS_FUNCTION, new GetRegisteredExecutionPlatformsFunction());
    skyFunctions.put(GET_REGISTERED_TOOLCHAINS_FUNCTION, new GetRegisteredToolchainsFunction());

    RecordingDifferencer differencer = new SequencedRecordingDifferencer();
    MemoizingEvaluator evaluator = new InMemoryMemoizingEvaluator(skyFunctions, differencer);
    driver = new SequentialBuildDriver(evaluator);
    PrecomputedValue.PATH_PACKAGE_LOCATOR.set(differencer, pkgLocator.get());
    PrecomputedValue.SKYLARK_SEMANTICS.set(differencer, SkylarkSemantics.DEFAULT_SEMANTICS);
    RepositoryDelegatorFunction.RESOLVED_FILE_INSTEAD_OF_WORKSPACE.set(
        differencer, Optional.<RootedPath>absent());
  }

  @Test
  public void getRuleByName() throws Exception {
    if (!analysisMock.isThisBazel()) {
      return;
    }
    scratch.overwriteFile("WORKSPACE", "http_archive(name = 'foo', url = 'http://foo')");

    SkyKey key = getRuleByNameKey("foo");
    EvaluationResult<GetRuleByNameValue> result = getRuleByName(key);

    assertThatEvaluationResult(result).hasNoError();

    Rule rule = result.get(key).rule();
    assertThat(rule).isNotNull();
    assertThat(rule.getName()).isEqualTo("foo");
  }

  EvaluationResult<GetRuleByNameValue> getRuleByName(SkyKey key) throws InterruptedException {
    return driver.<GetRuleByNameValue>evaluate(ImmutableList.of(key), EVALUATION_OPTIONS);
  }

  @Test
  public void getRuleByName_missing() throws Exception {
    if (!analysisMock.isThisBazel()) {
      return;
    }
    scratch.overwriteFile("WORKSPACE", "http_archive(name = 'foo', url = 'http://foo')");

    SkyKey key = getRuleByNameKey("bar");
    EvaluationResult<GetRuleByNameValue> result = getRuleByName(key);

    assertThatEvaluationResult(result)
        .hasErrorEntryForKeyThat(key)
        .hasExceptionThat()
        .hasMessageThat()
        .contains("The rule named 'bar' could not be resolved");
  }

  @Test
  public void getRegisteredToolchains() throws Exception {
    scratch.overwriteFile(
        "WORKSPACE", "register_toolchains(", "  '//toolchain:tc1',", "  '//toolchain:tc2')");

    SkyKey key = getRegisteredToolchainsKey();
    EvaluationResult<GetRegisteredToolchainsValue> result = getRegisteredToolchains(key);

    assertThatEvaluationResult(result).hasNoError();

    assertThat(result.get(key).registeredToolchains())
        // There are default toolchains that are always registered, so just check for the ones added
        .containsAllOf("//toolchain:tc1", "//toolchain:tc2")
        .inOrder();
  }

  EvaluationResult<GetRegisteredToolchainsValue> getRegisteredToolchains(SkyKey key)
      throws InterruptedException {
    return driver.<GetRegisteredToolchainsValue>evaluate(ImmutableList.of(key), EVALUATION_OPTIONS);
  }

  @Test
  public void getRegisteredExecutionPlatforms() throws Exception {
    scratch.overwriteFile(
        "WORKSPACE", "register_execution_platforms(", "  '//platform:ep1',", "  '//platform:ep2')");

    SkyKey key = getRegisteredExecutionPlatformsKey();
    EvaluationResult<GetRegisteredExecutionPlatformsValue> result =
        getRegisteredExecutionPlatforms(key);

    assertThatEvaluationResult(result).hasNoError();

    assertThat(result.get(key).registeredExecutionPlatforms())
        .containsExactly("//platform:ep1", "//platform:ep2")
        .inOrder();
  }

  EvaluationResult<GetRegisteredExecutionPlatformsValue> getRegisteredExecutionPlatforms(SkyKey key)
      throws InterruptedException {
    return driver.<GetRegisteredExecutionPlatformsValue>evaluate(
        ImmutableList.of(key), EVALUATION_OPTIONS);
  }

  // HELPER SKYFUNCTIONS

  // GetRuleByName.
  private static SkyKey getRuleByNameKey(String ruleName) {
    return new Key(ruleName);
  }

  private static final SkyFunctionName GET_RULE_BY_NAME_FUNCTION =
      SkyFunctionName.createHermetic("GET_RULE_BY_NAME");

  @AutoValue
  abstract static class GetRuleByNameValue implements SkyValue {
    abstract Rule rule();

    static GetRuleByNameValue create(Rule rule) {
      return new AutoValue_ExternalPackageUtilTest_GetRuleByNameValue(rule);
    }
  }

  private static final class GetRuleByNameFunction implements SkyFunction {

    @Nullable
    @Override
    public SkyValue compute(SkyKey skyKey, Environment env)
        throws SkyFunctionException, InterruptedException {
      String ruleName = (String) skyKey.argument();

      Rule rule = ExternalPackageUtil.getRuleByName(ruleName, env);
      if (rule == null) {
        return null;
      }
      return GetRuleByNameValue.create(rule);
    }

    @Nullable
    @Override
    public String extractTag(SkyKey skyKey) {
      return null;
    }
  }

  // GetRegisteredToolchains.
  private static SkyKey getRegisteredToolchainsKey() {
    return () -> GET_REGISTERED_TOOLCHAINS_FUNCTION;
  }

  private static final SkyFunctionName GET_REGISTERED_TOOLCHAINS_FUNCTION =
      SkyFunctionName.createHermetic("GET_REGISTERED_TOOLCHAINS");

  @AutoValue
  abstract static class GetRegisteredToolchainsValue implements SkyValue {
    abstract ImmutableList<String> registeredToolchains();

    static GetRegisteredToolchainsValue create(Iterable<String> registeredToolchains) {
      return new AutoValue_ExternalPackageUtilTest_GetRegisteredToolchainsValue(
          ImmutableList.copyOf(registeredToolchains));
    }
  }

  private static final class GetRegisteredToolchainsFunction implements SkyFunction {

    @Nullable
    @Override
    public SkyValue compute(SkyKey skyKey, Environment env)
        throws SkyFunctionException, InterruptedException {
      List<String> registeredToolchains = RegisteredToolchainsFunction.getRegisteredToolchains(env);
      if (registeredToolchains == null) {
        return null;
      }
      return GetRegisteredToolchainsValue.create(registeredToolchains);
    }

    @Nullable
    @Override
    public String extractTag(SkyKey skyKey) {
      return null;
    }
  }

  // GetRegisteredExecutionPlatforms.
  private static SkyKey getRegisteredExecutionPlatformsKey() {
    return () -> GET_REGISTERED_EXECUTION_PLATFORMS_FUNCTION;
  }

  private static final SkyFunctionName GET_REGISTERED_EXECUTION_PLATFORMS_FUNCTION =
      SkyFunctionName.createHermetic("GET_REGISTERED_EXECUTION_PLATFORMS_FUNCTION");

  @AutoValue
  abstract static class GetRegisteredExecutionPlatformsValue implements SkyValue {
    abstract ImmutableList<String> registeredExecutionPlatforms();

    static GetRegisteredExecutionPlatformsValue create(
        Iterable<String> registeredExecutionPlatforms) {
      return new AutoValue_ExternalPackageUtilTest_GetRegisteredExecutionPlatformsValue(
          ImmutableList.copyOf(registeredExecutionPlatforms));
    }
  }

  private static final class GetRegisteredExecutionPlatformsFunction implements SkyFunction {

    @Nullable
    @Override
    public SkyValue compute(SkyKey skyKey, Environment env)
        throws SkyFunctionException, InterruptedException {
      List<String> registeredExecutionPlatforms =
          RegisteredExecutionPlatformsFunction.getWorkspaceExecutionPlatforms(env);
      if (registeredExecutionPlatforms == null) {
        return null;
      }
      return GetRegisteredExecutionPlatformsValue.create(registeredExecutionPlatforms);
    }

    @Nullable
    @Override
    public String extractTag(SkyKey skyKey) {
      return null;
    }
  }

  static class Key extends AbstractSkyKey<String> {
    private Key(String arg) {
      super(arg);
    }

    @Override
    public SkyFunctionName functionName() {
      return GET_RULE_BY_NAME_FUNCTION;
    }
  }
}
