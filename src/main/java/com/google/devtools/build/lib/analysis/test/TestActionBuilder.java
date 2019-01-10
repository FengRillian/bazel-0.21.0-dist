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

import static com.google.devtools.build.lib.packages.BuildType.LABEL;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.ArtifactRoot;
import com.google.devtools.build.lib.analysis.AnalysisEnvironment;
import com.google.devtools.build.lib.analysis.FileProvider;
import com.google.devtools.build.lib.analysis.FilesToRunProvider;
import com.google.devtools.build.lib.analysis.PrerequisiteArtifacts;
import com.google.devtools.build.lib.analysis.RuleContext;
import com.google.devtools.build.lib.analysis.RunfilesSupport;
import com.google.devtools.build.lib.analysis.ShToolchain;
import com.google.devtools.build.lib.analysis.TransitiveInfoCollection;
import com.google.devtools.build.lib.analysis.actions.LazyWriteNestedSetOfPairAction;
import com.google.devtools.build.lib.analysis.config.BuildConfiguration;
import com.google.devtools.build.lib.analysis.configuredtargets.RuleConfiguredTarget.Mode;
import com.google.devtools.build.lib.analysis.test.TestProvider.TestParams;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.collect.nestedset.Order;
import com.google.devtools.build.lib.packages.TargetUtils;
import com.google.devtools.build.lib.packages.TestSize;
import com.google.devtools.build.lib.packages.TestTimeout;
import com.google.devtools.build.lib.util.OS;
import com.google.devtools.build.lib.util.Pair;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.common.options.EnumConverter;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import javax.annotation.Nullable;

/**
 * Helper class to create test actions.
 */
public final class TestActionBuilder {

  private static final String CC_CODE_COVERAGE_SCRIPT = "CC_CODE_COVERAGE_SCRIPT";
  private static final String LCOV_MERGER = "LCOV_MERGER";
  // The coverage tool Bazel uses to generate a code coverage report for C++.
  private static final String BAZEL_CC_COVERAGE_TOOL = "BAZEL_CC_COVERAGE_TOOL";
  // A file that contains a mapping between the reported source file path and the actual source
  // file path, relative to the workspace directory, if the two values are different. If the
  // reported source file is the same as the actual source path it will not be included in the file.
  private static final String COVERAGE_REPORTED_TO_ACTUAL_SOURCES_FILE =
      "COVERAGE_REPORTED_TO_ACTUAL_SOURCES_FILE";

  enum CcCoverageTool {
    GCOV,
    LCOV,
  }

  private static final CcCoverageTool DEFAULT_BAZEL_CC_COVERAGE_TOOL = CcCoverageTool.LCOV;

  private final RuleContext ruleContext;
  private RunfilesSupport runfilesSupport;
  private Artifact executable;
  private ExecutionInfo executionRequirements;
  private InstrumentedFilesInfo instrumentedFiles;
  private int explicitShardCount;
  private Map<String, String> extraEnv;

  public TestActionBuilder(RuleContext ruleContext) {
    this.ruleContext = ruleContext;
    this.extraEnv = new TreeMap<>();
  }

  /**
   * Creates the test actions and artifacts using the previously set parameters.
   *
   * @return ordered list of test status artifacts
   */
  public TestParams build() {
    Preconditions.checkState(runfilesSupport != null);
    boolean local = TargetUtils.isTestRuleAndRunsLocally(ruleContext.getRule());
    TestShardingStrategy strategy =
        ruleContext.getConfiguration().getFragment(TestConfiguration.class).testShardingStrategy();
    int shards = strategy.getNumberOfShards(
        local, explicitShardCount, isTestShardingCompliant(),
        TestSize.getTestSize(ruleContext.getRule()));
    Preconditions.checkState(shards >= 0);
    return createTestAction(shards);
  }

  private boolean isTestShardingCompliant() {
    // See if it has a data dependency on the special target
    // //tools:test_sharding_compliant. Test runners add this dependency
    // to show they speak the sharding protocol.
    // There are certain cases where this heuristic may fail, giving
    // a "false positive" (where we shard the test even though the
    // it isn't supported). We may want to refine this logic, but
    // heuristically sharding is currently experimental. Also, we do detect
    // false-positive cases and return an error.
    return runfilesSupport.getRunfilesSymlinkNames().contains(
        PathFragment.create("tools/test_sharding_compliant"));
  }

  /**
   * Set the runfiles and executable to be run as a test.
   */
  public TestActionBuilder setFilesToRunProvider(FilesToRunProvider provider) {
    Preconditions.checkNotNull(provider.getRunfilesSupport());
    Preconditions.checkNotNull(provider.getExecutable());
    this.runfilesSupport = provider.getRunfilesSupport();
    this.executable = provider.getExecutable();
    return this;
  }

  public TestActionBuilder setInstrumentedFiles(@Nullable InstrumentedFilesInfo instrumentedFiles) {
    this.instrumentedFiles = instrumentedFiles;
    return this;
  }

  public TestActionBuilder setExecutionRequirements(
      @Nullable ExecutionInfo executionRequirements) {
    this.executionRequirements = executionRequirements;
    return this;
  }

  public TestActionBuilder addExtraEnv(Map<String, String> extraEnv) {
    this.extraEnv.putAll(extraEnv);
    return this;
  }

  /**
   * Set the explicit shard count. Note that this may be overridden by the sharding strategy.
   */
  public TestActionBuilder setShardCount(int explicitShardCount) {
    this.explicitShardCount = explicitShardCount;
    return this;
  }

  /**
   * Converts to {@link TestActionBuilder.TestShardingStrategy}.
   */
  public static class ShardingStrategyConverter extends EnumConverter<TestShardingStrategy> {
    public ShardingStrategyConverter() {
      super(TestShardingStrategy.class, "test sharding strategy");
    }
  }

  /**
   * A strategy for running the same tests in many processes.
   */
  public static enum TestShardingStrategy {
    EXPLICIT {
      @Override public int getNumberOfShards(boolean isLocal, int shardCountFromAttr,
          boolean testShardingCompliant, TestSize testSize) {
        return Math.max(shardCountFromAttr, 0);
      }
    },

    EXPERIMENTAL_HEURISTIC {
      @Override public int getNumberOfShards(boolean isLocal, int shardCountFromAttr,
          boolean testShardingCompliant, TestSize testSize) {
        if (shardCountFromAttr >= 0) {
          return shardCountFromAttr;
        }
        if (isLocal || !testShardingCompliant) {
          return 0;
        }
        return testSize.getDefaultShards();
      }
    },

    DISABLED {
      @Override public int getNumberOfShards(boolean isLocal, int shardCountFromAttr,
          boolean testShardingCompliant, TestSize testSize) {
        return 0;
      }
    };

    public abstract int getNumberOfShards(boolean isLocal, int shardCountFromAttr,
        boolean testShardingCompliant, TestSize testSize);
  }

  /**
   * Creates a test action and artifacts for the given rule. The test action will
   * use the specified executable and runfiles.
   *
   * @return ordered list of test artifacts, one per action. These are used to drive
   *    execution in Skyframe, and by AggregatingTestListener and
   *    TestResultAnalyzer to keep track of completed and pending test runs.
   */
  private TestParams createTestAction(int shards) {
    PathFragment targetName = PathFragment.create(ruleContext.getLabel().getName());
    BuildConfiguration config = ruleContext.getConfiguration();
    AnalysisEnvironment env = ruleContext.getAnalysisEnvironment();
    ArtifactRoot root = config.getTestLogsDirectory(ruleContext.getRule().getRepository());

    // TODO(laszlocsomor), TODO(ulfjack): `isExecutedOnWindows` should use the execution platform,
    // not the host platform. Once Bazel can tell apart these platforms, fix the right side of this
    // initialization.
    final boolean isExecutedOnWindows = OS.getCurrent() == OS.WINDOWS;

    final boolean isUsingTestWrapperInsteadOfTestSetupScript =
        isExecutedOnWindows
            && ruleContext
                .getConfiguration()
                .getFragment(TestConfiguration.class)
                .isUsingWindowsNativeTestWrapper();

    NestedSetBuilder<Artifact> inputsBuilder = NestedSetBuilder.stableOrder();
    inputsBuilder.addTransitive(
        NestedSetBuilder.create(Order.STABLE_ORDER, runfilesSupport.getRunfilesMiddleman()));

    if (!isUsingTestWrapperInsteadOfTestSetupScript) {
      NestedSet<Artifact> testRuntime =
          PrerequisiteArtifacts.nestedSet(ruleContext, "$test_runtime", Mode.HOST);
      inputsBuilder.addTransitive(testRuntime);
    }
    TestTargetProperties testProperties = new TestTargetProperties(
        ruleContext, executionRequirements);

    // If the test rule does not provide InstrumentedFilesProvider, there's not much that we can do.
    final boolean collectCodeCoverage = config.isCodeCoverageEnabled()
        && instrumentedFiles != null;

    Artifact testActionExecutable =
        isUsingTestWrapperInsteadOfTestSetupScript
            ? ruleContext.getHostPrerequisiteArtifact("$test_wrapper")
            : ruleContext.getHostPrerequisiteArtifact("$test_setup_script");

    inputsBuilder.add(testActionExecutable);
    Artifact testXmlGeneratorScript =
        ruleContext.getHostPrerequisiteArtifact("$xml_generator_script");
    inputsBuilder.add(testXmlGeneratorScript);

    Artifact collectCoverageScript = null;
    TreeMap<String, String> extraTestEnv = new TreeMap<>();

    TestTargetExecutionSettings executionSettings;
    if (collectCodeCoverage) {
      collectCoverageScript = ruleContext.getHostPrerequisiteArtifact("$collect_coverage_script");
      inputsBuilder.add(collectCoverageScript);
      inputsBuilder.addTransitive(instrumentedFiles.getCoverageSupportFiles());
      // Add instrumented file manifest artifact to the list of inputs. This file will contain
      // exec paths of all source files that should be included into the code coverage output.
      NestedSet<Artifact> metadataFiles = instrumentedFiles.getInstrumentationMetadataFiles();
      inputsBuilder.addTransitive(metadataFiles);
      inputsBuilder.addTransitive(
          PrerequisiteArtifacts.nestedSet(ruleContext, ":coverage_support", Mode.DONT_CHECK));

      if (ruleContext.isAttrDefined("$collect_cc_coverage", LABEL)) {
        Artifact collectCcCoverage =
            ruleContext.getHostPrerequisiteArtifact("$collect_cc_coverage");
        inputsBuilder.add(collectCcCoverage);
        extraTestEnv.put(CC_CODE_COVERAGE_SCRIPT, collectCcCoverage.getExecPathString());
      }

      if (!instrumentedFiles.getReportedToActualSources().isEmpty()) {
        Artifact reportedToActualSourcesArtifact =
            ruleContext.getUniqueDirectoryArtifact(
                "_coverage_helpers", "reported_to_actual_sources.txt");
        ruleContext.registerAction(
            new LazyWriteNestedSetOfPairAction(
                ruleContext.getActionOwner(),
                reportedToActualSourcesArtifact,
                instrumentedFiles.getReportedToActualSources()));
        inputsBuilder.add(reportedToActualSourcesArtifact);
        extraTestEnv.put(
            COVERAGE_REPORTED_TO_ACTUAL_SOURCES_FILE,
            reportedToActualSourcesArtifact.getExecPathString());
      }

      // lcov is the default CC coverage tool unless otherwise specified on the command line.
      extraTestEnv.put(
          BAZEL_CC_COVERAGE_TOOL,
          ruleContext.getConfiguration().useGcovCoverage()
              ? CcCoverageTool.GCOV.toString()
              : DEFAULT_BAZEL_CC_COVERAGE_TOOL.toString());

      // We don't add this attribute to non-supported test target
      if (ruleContext.isAttrDefined("$lcov_merger", LABEL)) {
        TransitiveInfoCollection lcovMerger =
            ruleContext.getPrerequisite("$lcov_merger", Mode.TARGET);
        FilesToRunProvider lcovFilesToRun = lcovMerger.getProvider(FilesToRunProvider.class);
        if (lcovFilesToRun != null) {
          extraTestEnv.put(LCOV_MERGER, lcovFilesToRun.getExecutable().getExecPathString());
          inputsBuilder.addTransitive(lcovFilesToRun.getFilesToRun());
        } else {
          NestedSet<Artifact> filesToBuild =
              lcovMerger.getProvider(FileProvider.class).getFilesToBuild();

          if (Iterables.size(filesToBuild) == 1) {
            Artifact lcovMergerArtifact = Iterables.getOnlyElement(filesToBuild);
            extraTestEnv.put(LCOV_MERGER, lcovMergerArtifact.getExecPathString());
            inputsBuilder.add(lcovMergerArtifact);
          } else {
            ruleContext.attributeError("$lcov_merger",
                "the LCOV merger should be either an executable or a single artifact");
          }
        }
      }

      Artifact instrumentedFileManifest =
          InstrumentedFileManifestAction.getInstrumentedFileManifest(ruleContext,
              instrumentedFiles.getInstrumentedFiles(), metadataFiles);
      executionSettings = new TestTargetExecutionSettings(ruleContext, runfilesSupport,
          executable, instrumentedFileManifest, shards);
      inputsBuilder.add(instrumentedFileManifest);
      // TODO(ulfjack): Is this even ever set? If yes, does this cost us a lot of memory?
      for (Pair<String, String> coverageEnvEntry : instrumentedFiles.getCoverageEnvironment()) {
        extraTestEnv.put(coverageEnvEntry.getFirst(), coverageEnvEntry.getSecond());
      }
    } else {
      executionSettings = new TestTargetExecutionSettings(ruleContext, runfilesSupport,
          executable, null, shards);
    }

    extraTestEnv.putAll(extraEnv);

    if (config.getRunUnder() != null) {
      Artifact runUnderExecutable = executionSettings.getRunUnderExecutable();
      if (runUnderExecutable != null) {
        inputsBuilder.add(runUnderExecutable);
      }
    }

    int runsPerTest =
        config.getFragment(TestConfiguration.class).getRunsPerTestForLabel(ruleContext.getLabel());

    Iterable<Artifact> inputs = inputsBuilder.build();
    int shardRuns = (shards > 0 ? shards : 1);
    List<Artifact> results = Lists.newArrayListWithCapacity(runsPerTest * shardRuns);
    ImmutableList.Builder<Artifact> coverageArtifacts = ImmutableList.builder();

    for (int run = 0; run < runsPerTest; run++) {
      // Use a 1-based index for user friendliness.
      String testRunDir =
          runsPerTest > 1 ? String.format("run_%d_of_%d", run + 1, runsPerTest) : "";
      for (int shard = 0; shard < shardRuns; shard++) {
        String shardRunDir =
            (shardRuns > 1 ? String.format("shard_%d_of_%d", shard + 1, shards) : "");
        if (testRunDir.isEmpty()) {
          shardRunDir = shardRunDir.isEmpty() ? "" : shardRunDir + PathFragment.SEPARATOR_CHAR;
        } else {
          testRunDir += PathFragment.SEPARATOR_CHAR;
          shardRunDir = shardRunDir.isEmpty() ? testRunDir : shardRunDir + "_" + testRunDir;
        }
        Artifact testLog =
            ruleContext.getPackageRelativeArtifact(
                targetName.getRelative(shardRunDir + "test.log"), root);
        Artifact cacheStatus =
            ruleContext.getPackageRelativeArtifact(
                targetName.getRelative(shardRunDir + "test.cache_status"), root);

        Artifact coverageArtifact = null;
        if (collectCodeCoverage) {
          coverageArtifact = ruleContext.getPackageRelativeArtifact(
              targetName.getRelative(shardRunDir + "coverage.dat"), root);
          coverageArtifacts.add(coverageArtifact);
        }

        PathFragment shExecutable = ShToolchain.getPathOrError(ruleContext);
        env.registerAction(
            new TestRunnerAction(
                ruleContext.getActionOwner(),
                inputs,
                testActionExecutable,
                isUsingTestWrapperInsteadOfTestSetupScript,
                testXmlGeneratorScript,
                collectCoverageScript,
                testLog,
                cacheStatus,
                coverageArtifact,
                testProperties,
                extraTestEnv,
                executionSettings,
                shard,
                run,
                config,
                ruleContext.getWorkspaceName(),
                shExecutable));
        results.add(cacheStatus);
      }
    }
    // TODO(bazel-team): Passing the reportGenerator to every TestParams is a bit strange.
    FilesToRunProvider reportGenerator = null;
    if (config.isCodeCoverageEnabled()) {
      // It's not enough to add this if the rule has coverage enabled because the command line may
      // contain rules with baseline coverage but no test rules that have coverage enabled, and in
      // that case, we still need the report generator.
      TransitiveInfoCollection reportGeneratorTarget =
          ruleContext.getPrerequisite(":coverage_report_generator", Mode.HOST);
      reportGenerator = reportGeneratorTarget.getProvider(FilesToRunProvider.class);
    }

    return new TestParams(runsPerTest, shards, TestTimeout.getTestTimeout(ruleContext.getRule()),
        ruleContext.getRule().getRuleClass(), ImmutableList.copyOf(results),
        coverageArtifacts.build(), reportGenerator);
  }
}
