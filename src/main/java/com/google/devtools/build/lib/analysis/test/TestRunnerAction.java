// Copyright 2014 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.analysis.test;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.devtools.build.lib.actions.AbstractAction;
import com.google.devtools.build.lib.actions.ActionExecutionContext;
import com.google.devtools.build.lib.actions.ActionExecutionException;
import com.google.devtools.build.lib.actions.ActionInput;
import com.google.devtools.build.lib.actions.ActionInputHelper;
import com.google.devtools.build.lib.actions.ActionKeyContext;
import com.google.devtools.build.lib.actions.ActionOwner;
import com.google.devtools.build.lib.actions.ActionResult;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.ArtifactPathResolver;
import com.google.devtools.build.lib.actions.CommandLineExpansionException;
import com.google.devtools.build.lib.actions.ExecException;
import com.google.devtools.build.lib.actions.ExecutionInfoSpecifier;
import com.google.devtools.build.lib.actions.NotifyOnActionCacheHit;
import com.google.devtools.build.lib.analysis.RunfilesSupplierImpl;
import com.google.devtools.build.lib.analysis.config.BuildConfiguration;
import com.google.devtools.build.lib.analysis.config.RunUnder;
import com.google.devtools.build.lib.buildeventstream.TestFileNameConstants;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.collect.ImmutableIterable;
import com.google.devtools.build.lib.util.Fingerprint;
import com.google.devtools.build.lib.util.LoggingUtil;
import com.google.devtools.build.lib.util.Pair;
import com.google.devtools.build.lib.vfs.FileSystem;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.view.test.TestStatus.TestResultData;
import com.google.devtools.common.options.TriState;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import javax.annotation.Nullable;

/**
 * An Action representing a test with the associated environment (runfiles, environment variables,
 * test result, etc). It consumes test executable and runfiles artifacts and produces test result
 * and test status artifacts.
 */
// Not final so that we can mock it in tests.
public class TestRunnerAction extends AbstractAction
    implements NotifyOnActionCacheHit, ExecutionInfoSpecifier {
  public static final PathFragment COVERAGE_TMP_ROOT = PathFragment.create("_coverage");

  // Used for selecting subset of testcase / testmethods.
  private static final String TEST_BRIDGE_TEST_FILTER_ENV = "TESTBRIDGE_TEST_ONLY";

  private static final String GUID = "cc41f9d0-47a6-11e7-8726-eb6ce83a8cc8";
  public static final String MNEMONIC = "TestRunner";

  private final Artifact testSetupScript;
  private final Artifact testXmlGeneratorScript;
  private final Artifact collectCoverageScript;
  private final BuildConfiguration configuration;
  private final TestConfiguration testConfiguration;
  private final Artifact testLog;
  private final Artifact cacheStatus;
  private final PathFragment testWarningsPath;
  private final PathFragment unusedRunfilesLogPath;
  private final PathFragment shExecutable;
  private final PathFragment splitLogsPath;
  private final PathFragment splitLogsDir;
  private final PathFragment undeclaredOutputsDir;
  private final PathFragment undeclaredOutputsZipPath;
  private final PathFragment undeclaredOutputsAnnotationsDir;
  private final PathFragment undeclaredOutputsManifestPath;
  private final PathFragment undeclaredOutputsAnnotationsPath;
  private final PathFragment xmlOutputPath;
  @Nullable private final PathFragment testShard;
  private final PathFragment testExitSafe;
  private final PathFragment testStderr;
  private final PathFragment testInfrastructureFailure;
  private final PathFragment baseDir;
  private final Artifact coverageData;
  private final TestTargetProperties testProperties;
  private final TestTargetExecutionSettings executionSettings;
  private final int shardNum;
  private final int runNumber;
  private final String workspaceName;

  // Takes the value of the `--windows_native_test_wrapper` flag.
  private final boolean useTestWrapperInsteadOfTestSetupSh;

  // Mutable state related to test caching. Lazily initialized: null indicates unknown.
  private Boolean unconditionalExecution;

  /** Any extra environment variables (and values) added by the rule that created this action. */
  private final ImmutableMap<String, String> extraTestEnv;

  /**
   * The set of environment variables that are inherited from the client environment. These are
   * handled explicitly by the ActionCacheChecker and so don't have to be included in the cache key.
   */
  private final ImmutableIterable<String> requiredClientEnvVariables;

  private static ImmutableList<Artifact> list(Artifact... artifacts) {
    ImmutableList.Builder<Artifact> builder = ImmutableList.builder();
    for (Artifact artifact : artifacts) {
      if (artifact != null) {
        builder.add(artifact);
      }
    }
    return builder.build();
  }

  /**
   * Create new TestRunnerAction instance. Should not be called directly. Use {@link
   * TestActionBuilder} instead.
   *
   * @param shardNum The shard number. Must be 0 if totalShards == 0 (no sharding). Otherwise, must
   *     be >= 0 and < totalShards.
   * @param runNumber test run number
   */
  TestRunnerAction(
      ActionOwner owner,
      Iterable<Artifact> inputs,
      Artifact testSetupScript, // Must be in inputs
      boolean useTestWrapperInsteadOfTestSetupSh,
      Artifact testXmlGeneratorScript, // Must be in inputs
      @Nullable Artifact collectCoverageScript, // Must be in inputs, if not null
      Artifact testLog,
      Artifact cacheStatus,
      Artifact coverageArtifact,
      TestTargetProperties testProperties,
      Map<String, String> extraTestEnv,
      TestTargetExecutionSettings executionSettings,
      int shardNum,
      int runNumber,
      BuildConfiguration configuration,
      String workspaceName,
      PathFragment shExecutable) {
    super(
        owner,
        /*tools=*/ ImmutableList.of(),
        inputs,
        // Note that this action only cares about the runfiles, not the mapping.
        new RunfilesSupplierImpl(PathFragment.create("runfiles"), executionSettings.getRunfiles()),
        list(testLog, cacheStatus, coverageArtifact),
        configuration.getActionEnvironment());
    Preconditions.checkState((collectCoverageScript == null) == (coverageArtifact == null));
    this.testSetupScript = testSetupScript;
    this.useTestWrapperInsteadOfTestSetupSh = useTestWrapperInsteadOfTestSetupSh;
    this.testXmlGeneratorScript = testXmlGeneratorScript;
    this.collectCoverageScript = collectCoverageScript;
    this.configuration = Preconditions.checkNotNull(configuration);
    this.testConfiguration =
        Preconditions.checkNotNull(configuration.getFragment(TestConfiguration.class));
    this.testLog = testLog;
    this.cacheStatus = cacheStatus;
    this.coverageData = coverageArtifact;
    this.shardNum = shardNum;
    this.runNumber = runNumber;
    this.testProperties = Preconditions.checkNotNull(testProperties);
    this.executionSettings = Preconditions.checkNotNull(executionSettings);

    this.baseDir = cacheStatus.getExecPath().getParentDirectory();

    int totalShards = executionSettings.getTotalShards();
    Preconditions.checkState(
        (totalShards == 0 && shardNum == 0)
            || (totalShards > 0 && 0 <= shardNum && shardNum < totalShards));
    this.testExitSafe = baseDir.getChild("test.exited_prematurely");
    // testShard Path should be set only if sharding is enabled.
    this.testShard = totalShards > 1 ? baseDir.getChild("test.shard") : null;
    this.xmlOutputPath = baseDir.getChild("test.xml");
    this.testWarningsPath = baseDir.getChild("test.warnings");
    this.unusedRunfilesLogPath = baseDir.getChild("test.unused_runfiles_log");
    this.testStderr = baseDir.getChild("test.err");
    this.shExecutable = shExecutable;
    this.splitLogsDir = baseDir.getChild("test.raw_splitlogs");
    // See note in {@link #getSplitLogsPath} on the choice of file name.
    this.splitLogsPath = splitLogsDir.getChild("test.splitlogs");
    this.undeclaredOutputsDir = baseDir.getChild("test.outputs");
    this.undeclaredOutputsZipPath = undeclaredOutputsDir.getChild("outputs.zip");
    this.undeclaredOutputsAnnotationsDir = baseDir.getChild("test.outputs_manifest");
    this.undeclaredOutputsManifestPath = undeclaredOutputsAnnotationsDir.getChild("MANIFEST");
    this.undeclaredOutputsAnnotationsPath = undeclaredOutputsAnnotationsDir.getChild("ANNOTATIONS");
    this.testInfrastructureFailure = baseDir.getChild("test.infrastructure_failure");
    this.workspaceName = workspaceName;

    this.extraTestEnv = ImmutableMap.copyOf(extraTestEnv);
    this.requiredClientEnvVariables =
        ImmutableIterable.from(
            Iterables.concat(
                configuration.getActionEnvironment().getInheritedEnv(),
                configuration.getTestActionEnvironment().getInheritedEnv()));
  }

  public BuildConfiguration getConfiguration() {
    return configuration;
  }

  public final PathFragment getBaseDir() {
    return baseDir;
  }

  @Override
  public boolean showsOutputUnconditionally() {
    return true;
  }

  public List<ActionInput> getSpawnOutputs() {
    final List<ActionInput> outputs = new ArrayList<>();
    outputs.add(ActionInputHelper.fromPath(getXmlOutputPath()));
    outputs.add(ActionInputHelper.fromPath(getExitSafeFile()));
    if (isSharded()) {
      outputs.add(ActionInputHelper.fromPath(getTestShard()));
    }
    outputs.add(ActionInputHelper.fromPath(getTestWarningsPath()));
    outputs.add(ActionInputHelper.fromPath(getSplitLogsPath()));
    outputs.add(ActionInputHelper.fromPath(getUnusedRunfilesLogPath()));
    outputs.add(ActionInputHelper.fromPath(getInfrastructureFailureFile()));
    outputs.add(ActionInputHelper.fromPath(getUndeclaredOutputsZipPath()));
    outputs.add(ActionInputHelper.fromPath(getUndeclaredOutputsManifestPath()));
    outputs.add(ActionInputHelper.fromPath(getUndeclaredOutputsAnnotationsPath()));
    if (isCoverageMode()) {
      outputs.add(getCoverageData());
    }
    return outputs;
  }

  /**
   * Returns the list of mappings from file name constants to output files. This method checks the
   * file system for existence of these output files, so it must only be used after test execution.
   */
  // TODO(ulfjack): Instead of going to local disk here, use SpawnResult (add list of files there).
  public ImmutableList<Pair<String, Path>> getTestOutputsMapping(
      ArtifactPathResolver resolver, Path execRoot) {
    ImmutableList.Builder<Pair<String, Path>> builder = ImmutableList.builder();
    if (resolver.toPath(getTestLog()).exists()) {
      builder.add(Pair.of(TestFileNameConstants.TEST_LOG, resolver.toPath(getTestLog())));
    }
    if (getCoverageData() != null && resolver.toPath(getCoverageData()).exists()) {
      builder.add(Pair.of(TestFileNameConstants.TEST_COVERAGE,
          resolver.toPath(getCoverageData())));
    }
    if (execRoot != null) {
      ResolvedPaths resolvedPaths = resolve(execRoot);
      if (resolvedPaths.getXmlOutputPath().exists()) {
        builder.add(Pair.of(TestFileNameConstants.TEST_XML, resolvedPaths.getXmlOutputPath()));
      }
      if (resolvedPaths.getSplitLogsPath().exists()) {
        builder.add(Pair.of(TestFileNameConstants.SPLIT_LOGS, resolvedPaths.getSplitLogsPath()));
      }
      if (resolvedPaths.getTestWarningsPath().exists()) {
        builder.add(
            Pair.of(TestFileNameConstants.TEST_WARNINGS, resolvedPaths.getTestWarningsPath()));
      }
      if (resolvedPaths.getUndeclaredOutputsZipPath().exists()) {
        builder.add(
            Pair.of(
                TestFileNameConstants.UNDECLARED_OUTPUTS_ZIP,
                resolvedPaths.getUndeclaredOutputsZipPath()));
      }
      if (resolvedPaths.getUndeclaredOutputsManifestPath().exists()) {
        builder.add(
            Pair.of(
                TestFileNameConstants.UNDECLARED_OUTPUTS_MANIFEST,
                resolvedPaths.getUndeclaredOutputsManifestPath()));
      }
      if (resolvedPaths.getUndeclaredOutputsAnnotationsPath().exists()) {
        builder.add(
            Pair.of(
                TestFileNameConstants.UNDECLARED_OUTPUTS_ANNOTATIONS,
                resolvedPaths.getUndeclaredOutputsAnnotationsPath()));
      }
      if (resolvedPaths.getUnusedRunfilesLogPath().exists()) {
        builder.add(
            Pair.of(
                TestFileNameConstants.UNUSED_RUNFILES_LOG,
                resolvedPaths.getUnusedRunfilesLogPath()));
      }
      if (resolvedPaths.getInfrastructureFailureFile().exists()) {
        builder.add(
            Pair.of(
                TestFileNameConstants.TEST_INFRASTRUCTURE_FAILURE,
                resolvedPaths.getInfrastructureFailureFile()));
      }
    }
    return builder.build();
  }

  @Override
  protected void computeKey(ActionKeyContext actionKeyContext, Fingerprint fp)
      throws CommandLineExpansionException {
    fp.addString(GUID);
    fp.addStrings(executionSettings.getArgs().arguments());
    fp.addString(
        executionSettings.getTestFilter() == null ? "" : executionSettings.getTestFilter());
    RunUnder runUnder = executionSettings.getRunUnder();
    fp.addString(runUnder == null ? "" : runUnder.getValue());
    fp.addStringMap(extraTestEnv);
    // TODO(ulfjack): It might be better for performance to hash the action and test envs in config,
    // and only add a hash here.
    configuration.getActionEnvironment().addTo(fp);
    configuration.getTestActionEnvironment().addTo(fp);
    // The 'requiredClientEnvVariables' are handled by Skyframe and don't need to be added here.
    fp.addString(testProperties.getSize().toString());
    fp.addString(testProperties.getTimeout().toString());
    fp.addStrings(testProperties.getTags());
    fp.addInt(testProperties.isLocal() ? 1 : 0);
    fp.addInt(shardNum);
    fp.addInt(executionSettings.getTotalShards());
    fp.addInt(runNumber);
    fp.addInt(testConfiguration.getRunsPerTestForLabel(getOwner().getLabel()));
    fp.addInt(configuration.isCodeCoverageEnabled() ? 1 : 0);
  }

  @Override
  public boolean executeUnconditionally() {
    // Note: isVolatile must return true if executeUnconditionally can ever return true
    // for this instance.
    if (unconditionalExecution == null) {
      unconditionalExecution = computeExecuteUnconditionallyFromTestStatus();
    }
    return unconditionalExecution;
  }

  @Override
  public boolean isVolatile() {
    return true;
  }

  /** Saves cache status to disk. */
  public void saveCacheStatus(
      ActionExecutionContext actionExecutionContext,
      TestResultData data) throws IOException {
    try (OutputStream out = actionExecutionContext.getInputPath(cacheStatus).getOutputStream()) {
      data.writeTo(out);
    }
  }

  /** Returns the cache from disk, or null if the file doesn't exist or if there is an error. */
  @Nullable
  private TestResultData readCacheStatus() {
    try (InputStream in = cacheStatus.getPath().getInputStream()) {
      return TestResultData.parseFrom(in);
    } catch (IOException expected) {
      return null;
    }
  }

  private boolean computeExecuteUnconditionallyFromTestStatus() {
    return !canBeCached(
        testConfiguration.cacheTestResults(),
        readCacheStatus(),
        testProperties.isExternal(),
        testConfiguration.getRunsPerTestForLabel(getOwner().getLabel()));
  }

  @VisibleForTesting
  static boolean canBeCached(
      TriState cacheTestResults, TestResultData prevStatus, boolean isExternal, int runsPerTest) {
    if (cacheTestResults == TriState.NO) {
      return false;
    }
    if (isExternal) {
      return false;
    }
    if (cacheTestResults == TriState.AUTO && (runsPerTest > 1)) {
      return false;
    }
    // Test will not be executed unconditionally - check whether test result exists and is
    // valid. If it is, method will return false and we will rely on the dependency checker
    // to make a decision about test execution.
    if (cacheTestResults == TriState.AUTO && prevStatus != null && !prevStatus.getTestPassed()) {
      return false;
    }
    // Rely on the dependency checker to determine if the test can be cached. Note that the status
    // is a declared output, so its non-existence also triggers a re-run.
    return true;
  }

  /**
   * Returns whether caching has been deemed safe by looking at the previous test run (for local
   * caching). If the previous run is not present, return "true" here, as remote execution caching
   * should be safe.
   */
  public boolean shouldCacheResult() {
    return !executeUnconditionally();
  }

  @Override
  public void actionCacheHit(ActionCachedContext executor) {
    unconditionalExecution = null;
    try {
      executor
          .getEventBus()
          .post(
              executor
                  .getContext(TestActionContext.class)
                  .newCachedTestResult(executor.getExecRoot(), this, readCacheStatus()));
    } catch (IOException e) {
      LoggingUtil.logToRemote(Level.WARNING, "Failed creating cached protocol buffer", e);
    }
  }

  @Override
  protected String getRawProgressMessage() {
    return "Testing " + getTestName();
  }

  /**
   * Deletes <b>all</b> possible test outputs.
   *
   * <p>TestRunnerAction potentially can create many more non-declared outputs - xml output,
   * coverage data file and logs for failed attempts. All those outputs are uniquely identified by
   * the test log base name with arbitrary prefix and extension.
   */
  @Override
  protected void deleteOutputs(FileSystem fileSystem, Path execRoot) throws IOException {
    super.deleteOutputs(fileSystem, execRoot);

    // We do not rely on globs, as it causes quadratic behavior in --runs_per_test and test
    // shard count.

    // We also need to remove *.(xml|data|shard|warnings|zip) files if they are present.
    execRoot.getRelative(xmlOutputPath).delete();
    execRoot.getRelative(testWarningsPath).delete();
    execRoot.getRelative(unusedRunfilesLogPath).delete();
    // Note that splitLogsPath points to a file inside the splitLogsDir so
    // it's not necessary to delete it explicitly.
    FileSystemUtils.deleteTree(execRoot.getRelative(splitLogsDir));
    FileSystemUtils.deleteTree(execRoot.getRelative(undeclaredOutputsDir));
    FileSystemUtils.deleteTree(execRoot.getRelative(undeclaredOutputsAnnotationsDir));
    execRoot.getRelative(testStderr).delete();
    execRoot.getRelative(testExitSafe).delete();
    if (testShard != null) {
      execRoot.getRelative(testShard).delete();
    }
    execRoot.getRelative(testInfrastructureFailure).delete();

    // Coverage files use "coverage" instead of "test".
    String coveragePrefix = "coverage";

    // We cannot use coverageData artifact since it may be null. Generate coverage name instead.
    execRoot.getRelative(baseDir.getChild(coveragePrefix + ".dat")).delete();

    // Delete files fetched from remote execution.
    execRoot.getRelative(baseDir.getChild("test.zip")).delete();
    deleteTestAttemptsDirMaybe(execRoot.getRelative(baseDir), "test");
  }

  private void deleteTestAttemptsDirMaybe(Path outputDir, String namePrefix) throws IOException {
    Path testAttemptsDir = outputDir.getChild(namePrefix + "_attempts");
    if (testAttemptsDir.exists()) {
      // Normally we should have used deleteTree(testAttemptsDir). However, if test output is
      // in a FUSE filesystem implemented with the high-level API, there may be .fuse???????
      // entries, which prevent removing the directory.  As a workaround, code below will throw
      // IOException if it will fail to remove something inside testAttemptsDir, but will
      // silently suppress any exceptions when deleting testAttemptsDir itself.
      FileSystemUtils.deleteTreesBelow(testAttemptsDir);
      try {
        testAttemptsDir.delete();
      } catch (IOException e) {
        // Do nothing.
      }
    }
  }

  public void setupEnvVariables(Map<String, String> env, Duration timeout) {
    env.put("TEST_TARGET", Label.print(getOwner().getLabel()));
    env.put("TEST_SIZE", getTestProperties().getSize().toString());
    env.put("TEST_TIMEOUT", Long.toString(timeout.getSeconds()));
    env.put("TEST_WORKSPACE", getRunfilesPrefix());
    env.put(
        "TEST_BINARY",
        getExecutionSettings().getExecutable().getRootRelativePath().getCallablePathString());

    // When we run test multiple times, set different TEST_RANDOM_SEED values for each run.
    // Don't override any previous setting.
    if (testConfiguration.getRunsPerTestForLabel(getOwner().getLabel()) > 1
        && !env.containsKey("TEST_RANDOM_SEED")) {
      env.put("TEST_RANDOM_SEED", Integer.toString(getRunNumber() + 1));
    }

    String testFilter = getExecutionSettings().getTestFilter();
    if (testFilter != null) {
      env.put(TEST_BRIDGE_TEST_FILTER_ENV, testFilter);
    }

    env.put("TEST_WARNINGS_OUTPUT_FILE", getTestWarningsPath().getPathString());
    env.put("TEST_UNUSED_RUNFILES_LOG_FILE", getUnusedRunfilesLogPath().getPathString());

    env.put("TEST_LOGSPLITTER_OUTPUT_FILE", getSplitLogsPath().getPathString());

    env.put("TEST_UNDECLARED_OUTPUTS_ZIP", getUndeclaredOutputsZipPath().getPathString());
    env.put("TEST_UNDECLARED_OUTPUTS_DIR", getUndeclaredOutputsDir().getPathString());
    env.put("TEST_UNDECLARED_OUTPUTS_MANIFEST", getUndeclaredOutputsManifestPath().getPathString());
    env.put(
        "TEST_UNDECLARED_OUTPUTS_ANNOTATIONS",
        getUndeclaredOutputsAnnotationsPath().getPathString());
    env.put(
        "TEST_UNDECLARED_OUTPUTS_ANNOTATIONS_DIR",
        getUndeclaredOutputsAnnotationsDir().getPathString());

    env.put("TEST_PREMATURE_EXIT_FILE", getExitSafeFile().getPathString());
    env.put("TEST_INFRASTRUCTURE_FAILURE_FILE", getInfrastructureFailureFile().getPathString());

    if (isSharded()) {
      env.put("TEST_SHARD_INDEX", Integer.toString(getShardNum()));
      env.put("TEST_TOTAL_SHARDS", Integer.toString(getExecutionSettings().getTotalShards()));
      env.put("TEST_SHARD_STATUS_FILE", getTestShard().getPathString());
    }
    env.put("XML_OUTPUT_FILE", getXmlOutputPath().getPathString());

    if (!isEnableRunfiles()) {
      // If runfiles are disabled, tell remote-runtest.sh/local-runtest.sh about that.
      env.put("RUNFILES_MANIFEST_ONLY", "1");
    }

    if (isCoverageMode()) {
      // Instruct remote-runtest.sh/local-runtest.sh not to cd into the runfiles directory.
      // TODO(ulfjack): Find a way to avoid setting this variable.
      env.put("RUNTEST_PRESERVE_CWD", "1");

      env.put("COVERAGE_MANIFEST", getCoverageManifest().getExecPathString());
      env.put("COVERAGE_DIR", getCoverageDirectory().getPathString());
      env.put("COVERAGE_OUTPUT_FILE", getCoverageData().getExecPathString());
      // TODO(elenairina): Remove this after it reaches a blaze release.
      if (configuration.isExperimentalJavaCoverage()) {
        // This value ("released") tells lcov_merger whether it should use the old or the new
        // java  coverage implementation. The meaning of "released" is that lcov_merger will receive
        // this value only after blaze containing this change will be released.
        env.put("NEW_JAVA_COVERAGE_IMPL", "released");
      } else {
        // This value ("True") should have told lcov_merger whether it should use the old or the new
        // java  coverage implementation. Due to several failed attempts at submitting the new
        // implementation, this value will be treated still as the old implementation. This
        // environment variable must be set to a value recognized by lcov_merger.
        env.put("NEW_JAVA_COVERAGE_IMPL", "True");
      }
    }
  }

  /**
   * Gets the test name in a user-friendly format. Will generally include the target name and
   * run/shard numbers, if applicable.
   */
  public String getTestName() {
    String suffix = getTestSuffix();
    String label = Label.print(getOwner().getLabel());
    return suffix.isEmpty() ? label : label + " " + suffix;
  }

  /**
   * Gets the test suffix in a user-friendly format, eg "(shard 1 of 7)". Will include the target
   * name and run/shard numbers, if applicable.
   */
  public String getTestSuffix() {
    int totalShards = executionSettings.getTotalShards();
    // Use a 1-based index for user friendliness.
    int runsPerTest = testConfiguration.getRunsPerTestForLabel(getOwner().getLabel());
    if (totalShards > 1 && runsPerTest > 1) {
      return String.format(
          "(shard %d of %d, run %d of %d)", shardNum + 1, totalShards, runNumber + 1, runsPerTest);
    } else if (totalShards > 1) {
      return String.format("(shard %d of %d)", shardNum + 1, totalShards);
    } else if (runsPerTest > 1) {
      return String.format("(run %d of %d)", runNumber + 1, runsPerTest);
    } else {
      return "";
    }
  }

  public Artifact getTestLog() {
    return testLog;
  }

  /** Returns all environment variables which must be set in order to run this test. */
  public Map<String, String> getExtraTestEnv() {
    return extraTestEnv;
  }

  @Override
  public Iterable<String> getClientEnvironmentVariables() {
    return requiredClientEnvVariables;
  }

  public ResolvedPaths resolve(Path execRoot) {
    return new ResolvedPaths(execRoot);
  }

  public Artifact getCacheStatusArtifact() {
    return cacheStatus;
  }

  public PathFragment getTestWarningsPath() {
    return testWarningsPath;
  }

  public PathFragment getUnusedRunfilesLogPath() {
    return unusedRunfilesLogPath;
  }

  public PathFragment getSplitLogsPath() {
    return splitLogsPath;
  }

  public PathFragment getUndeclaredOutputsDir() {
    return undeclaredOutputsDir;
  }

  /** @return path to the optional zip file of undeclared test outputs. */
  public PathFragment getUndeclaredOutputsZipPath() {
    return undeclaredOutputsZipPath;
  }

  /** @return path to the undeclared output manifest file. */
  public PathFragment getUndeclaredOutputsManifestPath() {
    return undeclaredOutputsManifestPath;
  }

  public PathFragment getUndeclaredOutputsAnnotationsDir() {
    return undeclaredOutputsAnnotationsDir;
  }

  /** @return path to the undeclared output annotations file. */
  public PathFragment getUndeclaredOutputsAnnotationsPath() {
    return undeclaredOutputsAnnotationsPath;
  }

  public PathFragment getTestShard() {
    return testShard;
  }

  public PathFragment getExitSafeFile() {
    return testExitSafe;
  }

  public PathFragment getInfrastructureFailureFile() {
    return testInfrastructureFailure;
  }

  /** @return path to the optionally created XML output file created by the test. */
  public PathFragment getXmlOutputPath() {
    return xmlOutputPath;
  }

  /** @return coverage data artifact or null if code coverage was not requested. */
  @Nullable
  public Artifact getCoverageData() {
    return coverageData;
  }

  @Nullable
  public Artifact getCoverageManifest() {
    return getExecutionSettings().getInstrumentedFileManifest();
  }

  /** Returns true if coverage data should be gathered. */
  public boolean isCoverageMode() {
    return coverageData != null;
  }

  /**
   * Returns a directory to temporarily store coverage results for the given action relative to the
   * execution root. This directory is used to store all coverage results related to the test
   * execution with exception of the locally generated *.gcda files. Those are stored separately
   * using relative path within coverage directory.
   *
   * <p>The directory name for the given test runner action is constructed as: {@code
   * _coverage/target_path/test_log_name} where {@code test_log_name} is usually a target name but
   * potentially can include extra suffix, such as a shard number (if test execution was sharded).
   */
  public PathFragment getCoverageDirectory() {
    return COVERAGE_TMP_ROOT.getRelative(
        FileSystemUtils.removeExtension(getTestLog().getRootRelativePath()));
  }

  public TestTargetProperties getTestProperties() {
    return testProperties;
  }

  @Override
  public Map<String, String> getExecutionInfo() {
    return testProperties.getExecutionInfo();
  }

  public TestTargetExecutionSettings getExecutionSettings() {
    return executionSettings;
  }

  public boolean isSharded() {
    return testShard != null;
  }

  /**
   * @return the shard number for this action. If getTotalShards() > 0, must be >= 0 and <
   *     getTotalShards(). Otherwise, must be 0.
   */
  public int getShardNum() {
    return shardNum;
  }

  /** @return run number. */
  public int getRunNumber() {
    return runNumber;
  }

  /** @return the workspace name. */
  public String getRunfilesPrefix() {
    return workspaceName;
  }

  @Override
  public Artifact getPrimaryOutput() {
    return testLog;
  }

  @Override
  public ActionResult execute(ActionExecutionContext actionExecutionContext)
      throws ActionExecutionException, InterruptedException {
    TestActionContext context = actionExecutionContext.getContext(TestActionContext.class);
    try {
      return ActionResult.create(context.exec(this, actionExecutionContext));
    } catch (ExecException e) {
      throw e.toActionExecutionException(this);
    } finally {
      unconditionalExecution = null;
    }
  }

  @Override
  public String getMnemonic() {
    return MNEMONIC;
  }

  @Override
  public ImmutableSet<Artifact> getMandatoryOutputs() {
    return getOutputs();
  }

  public Artifact getTestSetupScript() {
    return testSetupScript;
  }

  public boolean isUsingTestWrapperInsteadOfTestSetupScript() {
    return useTestWrapperInsteadOfTestSetupSh;
  }

  public Artifact getTestXmlGeneratorScript() {
    return testXmlGeneratorScript;
  }

  @Nullable
  public Artifact getCollectCoverageScript() {
    return collectCoverageScript;
  }

  public PathFragment getShExecutable() {
    return shExecutable;
  }

  public ImmutableMap<String, String> getLocalShellEnvironment() {
    return configuration.getLocalShellEnvironment();
  }

  public boolean isEnableRunfiles() {
    return configuration.runfilesEnabled();
  }

  /** The same set of paths as the parent test action, resolved against a given exec root. */
  public final class ResolvedPaths {
    private final Path execRoot;

    ResolvedPaths(Path execRoot) {
      this.execRoot = Preconditions.checkNotNull(execRoot);
    }

    private Path getPath(PathFragment relativePath) {
      return execRoot.getRelative(relativePath);
    }

    public final Path getBaseDir() {
      return getPath(baseDir);
    }

    /**
     * In rare cases, error messages will be printed to stderr instead of stdout. The test action is
     * responsible for appending anything in the stderr file to the real test.log.
     */
    public Path getTestStderr() {
      return getPath(testStderr);
    }

    public Path getTestWarningsPath() {
      return getPath(testWarningsPath);
    }

    public Path getSplitLogsPath() {
      return getPath(splitLogsPath);
    }

    public Path getUnusedRunfilesLogPath() {
      return getPath(unusedRunfilesLogPath);
    }

    /** @return path to the directory containing the split logs (raw and proto file). */
    public Path getSplitLogsDir() {
      return getPath(splitLogsDir);
    }

    /** @return path to the optional zip file of undeclared test outputs. */
    public Path getUndeclaredOutputsZipPath() {
      return getPath(undeclaredOutputsZipPath);
    }

    /** @return path to the directory to hold undeclared test outputs. */
    public Path getUndeclaredOutputsDir() {
      return getPath(undeclaredOutputsDir);
    }

    /** @return path to the directory to hold undeclared output annotations parts. */
    public Path getUndeclaredOutputsAnnotationsDir() {
      return getPath(undeclaredOutputsAnnotationsDir);
    }

    /** @return path to the undeclared output manifest file. */
    public Path getUndeclaredOutputsManifestPath() {
      return getPath(undeclaredOutputsManifestPath);
    }

    /** @return path to the undeclared output annotations file. */
    public Path getUndeclaredOutputsAnnotationsPath() {
      return getPath(undeclaredOutputsAnnotationsPath);
    }

    @Nullable
    public Path getTestShard() {
      return testShard == null ? null : getPath(testShard);
    }

    public Path getExitSafeFile() {
      return getPath(testExitSafe);
    }

    public Path getInfrastructureFailureFile() {
      return getPath(testInfrastructureFailure);
    }

    /** @return path to the optionally created XML output file created by the test. */
    public Path getXmlOutputPath() {
      return getPath(xmlOutputPath);
    }
  }
}
