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
package com.google.devtools.build.lib.query2;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.Streams;
import com.google.devtools.build.lib.actions.ActionAnalysisMetadata;
import com.google.devtools.build.lib.actions.ActionExecutionMetadata;
import com.google.devtools.build.lib.actions.ActionKeyContext;
import com.google.devtools.build.lib.actions.ActionOwner;
import com.google.devtools.build.lib.actions.CommandLineExpansionException;
import com.google.devtools.build.lib.actions.ExecutionInfoSpecifier;
import com.google.devtools.build.lib.analysis.actions.SpawnAction;
import com.google.devtools.build.lib.analysis.configuredtargets.RuleConfiguredTarget;
import com.google.devtools.build.lib.buildeventstream.BuildEvent;
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos;
import com.google.devtools.build.lib.events.ExtendedEventHandler;
import com.google.devtools.build.lib.packages.AspectDescriptor;
import com.google.devtools.build.lib.query2.engine.QueryEnvironment.TargetAccessor;
import com.google.devtools.build.lib.query2.output.AqueryOptions;
import com.google.devtools.build.lib.skyframe.AspectValue;
import com.google.devtools.build.lib.skyframe.ConfiguredTargetValue;
import com.google.devtools.build.lib.skyframe.SkyframeExecutor;
import com.google.devtools.build.lib.util.CommandDescriptionForm;
import com.google.devtools.build.lib.util.CommandFailureUtils;
import com.google.devtools.build.lib.util.ShellEscaper;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.stream.Collectors;

/** Output callback for aquery, prints human readable output. */
public class ActionGraphTextOutputFormatterCallback extends AqueryThreadsafeCallback {

  private final ActionKeyContext actionKeyContext = new ActionKeyContext();

  ActionGraphTextOutputFormatterCallback(
      ExtendedEventHandler eventHandler,
      AqueryOptions options,
      OutputStream out,
      SkyframeExecutor skyframeExecutor,
      TargetAccessor<ConfiguredTargetValue> accessor) {
    super(eventHandler, options, out, skyframeExecutor, accessor);
  }

  @Override
  public String getName() {
    return "text";
  }

  @Override
  public void processOutput(Iterable<ConfiguredTargetValue> partialResult)
      throws IOException, InterruptedException {
    try {
      for (ConfiguredTargetValue configuredTargetValue : partialResult) {
        List<ActionAnalysisMetadata> actions = configuredTargetValue.getActions();
        for (ActionAnalysisMetadata action : actions) {
          writeAction(action, printStream);
        }
        if (options.useAspects) {
          if (configuredTargetValue.getConfiguredTarget() instanceof RuleConfiguredTarget) {
            for (AspectValue aspectValue : accessor.getAspectValues(configuredTargetValue)) {
              for (int i = 0; i < aspectValue.getNumActions(); i++) {
                writeAction(aspectValue.getAction(i), printStream);
              }
            }
          }
        }
      }
    } catch (CommandLineExpansionException e) {
      throw new IOException(e.getMessage());
    }
  }

  private void writeAction(ActionAnalysisMetadata action, PrintStream printStream)
      throws IOException, CommandLineExpansionException {
    ActionOwner actionOwner = action.getOwner();
    StringBuilder stringBuilder = new StringBuilder();
    stringBuilder
        .append(action.prettyPrint())
        .append('\n')
        .append("  Mnemonic: ")
        .append(action.getMnemonic())
        .append('\n');

    if (actionOwner != null) {
      BuildEvent configuration = actionOwner.getConfiguration();
      BuildEventStreamProtos.Configuration configProto =
          configuration.asStreamProto(/*context=*/ null).getConfiguration();
      stringBuilder
          .append("  Owner: ")
          .append(actionOwner.getLabel().toString())
          .append('\n')
          .append("  Configuration: ")
          .append(configProto.getMnemonic())
          .append('\n');
      ImmutableList<AspectDescriptor> aspectDescriptors = actionOwner.getAspectDescriptors();
      if (!aspectDescriptors.isEmpty()) {
        stringBuilder
            .append("  AspectDescriptors: [")
            .append(
                Streams.stream(aspectDescriptors)
                    .map(
                        aspectDescriptor -> {
                          StringBuilder aspectDescription = new StringBuilder();
                          aspectDescription
                              .append(aspectDescriptor.getAspectClass().getName())
                              .append('(')
                              .append(
                                  Streams.stream(
                                          aspectDescriptor
                                              .getParameters()
                                              .getAttributes()
                                              .entries())
                                      .map(
                                          parameter ->
                                              parameter.getKey()
                                                  + "='"
                                                  + parameter.getValue()
                                                  + "'")
                                      .collect(Collectors.joining(", ")))
                              .append(')');
                          return aspectDescription.toString();
                        })
                    .sorted()
                    .collect(Collectors.joining(",\n")))
            .append("]\n");
      }
    }

    if (action instanceof ActionExecutionMetadata) {
      ActionExecutionMetadata actionExecutionMetadata = (ActionExecutionMetadata) action;
      stringBuilder
          .append("  ActionKey: ")
          .append(actionExecutionMetadata.getKey(actionKeyContext))
          .append('\n');
    }

    stringBuilder
        .append("  Inputs: [")
        .append(
            Streams.stream(action.getInputs())
                .map(input -> input.getExecPathString())
                .sorted()
                .collect(Collectors.joining(", ")))
        .append("]\n")
        .append("  Outputs: [")
        .append(
            Streams.stream(action.getOutputs())
                .map(input -> input.getExecPathString())
                .sorted()
                .collect(Collectors.joining(", ")))
        .append("]\n");

    if (action instanceof SpawnAction) {
      SpawnAction spawnAction = (SpawnAction) action;
      // TODO(twerth): This handles the fixed environment. We probably want to output the inherited
      // environment as well.
      Iterable<Map.Entry<String, String>> fixedEnvironment =
          spawnAction.getEnvironment().getFixedEnv().toMap().entrySet();
      if (!Iterables.isEmpty(fixedEnvironment)) {
        stringBuilder
            .append("  Environment: [")
            .append(
                Streams.stream(fixedEnvironment)
                    .map(
                        environmentVariable ->
                            environmentVariable.getKey() + "=" + environmentVariable.getValue())
                    .sorted()
                    .collect(Collectors.joining(", ")))
            .append("]\n");
      }

      if (options.includeCommandline) {
        stringBuilder
            .append("  Command Line: ")
            .append(
                CommandFailureUtils.describeCommand(
                    CommandDescriptionForm.COMPLETE,
                    /* prettyPrintArgs= */ true,
                    spawnAction.getArguments(),
                    /* environment= */ null,
                    /* cwd= */ null))
            .append("\n");
      }
    }

    if (action instanceof ExecutionInfoSpecifier) {
      Set<Entry<String, String>> executionInfoSpecifiers =
          ((ExecutionInfoSpecifier) action).getExecutionInfo().entrySet();
      if (!executionInfoSpecifiers.isEmpty()) {
        stringBuilder
            .append("  ExecutionInfo: {")
            .append(
                executionInfoSpecifiers.stream()
                    .sorted(Map.Entry.comparingByKey())
                    .map(
                        e ->
                            String.format(
                                "%s: %s",
                                ShellEscaper.escapeString(e.getKey()),
                                ShellEscaper.escapeString(e.getValue())))
                    .collect(Collectors.joining(", ")))
            .append("}\n");
      }
    }

    stringBuilder.append('\n');

    printStream.write(stringBuilder.toString().getBytes(UTF_8));
  }
}
