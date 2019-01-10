// Copyright 2018 The Bazel Authors. All rights reserved.
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

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.buildtool.AqueryBuildTool;
import com.google.devtools.build.lib.buildtool.BuildRequest;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.query2.ActionGraphQueryEnvironment;
import com.google.devtools.build.lib.query2.engine.QueryEnvironment.QueryFunction;
import com.google.devtools.build.lib.query2.engine.QueryException;
import com.google.devtools.build.lib.query2.engine.QueryExpression;
import com.google.devtools.build.lib.query2.engine.QueryParser;
import com.google.devtools.build.lib.query2.output.AqueryOptions;
import com.google.devtools.build.lib.runtime.BlazeCommand;
import com.google.devtools.build.lib.runtime.BlazeCommandResult;
import com.google.devtools.build.lib.runtime.BlazeRuntime;
import com.google.devtools.build.lib.runtime.Command;
import com.google.devtools.build.lib.runtime.CommandEnvironment;
import com.google.devtools.build.lib.util.ExitCode;
import com.google.devtools.common.options.OptionPriority.PriorityCategory;
import com.google.devtools.common.options.OptionsParser;
import com.google.devtools.common.options.OptionsParsingException;
import com.google.devtools.common.options.OptionsParsingResult;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Handles the 'aquery' command on the Blaze command line. */
@Command(
    name = "aquery",
    builds = true,
    inherits = {BuildCommand.class},
    options = {AqueryOptions.class},
    usesConfigurationOptions = true,
    shortDescription = "Analyzes the given targets and queries the action graph.",
    allowResidue = true,
    completion = "label",
    help = "resource:aquery.txt")
public final class AqueryCommand implements BlazeCommand {

  @Override
  public void editOptions(OptionsParser optionsParser) {
    try {
      optionsParser.parse(
          PriorityCategory.COMPUTED_DEFAULT,
          "Option required by aquery",
          ImmutableList.of("--nobuild"));
    } catch (OptionsParsingException e) {
      throw new IllegalStateException("Aquery's known options failed to parse", e);
    }
  }

  @Override
  public BlazeCommandResult exec(CommandEnvironment env, OptionsParsingResult options) {
    // TODO(twerth): Reduce overlap with CqueryCommand.
    env.getReporter()
        .handle(
            Event.warn(
                "Note that the aquery command is still experimental "
                    + "and its API will change in the future."));
    if (options.getResidue().isEmpty()) {
      env.getReporter()
          .handle(
              Event.error(
                  "Missing query expression. Use the 'help aquery' command for syntax and help."));
      return BlazeCommandResult.exitCode(ExitCode.COMMAND_LINE_ERROR);
    }
    String query = Joiner.on(' ').join(options.getResidue());
    HashMap<String, QueryFunction> functions = new HashMap<>();
    for (QueryFunction queryFunction : ActionGraphQueryEnvironment.FUNCTIONS) {
      functions.put(queryFunction.getName(), queryFunction);
    }
    for (QueryFunction queryFunction : env.getRuntime().getQueryFunctions()) {
      functions.put(queryFunction.getName(), queryFunction);
    }
    QueryExpression expr;
    try {
      expr = QueryParser.parse(query, functions);
    } catch (QueryException e) {
      env.getReporter()
          .handle(Event.error("Error while parsing '" + query + "': " + e.getMessage()));
      return BlazeCommandResult.exitCode(ExitCode.COMMAND_LINE_ERROR);
    }

    List<String> topLevelTargets = options.getOptions(AqueryOptions.class).universeScope;
    Set<String> targetPatternSet = new LinkedHashSet<>();
    if (topLevelTargets.isEmpty()) {
      expr.collectTargetPatterns(targetPatternSet);
      topLevelTargets = new ArrayList<>(targetPatternSet);
    }
    BlazeRuntime runtime = env.getRuntime();

    BuildRequest request =
        BuildRequest.create(
            getClass().getAnnotation(Command.class).name(),
            options,
            runtime.getStartupOptionsProvider(),
            topLevelTargets,
            env.getReporter().getOutErr(),
            env.getCommandId(),
            env.getCommandStartTime());
    ExitCode exitCode =
        new AqueryBuildTool(env, expr).processRequest(request, null).getExitCondition();
    return BlazeCommandResult.exitCode(exitCode);
  }
}
