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
package com.google.devtools.build.lib.rules.cpp;

import com.google.common.base.Predicates;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.rules.cpp.CcToolchainFeatures.FeatureConfiguration;
import com.google.devtools.build.lib.rules.cpp.CcToolchainVariables.SequenceBuilder;
import com.google.devtools.build.lib.syntax.EvalException;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.util.ArrayList;
import java.util.List;

/** Enum covering all build variables we create for all various {@link CppLinkAction}. */
public enum LinkBuildVariables {
  /** Entries in the linker runtime search path (usually set by -rpath flag) */
  RUNTIME_LIBRARY_SEARCH_DIRECTORIES("runtime_library_search_directories"),
  /** Entries in the linker search path (usually set by -L flag) */
  LIBRARY_SEARCH_DIRECTORIES("library_search_directories"),
  /** Flags providing files to link as inputs in the linker invocation */
  LIBRARIES_TO_LINK("libraries_to_link"),
  /** Thinlto param file produced by thinlto-indexing action consumed by the final link action. */
  THINLTO_PARAM_FILE("thinlto_param_file"),
  /** Location of def file used on Windows with MSVC */
  DEF_FILE_PATH("def_file_path"),
  /** Location where hinlto should write thinlto_param_file flags when indexing. */
  THINLTO_INDEXING_PARAM_FILE("thinlto_indexing_param_file"),

  THINLTO_PREFIX_REPLACE("thinlto_prefix_replace"),
  /**
   * A build variable to let the LTO indexing step know how to map from the minimized bitcode file
   * to the full bitcode file used by the LTO Backends.
   */
  THINLTO_OBJECT_SUFFIX_REPLACE("thinlto_object_suffix_replace"),
  /**
   * A build variable for the path to the merged object file, which is an object file that is
   * created during the LTO indexing step and needs to be passed to the final link.
   */
  THINLTO_MERGED_OBJECT_FILE("thinlto_merged_object_file"),
  /** Location of linker param file created by bazel to overcome command line length limit */
  LINKER_PARAM_FILE("linker_param_file"),
  /** execpath of the output of the linker. */
  OUTPUT_EXECPATH("output_execpath"),
  /** "yes"|"no" depending on whether interface library should be generated. */
  GENERATE_INTERFACE_LIBRARY("generate_interface_library"),
  /** Path to the interface library builder tool. */
  INTERFACE_LIBRARY_BUILDER("interface_library_builder_path"),
  /** Input for the interface library ifso builder tool. */
  INTERFACE_LIBRARY_INPUT("interface_library_input_path"),
  /** Path where to generate interface library using the ifso builder tool. */
  INTERFACE_LIBRARY_OUTPUT("interface_library_output_path"),
  /** Linker flags coming from the legacy crosstool fields. */
  LEGACY_LINK_FLAGS("legacy_link_flags"),
  /** Linker flags coming from the --linkopt or linkopts attribute. */
  USER_LINK_FLAGS("user_link_flags"),
  /** Path to which to write symbol counts. */
  SYMBOL_COUNTS_OUTPUT("symbol_counts_output"),
  /** A build variable giving linkstamp paths. */
  LINKSTAMP_PATHS("linkstamp_paths"),
  /** Presence of this variable indicates that PIC code should be generated. */
  FORCE_PIC("force_pic"),
  /** Presence of this variable indicates that the debug symbols should be stripped. */
  STRIP_DEBUG_SYMBOLS("strip_debug_symbols"),
  /** Truthy when current action is a cc_test linking action, falsey otherwise. */
  IS_CC_TEST("is_cc_test"),
  /**
   * Presence of this variable indicates that files were compiled with fission (debug info is in
   * .dwo files instead of .o files and linker needs to know).
   */
  IS_USING_FISSION("is_using_fission");

  private final String variableName;

  LinkBuildVariables(String variableName) {
    this.variableName = variableName;
  }

  public String getVariableName() {
    return variableName;
  }

  public static CcToolchainVariables setupVariables(
      boolean isUsingLinkerNotArchiver,
      PathFragment binDirectoryPath,
      String outputFile,
      boolean isCreatingSharedLibrary,
      String paramFile,
      String thinltoParamFile,
      String thinltoMergedObjectFile,
      boolean mustKeepDebug,
      Artifact symbolCounts,
      CcToolchainProvider ccToolchainProvider,
      FeatureConfiguration featureConfiguration,
      boolean useTestOnlyFlags,
      boolean isLtoIndexing,
      Iterable<String> userLinkFlags,
      String interfaceLibraryBuilder,
      String interfaceLibraryOutput,
      PathFragment ltoOutputRootPrefix,
      String defFile,
      FdoProvider fdoProvider,
      Iterable<String> runtimeLibrarySearchDirectories,
      SequenceBuilder librariesToLink,
      Iterable<String> librarySearchDirectories,
      boolean isLegacyFullyStaticLinkingMode,
      boolean isStaticLinkingMode,
      boolean addIfsoRelatedVariables)
      throws EvalException {
    CcToolchainVariables.Builder buildVariables =
        new CcToolchainVariables.Builder(ccToolchainProvider.getBuildVariables());

    // symbol counting
    if (symbolCounts != null) {
      buildVariables.addStringVariable(
          SYMBOL_COUNTS_OUTPUT.getVariableName(), symbolCounts.getExecPathString());
    }

    // pic
    if (ccToolchainProvider.getForcePic()) {
      buildVariables.addStringVariable(FORCE_PIC.getVariableName(), "");
    }

    if (!mustKeepDebug && ccToolchainProvider.getShouldStripBinaries()) {
      buildVariables.addStringVariable(STRIP_DEBUG_SYMBOLS.getVariableName(), "");
    }

    if (isUsingLinkerNotArchiver
        && ccToolchainProvider.shouldCreatePerObjectDebugInfo(featureConfiguration)) {
      buildVariables.addStringVariable(IS_USING_FISSION.getVariableName(), "");
    }

    if (useTestOnlyFlags) {
      buildVariables.addIntegerVariable(IS_CC_TEST.getVariableName(), 1);
    } else {
      buildVariables.addIntegerVariable(IS_CC_TEST.getVariableName(), 0);
    }

    if (runtimeLibrarySearchDirectories != null) {
      buildVariables.addStringSequenceVariable(
          RUNTIME_LIBRARY_SEARCH_DIRECTORIES.getVariableName(), runtimeLibrarySearchDirectories);
    }

    if (librariesToLink != null) {
      buildVariables.addCustomBuiltVariable(LIBRARIES_TO_LINK.getVariableName(), librariesToLink);
    }

    buildVariables.addStringSequenceVariable(
        LIBRARY_SEARCH_DIRECTORIES.getVariableName(), librarySearchDirectories);

    if (paramFile != null) {
      buildVariables.addStringVariable(LINKER_PARAM_FILE.getVariableName(), paramFile);
    }

    // output exec path
    if (outputFile != null && !isLtoIndexing) {
      buildVariables.addStringVariable(OUTPUT_EXECPATH.getVariableName(), outputFile);
    }

    if (isLtoIndexing) {
      if (thinltoParamFile != null) {
        // This is a lto-indexing action and we want it to populate param file.
        buildVariables.addStringVariable(
            THINLTO_INDEXING_PARAM_FILE.getVariableName(), thinltoParamFile);
        // TODO(b/33846234): Remove once all the relevant crosstools don't depend on the variable.
        buildVariables.addStringVariable("thinlto_optional_params_file", "=" + thinltoParamFile);
      } else {
        buildVariables.addStringVariable(THINLTO_INDEXING_PARAM_FILE.getVariableName(), "");
        // TODO(b/33846234): Remove once all the relevant crosstools don't depend on the variable.
        buildVariables.addStringVariable("thinlto_optional_params_file", "");
      }
      buildVariables.addStringVariable(
          THINLTO_PREFIX_REPLACE.getVariableName(),
          binDirectoryPath.getSafePathString()
              + ";"
              + binDirectoryPath.getRelative(ltoOutputRootPrefix));
      String objectFileExtension =
          ccToolchainProvider
              .getFeatures()
              .getArtifactNameExtensionForCategory(ArtifactCategory.OBJECT_FILE);
      buildVariables.addStringVariable(
          THINLTO_OBJECT_SUFFIX_REPLACE.getVariableName(),
          Iterables.getOnlyElement(CppFileTypes.LTO_INDEXING_OBJECT_FILE.getExtensions())
              + ";" + objectFileExtension);
      if (thinltoMergedObjectFile != null) {
        buildVariables.addStringVariable(
            THINLTO_MERGED_OBJECT_FILE.getVariableName(), thinltoMergedObjectFile);
      }
    } else {
      if (thinltoParamFile != null) {
        // This is a normal link action and we need to use param file created by lto-indexing.
        buildVariables.addStringVariable(THINLTO_PARAM_FILE.getVariableName(), thinltoParamFile);
      }
    }

    if (addIfsoRelatedVariables) {
      boolean shouldGenerateInterfaceLibrary =
          outputFile != null
              && interfaceLibraryBuilder != null
              && interfaceLibraryOutput != null
              && !isLtoIndexing;
      buildVariables.addStringVariable(
          GENERATE_INTERFACE_LIBRARY.getVariableName(),
          shouldGenerateInterfaceLibrary ? "yes" : "no");
      buildVariables.addStringVariable(
          INTERFACE_LIBRARY_BUILDER.getVariableName(),
          shouldGenerateInterfaceLibrary ? interfaceLibraryBuilder : "ignored");
      buildVariables.addStringVariable(
          INTERFACE_LIBRARY_INPUT.getVariableName(),
          shouldGenerateInterfaceLibrary ? outputFile : "ignored");
      buildVariables.addStringVariable(
          INTERFACE_LIBRARY_OUTPUT.getVariableName(),
          shouldGenerateInterfaceLibrary ? interfaceLibraryOutput : "ignored");
    }

    if (defFile != null) {
      buildVariables.addStringVariable(DEF_FILE_PATH.getVariableName(), defFile);
    }

    if (featureConfiguration.isEnabled(CppRuleClasses.FDO_INSTRUMENT)) {
      buildVariables.addStringVariable("fdo_instrument_path", fdoProvider.getFdoInstrument());
    }

    Iterable<String> userLinkFlagsWithLtoIndexingIfNeeded;
    if (!isLtoIndexing) {
      userLinkFlagsWithLtoIndexingIfNeeded = userLinkFlags;
    } else {
      ImmutableList.Builder<String> opts = ImmutableList.builder();
      opts.addAll(userLinkFlags);
      opts.addAll(
          featureConfiguration.getCommandLine(
              CppActionNames.LTO_INDEXING, buildVariables.build(), /* expander= */ null));
      opts.addAll(ccToolchainProvider.getCppConfiguration().getLtoIndexOptions());
      userLinkFlagsWithLtoIndexingIfNeeded = opts.build();
    }

    // For now, silently ignore linkopts if this is a static library
    userLinkFlagsWithLtoIndexingIfNeeded =
        isUsingLinkerNotArchiver ? userLinkFlagsWithLtoIndexingIfNeeded : ImmutableList.of();

    buildVariables.addStringSequenceVariable(
        LinkBuildVariables.USER_LINK_FLAGS.getVariableName(),
        removePieIfCreatingSharedLibrary(
            isCreatingSharedLibrary, userLinkFlagsWithLtoIndexingIfNeeded));
    buildVariables.addStringSequenceVariable(
        LinkBuildVariables.LEGACY_LINK_FLAGS.getVariableName(),
        getToolchainFlags(
            isLegacyFullyStaticLinkingMode,
            isStaticLinkingMode,
            isUsingLinkerNotArchiver,
            featureConfiguration,
            ccToolchainProvider,
            useTestOnlyFlags,
            isCreatingSharedLibrary,
            userLinkFlags));

    return buildVariables.build();
  }

  private static ImmutableList<String> getToolchainFlags(
      boolean isLegacyFullyStaticLinkingMode,
      boolean isStaticLinkingMode,
      boolean isUsingLinkerNotArchiver,
      FeatureConfiguration featureConfiguration,
      CcToolchainProvider ccToolchainProvider,
      boolean useTestOnlyFlags,
      boolean isCreatingSharedLibrary,
      Iterable<String> userLinkFlags) {
    if (!isUsingLinkerNotArchiver) {
      return ImmutableList.of();
    }
    CppConfiguration cppConfiguration = ccToolchainProvider.getCppConfiguration();
    boolean sharedLinkopts =
        isCreatingSharedLibrary
            || Iterables.contains(userLinkFlags, "-shared")
            || cppConfiguration.hasSharedLinkOption();

    List<String> result = new ArrayList<>();

    // Extra toolchain link options based on the output's link staticness.
    if (isLegacyFullyStaticLinkingMode) {
      result.addAll(
          CppHelper.getFullyStaticLinkOptions(
              cppConfiguration, ccToolchainProvider, sharedLinkopts));
    } else if (isStaticLinkingMode) {
      if (!featureConfiguration.isEnabled(CppRuleClasses.STATIC_LINKING_MODE)) {
        result.addAll(
            CppHelper.getMostlyStaticLinkOptions(
                cppConfiguration,
                ccToolchainProvider,
                sharedLinkopts,
                featureConfiguration.isEnabled(CppRuleClasses.STATIC_LINK_CPP_RUNTIMES)));
      } else {
        result.addAll(ccToolchainProvider.getLegacyLinkOptions());
      }
    } else {
      if (!featureConfiguration.isEnabled(CppRuleClasses.DYNAMIC_LINKING_MODE)) {
        result.addAll(
            CppHelper.getDynamicLinkOptions(cppConfiguration, ccToolchainProvider, sharedLinkopts));
      } else {
        result.addAll(ccToolchainProvider.getLegacyLinkOptions());
      }
    }

    // Extra test-specific link options.
    if (useTestOnlyFlags) {
      result.addAll(ccToolchainProvider.getTestOnlyLinkOptions());
    }

    if (!cppConfiguration.enableLinkoptsInUserLinkFlags()) {
      result.addAll(cppConfiguration.getLinkopts());
    }

    // -pie is not compatible with shared and should be
    // removed when the latter is part of the link command. Should we need to further
    // distinguish between shared libraries and executables, we could add additional
    // command line / CROSSTOOL flags that distinguish them. But as long as this is
    // the only relevant use case we're just special-casing it here.
    return ImmutableList.copyOf(removePieIfCreatingSharedLibrary(isCreatingSharedLibrary, result));
  }

  private static Iterable<String> removePieIfCreatingSharedLibrary(
      boolean isCreatingSharedLibrary, Iterable<String> flags) {
    if (isCreatingSharedLibrary) {
      return Iterables.filter(
          flags,
          Predicates.not(
              Predicates.or(Predicates.equalTo("-pie"), Predicates.equalTo("-Wl,-pie"))));
    } else {
      return flags;
    }
  }
}
