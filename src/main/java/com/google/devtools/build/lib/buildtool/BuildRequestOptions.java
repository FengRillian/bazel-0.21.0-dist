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
package com.google.devtools.build.lib.buildtool;

import com.google.common.annotations.VisibleForTesting;
import com.google.devtools.build.lib.actions.LocalHostCapacity;
import com.google.devtools.build.lib.util.OptionsUtils;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.common.options.Converters;
import com.google.devtools.common.options.Converters.RangeConverter;
import com.google.devtools.common.options.Option;
import com.google.devtools.common.options.OptionDefinition;
import com.google.devtools.common.options.OptionDocumentationCategory;
import com.google.devtools.common.options.OptionEffectTag;
import com.google.devtools.common.options.OptionMetadataTag;
import com.google.devtools.common.options.OptionsBase;
import com.google.devtools.common.options.OptionsParser;
import com.google.devtools.common.options.OptionsParsingException;
import java.util.List;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Options interface for {@link BuildRequest}: can be used to parse command-line arguments.
 *
 * <p>See also {@code ExecutionOptions}; from the user's point of view, there's no qualitative
 * difference between these two sets of options.
 */
public class BuildRequestOptions extends OptionsBase {
  public static final OptionDefinition EXPERIMENTAL_MULTI_CPU =
      OptionsParser.getOptionDefinitionByName(BuildRequestOptions.class, "experimental_multi_cpu");
  private static final Logger logger = Logger.getLogger(BuildRequestOptions.class.getName());
  private static final int JOBS_TOO_HIGH_WARNING = 2500;
  @VisibleForTesting public static final int MAX_JOBS = 5000;

  /* "Execution": options related to the execution of a build: */

  @Option(
    name = "jobs",
    abbrev = 'j',
    defaultValue = "auto",
    documentationCategory = OptionDocumentationCategory.EXECUTION_STRATEGY,
    effectTags = {OptionEffectTag.HOST_MACHINE_RESOURCE_OPTIMIZATIONS, OptionEffectTag.EXECUTION},
    converter = JobsConverter.class,
    help =
        "The number of concurrent jobs to run. 0 means build sequentially."
            + " \"auto\" means to use a reasonable value derived from the machine's hardware"
            + " profile (e.g. the number of processors). Values above "
            + MAX_JOBS
            + " are not allowed, and values above "
            + JOBS_TOO_HIGH_WARNING
            + " may cause memory issues."
  )
  public int jobs;

  @Option(
    name = "progress_report_interval",
    defaultValue = "0",
    documentationCategory = OptionDocumentationCategory.LOGGING,
    effectTags = {OptionEffectTag.AFFECTS_OUTPUTS},
    converter = ProgressReportIntervalConverter.class,
    help =
        "The number of seconds to wait between two reports on still running jobs. The "
            + "default value 0 means to use the default 10:30:60 incremental algorithm."
  )
  public int progressReportInterval;

  @Option(
    name = "explain",
    defaultValue = "null",
    documentationCategory = OptionDocumentationCategory.LOGGING,
    effectTags = {OptionEffectTag.AFFECTS_OUTPUTS},
    converter = OptionsUtils.PathFragmentConverter.class,
    help =
        "Causes the build system to explain each executed step of the "
            + "build. The explanation is written to the specified log file."
  )
  public PathFragment explanationPath;

  @Option(
    name = "verbose_explanations",
    defaultValue = "false",
    documentationCategory = OptionDocumentationCategory.LOGGING,
    effectTags = {OptionEffectTag.AFFECTS_OUTPUTS},
    help =
        "Increases the verbosity of the explanations issued if --explain is enabled. "
            + "Has no effect if --explain is not enabled."
  )
  public boolean verboseExplanations;

  @Option(
    name = "output_filter",
    converter = Converters.RegexPatternConverter.class,
    defaultValue = "null",
    documentationCategory = OptionDocumentationCategory.LOGGING,
    effectTags = {OptionEffectTag.AFFECTS_OUTPUTS},
    help = "Only shows warnings for rules with a name matching the provided regular expression."
  )
  public Pattern outputFilter;

  @Option(
    name = "analyze",
    defaultValue = "true",
    documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
    effectTags = {OptionEffectTag.LOADING_AND_ANALYSIS, OptionEffectTag.AFFECTS_OUTPUTS},
    help =
        "Execute the analysis phase; this is the usual behaviour. Specifying --noanalyze causes "
            + "the build to stop before starting the analysis phase, returning zero iff the "
            + "package loading completed successfully; this mode is useful for testing."
  )
  public boolean performAnalysisPhase;

  @Option(
    name = "build",
    defaultValue = "true",
    documentationCategory = OptionDocumentationCategory.OUTPUT_SELECTION,
    effectTags = {OptionEffectTag.EXECUTION, OptionEffectTag.AFFECTS_OUTPUTS},
    help =
        "Execute the build; this is the usual behaviour. "
            + "Specifying --nobuild causes the build to stop before executing the build "
            + "actions, returning zero iff the package loading and analysis phases completed "
            + "successfully; this mode is useful for testing those phases."
  )
  public boolean performExecutionPhase;

  @Option(
    name = "output_groups",
    converter = Converters.CommaSeparatedOptionListConverter.class,
    allowMultiple = true,
    documentationCategory = OptionDocumentationCategory.OUTPUT_SELECTION,
    effectTags = {OptionEffectTag.EXECUTION, OptionEffectTag.AFFECTS_OUTPUTS},
    defaultValue = "",
    help =
        "Specifies which output groups of the top-level targets to build. If omitted, a default "
            + "set of output groups are built. When specified the default set is overridden. "
            + "However you may use --output_groups=+<output_group> or "
            + "--output_groups=-<output_group> to instead modify the set of output groups."
  )
  public List<String> outputGroups;

  @Option(
    name = "show_result",
    defaultValue = "1",
    documentationCategory = OptionDocumentationCategory.LOGGING,
    effectTags = {OptionEffectTag.AFFECTS_OUTPUTS},
    help =
        "Show the results of the build.  For each target, state whether or not it was brought "
            + "up-to-date, and if so, a list of output files that were built.  The printed files "
            + "are convenient strings for copy+pasting to the shell, to execute them.\n"
            + "This option requires an integer argument, which is the threshold number of "
            + "targets above which result information is not printed. Thus zero causes "
            + "suppression of the message and MAX_INT causes printing of the result to occur "
            + "always.  The default is one."
  )
  public int maxResultTargets;

  @Option(
    name = "experimental_show_artifacts",
    defaultValue = "false",
    documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
    effectTags = {OptionEffectTag.AFFECTS_OUTPUTS},
    help =
        "Output a list of all top level artifacts produced by this build."
            + "Use output format suitable for tool consumption. "
            + "This flag is temporary and intended to facilitate Android Studio integration. "
            + "This output format will likely change in the future or disappear completely."
  )
  public boolean showArtifacts;

  @Option(
    name = "announce",
    defaultValue = "false",
    documentationCategory = OptionDocumentationCategory.LOGGING,
    effectTags = {OptionEffectTag.AFFECTS_OUTPUTS},
    help = "Deprecated. No-op.",
    deprecationWarning = "This option is now deprecated and is a no-op"
  )
  public boolean announce;

  @Option(
    name = "symlink_prefix",
    defaultValue = "null",
    documentationCategory = OptionDocumentationCategory.OUTPUT_PARAMETERS,
    effectTags = {OptionEffectTag.AFFECTS_OUTPUTS},
    help =
        "The prefix that is prepended to any of the convenience symlinks that are created "
            + "after a build. If '/' is passed, then no symlinks are created and no warning is "
            + "emitted. If omitted, the default value is the name of the build tool."
  )
  public String symlinkPrefix;

  @Option(
    name = "experimental_multi_cpu",
    converter = Converters.CommaSeparatedOptionListConverter.class,
    allowMultiple = true,
    defaultValue = "",
    documentationCategory = OptionDocumentationCategory.OUTPUT_PARAMETERS,
    effectTags = {OptionEffectTag.AFFECTS_OUTPUTS},
    metadataTags = {OptionMetadataTag.EXPERIMENTAL},
    help =
        "This flag allows specifying multiple target CPUs. If this is specified, "
            + "the --cpu option is ignored."
  )
  public List<String> multiCpus;

  @Option(
    name = "output_tree_tracking",
    oldName = "experimental_output_tree_tracking",
    defaultValue = "true",
    documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
    effectTags = {OptionEffectTag.BAZEL_INTERNAL_CONFIGURATION},
    help =
        "If set, tell the output service (if any) to track when files in the output "
            + "tree have been modified externally (not by the build system). "
            + "This should improve incremental build speed when an appropriate output service "
            + "is enabled."
  )
  public boolean finalizeActions;

  @Option(
    name = "aspects",
    converter = Converters.CommaSeparatedOptionListConverter.class,
    defaultValue = "",
    documentationCategory = OptionDocumentationCategory.OUTPUT_PARAMETERS,
    effectTags = {OptionEffectTag.UNKNOWN},
    allowMultiple = true,
    help =
        "Comma-separated list of aspects to be applied to top-level targets. All aspects "
            + "are applied to all top-level targets independently. Aspects are specified in "
            + "the form <bzl-file-label>%<aspect_name>, "
            + "for example '//tools:my_def.bzl%my_aspect', where 'my_aspect' is a top-level "
            + "value from from a file tools/my_def.bzl"
  )
  public List<String> aspects;

  public String getSymlinkPrefix(String productName) {
    return symlinkPrefix == null ? productName + "-" : symlinkPrefix;
  }

  // Transitional flag for safely rolling out new convenience symlink behavior.
  // To be made a no-op and deleted once new symlink behavior is battle-tested.
  @Option(
    name = "use_top_level_targets_for_symlinks",
    defaultValue = "false",
    documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
    effectTags = {OptionEffectTag.AFFECTS_OUTPUTS},
    help =
        "If enabled, the symlinks are based on the configurations of the top-level targets "
            + " rather than the top-level target configuration. If this would be ambiguous, "
            + " the symlinks will be deleted to avoid confusion."
  )
  public boolean useTopLevelTargetsForSymlinks;

  /**
   * Returns whether to use the output directories used by the top-level targets for convenience
   * symlinks.
   *
   * <p>If true, then symlinks use the actual output directories of the top-level targets. The
   * symlinks will be created iff all top-level targets share the same output directory. Otherwise,
   * any stale symlinks from previous invocations will be deleted to avoid ambiguity.
   *
   * <p>If false, then symlinks use the output directory implied by command-line flags, regardless
   * of whether top-level targets have transitions which change them (or even have any output
   * directories at all, as in the case of a build with no targets or one which only builds source
   * files).
   */
  public boolean useTopLevelTargetsForSymlinks() {
    return useTopLevelTargetsForSymlinks;
  }

  @Option(
    name = "print_workspace_in_output_paths_if_needed",
    defaultValue = "false",
    documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
    effectTags = {OptionEffectTag.TERMINAL_OUTPUT},
    help =
        "If enabled, when the current working directory is deeper than the workspace (for example, "
            + "when running from <workspace>/foo instead of <workspace>), printed output paths "
            + "include the absolute path to the workspace (for example, "
            + "<workspace>/<symlink_prefix>-bin/foo/binary instead of "
            + "<symlink_prefix>-bin/foo/binary)."
  )
  public boolean printWorkspaceInOutputPathsIfNeeded;

  @Option(
    name = "use_action_cache",
    defaultValue = "true",
    documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
    effectTags = {
      OptionEffectTag.BAZEL_INTERNAL_CONFIGURATION,
      OptionEffectTag.HOST_MACHINE_RESOURCE_OPTIMIZATIONS
    },
    help = "Whether to use the action cache"
  )
  public boolean useActionCache;

  @Option(
      name = "discard_actions_after_execution",
      defaultValue = "true",
      documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
      metadataTags = OptionMetadataTag.INCOMPATIBLE_CHANGE,
      effectTags = {OptionEffectTag.LOSES_INCREMENTAL_STATE},
      help = "This option is deprecated and has no effect.")
  public boolean discardActionsAfterExecution;

  /** Converter for jobs: [0, MAX_JOBS] or "auto". */
  public static class JobsConverter extends RangeConverter {

    public JobsConverter() {
      super(0, MAX_JOBS);
    }

    @Override
    public Integer convert(String input) throws OptionsParsingException {
      int jobs;
      if (input.equals("auto")) {
        jobs = (int) Math.ceil(LocalHostCapacity.getLocalHostCapacity().getCpuUsage());
        if (jobs > MAX_JOBS) {
          logger.warning(
              "Detected "
                  + jobs
                  + " processors, which exceed the maximum allowed number of jobs of "
                  + MAX_JOBS
                  + "; something seems wrong");
          jobs = MAX_JOBS;
        }
        logger.info("Flag \"jobs\" was set to \"auto\"; using " + jobs + " jobs");
      } else {
        jobs = super.convert(input);
      }
      return jobs == 0 ? 1 : jobs; // Treat 0 jobs as a single task.
    }

    @Override
    public String getTypeDescription() {
      return "\"auto\" or " + super.getTypeDescription();
    }
  }

  /** Converter for progress_report_interval: [0, 3600]. */
  public static class ProgressReportIntervalConverter extends RangeConverter {
    public ProgressReportIntervalConverter() {
      super(0, 3600);
    }
  }
}
