// Copyright 2018 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.dynamic;

import com.google.auto.value.AutoValue;
import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.io.Files;
import com.google.devtools.build.lib.actions.ActionContext;
import com.google.devtools.build.lib.actions.ActionExecutionContext;
import com.google.devtools.build.lib.actions.ExecException;
import com.google.devtools.build.lib.actions.ExecutionRequirements;
import com.google.devtools.build.lib.actions.ExecutionStrategy;
import com.google.devtools.build.lib.actions.ExecutorInitException;
import com.google.devtools.build.lib.actions.SandboxedSpawnActionContext;
import com.google.devtools.build.lib.actions.Spawn;
import com.google.devtools.build.lib.actions.SpawnActionContext;
import com.google.devtools.build.lib.actions.SpawnResult;
import com.google.devtools.build.lib.actions.UserExecException;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.exec.ExecutionPolicy;
import com.google.devtools.build.lib.util.io.FileOutErr;
import com.google.devtools.build.lib.vfs.Path;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Phaser;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.logging.Logger;
import javax.annotation.Nullable;

/**
 * A spawn strategy that speeds up incremental builds while not slowing down full builds.
 *
 * <p>It tries to run spawn actions on the local machine, which is faster than running them
 * remotely, unless there are not enough resources available to run all of them in parallel, in
 * which case the increased parallelism of remote execution is likely to provide higher overall
 * throughput.
 *
 * <p>The strategy will delegate execution to either a remote spawn strategy for remote execution or
 * the sandboxed strategy for local execution. The general idea is to run spawns on the local 
 * machine, as long as it can keep up with the required parallelism. If it fails to do so, we assume
 * that the build would benefit from the increased parallelism available when using remote 
 * execution, so we switch to using remote execution for all following spawns.
 *
 * <p>One might ask, why we don't run spawns on the workstation all the time and just "spill over"
 * actions to remote execution when there are no local resources available. This would work, except 
 * that the cost of transferring action inputs and outputs from the local machine to and from remote
 * executors over the network is way too high - there is no point in executing an action locally and
 * save 0.5s of time, when it then takes us 5 seconds to upload the results to remote executors for 
 * another action that's scheduled to run there.
 */
@ExecutionStrategy(
  name = {"dynamic", "dynamic_worker"},
  contextType = SpawnActionContext.class
)
public class DynamicSpawnStrategy implements SpawnActionContext {
  private static final Logger logger = Logger.getLogger(DynamicSpawnStrategy.class.getName());

  enum StrategyIdentifier {
    NONE("unknown"),
    LOCAL("locally"),
    REMOTE("remotely");

    private final String prettyName;

    StrategyIdentifier(String prettyName) {
      this.prettyName = prettyName;
    }

    String prettyName() {
      return prettyName;
    }
  }

  @AutoValue
  abstract static class DynamicExecutionResult {
    static DynamicExecutionResult create(
        StrategyIdentifier strategyIdentifier,
        @Nullable FileOutErr fileOutErr,
        @Nullable ExecException execException,
        List<SpawnResult> spawnResults) {
      return new AutoValue_DynamicSpawnStrategy_DynamicExecutionResult(
          strategyIdentifier, fileOutErr, execException, spawnResults);
    }

    abstract StrategyIdentifier strategyIdentifier();

    @Nullable
    abstract FileOutErr fileOutErr();

    @Nullable
    abstract ExecException execException();

    /**
     * Returns a list of SpawnResults associated with executing a Spawn.
     *
     * <p>The list will typically contain one element, but could contain zero elements if spawn
     * execution did not complete, or multiple elements if multiple sub-spawns were executed.
     */
    abstract List<SpawnResult> spawnResults();
  }

  private static final ImmutableSet<String> WORKER_BLACKLISTED_MNEMONICS =
      ImmutableSet.of("JavaDeployJar");

  private final ExecutorService executorService;
  private final DynamicExecutionOptions options;
  private final Function<Spawn, ExecutionPolicy> getExecutionPolicy;
  private final AtomicBoolean delayLocalExecution = new AtomicBoolean(false);

  private @Nullable SandboxedSpawnActionContext remoteStrategy;
  private @Nullable SandboxedSpawnActionContext localStrategy;
  private @Nullable SandboxedSpawnActionContext workerStrategy;

  /**
   * Constructs a {@code DynamicSpawnStrategy}.
   *
   * @param executorService an {@link ExecutorService} that will be used to run Spawn actions.
   */
  public DynamicSpawnStrategy(
      ExecutorService executorService,
      DynamicExecutionOptions options,
      Function<Spawn, ExecutionPolicy> getExecutionPolicy) {
    this.executorService = executorService;
    this.options = options;
    this.getExecutionPolicy = getExecutionPolicy;
  }

  /**
   * Searches for a sandboxed spawn strategy with the given name.
   *
   * @param usedContexts the action contexts used during the build
   * @param name the name of the spawn strategy we are interested in
   * @return a sandboxed spawn strategy
   * @throws ExecutorInitException if the spawn strategy does not exist, or if it exists but is not
   *     sandboxed
   */
  private SandboxedSpawnActionContext findStrategy(
      Iterable<ActionContext> usedContexts, String name) throws ExecutorInitException {
    for (ActionContext context : usedContexts) {
      ExecutionStrategy strategy = context.getClass().getAnnotation(ExecutionStrategy.class);
      if (strategy != null && Arrays.asList(strategy.name()).contains(name)) {
        if (!(context instanceof SandboxedSpawnActionContext)) {
          throw new ExecutorInitException("Requested strategy " + name + " exists but does not "
              + "support sandboxing");
        }
        return (SandboxedSpawnActionContext) context;
      }
    }
    throw new ExecutorInitException("Requested strategy " + name + " does not exist");
  }

  @Override
  public void executorCreated(Iterable<ActionContext> usedContexts) throws ExecutorInitException {
    localStrategy = findStrategy(usedContexts, options.dynamicLocalStrategy);
    remoteStrategy = findStrategy(usedContexts, options.dynamicRemoteStrategy);
    workerStrategy = findStrategy(usedContexts, options.dynamicWorkerStrategy);
  }

  @Override
  public List<SpawnResult> exec(
      final Spawn spawn, final ActionExecutionContext actionExecutionContext)
      throws ExecException, InterruptedException {

    ExecutionPolicy executionPolicy = getExecutionPolicy.apply(spawn);

    // If a Spawn cannot run remotely, we must always execute it locally. Resources will already
    // have been acquired by Skyframe for us.
    if (executionPolicy.canRunLocallyOnly()) {
      return runLocally(spawn, actionExecutionContext, null);
    }

    // If a Spawn cannot run locally, we must always execute it remotely. For remote execution,
    // local resources should not be acquired.
    if (executionPolicy.canRunRemotelyOnly()) {
      return runRemotely(spawn, actionExecutionContext, null);
    }

    // At this point we have a Spawn that can run locally and can run remotely. Run it in parallel
    // using both the remote and the local strategy.
    ExecException exceptionDuringExecution = null;
    DynamicExecutionResult dynamicExecutionResult =
        DynamicExecutionResult.create(
            StrategyIdentifier.NONE, null, null, /*spawnResults=*/ ImmutableList.of());

    // As an invariant in Bazel, all actions must terminate before the build ends. We use a
    // synchronizer here, in the main thread, to wait for the termination of both local and remote
    // spawns. Termination implies successful completion, failure, or, if one spawn wins,
    // cancellation by the executor.
    //
    // In the case where one task completes successfully before the other starts, Bazel must
    // proceed and return, skipping the other spawn. To achieve this, we use Phaser for its ability
    // to register a variable number of tasks.
    //
    // TODO(b/118451841): Note that this may incur a performance issue where a remote spawn is
    // faster than a worker spawn, because the worker spawn cannot be cancelled once it starts. This
    // nullifies the gains from the faster spawn.
    Phaser bothTasksFinished = new Phaser(/*parties=*/ 1);

    try {
      final AtomicReference<Class<? extends SpawnActionContext>> outputsHaveBeenWritten =
          new AtomicReference<>(null);
      dynamicExecutionResult =
          executorService.invokeAny(
              ImmutableList.of(
                  new DynamicExecutionCallable(
                      bothTasksFinished,
                      StrategyIdentifier.LOCAL,
                      actionExecutionContext.getFileOutErr()) {
                    @Override
                    List<SpawnResult> callImpl() throws InterruptedException, ExecException {
                      // This is a rather simple approach to make it possible to score a cache hit
                      // on remote execution before even trying to start the action locally. This
                      // saves resources that would otherwise be wasted by continuously starting and
                      // immediately killing local processes. One possibility for improvement would
                      // be to establish a reporting mechanism from strategies back to here, where
                      // we delay starting locally until the remote strategy tells us that the
                      // action isn't a cache hit.
                      if (delayLocalExecution.get()) {
                        Thread.sleep(options.localExecutionDelay);
                      }
                      return runLocally(
                          spawn,
                          actionExecutionContext.withFileOutErr(fileOutErr),
                          outputsHaveBeenWritten);
                    }
                  },
                  new DynamicExecutionCallable(
                      bothTasksFinished,
                      StrategyIdentifier.REMOTE,
                      actionExecutionContext.getFileOutErr()) {
                    @Override
                    public List<SpawnResult> callImpl() throws InterruptedException, ExecException {
                      List<SpawnResult> spawnResults =
                          runRemotely(
                              spawn,
                              actionExecutionContext.withFileOutErr(fileOutErr),
                              outputsHaveBeenWritten);
                      delayLocalExecution.set(true);
                      return spawnResults;
                    }
                  }));
    } catch (ExecutionException e) {
      Throwables.propagateIfPossible(e.getCause(), InterruptedException.class);
      // DynamicExecutionCallable.callImpl only declares InterruptedException, so this should never
      // happen.
      exceptionDuringExecution = new UserExecException(e.getCause());
    } finally {
      bothTasksFinished.arriveAndAwaitAdvance();
      if (dynamicExecutionResult.execException() != null) {
        exceptionDuringExecution = dynamicExecutionResult.execException();
      }
      if (Thread.currentThread().isInterrupted()) {
        // Warn but don't throw, in case we're crashing.
        logger.warning("Interrupted waiting for dynamic execution tasks to finish");
      }
    }
    // Check for interruption outside of finally block, so we don't mask any other exceptions.
    // Clear the interrupt bit if it's set.
    if (exceptionDuringExecution == null && Thread.interrupted()) {
      throw new InterruptedException("Interrupted waiting for dynamic execution tasks to finish");
    }
    StrategyIdentifier winningStrategy = dynamicExecutionResult.strategyIdentifier();
    FileOutErr fileOutErr = dynamicExecutionResult.fileOutErr();
    if (StrategyIdentifier.NONE.equals(winningStrategy) || fileOutErr == null) {
      throw new IllegalStateException("Neither local or remote execution has started.");
    }

    try {
      moveFileOutErr(actionExecutionContext, fileOutErr);
    } catch (IOException e) {
      String strategyName = winningStrategy.name().toLowerCase();
      if (exceptionDuringExecution == null) {
        throw new UserExecException(
            String.format("Could not move action logs from %s execution", strategyName), e);
      } else {
        actionExecutionContext
            .getEventHandler()
            .handle(
                Event.warn(
                    String.format(
                        "Could not move action logs from %s execution: %s",
                        strategyName, e.toString())));
      }
    }

    if (exceptionDuringExecution != null) {
      throw exceptionDuringExecution;
    }

    if (options.debugSpawnScheduler) {
      actionExecutionContext
          .getEventHandler()
          .handle(
              Event.info(
                  String.format(
                      "%s action %s %s",
                      spawn.getMnemonic(),
                      dynamicExecutionResult.execException() == null ? "finished" : "failed",
                      winningStrategy.prettyName())));
    }

    // TODO(b/62588075) If a second list of spawnResults was generated (before execution was
    // cancelled), then we might want to save it as well (e.g. for metrics purposes).
    return dynamicExecutionResult.spawnResults();
  }

  private void moveFileOutErr(ActionExecutionContext actionExecutionContext, FileOutErr outErr)
      throws IOException {
    if (outErr.getOutputPath().exists()) {
      Files.move(
          outErr.getOutputPath().getPathFile(),
          actionExecutionContext.getFileOutErr().getOutputPath().getPathFile());
    }
    if (outErr.getErrorPath().exists()) {
      Files.move(
          outErr.getErrorPath().getPathFile(),
          actionExecutionContext.getFileOutErr().getErrorPath().getPathFile());
    }
  }

  private static FileOutErr getSuffixedFileOutErr(FileOutErr fileOutErr, String suffix) {
    Path outDir = Preconditions.checkNotNull(fileOutErr.getOutputPath().getParentDirectory());
    String outBaseName = fileOutErr.getOutputPath().getBaseName();
    Path errDir = Preconditions.checkNotNull(fileOutErr.getErrorPath().getParentDirectory());
    String errBaseName = fileOutErr.getErrorPath().getBaseName();
    return new FileOutErr(
        outDir.getChild(outBaseName + suffix), errDir.getChild(errBaseName + suffix));
  }

  private List<SpawnResult> runLocally(
      Spawn spawn,
      ActionExecutionContext actionExecutionContext,
      AtomicReference<Class<? extends SpawnActionContext>> outputWriteBarrier)
      throws ExecException, InterruptedException {
    SandboxedSpawnActionContext strategy;
    if (!WORKER_BLACKLISTED_MNEMONICS.contains(spawn.getMnemonic())
        && "1".equals(spawn.getExecutionInfo().get(ExecutionRequirements.SUPPORTS_WORKERS))) {
      strategy = Preconditions.checkNotNull(workerStrategy, "executorCreated not yet called");
    } else {
      strategy = Preconditions.checkNotNull(localStrategy, "executorCreated not yet called");
    }
    return strategy.exec(spawn, actionExecutionContext, outputWriteBarrier);
  }

  private List<SpawnResult> runRemotely(
      Spawn spawn,
      ActionExecutionContext actionExecutionContext,
      AtomicReference<Class<? extends SpawnActionContext>> outputWriteBarrier)
      throws ExecException, InterruptedException {
    SandboxedSpawnActionContext strategy =
        Preconditions.checkNotNull(remoteStrategy, "executorCreated not yet called");
    return strategy.exec(spawn, actionExecutionContext, outputWriteBarrier);
  }

  private abstract static class DynamicExecutionCallable
      implements Callable<DynamicExecutionResult> {
    private final Phaser taskFinished;
    private final StrategyIdentifier strategyIdentifier;
    protected final FileOutErr fileOutErr;

    DynamicExecutionCallable(
        Phaser taskFinished,
        StrategyIdentifier strategyIdentifier,
        FileOutErr fileOutErr) {
      this.taskFinished = taskFinished;
      this.strategyIdentifier = strategyIdentifier;
      this.fileOutErr = getSuffixedFileOutErr(fileOutErr, "." + strategyIdentifier.name());
    }

    abstract List<SpawnResult> callImpl() throws InterruptedException, ExecException;

    @Override
    public final DynamicExecutionResult call() throws InterruptedException {
      taskFinished.register();
      try {
        List<SpawnResult> spawnResults = callImpl();
        return DynamicExecutionResult.create(strategyIdentifier, fileOutErr, null, spawnResults);
      } catch (Exception e) {
        Throwables.throwIfInstanceOf(e, InterruptedException.class);
        return DynamicExecutionResult.create(
            strategyIdentifier,
            fileOutErr, e instanceof ExecException ? (ExecException) e : new UserExecException(e),
            /*spawnResults=*/ ImmutableList.of());
      } finally {
        try {
          fileOutErr.close();
        } catch (IOException ignored) {
          // Nothing we can do here.
        }
        taskFinished.arriveAndDeregister();
      }
    }
  }
}
