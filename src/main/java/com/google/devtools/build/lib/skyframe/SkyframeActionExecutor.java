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
package com.google.devtools.build.lib.skyframe;

import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.common.eventbus.EventBus;
import com.google.common.util.concurrent.Striped;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.google.devtools.build.lib.actions.Action;
import com.google.devtools.build.lib.actions.ActionAnalysisMetadata;
import com.google.devtools.build.lib.actions.ActionCacheChecker;
import com.google.devtools.build.lib.actions.ActionCacheChecker.Token;
import com.google.devtools.build.lib.actions.ActionCompletionEvent;
import com.google.devtools.build.lib.actions.ActionContext;
import com.google.devtools.build.lib.actions.ActionExecutedEvent;
import com.google.devtools.build.lib.actions.ActionExecutedEvent.ErrorTiming;
import com.google.devtools.build.lib.actions.ActionExecutionContext;
import com.google.devtools.build.lib.actions.ActionExecutionException;
import com.google.devtools.build.lib.actions.ActionExecutionStatusReporter;
import com.google.devtools.build.lib.actions.ActionGraph;
import com.google.devtools.build.lib.actions.ActionInput;
import com.google.devtools.build.lib.actions.ActionInputMap;
import com.google.devtools.build.lib.actions.ActionInputPrefetcher;
import com.google.devtools.build.lib.actions.ActionKeyContext;
import com.google.devtools.build.lib.actions.ActionLogBufferPathGenerator;
import com.google.devtools.build.lib.actions.ActionLookupData;
import com.google.devtools.build.lib.actions.ActionLookupValue;
import com.google.devtools.build.lib.actions.ActionMiddlemanEvent;
import com.google.devtools.build.lib.actions.ActionResult;
import com.google.devtools.build.lib.actions.ActionResultReceivedEvent;
import com.google.devtools.build.lib.actions.ActionStartedEvent;
import com.google.devtools.build.lib.actions.ActionStatusMessage;
import com.google.devtools.build.lib.actions.Actions;
import com.google.devtools.build.lib.actions.AlreadyReportedActionExecutionException;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.Artifact.ArtifactExpanderImpl;
import com.google.devtools.build.lib.actions.Artifact.OwnerlessArtifactWrapper;
import com.google.devtools.build.lib.actions.Artifact.SourceArtifact;
import com.google.devtools.build.lib.actions.ArtifactPathResolver;
import com.google.devtools.build.lib.actions.ArtifactPrefixConflictException;
import com.google.devtools.build.lib.actions.CachedActionEvent;
import com.google.devtools.build.lib.actions.EnvironmentalExecException;
import com.google.devtools.build.lib.actions.Executor;
import com.google.devtools.build.lib.actions.FileArtifactValue;
import com.google.devtools.build.lib.actions.FilesetOutputSymlink;
import com.google.devtools.build.lib.actions.LostInputsExecException.LostInputsActionExecutionException;
import com.google.devtools.build.lib.actions.MapBasedActionGraph;
import com.google.devtools.build.lib.actions.MetadataConsumer;
import com.google.devtools.build.lib.actions.MetadataProvider;
import com.google.devtools.build.lib.actions.MutableActionGraph;
import com.google.devtools.build.lib.actions.MutableActionGraph.ActionConflictException;
import com.google.devtools.build.lib.actions.NotifyOnActionCacheHit;
import com.google.devtools.build.lib.actions.NotifyOnActionCacheHit.ActionCachedContext;
import com.google.devtools.build.lib.actions.PackageRootResolver;
import com.google.devtools.build.lib.actions.TargetOutOfDateException;
import com.google.devtools.build.lib.actions.cache.MetadataHandler;
import com.google.devtools.build.lib.buildtool.BuildRequestOptions;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.concurrent.ExecutorUtil;
import com.google.devtools.build.lib.concurrent.Sharder;
import com.google.devtools.build.lib.concurrent.ThrowableRecordingRunnableWrapper;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.EventHandler;
import com.google.devtools.build.lib.events.ExtendedEventHandler;
import com.google.devtools.build.lib.events.Reporter;
import com.google.devtools.build.lib.profiler.Profiler;
import com.google.devtools.build.lib.profiler.ProfilerTask;
import com.google.devtools.build.lib.profiler.SilentCloseable;
import com.google.devtools.build.lib.rules.cpp.IncludeScannable;
import com.google.devtools.build.lib.runtime.KeepGoingOption;
import com.google.devtools.build.lib.util.Pair;
import com.google.devtools.build.lib.util.io.FileOutErr;
import com.google.devtools.build.lib.vfs.FileSystem;
import com.google.devtools.build.lib.vfs.OutputService;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.Root;
import com.google.devtools.build.lib.vfs.Symlinks;
import com.google.devtools.build.skyframe.SkyFunction.Environment;
import com.google.devtools.common.options.OptionsProvider;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ConcurrentNavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Lock;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Logger;
import javax.annotation.Nullable;

/**
 * Action executor: takes care of preparing an action for execution, executing it, validating that
 * all output artifacts were created, error reporting, etc.
 */
public final class SkyframeActionExecutor {
  private static final Logger logger = Logger.getLogger(SkyframeActionExecutor.class.getName());

  // Used to prevent check-then-act races in #createOutputDirectories. See the comment there for
  // more detail.
  private static final Striped<Lock> outputDirectoryDeletionLock = Striped.lock(64);

  private final ActionKeyContext actionKeyContext;
  private Reporter reporter;
  private Map<String, String> clientEnv = ImmutableMap.of();
  private Executor executorEngine;
  private ActionLogBufferPathGenerator actionLogBufferPathGenerator;
  private ActionCacheChecker actionCacheChecker;
  private final Profiler profiler = Profiler.instance();

  // We keep track of actions already executed this build in order to avoid executing a shared
  // action twice. Note that we may still unnecessarily re-execute the action on a subsequent
  // build: say actions A and B are shared. If A is requested on the first build and then B is
  // requested on the second build, we will execute B even though its output files are up to date.
  // However, we will not re-execute A on a subsequent build.
  // We do not allow the shared action to re-execute in the same build, even after the first
  // action has finished execution, because a downstream action might be reading the output file
  // at the same time as the shared action was writing to it.
  // This map is also used for Actions that try to execute twice because they have discovered
  // headers -- the SkyFunction tries to declare a dep on the missing headers and has to restart.
  // We don't want to execute the action again on the second entry to the SkyFunction.
  // In both cases, we store the already-computed ActionExecutionValue to avoid having to compute it
  // again.
  private ConcurrentMap<
          OwnerlessArtifactWrapper, Pair<ActionLookupData, FutureTask<ActionExecutionValue>>>
      buildActionMap;

  // Errors found when examining all actions in the graph are stored here, so that they can be
  // thrown when execution of the action is requested. This field is set during each call to
  // findAndStoreArtifactConflicts, and is preserved across builds otherwise.
  private ImmutableMap<ActionAnalysisMetadata, ConflictException> badActionMap = ImmutableMap.of();
  private OptionsProvider options;
  private boolean hadExecutionError;
  private MetadataProvider perBuildFileCache;
  private ActionInputPrefetcher actionInputPrefetcher;
  /** These variables are nulled out between executions. */
  private ProgressSupplier progressSupplier;
  private ActionCompletedReceiver completionReceiver;

  private final AtomicReference<ActionExecutionStatusReporter> statusReporterRef;
  private OutputService outputService;
  private boolean finalizeActions;
  private final Supplier<ImmutableList<Root>> sourceRootSupplier;
  private final Function<PathFragment, SourceArtifact> sourceArtifactFactory;

  SkyframeActionExecutor(
      ActionKeyContext actionKeyContext,
      AtomicReference<ActionExecutionStatusReporter> statusReporterRef,
      Supplier<ImmutableList<Root>> sourceRootSupplier,
      Function<PathFragment, SourceArtifact> sourceArtifactFactory) {
    this.actionKeyContext = actionKeyContext;
    this.statusReporterRef = statusReporterRef;
    this.sourceRootSupplier = sourceRootSupplier;
    this.sourceArtifactFactory = sourceArtifactFactory;
  }

  /**
   * A typed union of {@link ActionConflictException}, which indicates two actions that generate
   * the same {@link Artifact}, and {@link ArtifactPrefixConflictException}, which indicates that
   * the path of one {@link Artifact} is a prefix of another.
   */
  public static class ConflictException extends Exception {
    @Nullable private final ActionConflictException ace;
    @Nullable private final ArtifactPrefixConflictException apce;

    public ConflictException(ActionConflictException e) {
      super(e);
      this.ace = e;
      this.apce = null;
    }

    public ConflictException(ArtifactPrefixConflictException e) {
      super(e);
      this.ace = null;
      this.apce = e;
    }

    void rethrowTyped() throws ActionConflictException, ArtifactPrefixConflictException {
      if (ace == null) {
        throw Preconditions.checkNotNull(apce);
      }
      if (apce == null) {
        throw Preconditions.checkNotNull(ace);
      }
      throw new IllegalStateException();
    }
  }

  /**
   * Return the map of mostly recently executed bad actions to their corresponding exception.
   * See {#findAndStoreArtifactConflicts()}.
   */
  public ImmutableMap<ActionAnalysisMetadata, ConflictException> badActions() {
    // TODO(bazel-team): Move badActions() and findAndStoreArtifactConflicts() to SkyframeBuildView
    // now that it's done in the analysis phase.
    return badActionMap;
  }

  /**
   * Find conflicts between generated artifacts. There are two ways to have conflicts. First, if
   * two (unshareable) actions generate the same output artifact, this will result in an {@link
   * ActionConflictException}. Second, if one action generates an artifact whose path is a prefix of
   * another artifact's path, those two artifacts cannot exist simultaneously in the output tree.
   * This causes an {@link ArtifactPrefixConflictException}. The relevant exceptions are stored in
   * the executor in {@code badActionMap}, and will be thrown immediately when that action is
   * executed. Those exceptions persist, so that even if the action is not executed this build, the
   * first time it is executed, the correct exception will be thrown.
   *
   * <p>This method must be called if a new action was added to the graph this build, so
   * whenever a new configured target was analyzed this build. It is somewhat expensive (~1s
   * range for a medium build as of 2014), so it should only be called when necessary.
   *
   * <p>Conflicts found may not be requested this build, and so we may overzealously throw an error.
   * For instance, if actions A and B generate the same artifact foo, and the user first requests
   * A' depending on A, and then in a subsequent build B' depending on B, we will fail the second
   * build, even though it would have succeeded if it had been the only build. However, since
   * Skyframe does not know the transitive dependencies of the request, we err on the conservative
   * side.
   *
   * <p>If the user first runs one action on the first build, and on the second build adds a
   * conflicting action, only the second action's error may be reported (because the first action
   * will be cached), whereas if both actions were requested for the first time, both errors would
   * be reported. However, the first time an action is added to the build, we are guaranteed to find
   * any conflicts it has, since this method will compare it against all other actions. So there is
   * no sequence of builds that can evade the error.
   */
  void findAndStoreArtifactConflicts(Iterable<ActionLookupValue> actionLookupValues)
      throws InterruptedException {
    ConcurrentMap<ActionAnalysisMetadata, ConflictException> temporaryBadActionMap =
        new ConcurrentHashMap<>();
    Pair<ActionGraph, SortedMap<PathFragment, Artifact>> result;
    result =
        constructActionGraphAndPathMap(actionKeyContext, actionLookupValues, temporaryBadActionMap);
    ActionGraph actionGraph = result.first;
    SortedMap<PathFragment, Artifact> artifactPathMap = result.second;

    Map<ActionAnalysisMetadata, ArtifactPrefixConflictException> actionsWithArtifactPrefixConflict =
        Actions.findArtifactPrefixConflicts(actionGraph, artifactPathMap);
    for (Map.Entry<ActionAnalysisMetadata, ArtifactPrefixConflictException> actionExceptionPair :
        actionsWithArtifactPrefixConflict.entrySet()) {
      temporaryBadActionMap.put(
          actionExceptionPair.getKey(), new ConflictException(actionExceptionPair.getValue()));
    }

    this.badActionMap = ImmutableMap.copyOf(temporaryBadActionMap);
  }

  /**
   * Simultaneously construct an action graph for all the actions in Skyframe and a map from {@link
   * PathFragment}s to their respective {@link Artifact}s. We do this in a threadpool to save around
   * 1.5 seconds on a mid-sized build versus a single-threaded operation.
   */
  private static Pair<ActionGraph, SortedMap<PathFragment, Artifact>>
      constructActionGraphAndPathMap(
          ActionKeyContext actionKeyContext,
          Iterable<ActionLookupValue> values,
          ConcurrentMap<ActionAnalysisMetadata, ConflictException> badActionMap)
          throws InterruptedException {
    MutableActionGraph actionGraph = new MapBasedActionGraph(actionKeyContext);
    ConcurrentNavigableMap<PathFragment, Artifact> artifactPathMap =
        new ConcurrentSkipListMap<>(Actions.comparatorForPrefixConflicts());
    // Action graph construction is CPU-bound.
    int numJobs = Runtime.getRuntime().availableProcessors();
    // No great reason for expecting 5000 action lookup values, but not worth counting size of
    // values.
    Sharder<ActionLookupValue> actionShards = new Sharder<>(numJobs, 5000);
    for (ActionLookupValue value : values) {
      actionShards.add(value);
    }

    ThrowableRecordingRunnableWrapper wrapper = new ThrowableRecordingRunnableWrapper(
        "SkyframeActionExecutor#constructActionGraphAndPathMap");

    ExecutorService executor = Executors.newFixedThreadPool(
        numJobs,
        new ThreadFactoryBuilder().setNameFormat("ActionLookupValue Processor %d").build());
    Set<ActionAnalysisMetadata> registeredActions = Sets.newConcurrentHashSet();
    for (List<ActionLookupValue> shard : actionShards) {
      executor.execute(
          wrapper.wrap(
              actionRegistration(
                  shard, actionGraph, artifactPathMap, badActionMap, registeredActions)));
    }
    boolean interrupted = ExecutorUtil.interruptibleShutdown(executor);
    Throwables.propagateIfPossible(wrapper.getFirstThrownError());
    if (interrupted) {
      throw new InterruptedException();
    }
    return Pair.<ActionGraph, SortedMap<PathFragment, Artifact>>of(actionGraph, artifactPathMap);
  }

  private static Runnable actionRegistration(
      final List<ActionLookupValue> values,
      final MutableActionGraph actionGraph,
      final ConcurrentMap<PathFragment, Artifact> artifactPathMap,
      final ConcurrentMap<ActionAnalysisMetadata, ConflictException> badActionMap,
      final Set<ActionAnalysisMetadata> registeredActions) {
    return new Runnable() {
      @Override
      public void run() {
        for (ActionLookupValue value : values) {
          for (Map.Entry<Artifact, ActionAnalysisMetadata> entry :
              value.getMapForConsistencyCheck().entrySet()) {
            ActionAnalysisMetadata action = entry.getValue();
            // We have an entry for each <action, artifact> pair. Only try to register each action
            // once.
            if (registeredActions.add(action)) {
              try {
                actionGraph.registerAction(action);
              } catch (ActionConflictException e) {
                Exception oldException = badActionMap.put(action, new ConflictException(e));
                Preconditions.checkState(
                    oldException == null, "%s | %s | %s", action, e, oldException);
                // We skip the rest of the loop, and do not add the path->artifact mapping for this
                // artifact below -- we don't need to check it since this action is already in
                // error.
                continue;
              }
            }
            artifactPathMap.put(entry.getKey().getExecPath(), entry.getKey());
          }
        }
      }
    };
  }

  void prepareForExecution(
      Reporter reporter,
      Executor executor,
      OptionsProvider options,
      ActionCacheChecker actionCacheChecker,
      OutputService outputService) {
    this.reporter = Preconditions.checkNotNull(reporter);
    this.executorEngine = Preconditions.checkNotNull(executor);

    // Start with a new map each build so there's no issue with internal resizing.
    this.buildActionMap = Maps.newConcurrentMap();
    this.hadExecutionError = false;
    this.actionCacheChecker = Preconditions.checkNotNull(actionCacheChecker);
    // Don't cache possibly stale data from the last build.
    this.options = options;
    // Cache the finalizeActions value for performance, since we consult it on every action.
    this.finalizeActions = options.getOptions(BuildRequestOptions.class).finalizeActions;
    this.outputService = outputService;
  }

  public void setActionLogBufferPathGenerator(
      ActionLogBufferPathGenerator actionLogBufferPathGenerator) {
    this.actionLogBufferPathGenerator = actionLogBufferPathGenerator;
  }

  public void setClientEnv(Map<String, String> clientEnv) {
    // Copy once here, instead of on every construction of ActionExecutionContext.
    this.clientEnv = ImmutableMap.copyOf(clientEnv);
  }

  boolean usesActionFileSystem() {
    return outputService != null && outputService.supportsActionFileSystem();
  }

  Path getExecRoot() {
    return executorEngine.getExecRoot();
  }

  /** REQUIRES: {@link #usesActionFileSystem()} is true */
  FileSystem createActionFileSystem(
      String relativeOutputPath,
      ActionInputMap inputArtifactData,
      Iterable<Artifact> outputArtifacts) {
    return outputService.createActionFileSystem(
        executorEngine.getFileSystem(),
        executorEngine.getExecRoot().asFragment(),
        relativeOutputPath,
        sourceRootSupplier.get(),
        inputArtifactData,
        outputArtifacts,
        sourceArtifactFactory);
  }

  void updateActionFileSystemContext(
      FileSystem actionFileSystem,
      Environment env,
      MetadataConsumer consumer,
      ImmutableMap<Artifact, ImmutableList<FilesetOutputSymlink>> filesets)
      throws IOException {
    outputService.updateActionFileSystemContext(actionFileSystem, env, consumer, filesets);
  }

  void executionOver() {
    // These may transitively holds a bunch of heavy objects, so it's important to clear it at the
    // end of a build.
    this.reporter = null;
    this.options = null;
    this.executorEngine = null;
    this.outputService = null;
    this.buildActionMap = null;
    this.actionCacheChecker = null;
  }

  boolean probeActionExecution(Action action) {
    return buildActionMap.containsKey(new OwnerlessArtifactWrapper(action.getPrimaryOutput()));
  }

  void resetActionExecution(Action action) {
    buildActionMap.remove(new OwnerlessArtifactWrapper(action.getPrimaryOutput()));
  }

  private boolean actionReallyExecuted(Action action, ActionLookupData actionLookupData) {
    Pair<ActionLookupData, ?> cachedRun =
        Preconditions.checkNotNull(
            buildActionMap.get(new OwnerlessArtifactWrapper(action.getPrimaryOutput())),
            "%s %s",
            action,
            actionLookupData);
    return actionLookupData.equals(cachedRun.getFirst());
  }

  void noteActionEvaluationStarted(ActionLookupData actionLookupData, Action action) {
    this.completionReceiver.noteActionEvaluationStarted(actionLookupData, action);
  }

  /**
   * Executes the provided action on the current thread. Returns the ActionExecutionValue with the
   * result, either computed here or already computed on another thread.
   *
   * <p>For use from {@link ArtifactFunction} only.
   */
  ActionExecutionValue executeAction(
      ExtendedEventHandler eventHandler,
      Action action,
      ActionMetadataHandler metadataHandler,
      long actionStartTime,
      ActionExecutionContext actionExecutionContext,
      ActionLookupData actionLookupData)
      throws ActionExecutionException, InterruptedException {
    Exception exception = badActionMap.get(action);
    if (exception != null) {
      // If action had a conflict with some other action in the graph, report it now.
      reportError(exception.getMessage(), exception, action, null);
    }
    Artifact primaryOutput = action.getPrimaryOutput();
    FutureTask<ActionExecutionValue> actionTask =
        new FutureTask<>(
            new ActionRunner(
                eventHandler,
                action,
                metadataHandler,
                actionStartTime,
                actionExecutionContext,
                actionLookupData));
    // Check to see if another action is already executing/has executed this value.
    Pair<ActionLookupData, FutureTask<ActionExecutionValue>> oldAction =
        buildActionMap.putIfAbsent(
            new OwnerlessArtifactWrapper(primaryOutput), Pair.of(actionLookupData, actionTask));
    // true if this is a non-shared action or it's shared and to be executed.
    boolean isPrimaryActionForTheValue = oldAction == null;

    if (isPrimaryActionForTheValue) {
      actionTask.run();
    } else {
      // Wait for other action to finish, so any actions that depend on its outputs can execute.
      actionTask = oldAction.second;
    }
    try {
      ActionExecutionValue value = actionTask.get();
      return isPrimaryActionForTheValue
          ? value
          : value.transformForSharedAction(action.getOutputs());
    } catch (ExecutionException e) {
      Throwables.propagateIfPossible(e.getCause(),
          ActionExecutionException.class, InterruptedException.class);
      throw new IllegalStateException(e);
    } finally {
      String message = action.getProgressMessage();
      if (message != null) {
        // Tell the receiver that the action has completed *before* telling the reporter.
        // This way the latter will correctly show the number of completed actions when task
        // completion messages are enabled (--show_task_finish).
        completionReceiver.actionCompleted(actionLookupData);
        reporter.finishTask(null, prependExecPhaseStats(message));
      }
    }
  }

  /**
   * Returns an ActionExecutionContext suitable for executing a particular action. The caller should
   * pass the returned context to {@link #executeAction}, and any other method that needs to execute
   * tasks related to that action.
   */
  public ActionExecutionContext getContext(
      MetadataProvider perActionFileCache,
      MetadataHandler metadataHandler,
      Map<Artifact, Collection<Artifact>> expandedInputs,
      Map<Artifact, ImmutableList<FilesetOutputSymlink>> expandedFilesets,
      ImmutableMap<Artifact, ImmutableList<FilesetOutputSymlink>> topLevelFilesets,
      @Nullable FileSystem actionFileSystem,
      @Nullable Object skyframeDepsResult) {
    FileOutErr fileOutErr = actionLogBufferPathGenerator.generate(
        ArtifactPathResolver.createPathResolver(actionFileSystem, executorEngine.getExecRoot()));
    return new ActionExecutionContext(
        executorEngine,
        createFileCache(perActionFileCache, actionFileSystem),
        actionInputPrefetcher,
        actionKeyContext,
        metadataHandler,
        fileOutErr,
        clientEnv,
        topLevelFilesets,
        new ArtifactExpanderImpl(expandedInputs, expandedFilesets),
        actionFileSystem,
        skyframeDepsResult);
  }

  /**
   * Checks the action cache to see if {@code action} needs to be executed, or is up to date.
   * Returns a token with the semantics of {@link ActionCacheChecker#getTokenIfNeedToExecute}: null
   * if the action is up to date, and non-null if it needs to be executed, in which case that token
   * should be provided to the ActionCacheChecker after execution.
   */
  Token checkActionCache(
      ExtendedEventHandler eventHandler,
      Action action,
      MetadataHandler metadataHandler,
      long actionStartTime,
      Iterable<Artifact> resolvedCacheArtifacts,
      Map<String, String> clientEnv) {
    Token token;
    try (SilentCloseable c = profiler.profile(ProfilerTask.ACTION_CHECK, action.describe())) {
      token =
          actionCacheChecker.getTokenIfNeedToExecute(
              action,
              resolvedCacheArtifacts,
              clientEnv,
              options.getOptions(BuildRequestOptions.class).explanationPath != null
                  ? reporter
                  : null,
              metadataHandler);
    }
    if (token == null) {
      boolean eventPosted = false;
      // Notify BlazeRuntimeStatistics about the action middleman 'execution'.
      if (action.getActionType().isMiddleman()) {
        eventHandler.post(new ActionMiddlemanEvent(action, actionStartTime));
        eventPosted = true;
      }

      if (action instanceof NotifyOnActionCacheHit) {
        NotifyOnActionCacheHit notify = (NotifyOnActionCacheHit) action;
        ActionCachedContext context =
            new ActionCachedContext() {
              @Override
              public EventHandler getEventHandler() {
                return executorEngine.getEventHandler();
              }

              @Override
              public EventBus getEventBus() {
                return executorEngine.getEventBus();
              }

              @Override
              public FileSystem getFileSystem() {
                return executorEngine.getFileSystem();
              }

              @Override
              public Path getExecRoot() {
                return executorEngine.getExecRoot();
              }

              @Override
              public <T extends ActionContext> T getContext(Class<? extends T> type) {
                return executorEngine.getContext(type);
              }
            };
        notify.actionCacheHit(context);
      }

      // We still need to check the outputs so that output file data is available to the value.
      checkOutputs(action, metadataHandler);
      if (!eventPosted) {
        eventHandler.post(new CachedActionEvent(action, actionStartTime));
      }
    }
    return token;
  }

  void afterExecution(
      Action action,
      MetadataHandler metadataHandler,
      Token token,
      Map<String, String> clientEnv,
      ActionLookupData actionLookupData) {
    if (!actionReallyExecuted(action, actionLookupData)) {
      // If an action shared with this one executed, then we need not update the action cache, since
      // the other action will do it. Moreover, this action is not aware of metadata acquired
      // during execution, so its metadata handler is likely unusable anyway.
      return;
    }
    try {
      actionCacheChecker.afterExecution(action, token, metadataHandler, clientEnv);
    } catch (IOException e) {
      // Skyframe has already done all the filesystem access needed for outputs and swallows
      // IOExceptions for inputs. So an IOException is impossible here.
      throw new IllegalStateException(
          "failed to update action cache for " + action.prettyPrint()
              + ", but all outputs should already have been checked", e);
    }
  }

  @Nullable
  Iterable<Artifact> getActionCachedInputs(Action action, PackageRootResolver resolver)
      throws InterruptedException {
    return actionCacheChecker.getCachedInputs(action, resolver);
  }

  /**
   * Perform dependency discovery for action, which must discover its inputs.
   *
   * <p>This method is just a wrapper around {@link Action#discoverInputs} that properly processes
   * any ActionExecutionException thrown before rethrowing it to the caller.
   */
  Iterable<Artifact> discoverInputs(
      Action action,
      MetadataProvider perActionFileCache,
      MetadataHandler metadataHandler,
      Environment env,
      @Nullable FileSystem actionFileSystem)
      throws ActionExecutionException, InterruptedException {
    ActionExecutionContext actionExecutionContext =
        ActionExecutionContext.forInputDiscovery(
            executorEngine,
            createFileCache(perActionFileCache, actionFileSystem),
            actionInputPrefetcher,
            actionKeyContext,
            metadataHandler,
            actionLogBufferPathGenerator.generate(ArtifactPathResolver.createPathResolver(
                actionFileSystem, executorEngine.getExecRoot())),
            clientEnv,
            env,
            actionFileSystem);
    if (actionFileSystem != null) {
      // Note that when not using ActionFS, a global setup of the parent directories of the OutErr
      // streams is sufficient.
      setupActionFsFileOutErr(actionExecutionContext.getFileOutErr(), action);
    }
    try {
      actionExecutionContext.getEventBus().post(ActionStatusMessage.analysisStrategy(action));
      return action.discoverInputs(actionExecutionContext);
    } catch (ActionExecutionException e) {
      throw processAndThrow(
          env.getListener(),
          actionExecutionContext,
          action,
          e,
          actionExecutionContext.getFileOutErr(),
          ErrorTiming.BEFORE_EXECUTION);
    }
  }

  private MetadataProvider createFileCache(
      MetadataProvider graphFileCache, @Nullable FileSystem actionFileSystem) {
    if (actionFileSystem instanceof MetadataProvider) {
      return (MetadataProvider) actionFileSystem;
    }
    return new DelegatingPairFileCache(graphFileCache, perBuildFileCache);
  }

  /**
   * This method should be called if the builder encounters an error during
   * execution. This allows the builder to record that it encountered at
   * least one error, and may make it swallow its output to prevent
   * spamming the user any further.
   */
  private void recordExecutionError() {
    hadExecutionError = true;
  }

  /**
   * Returns true if the Builder is winding down (i.e. cancelling outstanding
   * actions and preparing to abort.)
   * The builder is winding down iff:
   * <ul>
   * <li>we had an execution error
   * <li>we are not running with --keep_going
   * </ul>
   */
  private boolean isBuilderAborting() {
    return hadExecutionError && !options.getOptions(KeepGoingOption.class).keepGoing;
  }

  void configure(MetadataProvider fileCache, ActionInputPrefetcher actionInputPrefetcher) {
    this.perBuildFileCache = fileCache;
    this.actionInputPrefetcher = actionInputPrefetcher;
  }

  private class ActionRunner implements Callable<ActionExecutionValue> {
    private final ExtendedEventHandler eventHandler;
    private final Action action;
    private final ActionMetadataHandler metadataHandler;
    private final long actionStartTime;
    private final ActionExecutionContext actionExecutionContext;
    private final ActionLookupData actionLookupData;

    ActionRunner(
        ExtendedEventHandler eventHandler,
        Action action,
        ActionMetadataHandler metadataHandler,
        long actionStartTime,
        ActionExecutionContext actionExecutionContext,
        ActionLookupData actionLookupData) {
      this.eventHandler = eventHandler;
      this.action = action;
      this.metadataHandler = metadataHandler;
      this.actionStartTime = actionStartTime;
      this.actionExecutionContext = actionExecutionContext;
      this.actionLookupData = actionLookupData;
    }

    @Override
    public ActionExecutionValue call() throws ActionExecutionException, InterruptedException {
      try (SilentCloseable c = profiler.profile(ProfilerTask.ACTION, action.describe())) {
        if (actionCacheChecker.isActionExecutionProhibited(action)) {
          // We can't execute an action (e.g. because --check_???_up_to_date option was used). Fail
          // the build instead.
          synchronized (reporter) {
            TargetOutOfDateException e = new TargetOutOfDateException(action);
            reporter.handle(Event.error(e.getMessage()));
            recordExecutionError();
            throw e;
          }
        }

        String message = action.getProgressMessage();
        if (message != null) {
          reporter.startTask(null, prependExecPhaseStats(message));
        }

        ActionExecutionStatusReporter statusReporter = statusReporterRef.get();
        try {
          statusReporter.updateStatus(ActionStatusMessage.preparingStrategy(action));

          Preconditions.checkState(
              actionExecutionContext.getMetadataHandler() == metadataHandler,
              "%s %s",
              actionExecutionContext.getMetadataHandler(),
              metadataHandler);
          // Delete the outputs before executing the action, just to ensure that
          // the action really does produce the outputs.
          try {
            if (!usesActionFileSystem()) {
              action.prepare(
                  actionExecutionContext.getFileSystem(), actionExecutionContext.getExecRoot());
            } else {
              setupActionFsFileOutErr(actionExecutionContext.getFileOutErr(), action);
            }
            createOutputDirectories(action, actionExecutionContext);
          } catch (IOException e) {
            reportError("failed to delete output files before executing action", e, action, null);
          }

          eventHandler.post(new ActionStartedEvent(action, actionStartTime));
          ActionResult actionResult = executeAction();
          return completeAction(eventHandler, actionResult);
        } finally {
          statusReporter.remove(action);
          eventHandler.post(new ActionCompletionEvent(actionStartTime, action, actionLookupData));
        }
      }
    }

    /**
     * Execute the specified action, in a profiler task. The caller is responsible for having
     * already checked that we need to execute it and for acquiring/releasing any scheduling locks
     * needed.
     *
     * <p>This is thread-safe so long as you don't try to execute the same action twice at the same
     * time (or overlapping times). May execute in a worker thread.
     *
     * @throws ActionExecutionException if the execution of the specified action failed for any
     *     reason.
     * @throws InterruptedException if the thread was interrupted.
     * @return true if the action output was dumped, false otherwise.
     */
    private ActionResult executeAction() throws ActionExecutionException, InterruptedException {
      // ActionExecutionExceptions that occur as the thread is interrupted are assumed to be a
      // result of that, so we throw InterruptedException instead.
      try (SilentCloseable c = profiler.profile(ProfilerTask.ACTION_EXECUTE, action.describe())) {
        return action.execute(actionExecutionContext);
      } catch (LostInputsActionExecutionException e) {
        // If inputs are lost, then avoid publishing ActionExecutedEvents. A higher-level handler
        // may try to fix things.
        throw e;
      } catch (ActionExecutionException e) {
        throw processAndThrow(
            eventHandler,
            actionExecutionContext,
            action,
            e,
            actionExecutionContext.getFileOutErr(),
            ErrorTiming.AFTER_EXECUTION);
      }
    }

    private ActionExecutionValue completeAction(
        ExtendedEventHandler eventHandler, ActionResult actionResult)
        throws ActionExecutionException {
      boolean outputAlreadyDumped = false;
      if (actionResult != ActionResult.EMPTY) {
        eventHandler.post(new ActionResultReceivedEvent(action, actionResult));
      }

      // Action terminated fine, now report the output.
      // The .showOutput() method is not necessarily a quick check: in its
      // current implementation it uses regular expression matching.
      FileOutErr outErrBuffer = actionExecutionContext.getFileOutErr();
      if (outErrBuffer.hasRecordedOutput()
          && (action.showsOutputUnconditionally()
              || reporter.showOutput(Label.print(action.getOwner().getLabel())))) {
        dumpRecordedOutErr(action, outErrBuffer);
        outputAlreadyDumped = true;
      }

      MetadataHandler metadataHandler = actionExecutionContext.getMetadataHandler();
      FileOutErr fileOutErr = actionExecutionContext.getFileOutErr();
      try {
        Preconditions.checkState(action.inputsDiscovered(),
            "Action %s successfully executed, but inputs still not known", action);

        try (SilentCloseable c =
            profiler.profile(ProfilerTask.ACTION_COMPLETE, action.describe())) {
          if (!checkOutputs(action, metadataHandler)) {
            reportError("not all outputs were created or valid", null, action,
                outputAlreadyDumped ? null : fileOutErr);
          }
        }

        if (outputService != null && finalizeActions) {
          try {
            outputService.finalizeAction(action, metadataHandler);
          } catch (EnvironmentalExecException | IOException e) {
            reportError("unable to finalize action", e, action, fileOutErr);
          }
        }

        reportActionExecution(
            eventHandler, actionExecutionContext, action, null, fileOutErr, ErrorTiming.NO_ERROR);
      } catch (ActionExecutionException actionException) {
        // Success in execution but failure in completion.
        reportActionExecution(
            eventHandler,
            actionExecutionContext,
            action,
            actionException,
            fileOutErr,
            ErrorTiming.AFTER_EXECUTION);
        throw actionException;
      } catch (IllegalStateException exception) {
        // More serious internal error, but failure still reported.
        reportActionExecution(
            eventHandler,
            actionExecutionContext,
            action,
            new ActionExecutionException(exception, action, true),
            fileOutErr,
            ErrorTiming.AFTER_EXECUTION);
        throw exception;
      }

      Preconditions.checkState(
          actionExecutionContext.getOutputSymlinks() == null
              || action instanceof SkyframeAwareAction,
          "Unexpected to find outputSymlinks set"
              + " in an action which is not a SkyframeAwareAction. Action: %s\n symlinks:%s",
          action,
          actionExecutionContext.getOutputSymlinks());
      return ActionExecutionValue.createFromOutputStore(
          this.metadataHandler.getOutputStore(),
          actionExecutionContext.getOutputSymlinks(),
          (action instanceof IncludeScannable)
              ? ((IncludeScannable) action).getDiscoveredModules()
              : null,
          ActionExecutionFunction.actionDependsOnBuildId(action));
    }
  }

  private void createOutputDirectories(Action action, ActionExecutionContext context)
      throws ActionExecutionException {
    try {
      Set<Path> done = new HashSet<>(); // avoid redundant calls for the same directory.
      for (Artifact outputFile : action.getOutputs()) {
        Path outputDir;
        if (outputFile.isTreeArtifact()) {
          outputDir = context.getPathResolver().toPath(outputFile);
        } else {
          outputDir = context.getPathResolver().toPath(outputFile).getParentDirectory();
        }

        if (done.add(outputDir)) {
          try {
            outputDir.createDirectoryAndParents();
            continue;
          } catch (IOException e) {
            /* Fall through to plan B. */
          }

          // Possibly some direct ancestors are not directories.  In that case, we traverse the
          // ancestors upward, deleting any non-directories, until we reach a directory, then try
          // again. This handles the case where a file becomes a directory, either from one build to
          // another, or within a single build.
          //
          // Symlinks should not be followed so in order to clean up symlinks pointing to Fileset
          // outputs from previous builds. See bug [incremental build of Fileset fails if
          // Fileset.out was changed to be a subdirectory of the old value].
          try {
            Path p = outputDir;
            while (true) {

              // This lock ensures that the only thread that observes a filesystem transition in
              // which the path p first exists and then does not is the thread that calls
              // p.delete() and causes the transition.
              //
              // If it were otherwise, then some thread A could test p.exists(), see that it does,
              // then test p.isDirectory(), see that p isn't a directory (because, say, thread
              // B deleted it), and then call p.delete(). That could result in two different kinds
              // of failures:
              //
              // 1) In the time between when thread A sees that p is not a directory and when thread
              // A calls p.delete(), thread B may reach the call to createDirectoryAndParents
              // and create a directory at p, which thread A then deletes. Thread B would then try
              // adding outputs to the directory it thought was there, and fail.
              //
              // 2) In the time between when thread A sees that p is not a directory and when thread
              // A calls p.delete(), thread B may create a directory at p, and then either create a
              // subdirectory beneath it or add outputs to it. Then when thread A tries to delete p,
              // it would fail.
              Lock lock = outputDirectoryDeletionLock.get(p);
              lock.lock();
              try {
                if (p.exists(Symlinks.NOFOLLOW)) {
                  boolean isDirectory = p.isDirectory(Symlinks.NOFOLLOW);
                  if (isDirectory) {
                    // If this directory used to be a tree artifact it won't be writable
                    p.setWritable(true);
                    break;
                  }
                  // p may be a file or dangling symlink, or a symlink to an old Fileset output
                  p.delete(); // throws IOException
                }
              } finally {
                lock.unlock();
              }

              p = p.getParentDirectory();
            }
            outputDir.createDirectoryAndParents();
          } catch (IOException e) {
            throw new ActionExecutionException(
                "failed to create output directory '" + outputDir + "'", e, action, false);
          }
        }
      }
    } catch (ActionExecutionException ex) {
      printError(ex.getMessage(), action, null);
      throw ex;
    }
  }

  private String prependExecPhaseStats(String message) {
    // Prints a progress message like:
    //   [2608/6445] Compiling foo/bar.cc [host]
    return progressSupplier.getProgressString() + " " + message;
  }

  private static void setupActionFsFileOutErr(FileOutErr fileOutErr, Action action)
      throws ActionExecutionException {
    try {
      fileOutErr.getOutputPath().getParentDirectory().createDirectoryAndParents();
      fileOutErr.getErrorPath().getParentDirectory().createDirectoryAndParents();
    } catch (IOException e) {
      throw new ActionExecutionException(
          "failed to create output directory for output streams'" + fileOutErr.getErrorPath() + "'",
          e, action, false);
    }
  }

  private ActionExecutionException processAndThrow(
      ExtendedEventHandler eventHandler,
      ActionExecutionContext actionExecutionContext,
      Action action,
      ActionExecutionException e,
      FileOutErr outErrBuffer,
      ErrorTiming errorTiming)
      throws ActionExecutionException {
    reportActionExecution(
        eventHandler, actionExecutionContext, action, e, outErrBuffer, errorTiming);
    boolean reported = reportErrorIfNotAbortingMode(e, outErrBuffer);

    ActionExecutionException toThrow = e;
    if (reported){
      // If we already printed the error for the exception we mark it as already reported
      // so that we do not print it again in upper levels.
      // Note that we need to report it here since we want immediate feedback of the errors
      // and in some cases the upper-level printing mechanism only prints one of the errors.
      toThrow = new AlreadyReportedActionExecutionException(e);
    }

    // Now, rethrow the exception.
    // This can have two effects:
    // If we're still building, the exception will get retrieved by the
    // completor and rethrown.
    // If we're aborting, the exception will never be retrieved from the
    // completor, since the completor is waiting for all outstanding jobs
    // to finish. After they have finished, it will only rethrow the
    // exception that initially caused it to abort will and not check the
    // exit status of any actions that had finished in the meantime.
    throw toThrow;
  }

  private static void reportMissingOutputFile(
      Action action, Artifact output, Reporter reporter, boolean isSymlink, IOException exception) {
    boolean genrule = action.getMnemonic().equals("Genrule");
    String prefix = (genrule ? "declared output '" : "output '") + output.prettyPrint() + "' ";
    logger.warning(
        String.format(
            "Error creating %s%s%s: %s",
            isSymlink ? "symlink " : "",
            prefix,
            genrule ? " by genrule" : "",
            exception.getMessage()));
    if (isSymlink) {
      String msg = prefix + "is a dangling symbolic link";
      reporter.handle(Event.error(action.getOwner().getLocation(), msg));
    } else {
      String suffix = genrule ? " by genrule. This is probably "
          + "because the genrule actually didn't create this output, or because the output was a "
          + "directory and the genrule was run remotely (note that only the contents of "
          + "declared file outputs are copied from genrules run remotely)" : "";
      reporter.handle(Event.error(
          action.getOwner().getLocation(), prefix + "was not created" + suffix));
    }
  }

  private static void reportOutputTreeArtifactErrors(
      Action action, Artifact output, Reporter reporter, IOException e) {
    String errorMessage;
    if (e instanceof FileNotFoundException) {
      errorMessage = String.format("TreeArtifact %s was not created", output.prettyPrint());
    } else {
      errorMessage = String.format(
          "Error while validating output TreeArtifact %s : %s", output, e.getMessage());
    }

    reporter.handle(Event.error(action.getOwner().getLocation(), errorMessage));
  }

  /**
   * Validates that all action outputs were created or intentionally omitted. This can result in
   * chmod calls on the output files; see {@link ActionMetadataHandler}.
   *
   * @return false if some outputs are missing, true - otherwise.
   */
  private boolean checkOutputs(Action action, MetadataHandler metadataHandler) {
    boolean success = true;
    for (Artifact output : action.getOutputs()) {
      // getMetadata has the side effect of adding the artifact to the cache if it's not there
      // already (e.g., due to a previous call to MetadataHandler.injectDigest), therefore we only
      // call it if we know the artifact is not omitted.
      if (!metadataHandler.artifactOmitted(output)) {
        try {
          metadataHandler.getMetadata(output);
        } catch (IOException e) {
          success = false;
          if (output.isTreeArtifact()) {
            reportOutputTreeArtifactErrors(action, output, reporter, e);
          } else {
            // Are all exceptions caught due to missing files?
            reportMissingOutputFile(action, output, reporter, output.getPath().isSymbolicLink(), e);
          }
        }
      }
    }
    return success;
  }

  /**
   * Convenience function for reporting that the action failed due to a
   * the exception cause, if there is an additional explanatory message that
   * clarifies the message of the exception. Combines the user-provided message
   * and the exceptions' message and reports the combination as error.
   * Then, throws an ActionExecutionException with the reported error as
   * message and the provided exception as the cause.
   *
   * @param message A small text that explains why the action failed
   * @param cause The exception that caused the action to fail
   * @param action The action that failed
   * @param actionOutput The output of the failed Action.
   *     May be null, if there is no output to display
   */
  private void reportError(String message, Throwable cause, Action action, FileOutErr actionOutput)
      throws ActionExecutionException {
    ActionExecutionException ex;
    if (cause == null) {
      ex = new ActionExecutionException(message, action, false);
    } else {
      ex = new ActionExecutionException(message, cause, action, false);
    }
    printError(ex.getMessage(), action, actionOutput);
    throw ex;
  }

  /**
   * For the action 'action' that failed due to 'ex' with the output
   * 'actionOutput', notify the user about the error. To notify the user, the
   * method first displays the output of the action and then reports an error
   * via the reporter. The method ensures that the two messages appear next to
   * each other by locking the outErr object where the output is displayed.
   *
   * @param message The reason why the action failed
   * @param action The action that failed, must not be null.
   * @param actionOutput The output of the failed Action.
   *     May be null, if there is no output to display
   */
  private void printError(String message, Action action, FileOutErr actionOutput) {
    synchronized (reporter) {
      if (options.getOptions(KeepGoingOption.class).keepGoing) {
        message = "Couldn't " + describeAction(action) + ": " + message;
      }
      Event event = Event.error(action.getOwner().getLocation(), message);
      dumpRecordedOutErr(event, actionOutput);
      recordExecutionError();
    }
  }

  /** Describe an action, for use in error messages. */
  private static String describeAction(Action action) {
    if (action.getOutputs().isEmpty()) {
      return "run " + action.prettyPrint();
    } else if (action.getActionType().isMiddleman()) {
      return "build " + action.prettyPrint();
    } else {
      return "build file " + action.getPrimaryOutput().prettyPrint();
    }
  }

  /**
   * Dump the output from the action.
   *
   * @param action The action whose output is being dumped
   * @param outErrBuffer The OutErr that recorded the actions output
   */
  private void dumpRecordedOutErr(Action action, FileOutErr outErrBuffer) {
    StringBuilder message = new StringBuilder("");
    message.append("From ");
    message.append(action.describe());
    message.append(":");
    Event event = Event.info(message.toString());
    dumpRecordedOutErr(event, outErrBuffer);
  }

  /**
   * Dump the output from the action.
   *
   * @param prefixEvent An event to post before dumping the output
   * @param outErrBuffer The OutErr that recorded the actions output
   */
  private void dumpRecordedOutErr(Event prefixEvent, FileOutErr outErrBuffer) {
    // Only print the output if we're not winding down.
    if (isBuilderAborting()) {
      return;
    }
    if (outErrBuffer != null && outErrBuffer.hasRecordedOutput()) {
      // Bind the output to the prefix event.
      // Note: here we temporarily (until the event is handled by the UI) read all
      // output into memory; as the output of regular actions (as opposed to test runs) usually is
      // short, so this should not be a problem. If it does turn out to be a problem, we have to
      // pass the outErrbuffer instead.
      reporter.handle(
          prefixEvent.withStdoutStderr(outErrBuffer.outAsLatin1(), outErrBuffer.errAsLatin1()));
    } else {
      reporter.handle(prefixEvent);
    }
  }

  private void reportActionExecution(
      ExtendedEventHandler eventHandler,
      ActionExecutionContext actionExecutionContext,
      Action action,
      ActionExecutionException exception,
      FileOutErr outErr,
      ErrorTiming errorTiming) {
    Path stdout = null;
    Path stderr = null;

    if (outErr.hasRecordedStdout()) {
      stdout = outErr.getOutputPath();
    }
    if (outErr.hasRecordedStderr()) {
      stderr = outErr.getErrorPath();
    }
    eventHandler.post(
        new ActionExecutedEvent(
            action.getPrimaryOutput().getExecPath(),
            action,
            exception,
            actionExecutionContext.getInputPath(action.getPrimaryOutput()),
            stdout,
            stderr,
            errorTiming));
  }

  /**
   * Returns true if the exception was reported. False otherwise. Currently this is a copy of what
   * we did in pre-Skyframe execution. The main implication is that we are printing the error to the
   * top level reporter instead of the action reporter. Because of that Skyframe values do not know
   * about the errors happening in the execution phase. Even if we change in the future to log to
   * the action reporter (that would be done in ActionExecutionFunction.compute() when we get an
   * ActionExecutionException), we probably do not want to also store the StdErr output, so
   * dumpRecordedOutErr() should still be called here.
   */
  private boolean reportErrorIfNotAbortingMode(ActionExecutionException ex,
      FileOutErr outErrBuffer) {
    // For some actions (e.g., many local actions) the pollInterruptedStatus()
    // won't notice that we had an interrupted job. It will continue.
    // For that reason we must take care to NOT report errors if we're
    // in the 'aborting' mode: Any cancelled action would show up here.
    synchronized (this.reporter) {
      if (!isBuilderAborting()) {
        // Oops. The action aborted. Report the problem.
        printError(ex.getMessage(), ex.getAction(), outErrBuffer);
        return true;
      }
    }
    return false;
  }

  /** An object supplying data for action execution progress reporting. */
  public interface ProgressSupplier {
    /** Returns the progress string to prefix action execution messages with. */
    String getProgressString();
  }

  /** An object that can be notified about action completion. */
  public interface ActionCompletedReceiver {
    /** Receives a completed action. */
    void actionCompleted(ActionLookupData actionLookupData);
    /** Notes that an action has started, giving the key. */
    void noteActionEvaluationStarted(ActionLookupData actionLookupData, Action action);
  }

  public void setActionExecutionProgressReportingObjects(
      @Nullable ProgressSupplier progressSupplier,
      @Nullable ActionCompletedReceiver completionReceiver) {
    this.progressSupplier = progressSupplier;
    this.completionReceiver = completionReceiver;
  }

  private static class DelegatingPairFileCache implements MetadataProvider {
    private final MetadataProvider perActionCache;
    private final MetadataProvider perBuildFileCache;

    private DelegatingPairFileCache(
        MetadataProvider mainCache, MetadataProvider perBuildFileCache) {
      this.perActionCache = mainCache;
      this.perBuildFileCache = perBuildFileCache;
    }

    @Override
    public FileArtifactValue getMetadata(ActionInput input) throws IOException {
      FileArtifactValue metadata = perActionCache.getMetadata(input);
      return (metadata != null) && (metadata != FileArtifactValue.MISSING_FILE_MARKER)
          ? metadata
          : perBuildFileCache.getMetadata(input);
    }

    @Override
    public ActionInput getInput(String execPath) {
      ActionInput input = perActionCache.getInput(execPath);
      return input != null ? input : perBuildFileCache.getInput(execPath);
    }
  }
}
