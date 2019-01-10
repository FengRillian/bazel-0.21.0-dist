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

import com.google.devtools.common.options.Option;
import com.google.devtools.common.options.OptionDocumentationCategory;
import com.google.devtools.common.options.OptionEffectTag;
import com.google.devtools.common.options.OptionsBase;

/**
 * Options related to dynamic spawn execution.
 */
public class DynamicExecutionOptions extends OptionsBase {

  @Option(
      name = "experimental_spawn_scheduler",
      documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
      effectTags = {OptionEffectTag.UNKNOWN},
      defaultValue = "null",
      help =
          "Run actions locally instead of remotely for incremental builds as long as enough "
              + "resources are available to execute all runnable actions in parallel.",
      expansion = {"--internal_spawn_scheduler", "--spawn_strategy=dynamic"})
  public Void experimentalSpawnScheduler;

  @Option(
    name = "internal_spawn_scheduler",
    documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
    effectTags = {OptionEffectTag.UNKNOWN},
    defaultValue = "false",
    help =
        "Placeholder option so that we can tell in Blaze whether the spawn scheduler was "
            + "enabled."
  )
  public boolean internalSpawnScheduler;

  @Option(
    name = "dynamic_local_strategy",
    documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
    effectTags = {OptionEffectTag.UNKNOWN},
    defaultValue = "sandboxed",
    help = "Strategy to use when the dynamic spawn scheduler decides to run an action locally."
  )
  public String dynamicLocalStrategy;

  @Option(
      name = "dynamic_remote_strategy",
      documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
      effectTags = {OptionEffectTag.UNKNOWN},
      defaultValue = "remote",
      help = "Strategy to use when the dynamic spawn scheduler decides to run an action remotely."
  )
  public String dynamicRemoteStrategy;

  @Option(
      name = "dynamic_worker_strategy",
      documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
      effectTags = {OptionEffectTag.UNKNOWN},
      defaultValue = "worker",
      help = "Strategy to use when the dynamic spawn scheduler decides to run an action in a"
          + " worker."
  )
  public String dynamicWorkerStrategy;

  @Option(
    name = "experimental_local_execution_delay",
    documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
    effectTags = {OptionEffectTag.UNKNOWN},
    defaultValue = "1000",
    help =
        "How many milliseconds should local execution be delayed, if remote execution was faster"
            + " during a build at least once?"
  )
  public int localExecutionDelay;

  @Option(
    name = "experimental_debug_spawn_scheduler",
    documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
    effectTags = {OptionEffectTag.UNKNOWN},
    defaultValue = "false"
  )
  public boolean debugSpawnScheduler;
}
