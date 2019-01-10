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

package com.google.devtools.build.lib.analysis;

import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.analysis.config.BuildConfiguration;
import com.google.devtools.build.lib.analysis.config.BuildConfiguration.LabelListConverter;
import com.google.devtools.build.lib.analysis.config.FragmentOptions;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.common.options.Converters.CommaSeparatedOptionListConverter;
import com.google.devtools.common.options.Option;
import com.google.devtools.common.options.OptionDocumentationCategory;
import com.google.devtools.common.options.OptionEffectTag;
import java.util.List;

/** Command-line options for platform-related configuration. */
public class PlatformOptions extends FragmentOptions {
  @Option(
    name = "host_platform",
    oldName = "experimental_host_platform",
    converter = BuildConfiguration.EmptyToNullLabelConverter.class,
    defaultValue = "@bazel_tools//platforms:host_platform",
    documentationCategory = OptionDocumentationCategory.TOOLCHAIN,
    effectTags = {
      OptionEffectTag.AFFECTS_OUTPUTS,
      OptionEffectTag.CHANGES_INPUTS,
      OptionEffectTag.LOADING_AND_ANALYSIS
    },
    help = "The label of a platform rule that describes the host system."
  )
  public Label hostPlatform;

  @Option(
    name = "host_platform_remote_properties_override",
    oldName = "experimental_remote_platform_override",
    defaultValue = "null",
    documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
    effectTags = {OptionEffectTag.UNKNOWN},
    help =
        "Manually set the remote_execution_properties for the host platform"
            + " if it is not already set."
  )
  public String hostPlatformRemotePropertiesOverride;

  @Option(
      name = "extra_execution_platforms",
      converter = CommaSeparatedOptionListConverter.class,
      defaultValue = "",
      documentationCategory = OptionDocumentationCategory.TOOLCHAIN,
      allowMultiple = true,
      effectTags = {OptionEffectTag.EXECUTION},
      help =
          "The platforms that are available as execution platforms to run actions. "
              + "Platforms can be specified by exact target, or as a target pattern. "
              + "These platforms will be considered before those declared in the WORKSPACE file by "
              + "register_execution_platforms().")
  public List<String> extraExecutionPlatforms;

  @Option(
    name = "platforms",
    oldName = "experimental_platforms",
    converter = LabelListConverter.class,
    defaultValue = "@bazel_tools//platforms:target_platform",
    documentationCategory = OptionDocumentationCategory.TOOLCHAIN,
    effectTags = {
      OptionEffectTag.AFFECTS_OUTPUTS,
      OptionEffectTag.CHANGES_INPUTS,
      OptionEffectTag.LOADING_AND_ANALYSIS
    },
    help =
        "The labels of the platform rules describing the target platforms for the current command."
  )
  public List<Label> platforms;

  @Option(
      name = "extra_toolchains",
      defaultValue = "",
      converter = CommaSeparatedOptionListConverter.class,
      documentationCategory = OptionDocumentationCategory.TOOLCHAIN,
      allowMultiple = true,
      effectTags = {
        OptionEffectTag.AFFECTS_OUTPUTS,
        OptionEffectTag.CHANGES_INPUTS,
        OptionEffectTag.LOADING_AND_ANALYSIS
      },
      help =
          "The toolchain rules to be considered during toolchain resolution. "
              + "Toolchains can be specified by exact target, or as a target pattern. "
              + "These toolchains will be considered before those declared in the WORKSPACE file "
              + "by register_toolchains().")
  public List<String> extraToolchains;

  @Option(
    name = "toolchain_resolution_override",
    allowMultiple = true,
    defaultValue = "",
    documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
    effectTags = {
      OptionEffectTag.AFFECTS_OUTPUTS,
      OptionEffectTag.CHANGES_INPUTS,
      OptionEffectTag.LOADING_AND_ANALYSIS
    },
    deprecationWarning =
        "toolchain_resolution_override is now a no-op and will be removed in"
            + " an upcoming release",
    help =
        "Override toolchain resolution for a toolchain type with a specific toolchain. "
            + "Example: --toolchain_resolution_override=@io_bazel_rules_go//:toolchain="
            + "@io_bazel_rules_go//:linux-arm64-toolchain"
  )
  public List<String> toolchainResolutionOverrides;

  @Option(
      name = "toolchain_resolution_debug",
      defaultValue = "false",
      documentationCategory = OptionDocumentationCategory.LOGGING,
      effectTags = {OptionEffectTag.TERMINAL_OUTPUT},
      help =
          "Print debug information while finding toolchains for a rule. This might help developers "
              + "of Bazel or Starlark rules with debugging failures due to missing toolchains.")
  public boolean toolchainResolutionDebug;

  @Option(
    name = "enabled_toolchain_types",
    defaultValue = "",
    converter = LabelListConverter.class,
    documentationCategory = OptionDocumentationCategory.TOOLCHAIN,
    effectTags = {OptionEffectTag.LOADING_AND_ANALYSIS},
    help =
        "Enable toolchain resolution for the given toolchain type, if the rules used support that. "
            + "This does not directly change the core Blaze machinery, but is a signal to "
            + "participating rule implementations that toolchain resolution should be used."
  )
  public List<Label> enabledToolchainTypes;

  @Override
  public PlatformOptions getHost() {
    PlatformOptions host = (PlatformOptions) getDefault();
    host.platforms =
        this.hostPlatform == null ? ImmutableList.of() : ImmutableList.of(this.hostPlatform);
    host.hostPlatform = this.hostPlatform;
    host.extraExecutionPlatforms = this.extraExecutionPlatforms;
    host.extraToolchains = this.extraToolchains;
    host.enabledToolchainTypes = this.enabledToolchainTypes;
    host.hostPlatformRemotePropertiesOverride = this.hostPlatformRemotePropertiesOverride;
    host.toolchainResolutionDebug = this.toolchainResolutionDebug;
    host.toolchainResolutionOverrides = this.toolchainResolutionOverrides;
    return host;
  }
}
