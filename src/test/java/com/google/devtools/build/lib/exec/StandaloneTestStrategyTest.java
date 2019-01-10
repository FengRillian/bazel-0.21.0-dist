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

package com.google.devtools.build.lib.exec;

import static com.google.common.truth.Truth.assertThat;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.fail;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.common.collect.MoreCollectors;
import com.google.devtools.build.lib.actions.ActionContext;
import com.google.devtools.build.lib.actions.ActionExecutionContext;
import com.google.devtools.build.lib.actions.ActionInputPrefetcher;
import com.google.devtools.build.lib.actions.ActionKeyContext;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.Spawn;
import com.google.devtools.build.lib.actions.SpawnActionContext;
import com.google.devtools.build.lib.actions.SpawnResult;
import com.google.devtools.build.lib.actions.SpawnResult.Status;
import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.test.TestProvider;
import com.google.devtools.build.lib.analysis.test.TestResult;
import com.google.devtools.build.lib.analysis.test.TestRunnerAction;
import com.google.devtools.build.lib.analysis.util.BuildViewTestCase;
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.TestStatus;
import com.google.devtools.build.lib.clock.BlazeClock;
import com.google.devtools.build.lib.clock.Clock;
import com.google.devtools.build.lib.events.ExtendedEventHandler;
import com.google.devtools.build.lib.events.StoredEventHandler;
import com.google.devtools.build.lib.exec.TestStrategy.TestOutputFormat;
import com.google.devtools.build.lib.util.OS;
import com.google.devtools.build.lib.util.io.FileOutErr;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.view.test.TestStatus.BlazeTestStatus;
import com.google.devtools.common.options.Options;
import java.io.IOException;
import java.io.OutputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/** Unit tests for {@link StandaloneTestStrategy}. */
@RunWith(JUnit4.class)
public final class StandaloneTestStrategyTest extends BuildViewTestCase {

  private static class TestedStandaloneTestStrategy extends StandaloneTestStrategy {
    TestResult postedResult = null;

    public TestedStandaloneTestStrategy(
        ExecutionOptions executionOptions, BinTools binTools, Path tmpDirRoot) {
      super(executionOptions, binTools, tmpDirRoot);
    }

    @Override
    protected void postTestResult(
        ActionExecutionContext actionExecutionContext, TestResult result) {
      postedResult = result;
    }
  }

  private class FakeActionExecutionContext extends ActionExecutionContext {
    private final SpawnActionContext spawnActionContext;

    public FakeActionExecutionContext(
        FileOutErr fileOutErr, SpawnActionContext spawnActionContext) {
      super(
          /* executor= */ null,
          /* actionInputFileCache= */ null,
          ActionInputPrefetcher.NONE,
          new ActionKeyContext(),
          /* metadataHandler= */ null,
          fileOutErr,
          /* clientEnv= */ ImmutableMap.of(),
          /* topLevelFilesets= */ ImmutableMap.of(),
          /* artifactExpander= */ null,
          /* actionFileSystem= */ null,
          /* skyframeDepsResult= */ null);
      this.spawnActionContext = spawnActionContext;
    }

    @Override
    public Clock getClock() {
      return BlazeClock.instance();
    }

    @Override
    public <T extends ActionContext> T getContext(Class<? extends T> type) {
      return SpawnActionContext.class.equals(type) ? type.cast(spawnActionContext) : null;
    }

    @Override
    public ExtendedEventHandler getEventHandler() {
      return storedEvents;
    }

    @Override
    public Path getExecRoot() {
      return outputBase.getRelative("execroot");
    }

    @Override
    public ActionExecutionContext withFileOutErr(FileOutErr fileOutErr) {
      return new FakeActionExecutionContext(fileOutErr, spawnActionContext);
    }
  }

  @Mock private SpawnActionContext spawnActionContext;

  private StoredEventHandler storedEvents = new StoredEventHandler();

  @Before
  public final void setUp() throws Exception {
    MockitoAnnotations.initMocks(this);
  }

  private FileOutErr createTempOutErr(Path tmpDirRoot) {
    Path outPath = tmpDirRoot.getRelative("test-out.txt");
    Path errPath = tmpDirRoot.getRelative("test-err.txt");
    return new FileOutErr(outPath, errPath);
  }

  private TestRunnerAction getTestAction(String target) throws Exception {
    ConfiguredTarget configuredTarget = getConfiguredTarget(target);
    List<Artifact> testStatusArtifacts =
        configuredTarget.getProvider(TestProvider.class).getTestParams().getTestStatusArtifacts();
    Artifact testStatusArtifact = Iterables.getOnlyElement(testStatusArtifacts);
    TestRunnerAction action = (TestRunnerAction) getGeneratingAction(testStatusArtifact);
    action.getTestLog().getPath().getParentDirectory().createDirectoryAndParents();
    return action;
  }

  @Test
  public void testRunTestOnce() throws Exception {
    ExecutionOptions executionOptions = ExecutionOptions.DEFAULTS;
    Path tmpDirRoot = TestStrategy.getTmpRoot(rootDirectory, outputBase, executionOptions);
    BinTools binTools = BinTools.forUnitTesting(directories, analysisMock.getEmbeddedTools());
    TestedStandaloneTestStrategy standaloneTestStrategy =
        new TestedStandaloneTestStrategy(executionOptions, binTools, tmpDirRoot);

    // setup a test action
    scratch.file("standalone/simple_test.sh", "this does not get executed, it is mocked out");
    scratch.file(
        "standalone/BUILD",
        "sh_test(",
        "    name = \"simple_test\",",
        "    size = \"small\",",
        "    srcs = [\"simple_test.sh\"],",
        ")");
    TestRunnerAction testRunnerAction = getTestAction("//standalone:simple_test");

    SpawnResult expectedSpawnResult =
        new SpawnResult.Builder()
            .setStatus(Status.SUCCESS)
            .setWallTime(Duration.ofMillis(10))
            .setRunnerName("test")
            .build();
    when(spawnActionContext.exec(any(), any())).thenReturn(ImmutableList.of(expectedSpawnResult));

    ActionExecutionContext actionExecutionContext =
        new FakeActionExecutionContext(createTempOutErr(tmpDirRoot), spawnActionContext);

    // actual StandaloneTestStrategy execution
    List<SpawnResult> spawnResults =
        standaloneTestStrategy.exec(testRunnerAction, actionExecutionContext);

    assertThat(spawnResults).contains(expectedSpawnResult);
    TestResult result = standaloneTestStrategy.postedResult;
    assertThat(result).isNotNull();
    assertThat(result.isCached()).isFalse();
    assertThat(result.getTestAction()).isSameAs(testRunnerAction);
    assertThat(result.getData().getTestPassed()).isTrue();
    assertThat(result.getData().getRemotelyCached()).isFalse();
    assertThat(result.getData().getIsRemoteStrategy()).isFalse();
    assertThat(result.getData().getRunDurationMillis()).isEqualTo(10);
    assertThat(result.getData().getTestTimesList()).containsExactly(10L);
    TestAttempt attempt =
        storedEvents
            .getPosts()
            .stream()
            .filter(TestAttempt.class::isInstance)
            .map(TestAttempt.class::cast)
            .collect(MoreCollectors.onlyElement());
    assertThat(attempt.getExecutionInfo().getStrategy()).isEqualTo("test");
    assertThat(attempt.getExecutionInfo().getHostname()).isEqualTo("");
  }

  @Test
  public void testRunFlakyTest() throws Exception {
    ExecutionOptions executionOptions = Options.getDefaults(ExecutionOptions.class);
    // TODO(ulfjack): Update this test for split xml generation.
    executionOptions.splitXmlGeneration = false;

    Path tmpDirRoot = TestStrategy.getTmpRoot(rootDirectory, outputBase, executionOptions);
    BinTools binTools = BinTools.forUnitTesting(directories, analysisMock.getEmbeddedTools());
    TestedStandaloneTestStrategy standaloneTestStrategy =
        new TestedStandaloneTestStrategy(executionOptions, binTools, tmpDirRoot);

    // setup a test action
    scratch.file("standalone/simple_test.sh", "this does not get executed, it is mocked out");
    scratch.file(
        "standalone/BUILD",
        "sh_test(",
        "    name = \"simple_test\",",
        "    size = \"small\",",
        "    srcs = [\"simple_test.sh\"],",
        "    flaky = True,",
        ")");
    TestRunnerAction testRunnerAction = getTestAction("//standalone:simple_test");

    SpawnResult failSpawnResult =
        new SpawnResult.Builder()
            .setStatus(Status.NON_ZERO_EXIT)
            .setExitCode(1)
            .setWallTime(Duration.ofMillis(10))
            .setRunnerName("test")
            .build();
    SpawnResult passSpawnResult =
        new SpawnResult.Builder()
            .setStatus(Status.SUCCESS)
            .setWallTime(Duration.ofMillis(15))
            .setRunnerName("test")
            .build();
    when(spawnActionContext.exec(any(), any()))
        .thenThrow(new SpawnExecException("test failed", failSpawnResult, false))
        .thenReturn(ImmutableList.of(passSpawnResult));

    ActionExecutionContext actionExecutionContext =
        new FakeActionExecutionContext(createTempOutErr(tmpDirRoot), spawnActionContext);

    // actual StandaloneTestStrategy execution
    List<SpawnResult> spawnResults =
        standaloneTestStrategy.exec(testRunnerAction, actionExecutionContext);

    assertThat(spawnResults).containsExactly(passSpawnResult);

    TestResult result = standaloneTestStrategy.postedResult;
    assertThat(result).isNotNull();
    assertThat(result.isCached()).isFalse();
    assertThat(result.getTestAction()).isSameAs(testRunnerAction);
    assertThat(result.getData().getStatus()).isEqualTo(BlazeTestStatus.FLAKY);
    assertThat(result.getData().getTestPassed()).isTrue();
    assertThat(result.getData().getRemotelyCached()).isFalse();
    assertThat(result.getData().getIsRemoteStrategy()).isFalse();
    assertThat(result.getData().getRunDurationMillis()).isEqualTo(15L);
    assertThat(result.getData().getTestTimesList()).containsExactly(10L, 15L);
    List<TestAttempt> attempts =
        storedEvents
            .getPosts()
            .stream()
            .filter(TestAttempt.class::isInstance)
            .map(TestAttempt.class::cast)
            .collect(ImmutableList.toImmutableList());
    assertThat(attempts).hasSize(2);
    TestAttempt failedAttempt = attempts.get(0);
    assertThat(failedAttempt.getExecutionInfo().getStrategy()).isEqualTo("test");
    assertThat(failedAttempt.getExecutionInfo().getHostname()).isEqualTo("");
    assertThat(failedAttempt.getStatus()).isEqualTo(TestStatus.FAILED);
    assertThat(failedAttempt.getExecutionInfo().getCachedRemotely()).isFalse();
    TestAttempt okAttempt = attempts.get(1);
    assertThat(okAttempt.getStatus()).isEqualTo(TestStatus.PASSED);
    assertThat(okAttempt.getExecutionInfo().getStrategy()).isEqualTo("test");
    assertThat(okAttempt.getExecutionInfo().getHostname()).isEqualTo("");
  }

  @Test
  public void testRunTestRemotely() throws Exception {
    ExecutionOptions executionOptions = ExecutionOptions.DEFAULTS;
    Path tmpDirRoot = TestStrategy.getTmpRoot(rootDirectory, outputBase, executionOptions);
    BinTools binTools = BinTools.forUnitTesting(directories, analysisMock.getEmbeddedTools());
    TestedStandaloneTestStrategy standaloneTestStrategy =
        new TestedStandaloneTestStrategy(executionOptions, binTools, tmpDirRoot);

    // setup a test action
    scratch.file("standalone/simple_test.sh", "this does not get executed, it is mocked out");
    scratch.file(
        "standalone/BUILD",
        "sh_test(",
        "    name = \"simple_test\",",
        "    size = \"small\",",
        "    srcs = [\"simple_test.sh\"],",
        ")");
    TestRunnerAction testRunnerAction = getTestAction("//standalone:simple_test");

    SpawnResult expectedSpawnResult =
        new SpawnResult.Builder()
            .setStatus(Status.SUCCESS)
            .setWallTime(Duration.ofMillis(10))
            .setRunnerName("remote")
            .setExecutorHostname("a-remote-host")
            .build();
    when(spawnActionContext.exec(any(), any())).thenReturn(ImmutableList.of(expectedSpawnResult));

    ActionExecutionContext actionExecutionContext =
        new FakeActionExecutionContext(createTempOutErr(tmpDirRoot), spawnActionContext);

    // actual StandaloneTestStrategy execution
    List<SpawnResult> spawnResults =
        standaloneTestStrategy.exec(testRunnerAction, actionExecutionContext);

    assertThat(spawnResults).contains(expectedSpawnResult);

    TestResult result = standaloneTestStrategy.postedResult;
    assertThat(result).isNotNull();
    assertThat(result.isCached()).isFalse();
    assertThat(result.getTestAction()).isSameAs(testRunnerAction);
    assertThat(result.getData().getTestPassed()).isTrue();
    assertThat(result.getData().getRemotelyCached()).isFalse();
    assertThat(result.getData().getIsRemoteStrategy()).isTrue();
    assertThat(result.getData().getRunDurationMillis()).isEqualTo(10);
    assertThat(result.getData().getTestTimesList()).containsExactly(10L);
    TestAttempt attempt =
        storedEvents
            .getPosts()
            .stream()
            .filter(TestAttempt.class::isInstance)
            .map(TestAttempt.class::cast)
            .collect(MoreCollectors.onlyElement());
    assertThat(attempt.getStatus()).isEqualTo(TestStatus.PASSED);
    assertThat(attempt.getExecutionInfo().getStrategy()).isEqualTo("remote");
    assertThat(attempt.getExecutionInfo().getHostname()).isEqualTo("a-remote-host");
  }

  @Test
  public void testRunRemotelyCachedTest() throws Exception {
    ExecutionOptions executionOptions = ExecutionOptions.DEFAULTS;
    Path tmpDirRoot = TestStrategy.getTmpRoot(rootDirectory, outputBase, executionOptions);
    BinTools binTools = BinTools.forUnitTesting(directories, analysisMock.getEmbeddedTools());
    TestedStandaloneTestStrategy standaloneTestStrategy =
        new TestedStandaloneTestStrategy(executionOptions, binTools, tmpDirRoot);

    // setup a test action
    scratch.file("standalone/simple_test.sh", "this does not get executed, it is mocked out");
    scratch.file(
        "standalone/BUILD",
        "sh_test(",
        "    name = \"simple_test\",",
        "    size = \"small\",",
        "    srcs = [\"simple_test.sh\"],",
        ")");
    TestRunnerAction testRunnerAction = getTestAction("//standalone:simple_test");

    SpawnResult expectedSpawnResult =
        new SpawnResult.Builder()
            .setStatus(Status.SUCCESS)
            .setCacheHit(true)
            .setWallTime(Duration.ofMillis(10))
            .setRunnerName("remote cache")
            .build();
    when(spawnActionContext.exec(any(), any())).thenReturn(ImmutableList.of(expectedSpawnResult));

    ActionExecutionContext actionExecutionContext =
        new FakeActionExecutionContext(createTempOutErr(tmpDirRoot), spawnActionContext);

    // actual StandaloneTestStrategy execution
    List<SpawnResult> spawnResults =
        standaloneTestStrategy.exec(testRunnerAction, actionExecutionContext);

    // check that the rigged SpawnResult was returned
    assertThat(spawnResults).contains(expectedSpawnResult);

    TestResult result = standaloneTestStrategy.postedResult;
    assertThat(result).isNotNull();
    assertThat(result.isCached()).isFalse();
    assertThat(result.getTestAction()).isSameAs(testRunnerAction);
    assertThat(result.getData().getTestPassed()).isTrue();
    assertThat(result.getData().getRemotelyCached()).isTrue();
    assertThat(result.getData().getIsRemoteStrategy()).isFalse();
    assertThat(result.getData().getRunDurationMillis()).isEqualTo(10);
    assertThat(result.getData().getTestTimesList()).containsExactly(10L);
    TestAttempt attempt =
        storedEvents
            .getPosts()
            .stream()
            .filter(TestAttempt.class::isInstance)
            .map(TestAttempt.class::cast)
            .collect(MoreCollectors.onlyElement());
    assertThat(attempt.getExecutionInfo().getStrategy()).isEqualTo("remote cache");
    assertThat(attempt.getExecutionInfo().getHostname()).isEqualTo("");
  }

  @Test
  public void testThatTestLogAndOutputAreReturned() throws Exception {
    ExecutionOptions executionOptions = Options.getDefaults(ExecutionOptions.class);
    executionOptions.testOutput = TestOutputFormat.ERRORS;
    Path tmpDirRoot = TestStrategy.getTmpRoot(rootDirectory, outputBase, executionOptions);
    BinTools binTools = BinTools.forUnitTesting(directories, analysisMock.getEmbeddedTools());
    TestedStandaloneTestStrategy standaloneTestStrategy =
        new TestedStandaloneTestStrategy(executionOptions, binTools, tmpDirRoot);

    // setup a test action
    scratch.file("standalone/failing_test.sh", "this does not get executed, it is mocked out");
    scratch.file(
        "standalone/BUILD",
        "sh_test(",
        "    name = \"failing_test\",",
        "    size = \"small\",",
        "    srcs = [\"failing_test.sh\"],",
        ")");
    TestRunnerAction testRunnerAction = getTestAction("//standalone:failing_test");

    SpawnResult expectedSpawnResult =
        new SpawnResult.Builder()
            .setStatus(Status.NON_ZERO_EXIT)
            .setExitCode(1)
            .setRunnerName("test")
            .build();
    when(spawnActionContext.exec(any(), any()))
        .thenAnswer(
            (invocation) -> {
              Spawn spawn = invocation.getArgumentAt(0, Spawn.class);
              if (spawn.getOutputFiles().size() != 1) {
                FileOutErr outErr =
                    invocation.getArgumentAt(1, ActionExecutionContext.class).getFileOutErr();
                try (OutputStream stream = outErr.getOutputStream()) {
                  stream.write("This will not appear in the test output: bla\n".getBytes(UTF_8));
                  stream.write((TestLogHelper.HEADER_DELIMITER + "\n").getBytes(UTF_8));
                  stream.write("This will appear in the test output: foo\n".getBytes(UTF_8));
                }
                throw new SpawnExecException(
                    "Failure!!",
                    expectedSpawnResult,
                    /*forciblyRunRemotely=*/ false,
                    /*catastrophe=*/ false);
              } else {
                return ImmutableList.of(
                    new SpawnResult.Builder()
                        .setStatus(Status.SUCCESS)
                        .setRunnerName("test")
                        .build());
              }
            });

    FileOutErr outErr = createTempOutErr(tmpDirRoot);
    ActionExecutionContext actionExecutionContext =
        new FakeActionExecutionContext(outErr, spawnActionContext);

    // actual StandaloneTestStrategy execution
    List<SpawnResult> spawnResults =
        standaloneTestStrategy.exec(testRunnerAction, actionExecutionContext);

    // check that the rigged SpawnResult was returned
    assertThat(spawnResults).contains(expectedSpawnResult);
    // check that the test log contains all the output
    try {
      String logData = FileSystemUtils.readContent(testRunnerAction.getTestLog().getPath(), UTF_8);
      assertThat(logData).contains("bla");
      assertThat(logData).contains(TestLogHelper.HEADER_DELIMITER);
      assertThat(logData).contains("foo");
    } catch (IOException e) {
      fail("Test log missing: " + testRunnerAction.getTestLog().getPath());
    }
    // check that the test stdout contains all the expected output
    outErr.close(); // Create the output files.
    try {
      String outData = FileSystemUtils.readContent(outErr.getOutputPath(), UTF_8);
      assertThat(outData)
          .contains("==================== Test output for //standalone:failing_test:");
      assertThat(outData).doesNotContain("bla");
      assertThat(outData).doesNotContain(TestLogHelper.HEADER_DELIMITER);
      assertThat(outData).contains("foo");
      assertThat(outData)
          .contains(
              "================================================================================");
    } catch (IOException e) {
      fail("Test stdout file missing: " + outErr.getOutputPath());
    }
    assertThat(outErr.getErrorPath().exists()).isFalse();
  }

  @Test
  public void testThatTestLogAndOutputAreReturnedWithSplitXmlGeneration() throws Exception {
    ExecutionOptions executionOptions = Options.getDefaults(ExecutionOptions.class);
    executionOptions.testOutput = TestOutputFormat.ERRORS;
    executionOptions.splitXmlGeneration = true;
    Path tmpDirRoot = TestStrategy.getTmpRoot(rootDirectory, outputBase, executionOptions);
    BinTools binTools = BinTools.forUnitTesting(directories, analysisMock.getEmbeddedTools());
    TestedStandaloneTestStrategy standaloneTestStrategy =
        new TestedStandaloneTestStrategy(executionOptions, binTools, tmpDirRoot);

    // setup a test action
    scratch.file("standalone/failing_test.sh", "this does not get executed, it is mocked out");
    scratch.file(
        "standalone/BUILD",
        "sh_test(",
        "    name = \"failing_test\",",
        "    size = \"small\",",
        "    srcs = [\"failing_test.sh\"],",
        ")");
    TestRunnerAction testRunnerAction = getTestAction("//standalone:failing_test");

    SpawnResult testSpawnResult =
        new SpawnResult.Builder()
            .setStatus(Status.NON_ZERO_EXIT)
            .setExitCode(1)
            .setRunnerName("test")
            .build();
    SpawnResult xmlGeneratorSpawnResult =
        new SpawnResult.Builder()
            .setStatus(Status.SUCCESS)
            .setRunnerName("test")
            .build();
    List<FileOutErr> called = new ArrayList<>();
    when(spawnActionContext.exec(any(), any()))
        .thenAnswer(
            (invocation) -> {
              Spawn spawn = invocation.getArgumentAt(0, Spawn.class);
              FileOutErr outErr =
                  invocation.getArgumentAt(1, ActionExecutionContext.class).getFileOutErr();
              called.add(outErr);
              if (spawn.getOutputFiles().size() != 1) {
                try (OutputStream stream = outErr.getOutputStream()) {
                  stream.write("This will not appear in the test output: bla\n".getBytes(UTF_8));
                  stream.write((TestLogHelper.HEADER_DELIMITER + "\n").getBytes(UTF_8));
                  stream.write("This will appear in the test output: foo\n".getBytes(UTF_8));
                }
                throw new SpawnExecException(
                    "Failure!!",
                    testSpawnResult,
                    /*forciblyRunRemotely=*/ false,
                    /*catastrophe=*/ false);
              } else {
                String testName =
                    OS.getCurrent() == OS.WINDOWS
                        ? "standalone/failing_test.exe"
                        : "standalone/failing_test";
                assertThat(spawn.getEnvironment()).containsEntry("TEST_BINARY", testName);
                return ImmutableList.of(xmlGeneratorSpawnResult);
              }
            });

    FileOutErr outErr = createTempOutErr(tmpDirRoot);
    ActionExecutionContext actionExecutionContext =
        new FakeActionExecutionContext(outErr, spawnActionContext);

    // actual StandaloneTestStrategy execution
    List<SpawnResult> spawnResults =
        standaloneTestStrategy.exec(testRunnerAction, actionExecutionContext);

    // check that the rigged SpawnResult was returned
    assertThat(spawnResults).containsExactly(testSpawnResult, xmlGeneratorSpawnResult);
    // check that the test log contains all the output
    String logData = FileSystemUtils.readContent(testRunnerAction.getTestLog().getPath(), UTF_8);
    assertThat(logData).contains("bla");
    assertThat(logData).contains(TestLogHelper.HEADER_DELIMITER);
    assertThat(logData).contains("foo");
    // check that the test stdout contains all the expected output
    outErr.close(); // Create the output files.
    String outData = FileSystemUtils.readContent(outErr.getOutputPath(), UTF_8);
    assertThat(outData)
        .contains("==================== Test output for //standalone:failing_test:");
    assertThat(outData).doesNotContain("bla");
    assertThat(outData).doesNotContain(TestLogHelper.HEADER_DELIMITER);
    assertThat(outData).contains("foo");
    assertThat(outData)
        .contains(
            "================================================================================");
    assertThat(outErr.getErrorPath().exists()).isFalse();
    assertThat(called).hasSize(2);
    assertThat(called).containsNoDuplicates();
  }

  @Test
  public void testEmptyOutputCreatesEmptyLogFile() throws Exception {
    ExecutionOptions executionOptions = Options.getDefaults(ExecutionOptions.class);
    executionOptions.testOutput = TestOutputFormat.ALL;
    Path tmpDirRoot = TestStrategy.getTmpRoot(rootDirectory, outputBase, executionOptions);
    BinTools binTools = BinTools.forUnitTesting(directories, analysisMock.getEmbeddedTools());
    TestedStandaloneTestStrategy standaloneTestStrategy =
        new TestedStandaloneTestStrategy(executionOptions, binTools, tmpDirRoot);

    // setup a test action
    scratch.file("standalone/empty_test.sh", "this does not get executed, it is mocked out");
    scratch.file(
        "standalone/BUILD",
        "sh_test(",
        "    name = \"empty_test\",",
        "    size = \"small\",",
        "    srcs = [\"empty_test.sh\"],",
        ")");
    TestRunnerAction testRunnerAction = getTestAction("//standalone:empty_test");

    SpawnResult expectedSpawnResult =
        new SpawnResult.Builder().setStatus(Status.SUCCESS).setRunnerName("test").build();
    when(spawnActionContext.exec(any(), any())).thenReturn(ImmutableList.of(expectedSpawnResult));

    FileOutErr outErr = createTempOutErr(tmpDirRoot);
    ActionExecutionContext actionExecutionContext =
        new FakeActionExecutionContext(outErr, spawnActionContext);

    // actual StandaloneTestStrategy execution
    List<SpawnResult> spawnResults =
        standaloneTestStrategy.exec(testRunnerAction, actionExecutionContext);

    // check that the rigged SpawnResult was returned
    assertThat(spawnResults).contains(expectedSpawnResult);
    // check that the test log contains all the output
    try {
      String logData = FileSystemUtils.readContent(testRunnerAction.getTestLog().getPath(), UTF_8);
      assertThat(logData).isEmpty();
    } catch (IOException e) {
      fail("Test log missing: " + testRunnerAction.getTestLog().getPath());
    }
    // check that the test stdout contains all the expected output
    outErr.close(); // Create the output files.
    try {
      String outData = FileSystemUtils.readContent(outErr.getOutputPath(), UTF_8);
      String emptyOutput =
          "==================== Test output for //standalone:empty_test:(\\s)*"
              + "================================================================================(\\s)*";
      assertThat(outData).matches(emptyOutput);
    } catch (IOException e) {
      fail("Test stdout file missing: " + outErr.getOutputPath());
    }
    assertThat(outErr.getErrorPath().exists()).isFalse();
  }
}
