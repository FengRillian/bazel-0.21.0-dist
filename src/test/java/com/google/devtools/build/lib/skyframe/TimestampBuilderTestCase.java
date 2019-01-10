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
package com.google.devtools.build.lib.skyframe;

import static com.google.devtools.build.lib.actions.util.ActionCacheTestHelper.AMNESIAC_CACHE;

import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Range;
import com.google.common.collect.Sets;
import com.google.devtools.build.lib.actions.Action;
import com.google.devtools.build.lib.actions.ActionAnalysisMetadata;
import com.google.devtools.build.lib.actions.ActionCacheChecker;
import com.google.devtools.build.lib.actions.ActionExecutionContext;
import com.google.devtools.build.lib.actions.ActionExecutionException;
import com.google.devtools.build.lib.actions.ActionExecutionStatusReporter;
import com.google.devtools.build.lib.actions.ActionInputPrefetcher;
import com.google.devtools.build.lib.actions.ActionKeyContext;
import com.google.devtools.build.lib.actions.ActionLogBufferPathGenerator;
import com.google.devtools.build.lib.actions.ActionLookupData;
import com.google.devtools.build.lib.actions.ActionLookupValue;
import com.google.devtools.build.lib.actions.ActionResult;
import com.google.devtools.build.lib.actions.Actions;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.ArtifactOwner;
import com.google.devtools.build.lib.actions.ArtifactRoot;
import com.google.devtools.build.lib.actions.ArtifactSkyKey;
import com.google.devtools.build.lib.actions.BasicActionLookupValue;
import com.google.devtools.build.lib.actions.BuildFailedException;
import com.google.devtools.build.lib.actions.Executor;
import com.google.devtools.build.lib.actions.FileStateValue;
import com.google.devtools.build.lib.actions.FileValue;
import com.google.devtools.build.lib.actions.MetadataProvider;
import com.google.devtools.build.lib.actions.MutableActionGraph.ActionConflictException;
import com.google.devtools.build.lib.actions.ResourceManager;
import com.google.devtools.build.lib.actions.ResourceSet;
import com.google.devtools.build.lib.actions.TestExecException;
import com.google.devtools.build.lib.actions.cache.ActionCache;
import com.google.devtools.build.lib.actions.cache.Protos.ActionCacheStatistics;
import com.google.devtools.build.lib.actions.cache.Protos.ActionCacheStatistics.MissReason;
import com.google.devtools.build.lib.actions.util.DummyExecutor;
import com.google.devtools.build.lib.actions.util.InjectedActionLookupKey;
import com.google.devtools.build.lib.actions.util.TestAction;
import com.google.devtools.build.lib.analysis.BlazeDirectories;
import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.ServerDirectories;
import com.google.devtools.build.lib.analysis.TopLevelArtifactContext;
import com.google.devtools.build.lib.buildtool.BuildRequestOptions;
import com.google.devtools.build.lib.buildtool.SkyframeBuilder;
import com.google.devtools.build.lib.clock.BlazeClock;
import com.google.devtools.build.lib.clock.Clock;
import com.google.devtools.build.lib.events.Reporter;
import com.google.devtools.build.lib.events.StoredEventHandler;
import com.google.devtools.build.lib.exec.SingleBuildFileCache;
import com.google.devtools.build.lib.pkgcache.PathPackageLocator;
import com.google.devtools.build.lib.runtime.KeepGoingOption;
import com.google.devtools.build.lib.skyframe.AspectValue.AspectKey;
import com.google.devtools.build.lib.skyframe.ExternalFilesHelper.ExternalFileAction;
import com.google.devtools.build.lib.skyframe.PackageLookupFunction.CrossRepositoryLabelViolationStrategy;
import com.google.devtools.build.lib.skyframe.SkyframeActionExecutor.ActionCompletedReceiver;
import com.google.devtools.build.lib.skyframe.SkyframeActionExecutor.ProgressSupplier;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.AutoCodec;
import com.google.devtools.build.lib.testutil.FoundationTestCase;
import com.google.devtools.build.lib.testutil.TestConstants;
import com.google.devtools.build.lib.testutil.TestRuleClassProvider;
import com.google.devtools.build.lib.testutil.TestUtils;
import com.google.devtools.build.lib.util.AbruptExitException;
import com.google.devtools.build.lib.util.io.TimestampGranularityMonitor;
import com.google.devtools.build.lib.vfs.FileSystem;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.Root;
import com.google.devtools.build.skyframe.CycleInfo;
import com.google.devtools.build.skyframe.ErrorInfo;
import com.google.devtools.build.skyframe.EvaluationContext;
import com.google.devtools.build.skyframe.EvaluationProgressReceiver;
import com.google.devtools.build.skyframe.EvaluationResult;
import com.google.devtools.build.skyframe.InMemoryMemoizingEvaluator;
import com.google.devtools.build.skyframe.RecordingDifferencer;
import com.google.devtools.build.skyframe.SequencedRecordingDifferencer;
import com.google.devtools.build.skyframe.SequentialBuildDriver;
import com.google.devtools.build.skyframe.SkyFunction;
import com.google.devtools.build.skyframe.SkyFunctionException;
import com.google.devtools.build.skyframe.SkyFunctionName;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import com.google.devtools.common.options.OptionsParser;
import com.google.devtools.common.options.OptionsParsingException;
import com.google.devtools.common.options.OptionsProvider;
import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nullable;
import org.junit.Before;

/**
 * The common code that's shared between various builder tests.
 */
public abstract class TimestampBuilderTestCase extends FoundationTestCase {
  @AutoCodec
  protected static final ActionLookupValue.ActionLookupKey ACTION_LOOKUP_KEY =
      new InjectedActionLookupKey("action_lookup_key");

  protected static final Predicate<Action> ALWAYS_EXECUTE_FILTER = Predicates.alwaysTrue();
  protected static final String CYCLE_MSG = "Yarrrr, there be a cycle up in here";

  protected Clock clock = BlazeClock.instance();
  protected TimestampGranularityMonitor tsgm;
  protected RecordingDifferencer differencer = new SequencedRecordingDifferencer();
  private Set<ActionAnalysisMetadata> actions;
  protected OptionsParser options;

  protected final ActionKeyContext actionKeyContext = new ActionKeyContext();

  @Before
  public final void initialize() throws Exception  {
    options = OptionsParser.newOptionsParser(KeepGoingOption.class, BuildRequestOptions.class);
    options.parse();
    inMemoryCache = new InMemoryActionCache();
    tsgm = new TimestampGranularityMonitor(clock);
    ResourceManager.instance().setAvailableResources(ResourceSet.createWithRamCpu(100, 1));
    actions = new HashSet<>();
    actionTemplateExpansionFunction = new ActionTemplateExpansionFunction(actionKeyContext);
  }

  protected void clearActions() {
    actions.clear();
  }

  protected <T extends ActionAnalysisMetadata> T registerAction(T action) {
    actions.add(action);
    return action;
  }

  protected Builder createBuilder(ActionCache actionCache) throws Exception {
    return createBuilder(actionCache, 1, /*keepGoing=*/ false);
  }

  /**
   * Create a ParallelBuilder with a DatabaseDependencyChecker using the
   * specified ActionCache.
   */
  protected Builder createBuilder(
      ActionCache actionCache, final int threadCount, final boolean keepGoing) throws Exception {
    return createBuilder(actionCache, threadCount, keepGoing, null);
  }

  protected Builder createBuilder(
      final ActionCache actionCache,
      final int threadCount,
      final boolean keepGoing,
      @Nullable EvaluationProgressReceiver evaluationProgressReceiver) throws Exception {
    AtomicReference<PathPackageLocator> pkgLocator =
        new AtomicReference<>(
            new PathPackageLocator(
                outputBase,
                ImmutableList.of(Root.fromPath(rootDirectory)),
                BazelSkyframeExecutorConstants.BUILD_FILES_BY_PRIORITY));
    AtomicReference<TimestampGranularityMonitor> tsgmRef = new AtomicReference<>(tsgm);
    BlazeDirectories directories =
        new BlazeDirectories(
            new ServerDirectories(rootDirectory, outputBase, outputBase),
            rootDirectory,
            /* defaultSystemJavabase= */ null,
            TestConstants.PRODUCT_NAME);
    ExternalFilesHelper externalFilesHelper = ExternalFilesHelper.createForTesting(
        pkgLocator,
        ExternalFileAction.DEPEND_ON_EXTERNAL_PKG_FOR_EXTERNAL_REPO_PATHS,
        directories);
    differencer = new SequencedRecordingDifferencer();

    ActionExecutionStatusReporter statusReporter =
        ActionExecutionStatusReporter.create(new StoredEventHandler());
    final SkyframeActionExecutor skyframeActionExecutor =
        new SkyframeActionExecutor(
            actionKeyContext,
            new AtomicReference<>(statusReporter),
            /*sourceRootSupplier=*/ () -> ImmutableList.of(),
            /*sourceArtifactFactory=*/ unused -> null);

    Path actionOutputBase = scratch.dir("/usr/local/google/_blaze_jrluser/FAKEMD5/action_out/");
    skyframeActionExecutor.setActionLogBufferPathGenerator(
        new ActionLogBufferPathGenerator(actionOutputBase));

    MetadataProvider cache =
        new SingleBuildFileCache(rootDirectory.getPathString(), scratch.getFileSystem());
    skyframeActionExecutor.configure(cache, ActionInputPrefetcher.NONE);

    final InMemoryMemoizingEvaluator evaluator =
        new InMemoryMemoizingEvaluator(
            ImmutableMap.<SkyFunctionName, SkyFunction>builder()
                .put(FileStateValue.FILE_STATE, new FileStateFunction(tsgmRef, externalFilesHelper))
                .put(FileValue.FILE, new FileFunction(pkgLocator))
                .put(Artifact.ARTIFACT, new ArtifactFunction(() -> true))
                .put(
                    SkyFunctions.ACTION_EXECUTION,
                    new ActionExecutionFunction(skyframeActionExecutor, directories, tsgmRef))
                .put(
                    SkyFunctions.PACKAGE,
                    new PackageFunction(null, null, null, null, null, null, null))
                .put(
                    SkyFunctions.PACKAGE_LOOKUP,
                    new PackageLookupFunction(
                        null,
                        CrossRepositoryLabelViolationStrategy.ERROR,
                        BazelSkyframeExecutorConstants.BUILD_FILES_BY_PRIORITY))
                .put(
                    SkyFunctions.WORKSPACE_AST,
                    new WorkspaceASTFunction(TestRuleClassProvider.getRuleClassProvider()))
                .put(
                    SkyFunctions.WORKSPACE_FILE,
                    new WorkspaceFileFunction(
                        TestRuleClassProvider.getRuleClassProvider(),
                        TestConstants.PACKAGE_FACTORY_BUILDER_FACTORY_FOR_TESTING
                            .builder(directories)
                            .build(TestRuleClassProvider.getRuleClassProvider()),
                        directories))
                .put(SkyFunctions.EXTERNAL_PACKAGE, new ExternalPackageFunction())
                .put(
                    SkyFunctions.ACTION_TEMPLATE_EXPANSION,
                    new DelegatingActionTemplateExpansionFunction())
                .build(),
            differencer,
            evaluationProgressReceiver);
    final SequentialBuildDriver driver = new SequentialBuildDriver(evaluator);
    PrecomputedValue.BUILD_ID.set(differencer, UUID.randomUUID());
    PrecomputedValue.ACTION_ENV.set(differencer, ImmutableMap.<String, String>of());
    PrecomputedValue.PATH_PACKAGE_LOCATOR.set(differencer, pkgLocator.get());

    return new Builder() {
      private void setGeneratingActions() throws ActionConflictException {
        if (evaluator.getExistingValue(ACTION_LOOKUP_KEY) == null) {
          differencer.inject(
              ImmutableMap.of(
                  ACTION_LOOKUP_KEY,
                  new BasicActionLookupValue(
                      Actions.filterSharedActionsAndThrowActionConflict(
                          actionKeyContext, ImmutableList.copyOf(actions)))));
        }
      }

      @Override
      public void buildArtifacts(
          Reporter reporter,
          Set<Artifact> artifacts,
          Set<ConfiguredTarget> parallelTests,
          Set<ConfiguredTarget> exclusiveTests,
          Set<ConfiguredTarget> targetsToBuild,
          Set<ConfiguredTarget> targetsToSkip,
          Collection<AspectValue> aspects,
          Executor executor,
          Set<ConfiguredTargetKey> builtTargets,
          Set<AspectKey> builtAspects,
          OptionsProvider options,
          Range<Long> lastExecutionTimeRange,
          TopLevelArtifactContext topLevelArtifactContext)
          throws BuildFailedException, AbruptExitException, InterruptedException,
              TestExecException {
        skyframeActionExecutor.prepareForExecution(
            reporter,
            executor,
            options,
            new ActionCacheChecker(
                actionCache, null, actionKeyContext, ALWAYS_EXECUTE_FILTER, null),
            null);
        skyframeActionExecutor.setActionExecutionProgressReportingObjects(
            EMPTY_PROGRESS_SUPPLIER, EMPTY_COMPLETION_RECEIVER);

        List<SkyKey> keys = new ArrayList<>();
        for (Artifact artifact : artifacts) {
          keys.add(ArtifactSkyKey.key(artifact, true));
        }

        try {
          setGeneratingActions();
        } catch (ActionConflictException e) {
          throw new IllegalStateException(e);
        }

        EvaluationContext evaluationContext =
            EvaluationContext.newBuilder()
                .setKeepGoing(keepGoing)
                .setNumThreads(threadCount)
                .setEventHander(reporter)
                .build();
        EvaluationResult<SkyValue> result = driver.evaluate(keys, evaluationContext);

        if (result.hasError()) {
          boolean hasCycles = false;
          for (Map.Entry<SkyKey, ErrorInfo> entry : result.errorMap().entrySet()) {
            Iterable<CycleInfo> cycles = entry.getValue().getCycleInfo();
            hasCycles |= !Iterables.isEmpty(cycles);
          }
          if (hasCycles) {
            throw new BuildFailedException(CYCLE_MSG);
          } else if (result.errorMap().isEmpty() || keepGoing) {
            throw new BuildFailedException();
          } else {
            SkyframeBuilder.rethrow(Preconditions.checkNotNull(result.getError().getException()));
          }
        }
      }
    };
  }

  /** A non-persistent cache. */
  protected InMemoryActionCache inMemoryCache;

  protected SkyFunction actionTemplateExpansionFunction;

  /** A class that records an event. */
  protected static class Button implements Runnable {
    protected boolean pressed = false;

    @Override
    public void run() {
      pressed = true;
    }
  }

  /** A class that counts occurrences of an event. */
  static class Counter implements Runnable {
    int count = 0;

    @Override
    public void run() {
      count++;
    }
  }

  protected Artifact createSourceArtifact(String name) {
    return createSourceArtifact(scratch.getFileSystem(), name);
  }

  private static Artifact createSourceArtifact(FileSystem fs, String name) {
    Path root = fs.getPath(TestUtils.tmpDir());
    return new Artifact.SourceArtifact(
        ArtifactRoot.asSourceRoot(Root.fromPath(root)),
        PathFragment.create(name),
        ArtifactOwner.NullArtifactOwner.INSTANCE);
  }

  protected Artifact createDerivedArtifact(String name) {
    return createDerivedArtifact(scratch.getFileSystem(), name);
  }

  Artifact createDerivedArtifact(FileSystem fs, String name) {
    Path execRoot = fs.getPath(TestUtils.tmpDir());
    PathFragment execPath = PathFragment.create("out").getRelative(name);
    return new Artifact(
        ArtifactRoot.asDerivedRoot(execRoot, execRoot.getRelative("out")),
        execPath,
        ACTION_LOOKUP_KEY);
  }

  /**
   * Creates and returns a new "amnesiac" builder based on the amnesiac cache.
   */
  protected Builder amnesiacBuilder() throws Exception {
    return createBuilder(AMNESIAC_CACHE);
  }

  /**
   * Creates and returns a new caching builder based on the inMemoryCache.
   */
  protected Builder cachingBuilder() throws Exception {
    return createBuilder(inMemoryCache);
  }

  /**
   * Creates a TestAction from 'inputs' to 'outputs', and a new button, such
   * that executing the action causes the button to be pressed.  The button is
   * returned.
   */
  protected Button createActionButton(Collection<Artifact> inputs, Collection<Artifact> outputs) {
    Button button = new Button();
    registerAction(new TestAction(button, inputs, outputs));
    return button;
  }

  /**
   * Creates a TestAction from 'inputs' to 'outputs', and a new counter, such
   * that executing the action causes the counter to be incremented.  The
   * counter is returned.
   */
  protected Counter createActionCounter(Collection<Artifact> inputs, Collection<Artifact> outputs) {
    Counter counter = new Counter();
    registerAction(new TestAction(counter, inputs, outputs));
    return counter;
  }

  protected static Set<Artifact> emptySet = Collections.emptySet();

  protected void buildArtifacts(Builder builder, Artifact... artifacts)
      throws BuildFailedException, AbruptExitException, InterruptedException, TestExecException,
          OptionsParsingException {
    buildArtifacts(builder, new DummyExecutor(fileSystem, rootDirectory), artifacts);
  }

  protected void buildArtifacts(Builder builder, Executor executor, Artifact... artifacts)
      throws BuildFailedException, AbruptExitException, InterruptedException, TestExecException,
          OptionsParsingException {

    tsgm.setCommandStartTime();
    Set<Artifact> artifactsToBuild = Sets.newHashSet(artifacts);
    Set<ConfiguredTargetKey> builtTargets = new HashSet<>();
    Set<AspectKey> builtAspects = new HashSet<>();
    try {
      builder.buildArtifacts(
          reporter,
          artifactsToBuild,
          null,
          null,
          null,
          null,
          null,
          executor,
          builtTargets,
          builtAspects,
          options,
          null,
          null);
    } finally {
      tsgm.waitForTimestampGranularity(reporter.getOutErr());
    }
  }

  /** {@link TestAction} that copies its single input to its single output. */
  protected static class CopyingAction extends TestAction {
    CopyingAction(Runnable effect, Artifact input, Artifact output) {
      super(effect, ImmutableSet.of(input), ImmutableSet.of(output));
    }

    @Override
    public ActionResult execute(ActionExecutionContext actionExecutionContext)
        throws ActionExecutionException {
      ActionResult actionResult = super.execute(actionExecutionContext);
      try {
        FileSystemUtils.copyFile(
            Iterables.getOnlyElement(getInputs()).getPath(),
            Iterables.getOnlyElement(getOutputs()).getPath());
      } catch (IOException e) {
        throw new IllegalStateException(e);
      }
      return actionResult;
    }
  }

  /** In-memory {@link ActionCache} backed by a HashMap */
  protected static class InMemoryActionCache implements ActionCache {

    private final Map<String, Entry> actionCache = new HashMap<>();

    @Override
    public synchronized void put(String key, ActionCache.Entry entry) {
      actionCache.put(key, entry);
    }

    @Override
    public synchronized Entry get(String key) {
      return actionCache.get(key);
    }

    @Override
    public synchronized void remove(String key) {
      actionCache.remove(key);
    }

    public synchronized void reset() {
      actionCache.clear();
    }

    @Override
    public long save() {
      // safe to ignore
      return 0;
    }

    @Override
    public void clear() {
      // safe to ignore
    }

    @Override
    public void dump(PrintStream out) {
      out.println("In-memory action cache has " + actionCache.size() + " records");
    }

    @Override
    public void accountHit() {
      // Not needed for these tests.
    }

    @Override
    public void accountMiss(MissReason reason) {
      // Not needed for these tests.
    }

    @Override
    public void mergeIntoActionCacheStatistics(ActionCacheStatistics.Builder builder) {
      // Not needed for these tests.
    }

    @Override
    public void resetStatistics() {
      // Not needed for these tests.
    }
  }

  private class DelegatingActionTemplateExpansionFunction implements SkyFunction {
    @Override
    public SkyValue compute(SkyKey skyKey, Environment env)
        throws SkyFunctionException, InterruptedException {
      return actionTemplateExpansionFunction.compute(skyKey, env);
    }

    @Override
    public String extractTag(SkyKey skyKey) {
      return actionTemplateExpansionFunction.extractTag(skyKey);
    }
  }

  private static final ProgressSupplier EMPTY_PROGRESS_SUPPLIER =
      new ProgressSupplier() {
        @Override
        public String getProgressString() {
          return "";
        }
      };

  private static final ActionCompletedReceiver EMPTY_COMPLETION_RECEIVER =
      new ActionCompletedReceiver() {
        @Override
        public void actionCompleted(ActionLookupData actionLookupData) {}

        @Override
        public void noteActionEvaluationStarted(ActionLookupData actionLookupData, Action action) {}
      };
}
