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
package com.google.devtools.build.lib.buildtool;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.actions.BuildFailedException;
import com.google.devtools.build.lib.actions.TestExecException;
import com.google.devtools.build.lib.analysis.AnalysisResult;
import com.google.devtools.build.lib.analysis.BuildInfoEvent;
import com.google.devtools.build.lib.analysis.BuildView;
import com.google.devtools.build.lib.analysis.ViewCreationFailedException;
import com.google.devtools.build.lib.analysis.config.BuildOptions;
import com.google.devtools.build.lib.analysis.config.DefaultsPackage;
import com.google.devtools.build.lib.analysis.config.InvalidConfigurationException;
import com.google.devtools.build.lib.buildeventstream.BuildEventId;
import com.google.devtools.build.lib.buildtool.PostAnalysisQueryBuildTool.PostAnalysisQueryCommandLineException;
import com.google.devtools.build.lib.buildtool.buildevent.BuildCompleteEvent;
import com.google.devtools.build.lib.buildtool.buildevent.BuildInterruptedEvent;
import com.google.devtools.build.lib.buildtool.buildevent.BuildStartingEvent;
import com.google.devtools.build.lib.buildtool.buildevent.NoExecutionEvent;
import com.google.devtools.build.lib.cmdline.TargetParsingException;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.OutputFilter;
import com.google.devtools.build.lib.events.Reporter;
import com.google.devtools.build.lib.pkgcache.LoadingFailedException;
import com.google.devtools.build.lib.profiler.ProfilePhase;
import com.google.devtools.build.lib.profiler.Profiler;
import com.google.devtools.build.lib.profiler.SilentCloseable;
import com.google.devtools.build.lib.runtime.BlazeRuntime;
import com.google.devtools.build.lib.runtime.CommandEnvironment;
import com.google.devtools.build.lib.util.AbruptExitException;
import com.google.devtools.build.lib.util.ExitCode;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Provides the bulk of the implementation of the 'blaze build' command.
 *
 * <p>The various concrete build command classes handle the command options and request
 * setup, then delegate the handling of the request (the building of targets) to this class.
 *
 * <p>The main entry point is {@link #buildTargets}.
 *
 * <p>Most of analysis is handled in {@link BuildView}, and execution in {@link ExecutionTool}.
 */
public class BuildTool {

  private static Logger logger = Logger.getLogger(BuildTool.class.getName());

  protected CommandEnvironment env;
  protected BlazeRuntime runtime;

  /**
   * Constructs a BuildTool.
   *
   * @param env a reference to the command environment of the currently executing command
   */
  public BuildTool(CommandEnvironment env) {
    this.env = env;
    this.runtime = env.getRuntime();
  }

  /**
   * The crux of the build system. Builds the targets specified in the request using the specified
   * Executor.
   *
   * <p>Performs loading, analysis and execution for the specified set of targets, honoring the
   * configuration options in the BuildRequest. Returns normally iff successful, throws an exception
   * otherwise.
   *
   * <p>Callers must ensure that {@link #stopRequest} is called after this method, even if it
   * throws.
   *
   * <p>The caller is responsible for setting up and syncing the package cache.
   *
   * <p>During this function's execution, the actualTargets and successfulTargets fields of the
   * request object are set.
   *
   * @param request the build request that this build tool is servicing, which specifies various
   *     options; during this method's execution, the actualTargets and successfulTargets fields of
   *     the request object are populated
   * @param result the build result that is the mutable result of this build
   * @param validator target validator
   */
  public void buildTargets(BuildRequest request, BuildResult result, TargetValidator validator)
      throws BuildFailedException, InterruptedException, ViewCreationFailedException,
          TargetParsingException, LoadingFailedException, AbruptExitException,
          InvalidConfigurationException, TestExecException, PostAnalysisQueryCommandLineException {
    try (SilentCloseable c = Profiler.instance().profile("validateOptions")) {
      validateOptions(request);
    }
    BuildOptions buildOptions;
    try (SilentCloseable c = Profiler.instance().profile("createBuildOptions")) {
      buildOptions = runtime.createBuildOptions(request);
    }
    // Sync the package manager before sending the BuildStartingEvent in runLoadingPhase()
    try (SilentCloseable c = Profiler.instance().profile("setupPackageCache")) {
      env.setupPackageCache(request, DefaultsPackage.getDefaultsPackageContent(buildOptions));
    }

    ExecutionTool executionTool = null;
    boolean catastrophe = false;
    try {
      try (SilentCloseable c = Profiler.instance().profile("BuildStartingEvent")) {
        env.getEventBus().post(new BuildStartingEvent(env, request));
      }
      logger.info("Build identifier: " + request.getId());

      // Error out early if multi_cpus is set, but we're not in build or test command.
      if (!request.getMultiCpus().isEmpty()) {
        getReporter().handle(Event.warn(
            "The --experimental_multi_cpu option is _very_ experimental and only intended for "
            + "internal testing at this time. If you do not work on the build tool, then you "
            + "should stop now!"));
        if (!"build".equals(request.getCommandName()) && !"test".equals(request.getCommandName())) {
          throw new InvalidConfigurationException(
              "The experimental setting to select multiple CPUs is only supported for 'build' and "
              + "'test' right now!");
        }
      }

      // Exit if there are any pending exceptions from modules.
      env.throwPendingException();

      initializeOutputFilter(request);

      AnalysisPhaseRunner analysisPhaseRunner = new AnalysisPhaseRunner(env);
      AnalysisResult analysisResult = analysisPhaseRunner.execute(request, buildOptions, validator);

      // We cannot move the executionTool down to the execution phase part since it does set up the
      // symlinks for tools.
      // TODO(twerth): Extract embedded tool setup from execution tool and move object creation to
      // execution phase.
      executionTool = new ExecutionTool(env, request);
      if (request.getBuildOptions().performAnalysisPhase) {
        result.setBuildConfigurationCollection(analysisResult.getConfigurationCollection());
        result.setActualTargets(analysisResult.getTargetsToBuild());
        result.setTestTargets(analysisResult.getTargetsToTest());

        try (SilentCloseable c = Profiler.instance().profile("postProcessAnalysisResult")) {
          postProcessAnalysisResult(request, analysisResult);
        }

        // Execution phase.
        if (needsExecutionPhase(request.getBuildOptions())) {
          try (SilentCloseable closeable = Profiler.instance().profile("ExecutionTool.init")) {
            executionTool.init();
          }
          executionTool.executeBuild(
              request.getId(),
              analysisResult,
              result,
              analysisResult.getPackageRoots(),
              request.getTopLevelArtifactContext());
        } else {
          env.getReporter().post(new NoExecutionEvent());
        }
        String delayedErrorMsg = analysisResult.getError();
        if (delayedErrorMsg != null) {
          throw new BuildFailedException(delayedErrorMsg);
        }
      }
      Profiler.instance().markPhase(ProfilePhase.FINISH);
    } catch (Error | RuntimeException e) {
      request
          .getOutErr()
          .printErrLn(
              "Internal error thrown during build. Printing stack trace: "
                  + Throwables.getStackTraceAsString(e));
      catastrophe = true;
      throw e;
    } finally {
      if (executionTool != null) {
        executionTool.shutdown();
      }
      if (!catastrophe) {
        // Delete dirty nodes to ensure that they do not accumulate indefinitely.
        long versionWindow = request.getViewOptions().versionWindowForDirtyNodeGc;
        if (versionWindow != -1) {
          env.getSkyframeExecutor().deleteOldNodes(versionWindow);
        }
        // The workspace status actions will not run with certain flags, or if an error
        // occurs early in the build. Tell a lie so that the event is not missing.
        // If multiple build_info events are sent, only the first is kept, so this does not harm
        // successful runs (which use the workspace status action).
        env.getEventBus()
            .post(
                new BuildInfoEvent(
                    env.getBlazeWorkspace().getWorkspaceStatusActionFactory()
                        .createDummyWorkspaceStatus()));
      }
    }
  }

  /**
   * This class is meant to be overridden by classes that want to perform the Analysis phase and
   * then process the results in some interesting way. See {@link CqueryBuildTool} as an example.
   */
  protected void postProcessAnalysisResult(
      BuildRequest request,
      AnalysisResult analysisResult)
      throws InterruptedException, ViewCreationFailedException,
          PostAnalysisQueryCommandLineException {
  }

  private void reportExceptionError(Exception e) {
    if (e.getMessage() != null) {
      getReporter().handle(Event.error(e.getMessage()));
    }
  }

  /**
   * The crux of the build system. Builds the targets specified in the request using the specified
   * Executor.
   *
   * <p>Performs loading, analysis and execution for the specified set of targets, honoring the
   * configuration options in the BuildRequest. Returns normally iff successful, throws an exception
   * otherwise.
   *
   * <p>The caller is responsible for setting up and syncing the package cache.
   *
   * <p>During this function's execution, the actualTargets and successfulTargets
   * fields of the request object are set.
   *
   * @param request the build request that this build tool is servicing, which specifies various
   *        options; during this method's execution, the actualTargets and successfulTargets fields
   *        of the request object are populated
   * @param validator target validator
   * @return the result as a {@link BuildResult} object
   */
  public BuildResult processRequest(
      BuildRequest request, TargetValidator validator) {
    BuildResult result = new BuildResult(request.getStartTime());
    maybeSetStopOnFirstFailure(request, result);
    Throwable catastrophe = null;
    ExitCode exitCode = ExitCode.BLAZE_INTERNAL_ERROR;
    try {
      buildTargets(request, result, validator);
      exitCode = ExitCode.SUCCESS;
    } catch (BuildFailedException e) {
      if (e.isErrorAlreadyShown()) {
        // The actual error has already been reported by the Builder.
      } else {
        reportExceptionError(e);
      }
      if (e.isCatastrophic()) {
        result.setCatastrophe();
      }
      exitCode = e.getExitCode() != null ? e.getExitCode() : ExitCode.BUILD_FAILURE;
    } catch (InterruptedException e) {
      // We may have been interrupted by an error, or the user's interruption may have raced with
      // an error, so check to see if we should report that error code instead.
      exitCode = env.getPendingExitCode();
      if (exitCode == null) {
        exitCode = ExitCode.INTERRUPTED;
        env.getReporter().handle(Event.error("build interrupted"));
        env.getEventBus().post(new BuildInterruptedEvent());
      } else {
        // Report the exception from the environment - the exception we're handling here is just an
        // interruption.
        reportExceptionError(env.getPendingException());
        result.setCatastrophe();
      }
    } catch (TargetParsingException | LoadingFailedException | ViewCreationFailedException e) {
      exitCode = ExitCode.PARSING_FAILURE;
      reportExceptionError(e);
    } catch (PostAnalysisQueryCommandLineException e) {
      exitCode = ExitCode.COMMAND_LINE_ERROR;
      reportExceptionError(e);
    } catch (TestExecException e) {
      // ExitCode.SUCCESS means that build was successful. Real return code of program
      // is going to be calculated in TestCommand.doTest().
      exitCode = ExitCode.SUCCESS;
      reportExceptionError(e);
    } catch (InvalidConfigurationException e) {
      exitCode = ExitCode.COMMAND_LINE_ERROR;
      reportExceptionError(e);
      // TODO(gregce): With "global configurations" we cannot tie a configuration creation failure
      // to a single target and have to halt the entire build. Once configurations are genuinely
      // created as part of the analysis phase they should report their error on the level of the
      // target(s) that triggered them.
      result.setCatastrophe();
    } catch (AbruptExitException e) {
      exitCode = e.getExitCode();
      reportExceptionError(e);
      result.setCatastrophe();
    } catch (Throwable throwable) {
      catastrophe = throwable;
      Throwables.propagate(throwable);
    } finally {
      stopRequest(result, catastrophe, exitCode);
    }

    return result;
  }

  private void maybeSetStopOnFirstFailure(BuildRequest request, BuildResult result) {
    if (shouldStopOnFailure(request)) {
      result.setStopOnFirstFailure(true);
    }
  }

  private boolean shouldStopOnFailure(BuildRequest request) {
    return !(request.getKeepGoing() && request.getExecutionOptions().testKeepGoing);
  }

  /**
   * Initializes the output filter to the value given with {@code --output_filter}.
   */
  private void initializeOutputFilter(BuildRequest request) {
    Pattern outputFilter = request.getBuildOptions().outputFilter;
    if (outputFilter != null) {
      getReporter().setOutputFilter(OutputFilter.RegexOutputFilter.forPattern(outputFilter));
    }
  }

  private static boolean needsExecutionPhase(BuildRequestOptions options) {
    return options.performAnalysisPhase && options.performExecutionPhase;
  }

  /**
   * Stops processing the specified request.
   *
   * <p>This logs the build result, cleans up and stops the clock.
   *
   * @param crash Any unexpected RuntimeException or Error. May be null
   * @param exitCondition A suggested exit condition from either the build logic or
   *        a thrown exception somewhere along the way.
   */
  public void stopRequest(BuildResult result, Throwable crash, ExitCode exitCondition) {
    Preconditions.checkState((crash == null) || !exitCondition.equals(ExitCode.SUCCESS));
    result.setUnhandledThrowable(crash);
    result.setExitCondition(exitCondition);
    InterruptedException ie = null;
    try {
      env.getSkyframeExecutor().notifyCommandComplete();
    } catch (InterruptedException e) {
      env.getReporter().handle(Event.error("Build interrupted during command completion"));
      ie = e;
    }
    // The stop time has to be captured before we send the BuildCompleteEvent.
    result.setStopTime(runtime.getClock().currentTimeMillis());
    env.getEventBus()
        .post(
            new BuildCompleteEvent(
                result,
                ImmutableList.of(BuildEventId.buildToolLogs(), BuildEventId.buildMetrics())));
    // Post the build tool logs event; the corresponding local files may be contributed from
    // modules, and this has to happen after posting the BuildCompleteEvent because that's when
    // modules add their data to the collection.
    env.getEventBus().post(result.getBuildToolLogCollection().freeze().toEvent());
    if (ie != null) {
      if (exitCondition.equals(ExitCode.SUCCESS)) {
        result.setExitCondition(ExitCode.INTERRUPTED);
      } else if (!exitCondition.equals(ExitCode.INTERRUPTED)) {
        logger.log(
            Level.WARNING,
            "Suppressed interrupted exception during stop request because already failing with exit"
                + " code "
                + exitCondition,
            ie);
      }
    }
  }

  /**
   * Validates the options for this BuildRequest.
   *
   * <p>Issues warnings for the use of deprecated options, and warnings or errors for any option
   * settings that conflict.
   */
  @VisibleForTesting
  public void validateOptions(BuildRequest request) throws InvalidConfigurationException {
    for (String issue : request.validateOptions()) {
      getReporter().handle(Event.warn(issue));
    }
  }

  private Reporter getReporter() {
    return env.getReporter();
  }
}
