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

package com.google.devtools.build.lib.skylarkbuildapi.cpp;

import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.analysis.skylark.SkylarkRuleContext;
import com.google.devtools.build.lib.events.Location;
import com.google.devtools.build.lib.skylarkbuildapi.ProviderApi;
import com.google.devtools.build.lib.skylarkbuildapi.SkylarkActionFactoryApi;
import com.google.devtools.build.lib.skylarkbuildapi.SkylarkRuleContextApi;
import com.google.devtools.build.lib.skylarkinterface.Param;
import com.google.devtools.build.lib.skylarkinterface.ParamType;
import com.google.devtools.build.lib.skylarkinterface.SkylarkCallable;
import com.google.devtools.build.lib.skylarkinterface.SkylarkModule;
import com.google.devtools.build.lib.syntax.Environment;
import com.google.devtools.build.lib.syntax.EvalException;
import com.google.devtools.build.lib.syntax.Runtime.NoneType;
import com.google.devtools.build.lib.syntax.SkylarkDict;
import com.google.devtools.build.lib.syntax.SkylarkList;
import com.google.devtools.build.lib.syntax.SkylarkNestedSet;

/** Utilites related to C++ support. */
@SkylarkModule(
    name = "cc_common",
    doc = "Utilities for C++ compilation, linking, and command line " + "generation.")
public interface CcModuleApi<
    CcToolchainProviderT extends CcToolchainProviderApi,
    FeatureConfigurationT extends FeatureConfigurationApi,
    CcToolchainVariablesT extends CcToolchainVariablesApi,
    LibraryToLinkT extends LibraryToLinkApi,
    CcLinkParamsT extends CcLinkParamsApi,
    CcSkylarkInfoT extends CcSkylarkInfoApi> {

  @SkylarkCallable(
      name = "CcToolchainInfo",
      doc =
          "The key used to retrieve the provider that contains information about the C++ "
              + "toolchain being used",
      structField = true)
  ProviderApi getCcToolchainProvider();

  @Deprecated
  @SkylarkCallable(
      name = "do_not_use_tools_cpp_compiler_present",
      doc =
          "Do not use this field, its only puprose is to help with migration from "
              + "config_setting.values{'compiler') to "
              + "config_settings.flag_values{'@bazel_tools//tools/cpp:compiler'}",
      structField = true)
  default void compilerFlagExists() {}

  @SkylarkCallable(
      name = "configure_features",
      doc = "Creates a feature_configuration instance.",
      parameters = {
        @Param(
            name = "cc_toolchain",
            doc = "cc_toolchain for which we configure features.",
            positional = false,
            named = true,
            type = CcToolchainProviderApi.class),
        @Param(
            name = "requested_features",
            doc = "List of features to be enabled.",
            positional = false,
            named = true,
            defaultValue = "[]",
            type = SkylarkList.class),
        @Param(
            name = "unsupported_features",
            doc = "List of features that are unsupported by the current rule.",
            positional = false,
            named = true,
            defaultValue = "[]",
            type = SkylarkList.class),
      })
  FeatureConfigurationT configureFeatures(
      CcToolchainProviderT toolchain,
      SkylarkList<String> requestedFeatures,
      SkylarkList<String> unsupportedFeatures)
      throws EvalException;

  @SkylarkCallable(
      name = "get_tool_for_action",
      doc = "Returns tool path for given action.",
      parameters = {
        @Param(
            name = "feature_configuration",
            doc = "Feature configuration to be queried.",
            positional = false,
            named = true,
            type = FeatureConfigurationApi.class),
        @Param(
            name = "action_name",
            doc =
                "Name of the action. Has to be one of the names in "
                    + "@bazel_tools//tools/build_defs/cc:action_names.bzl.",
            named = true,
            positional = false),
      })
  String getToolForAction(FeatureConfigurationT featureConfiguration, String actionName);

  @SkylarkCallable(
      name = "is_enabled",
      doc = "Returns True if given feature is enabled in the feature configuration.",
      parameters = {
        @Param(
            name = "feature_configuration",
            doc = "Feature configuration to be queried.",
            positional = false,
            named = true,
            type = FeatureConfigurationApi.class),
        @Param(
            name = "feature_name",
            doc = "Name of the feature.",
            named = true,
            positional = false),
      })
  boolean isEnabled(FeatureConfigurationT featureConfiguration, String featureName);

  @SkylarkCallable(
      name = "get_memory_inefficient_command_line",
      doc =
          "Returns flattened command line flags for given action, using given variables for "
              + "expansion. Flattens nested sets and ideally should not be used, or at least "
              + "should not outlive analysis. Work on memory efficient function returning Args is "
              + "ongoing.",
      parameters = {
        @Param(
            name = "feature_configuration",
            doc = "Feature configuration to be queried.",
            positional = false,
            named = true,
            type = FeatureConfigurationApi.class),
        @Param(
            name = "action_name",
            doc =
                "Name of the action. Has to be one of the names in "
                    + "@bazel_tools//tools/build_defs/cc:action_names.bzl.",
            named = true,
            positional = false),
        @Param(
            name = "variables",
            doc = "Build variables to be used for template expansions.",
            named = true,
            positional = false,
            type = CcToolchainVariablesApi.class),
      })
  SkylarkList<String> getCommandLine(
      FeatureConfigurationT featureConfiguration,
      String actionName,
      CcToolchainVariablesT variables);

  @SkylarkCallable(
      name = "get_environment_variables",
      doc = "Returns environment variables to be set for given action.",
      parameters = {
        @Param(
            name = "feature_configuration",
            doc = "Feature configuration to be queried.",
            positional = false,
            named = true,
            type = FeatureConfigurationApi.class),
        @Param(
            name = "action_name",
            doc =
                "Name of the action. Has to be one of the names in "
                    + "@bazel_tools//tools/build_defs/cc:action_names.bzl.",
            named = true,
            positional = false),
        @Param(
            name = "variables",
            doc = "Build variables to be used for template expansion.",
            positional = false,
            named = true,
            type = CcToolchainVariablesApi.class),
      })
  SkylarkDict<String, String> getEnvironmentVariable(
      FeatureConfigurationT featureConfiguration,
      String actionName,
      CcToolchainVariablesT variables);

  @SkylarkCallable(
      name = "create_compile_variables",
      doc = "Returns variables used for compilation actions.",
      parameters = {
        @Param(
            name = "cc_toolchain",
            doc = "cc_toolchain for which we are creating build variables.",
            positional = false,
            named = true,
            type = CcToolchainProviderApi.class),
        @Param(
            name = "feature_configuration",
            doc = "Feature configuration to be queried.",
            positional = false,
            named = true,
            type = FeatureConfigurationApi.class),
        @Param(
            name = "source_file",
            doc =
                "Optional source file for the compilation. Please prefer passing source_file here "
                    + "over appending it to the end of the command line generated from "
                    + "cc_common.get_memory_inefficient_command_line, as then it's in the power of "
                    + "the toolchain author to properly specify and position compiler flags.",
            named = true,
            positional = false,
            defaultValue = "None",
            noneable = true),
        @Param(
            name = "output_file",
            doc =
                "Optional output file of the compilation. Please prefer passing output_file here "
                    + "over appending it to the end of the command line generated from "
                    + "cc_common.get_memory_inefficient_command_line, as then it's in the power of "
                    + "the toolchain author to properly specify and position compiler flags.",
            named = true,
            positional = false,
            defaultValue = "None",
            noneable = true),
        @Param(
            name = "user_compile_flags",
            doc =
                "List of additional compilation flags (copts). Passing depset is deprecated and "
                    + "will be removed by --incompatible_disable_depset_in_cc_user_flags flag.",
            positional = false,
            named = true,
            defaultValue = "None",
            noneable = true,
            allowedTypes = {
              @ParamType(type = NoneType.class),
              @ParamType(type = SkylarkList.class),
              @ParamType(type = SkylarkNestedSet.class)
            }),
        @Param(
            name = "include_directories",
            doc = "Depset of include directories.",
            positional = false,
            named = true,
            defaultValue = "None",
            noneable = true,
            allowedTypes = {
              @ParamType(type = NoneType.class),
              @ParamType(type = SkylarkNestedSet.class)
            }),
        @Param(
            name = "quote_include_directories",
            doc = "Depset of quote include directories.",
            positional = false,
            named = true,
            defaultValue = "None",
            noneable = true,
            allowedTypes = {
              @ParamType(type = NoneType.class),
              @ParamType(type = SkylarkNestedSet.class)
            }),
        @Param(
            name = "system_include_directories",
            doc = "Depset of system include directories.",
            positional = false,
            named = true,
            defaultValue = "None",
            noneable = true,
            allowedTypes = {
              @ParamType(type = NoneType.class),
              @ParamType(type = SkylarkNestedSet.class)
            }),
        @Param(
            name = "preprocessor_defines",
            doc = "Depset of preprocessor defines.",
            positional = false,
            named = true,
            defaultValue = "None",
            noneable = true,
            allowedTypes = {
              @ParamType(type = NoneType.class),
              @ParamType(type = SkylarkNestedSet.class)
            }),
        @Param(
            name = "use_pic",
            doc = "When true the compilation will generate position independent code.",
            positional = false,
            named = true,
            defaultValue = "False"),
        // TODO(b/65151735): Remove once we migrate crosstools to features
        @Param(
            name = "add_legacy_cxx_options",
            doc =
                "When true the flags will contain options coming from legacy cxx_flag crosstool "
                    + "fields.",
            named = true,
            positional = false,
            defaultValue = "False")
      })
  CcToolchainVariablesT getCompileBuildVariables(
      CcToolchainProviderT ccToolchainProvider,
      FeatureConfigurationT featureConfiguration,
      Object sourceFile,
      Object outputFile,
      Object userCompileFlags,
      Object includeDirs,
      Object quoteIncludeDirs,
      Object systemIncludeDirs,
      Object defines,
      boolean usePic,
      boolean addLegacyCxxOptions)
      throws EvalException;

  @SkylarkCallable(
      name = "create_link_variables",
      doc = "Returns link variables used for linking actions.",
      parameters = {
        @Param(
            name = "cc_toolchain",
            doc = "cc_toolchain for which we are creating build variables.",
            positional = false,
            named = true,
            type = CcToolchainProviderApi.class),
        @Param(
            name = "feature_configuration",
            doc = "Feature configuration to be queried.",
            positional = false,
            named = true,
            type = FeatureConfigurationApi.class),
        @Param(
            name = "library_search_directories",
            doc = "Depset of directories where linker will look for libraries at link time.",
            positional = false,
            named = true,
            defaultValue = "None",
            noneable = true,
            allowedTypes = {
              @ParamType(type = NoneType.class),
              @ParamType(type = SkylarkNestedSet.class)
            }),
        @Param(
            name = "runtime_library_search_directories",
            doc = "Depset of directories where loader will look for libraries at runtime.",
            positional = false,
            named = true,
            defaultValue = "None",
            noneable = true,
            allowedTypes = {
              @ParamType(type = NoneType.class),
              @ParamType(type = SkylarkNestedSet.class)
            }),
        @Param(
            name = "user_link_flags",
            doc =
                "List of additional link flags (linkopts). Passing depset is deprecated and "
                    + "will be removed by --incompatible_disable_depset_in_cc_user_flags flag.",
            positional = false,
            named = true,
            defaultValue = "None",
            noneable = true,
            allowedTypes = {
              @ParamType(type = NoneType.class),
              @ParamType(type = SkylarkList.class),
              @ParamType(type = SkylarkNestedSet.class)
            }),
        @Param(
            name = "output_file",
            doc = "Optional output file path.",
            named = true,
            positional = false,
            defaultValue = "None",
            noneable = true),
        @Param(
            name = "param_file",
            doc = "Optional param file path.",
            named = true,
            positional = false,
            defaultValue = "None",
            noneable = true),
        @Param(
            name = "def_file",
            doc = "Optional .def file path.",
            named = true,
            positional = false,
            defaultValue = "None",
            noneable = true),
        // TODO(b/65151735): Remove once we migrate crosstools to features
        @Param(
            name = "is_using_linker",
            doc =
                "True when using linker, False when archiver. Caller is responsible for keeping "
                    + "this in sync with action name used (is_using_linker = True for linking "
                    + "executable or dynamic library, is_using_linker = False for archiving static "
                    + "library).",
            named = true,
            positional = false,
            defaultValue = "True"),
        // TODO(b/65151735): Remove once we migrate crosstools to features
        @Param(
            name = "is_linking_dynamic_library",
            doc =
                "True when creating dynamic library, False when executable or static library. "
                    + "Caller is responsible for keeping this in sync with action name used. "
                    + ""
                    + "This field will be removed once b/65151735 is fixed.",
            named = true,
            positional = false,
            defaultValue = "False"),
        // TODO(b/65151735): Remove once we migrate crosstools to features
        @Param(
            name = "must_keep_debug",
            doc =
                "When set to True, bazel will expose 'strip_debug_symbols' variable, which is "
                    + "usually used to use the linker to strip debug symbols from the output file.",
            named = true,
            positional = false,
            defaultValue = "True"),
        // TODO(b/65151735): Remove once we migrate crosstools to features
        @Param(
            name = "use_test_only_flags",
            doc =
                "When set to True flags coming from test_only_linker_flag crosstool fields will"
                    + " be included."
                    + ""
                    + "This field will be removed once b/65151735 is fixed.",
            named = true,
            positional = false,
            defaultValue = "False"),
        // TODO(b/65151735): Remove once we migrate crosstools to features
        @Param(
            name = "is_static_linking_mode",
            doc =
                "True when using static_linking_mode, False when using dynamic_linking_mode. "
                    + "Caller is responsible for keeping this in sync with 'static_linking_mode' "
                    + "and 'dynamic_linking_mode' features enabled on the feature configuration. "
                    + ""
                    + "This field will be removed once b/65151735 is fixed.",
            named = true,
            positional = false,
            defaultValue = "True"),
      })
  CcToolchainVariablesT getLinkBuildVariables(
      CcToolchainProviderT ccToolchainProvider,
      FeatureConfigurationT featureConfiguration,
      Object librarySearchDirectories,
      Object runtimeLibrarySearchDirectories,
      Object userLinkFlags,
      Object outputFile,
      Object paramFile,
      Object defFile,
      boolean isUsingLinkerNotArchiver,
      boolean isCreatingSharedLibrary,
      boolean mustKeepDebug,
      boolean useTestOnlyFlags,
      boolean isStaticLinkingMode)
      throws EvalException;

  @SkylarkCallable(name = "empty_variables", documented = false)
  CcToolchainVariablesT getVariables();

  @SkylarkCallable(
      name = "create_library_to_link",
      documented = false,
      useLocation = true,
      useEnvironment = true,
      parameters = {
        @Param(
            name = "ctx",
            doc = "Starlark rule context.",
            positional = false,
            named = true,
            noneable = true,
            defaultValue = "None",
            type = SkylarkRuleContext.class),
        @Param(
            name = "library",
            doc = "Library to be linked.",
            positional = false,
            named = true,
            noneable = true,
            defaultValue = "None",
            type = Artifact.class),
        @Param(
            name = "artifact_category",
            doc =
                "Artifact category. Can be: static_library, alwayslink_static_library, "
                    + "dynamic_library or interface_library",
            positional = false,
            named = true,
            noneable = true,
            defaultValue = "None",
            type = String.class),
        @Param(
            name = "actions",
            type = SkylarkActionFactoryApi.class,
            positional = false,
            named = true,
            noneable = true,
            defaultValue = "None",
            doc = "Actions."),
        @Param(
            name = "feature_configuration",
            doc = "Feature configuration to be queried.",
            positional = false,
            named = true,
            noneable = true,
            defaultValue = "None",
            type = FeatureConfigurationApi.class),
        @Param(
            name = "cc_toolchain",
            doc = "C++ toolchain provider to be used.",
            positional = false,
            named = true,
            noneable = true,
            defaultValue = "None",
            type = CcToolchainProviderApi.class),
        @Param(
            name = "static_library",
            doc = "Library to be linked.",
            positional = false,
            named = true,
            noneable = true,
            defaultValue = "None",
            type = Artifact.class),
        @Param(
            name = "pic_static_library",
            doc = "Library to be linked.",
            positional = false,
            named = true,
            noneable = true,
            defaultValue = "None",
            type = Artifact.class),
        @Param(
            name = "dynamic_library",
            doc = "Library to be linked.",
            positional = false,
            named = true,
            noneable = true,
            defaultValue = "None",
            type = Artifact.class),
        @Param(
            name = "interface_library",
            doc = "Library to be linked.",
            positional = false,
            named = true,
            noneable = true,
            defaultValue = "None",
            type = Artifact.class),
        @Param(
            name = "alwayslink",
            doc = "Alwayslink.",
            positional = false,
            named = true,
            defaultValue = "False"),
      })
  Object createLibraryLinkerInput(
      Object skylarkRuleContext,
      Object library,
      Object skylarkArtifactCategory,
      Object actions,
      Object featureConfiguration,
      Object ccToolchainProvider,
      Object staticLibrary,
      Object picStaticLibrary,
      Object dynamicLibrary,
      Object interfaceLibrary,
      boolean alwayslink,
      Location location,
      Environment environment)
      throws EvalException, InterruptedException;

  @SkylarkCallable(
      name = "create_linking_context",
      documented = false,
      useLocation = true,
      useEnvironment = true,
      parameters = {
        @Param(
            name = "ctx",
            positional = false,
            named = true,
            noneable = true,
            defaultValue = "None",
            type = SkylarkRuleContextApi.class,
            doc = "The rule context."),
        @Param(
            name = "static_mode_params_for_dynamic_library",
            positional = false,
            named = true,
            noneable = true,
            defaultValue = "None",
            type = CcLinkParamsApi.class,
            doc = "Parameters for linking a dynamic library statically."),
        @Param(
            name = "static_mode_params_for_executable",
            doc = "Parameters for linking an executable statically",
            positional = false,
            named = true,
            noneable = true,
            defaultValue = "None",
            type = CcLinkParamsApi.class),
        @Param(
            name = "dynamic_mode_params_for_dynamic_library",
            doc = "Parameters for linking a dynamic library dynamically",
            positional = false,
            named = true,
            noneable = true,
            defaultValue = "None",
            type = CcLinkParamsApi.class),
        @Param(
            name = "dynamic_mode_params_for_executable",
            doc = "Parameters for linking an executable dynamically",
            positional = false,
            named = true,
            noneable = true,
            defaultValue = "None",
            type = CcLinkParamsApi.class),
        @Param(
            name = "libraries_to_link",
            doc = "The libraries to link",
            positional = false,
            named = true,
            noneable = true,
            defaultValue = "None",
            type = SkylarkList.class),
        @Param(
            name = "user_link_flags",
            doc = "User link flags",
            positional = false,
            named = true,
            noneable = true,
            defaultValue = "None",
            type = SkylarkList.class)
      })
  Object createCcLinkingInfo(
      Object skylarkRuleContextObject,
      Object staticModeParamsForDynamicLibraryObject,
      Object staticModeParamsForExecutableObject,
      Object dynamicModeParamsForDynamicLibraryObject,
      Object dynamicModeParamsForExecutableObject,
      Object librariesToLinkObject,
      Object userLinkFlagsObject,
      Location location,
      Environment environment)
      throws EvalException, InterruptedException;

  @SkylarkCallable(
      name = "create_symlink_library_to_link",
      documented = false,
      parameters = {
        @Param(
            name = "ctx",
            doc = "Starlark rule context.",
            positional = false,
            named = true,
            type = SkylarkRuleContext.class),
        @Param(
            name = "cc_toolchain",
            doc = "C++ toolchain provider to be used.",
            positional = false,
            named = true,
            type = CcToolchainProviderApi.class),
        @Param(
            name = "library",
            doc = "Library that should be symlinked.",
            positional = false,
            named = true,
            type = Artifact.class),
      })
  LibraryToLinkT createSymlinkLibraryLinkerInput(
      SkylarkRuleContext skylarkRuleContext, CcToolchainProviderT ccToolchain, Artifact library);

  @SkylarkCallable(
      name = "create_cc_link_params",
      doc = "Creates cc link parameters",
      parameters = {
        @Param(
            name = "ctx",
            positional = false,
            named = true,
            type = SkylarkRuleContextApi.class,
            doc = "The rule context."),
        @Param(
            name = "libraries_to_link",
            doc =
                "List of libraries that should be passed to the linker/archiver. They can be "
                    + "static and/or dynamic libraries.",
            positional = false,
            named = true,
            noneable = true,
            defaultValue = "None",
            allowedTypes = {
              @ParamType(type = SkylarkNestedSet.class),
              @ParamType(type = NoneType.class)
            }),
        @Param(
            name = "dynamic_libraries_for_runtime",
            doc =
                "When 'libraries_to_link' has dynamic libraries, then the runtime library can "
                    + "be specified as well. This is not obligatory though, as we may provide a "
                    + "library for linking and at runtime the actual library will be provided by "
                    + "the system.",
            positional = false,
            named = true,
            noneable = true,
            defaultValue = "None",
            allowedTypes = {
              @ParamType(type = SkylarkNestedSet.class),
              @ParamType(type = NoneType.class)
            }),
        @Param(
            name = "user_link_flags",
            doc = "List of user provided linker flags.",
            positional = false,
            named = true,
            noneable = true,
            defaultValue = "None",
            allowedTypes = {
              @ParamType(type = SkylarkNestedSet.class),
              @ParamType(type = NoneType.class)
            })
      })
  CcLinkParamsT createCcLinkParams(
      SkylarkRuleContext skylarkRuleContext,
      Object skylarkLibrariesToLink,
      Object skylarkDynamicLibrariesForRuntime,
      Object skylarkUserLinkFlags)
      throws EvalException, InterruptedException;

  @SkylarkCallable(
      name = "create_cc_skylark_info",
      documented = false,
      parameters = {
        // TODO(plf): Make this parameter mandatory. Change cc_embed_data.bzl first.
        @Param(
            name = "ctx",
            doc = "Starlark rule context.",
            positional = false,
            named = true,
            noneable = true,
            defaultValue = "None",
            allowedTypes = {
              @ParamType(type = SkylarkRuleContextApi.class),
              @ParamType(type = NoneType.class)
            })
      })
  CcSkylarkInfoT createCcSkylarkInfo(Object skylarkRuleContextObject)
      throws EvalException, InterruptedException;
}
