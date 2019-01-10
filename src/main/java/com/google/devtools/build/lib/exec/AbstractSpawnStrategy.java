// Copyright 2017 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.exec;

import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.eventbus.EventBus;
import com.google.devtools.build.lib.actions.ActionExecutionContext;
import com.google.devtools.build.lib.actions.ActionExecutionMetadata;
import com.google.devtools.build.lib.actions.ActionInput;
import com.google.devtools.build.lib.actions.ActionStatusMessage;
import com.google.devtools.build.lib.actions.Artifact.ArtifactExpander;
import com.google.devtools.build.lib.actions.ArtifactPathResolver;
import com.google.devtools.build.lib.actions.EnvironmentalExecException;
import com.google.devtools.build.lib.actions.ExecException;
import com.google.devtools.build.lib.actions.MetadataProvider;
import com.google.devtools.build.lib.actions.SandboxedSpawnActionContext;
import com.google.devtools.build.lib.actions.Spawn;
import com.google.devtools.build.lib.actions.SpawnActionContext;
import com.google.devtools.build.lib.actions.SpawnResult;
import com.google.devtools.build.lib.actions.SpawnResult.Status;
import com.google.devtools.build.lib.actions.Spawns;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.exec.SpawnCache.CacheHandle;
import com.google.devtools.build.lib.exec.SpawnRunner.ProgressStatus;
import com.google.devtools.build.lib.exec.SpawnRunner.SpawnExecutionContext;
import com.google.devtools.build.lib.util.CommandFailureUtils;
import com.google.devtools.build.lib.util.io.FileOutErr;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.SortedMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Abstract common ancestor for spawn strategies implementing the common parts. */
public abstract class AbstractSpawnStrategy implements SandboxedSpawnActionContext {
  private final SpawnInputExpander spawnInputExpander;
  private final SpawnRunner spawnRunner;
  private final AtomicInteger execCount = new AtomicInteger();

  public AbstractSpawnStrategy(Path execRoot, SpawnRunner spawnRunner) {
    this.spawnInputExpander = new SpawnInputExpander(execRoot, false);
    this.spawnRunner = spawnRunner;
  }

  /**
   * Get's the {@link SpawnRunner} that this {@link AbstractSpawnStrategy} uses to actually run
   * spawns.
   *
   * <p>This is considered a stop-gap until we refactor the entire SpawnStrategy / SpawnRunner
   * mechanism to no longer need Spawn strategies.
   */
  public SpawnRunner getSpawnRunner() {
    return spawnRunner;
  }

  @Override
  public List<SpawnResult> exec(Spawn spawn, ActionExecutionContext actionExecutionContext)
      throws ExecException, InterruptedException {
    return exec(spawn, actionExecutionContext, null);
  }

  @Override
  public List<SpawnResult> exec(
      Spawn spawn,
      ActionExecutionContext actionExecutionContext,
      AtomicReference<Class<? extends SpawnActionContext>> writeOutputFiles)
      throws ExecException, InterruptedException {
    actionExecutionContext.maybeReportSubcommand(spawn);

    final Duration timeout = Spawns.getTimeout(spawn);
    SpawnExecutionContext context =
        new SpawnExecutionContextImpl(spawn, actionExecutionContext, writeOutputFiles, timeout);
    // TODO(ulfjack): Provide a way to disable the cache. We don't want the RemoteSpawnStrategy to
    // check the cache twice. Right now that can't happen because this is hidden behind an
    // experimental flag.
    SpawnCache cache = actionExecutionContext.getContext(SpawnCache.class);
    // In production, the getContext method guarantees that we never get null back. However, our
    // integration tests don't set it up correctly, so cache may be null in testing.
    if (cache == null) {
      cache = SpawnCache.NO_CACHE;
    }
    SpawnResult spawnResult;
    ExecException ex = null;
    try {
      try (CacheHandle cacheHandle = cache.lookup(spawn, context)) {
        if (cacheHandle.hasResult()) {
          spawnResult = Preconditions.checkNotNull(cacheHandle.getResult());
        } else {
          // Actual execution.
          spawnResult = spawnRunner.execAsync(spawn, context).get();
          if (cacheHandle.willStore()) {
            cacheHandle.store(spawnResult);
          }
        }
      }
    } catch (IOException e) {
      throw new EnvironmentalExecException("Unexpected IO error.", e);
    } catch (SpawnExecException e) {
      ex = e;
      spawnResult = e.getSpawnResult();
      // Log the Spawn and re-throw.
    }

    SpawnLogContext spawnLogContext = actionExecutionContext.getContext(SpawnLogContext.class);
    if (spawnLogContext != null) {
      try {
        spawnLogContext.logSpawn(
            spawn,
            actionExecutionContext.getMetadataProvider(),
            context.getInputMapping(true),
            context.getTimeout(),
            spawnResult);
      } catch (IOException e) {
        actionExecutionContext
            .getEventHandler()
            .handle(
                Event.warn("Exception " + e + " while logging properties of " + spawn.toString()));
      }
    }
    if (ex != null) {
      throw ex;
    }

    if (spawnResult.status() != Status.SUCCESS) {
      String cwd = actionExecutionContext.getExecRoot().getPathString();
      String resultMessage = spawnResult.getFailureMessage();
      String message =
          !Strings.isNullOrEmpty(resultMessage)
              ? resultMessage
              : CommandFailureUtils.describeCommandFailure(
                  actionExecutionContext.getVerboseFailures(),
                  spawn.getArguments(),
                  spawn.getEnvironment(),
                  cwd,
                  spawn.getExecutionPlatform());
      throw new SpawnExecException(message, spawnResult, /*forciblyRunRemotely=*/false);
    }
    return ImmutableList.of(spawnResult);
  }

  private final class SpawnExecutionContextImpl implements SpawnExecutionContext {
    private final Spawn spawn;
    private final ActionExecutionContext actionExecutionContext;
    private final AtomicReference<Class<? extends SpawnActionContext>> writeOutputFiles;
    private final Duration timeout;

    private final int id = execCount.incrementAndGet();
    // Memoize the input mapping so that prefetchInputs can reuse it instead of recomputing it.
    // TODO(ulfjack): Guard against client modification of this map.
    private SortedMap<PathFragment, ActionInput> lazyInputMapping;

    public SpawnExecutionContextImpl(
        Spawn spawn,
        ActionExecutionContext actionExecutionContext,
        AtomicReference<Class<? extends SpawnActionContext>> writeOutputFiles,
        Duration timeout) {
      this.spawn = spawn;
      this.actionExecutionContext = actionExecutionContext;
      this.writeOutputFiles = writeOutputFiles;
      this.timeout = timeout;
    }

    @Override
    public int getId() {
      return id;
    }

    @Override
    public void prefetchInputs() throws IOException {
      if (Spawns.shouldPrefetchInputsForLocalExecution(spawn)) {
        actionExecutionContext
            .getActionInputPrefetcher()
            .prefetchFiles(getInputMapping(true).values());
      }
    }

    @Override
    public MetadataProvider getMetadataProvider() {
      return actionExecutionContext.getMetadataProvider();
    }

    @Override
    public ArtifactExpander getArtifactExpander() {
      return actionExecutionContext.getArtifactExpander();
    }

    @Override
    public ArtifactPathResolver getPathResolver() {
      return actionExecutionContext.getPathResolver();
    }

    @Override
    public void lockOutputFiles() throws InterruptedException {
      Class<? extends SpawnActionContext> token = AbstractSpawnStrategy.this.getClass();
      if (writeOutputFiles != null
          && writeOutputFiles.get() != token
          && !writeOutputFiles.compareAndSet(null, token)) {
        throw new InterruptedException();
      }
    }

    @Override
    public boolean speculating() {
      return writeOutputFiles != null;
    }

    @Override
    public Duration getTimeout() {
      return timeout;
    }

    @Override
    public FileOutErr getFileOutErr() {
      return actionExecutionContext.getFileOutErr();
    }

    @Override
    public SortedMap<PathFragment, ActionInput> getInputMapping(
        boolean expandTreeArtifactsInRunfiles) throws IOException {
      if (lazyInputMapping == null) {
        lazyInputMapping =
            spawnInputExpander.getInputMapping(
                spawn,
                actionExecutionContext.getArtifactExpander(),
                actionExecutionContext.getPathResolver(),
                actionExecutionContext.getMetadataProvider(),
                expandTreeArtifactsInRunfiles);
      }
      return lazyInputMapping;
    }

    @Override
    public void report(ProgressStatus state, String name) {
      ActionExecutionMetadata action = spawn.getResourceOwner();
      if (action.getOwner() == null) {
        return;
      }

      // TODO(djasper): This should not happen as per the contract of ActionExecutionMetadata, but
      // there are implementations that violate the contract. Remove when those are gone.
      if (action.getPrimaryOutput() == null) {
        return;
      }

      // TODO(ulfjack): We should report more details to the UI.
      EventBus eventBus = actionExecutionContext.getEventBus();
      switch (state) {
        case EXECUTING:
        case CHECKING_CACHE:
          eventBus.post(ActionStatusMessage.runningStrategy(action, name));
          break;
        case SCHEDULING:
          eventBus.post(ActionStatusMessage.schedulingStrategy(action));
          break;
        default:
          break;
      }
    }
  }
}
