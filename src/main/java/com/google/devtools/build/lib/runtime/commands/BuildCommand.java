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
package com.google.devtools.build.lib.runtime.commands;

import com.google.devtools.build.lib.analysis.AnalysisOptions;
import com.google.devtools.build.lib.buildtool.BuildRequest;
import com.google.devtools.build.lib.buildtool.BuildRequestOptions;
import com.google.devtools.build.lib.buildtool.BuildTool;
import com.google.devtools.build.lib.exec.ExecutionOptions;
import com.google.devtools.build.lib.exec.local.LocalExecutionOptions;
import com.google.devtools.build.lib.pkgcache.LoadingOptions;
import com.google.devtools.build.lib.pkgcache.PackageCacheOptions;
import com.google.devtools.build.lib.profiler.Profiler;
import com.google.devtools.build.lib.profiler.SilentCloseable;
import com.google.devtools.build.lib.runtime.BlazeCommand;
import com.google.devtools.build.lib.runtime.BlazeCommandResult;
import com.google.devtools.build.lib.runtime.BlazeRuntime;
import com.google.devtools.build.lib.runtime.Command;
import com.google.devtools.build.lib.runtime.CommandEnvironment;
import com.google.devtools.build.lib.runtime.KeepGoingOption;
import com.google.devtools.build.lib.runtime.LoadingPhaseThreadsOption;
import com.google.devtools.build.lib.util.ExitCode;
import com.google.devtools.common.options.OptionsParser;
import com.google.devtools.common.options.OptionsParsingResult;
import java.util.List;

/**
 * Handles the 'build' command on the Blaze command line, including targets named by arguments
 * passed to Blaze.
 */
@Command(
  name = "build",
  builds = true,
  options = {
    BuildRequestOptions.class,
    ExecutionOptions.class,
    LocalExecutionOptions.class,
    PackageCacheOptions.class,
    AnalysisOptions.class,
    LoadingOptions.class,
    KeepGoingOption.class,
    LoadingPhaseThreadsOption.class
  },
  usesConfigurationOptions = true,
  shortDescription = "Builds the specified targets.",
  allowResidue = true,
  completion = "label",
  help = "resource:build.txt"
)
public final class BuildCommand implements BlazeCommand {

  @Override
  public void editOptions(OptionsParser optionsParser) {
  }

  @Override
  public BlazeCommandResult exec(CommandEnvironment env, OptionsParsingResult options) {
    BlazeRuntime runtime = env.getRuntime();
    List<String> targets;
    try (SilentCloseable closeable = Profiler.instance().profile("ProjectFileSupport.getTargets")) {
      targets = ProjectFileSupport.getTargets(runtime.getProjectFileProvider(), options);
    }

    BuildRequest request;
    try (SilentCloseable closeable = Profiler.instance().profile("BuildRequest.create")) {
      request = BuildRequest.create(
          getClass().getAnnotation(Command.class).name(), options,
          runtime.getStartupOptionsProvider(),
          targets,
          env.getReporter().getOutErr(), env.getCommandId(), env.getCommandStartTime());
    }
    ExitCode exitCode = new BuildTool(env).processRequest(request, null).getExitCondition();
    return BlazeCommandResult.exitCode(exitCode);
  }
}
