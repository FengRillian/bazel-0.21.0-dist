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

package com.google.devtools.build.lib.analysis;

import static java.util.stream.Collectors.toSet;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Multimap;
import com.google.common.collect.Sets;
import com.google.common.eventbus.EventBus;
import com.google.devtools.build.lib.actions.ActionAnalysisMetadata;
import com.google.devtools.build.lib.actions.ActionGraph;
import com.google.devtools.build.lib.actions.ActionLookupValue;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.ArtifactFactory;
import com.google.devtools.build.lib.actions.ArtifactOwner;
import com.google.devtools.build.lib.actions.PackageRoots;
import com.google.devtools.build.lib.analysis.config.BuildConfiguration;
import com.google.devtools.build.lib.analysis.config.BuildConfigurationCollection;
import com.google.devtools.build.lib.analysis.config.BuildOptions;
import com.google.devtools.build.lib.analysis.config.InvalidConfigurationException;
import com.google.devtools.build.lib.analysis.constraints.TopLevelConstraintSemantics;
import com.google.devtools.build.lib.analysis.test.CoverageReportActionFactory;
import com.google.devtools.build.lib.analysis.test.CoverageReportActionFactory.CoverageReportActionsWrapper;
import com.google.devtools.build.lib.analysis.test.InstrumentedFilesInfo;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadCompatible;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.ExtendedEventHandler;
import com.google.devtools.build.lib.packages.AspectClass;
import com.google.devtools.build.lib.packages.AspectDescriptor;
import com.google.devtools.build.lib.packages.AspectParameters;
import com.google.devtools.build.lib.packages.Attribute;
import com.google.devtools.build.lib.packages.NativeAspectClass;
import com.google.devtools.build.lib.packages.NoSuchPackageException;
import com.google.devtools.build.lib.packages.NoSuchTargetException;
import com.google.devtools.build.lib.packages.Rule;
import com.google.devtools.build.lib.packages.Target;
import com.google.devtools.build.lib.packages.TargetUtils;
import com.google.devtools.build.lib.pkgcache.PackageManager.PackageManagerStatistics;
import com.google.devtools.build.lib.profiler.Profiler;
import com.google.devtools.build.lib.profiler.SilentCloseable;
import com.google.devtools.build.lib.skyframe.AspectValue;
import com.google.devtools.build.lib.skyframe.AspectValue.AspectKey;
import com.google.devtools.build.lib.skyframe.AspectValue.AspectValueKey;
import com.google.devtools.build.lib.skyframe.ConfiguredTargetKey;
import com.google.devtools.build.lib.skyframe.CoverageReportValue;
import com.google.devtools.build.lib.skyframe.PrepareAnalysisPhaseValue;
import com.google.devtools.build.lib.skyframe.SkyframeAnalysisResult;
import com.google.devtools.build.lib.skyframe.SkyframeBuildView;
import com.google.devtools.build.lib.skyframe.SkyframeExecutor;
import com.google.devtools.build.lib.skyframe.TargetPatternPhaseValue;
import com.google.devtools.build.lib.syntax.SkylarkImport;
import com.google.devtools.build.lib.syntax.SkylarkImports;
import com.google.devtools.build.lib.syntax.SkylarkImports.SkylarkImportSyntaxException;
import com.google.devtools.build.lib.util.Pair;
import com.google.devtools.build.lib.util.RegexFilter;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.WalkableGraph;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import javax.annotation.Nullable;

/**
 * The BuildView presents a semantically-consistent and transitively-closed
 * dependency graph for some set of packages.
 *
 * <h2>Package design</h2>
 *
 * <p>This package contains the Blaze dependency analysis framework (aka
 * "analysis phase").  The goal of this code is to perform semantic analysis of
 * all of the build targets required for a given build, to report
 * errors/warnings for any problems in the input, and to construct an "action
 * graph" (see {@code lib.actions} package) correctly representing the work to
 * be done during the execution phase of the build.
 *
 * <p><b>Configurations</b> the inputs to a build come from two sources: the
 * intrinsic inputs, specified in the BUILD file, are called <em>targets</em>.
 * The environmental inputs, coming from the build tool, the command-line, or
 * configuration files, are called the <em>configuration</em>.  Only when a
 * target and a configuration are combined is there sufficient information to
 * perform a build. </p>
 *
 * <p>Targets are implemented by the {@link Target} hierarchy in the {@code
 * lib.packages} code.  Configurations are implemented by {@link
 * BuildConfiguration}.  The pair of these together is represented by an
 * instance of class {@link ConfiguredTarget}; this is the root of a hierarchy
 * with different implementations for each kind of target: source file, derived
 * file, rules, etc.
 *
 * <p>The framework code in this package (as opposed to its subpackages) is
 * responsible for constructing the {@code ConfiguredTarget} graph for a given
 * target and configuration, taking care of such issues as:
 * <ul>
 *   <li>caching common subgraphs.
 *   <li>detecting and reporting cycles.
 *   <li>correct propagation of errors through the graph.
 *   <li>reporting universal errors, such as dependencies from production code
 *       to tests, or to experimental branches.
 *   <li>capturing and replaying errors.
 *   <li>maintaining the graph from one build to the next to
 *       avoid unnecessary recomputation.
 *   <li>checking software licenses.
 * </ul>
 *
 * <p>See also {@link ConfiguredTarget} which documents some important
 * invariants.
 */
public class BuildView {
  private static final Logger logger = Logger.getLogger(BuildView.class.getName());

  private final BlazeDirectories directories;

  private final SkyframeExecutor skyframeExecutor;
  private final SkyframeBuildView skyframeBuildView;

  private final ConfiguredRuleClassProvider ruleClassProvider;

  /**
   * A factory class to create the coverage report action. May be null.
   */
  @Nullable private final CoverageReportActionFactory coverageReportActionFactory;

  public BuildView(BlazeDirectories directories,
      ConfiguredRuleClassProvider ruleClassProvider,
      SkyframeExecutor skyframeExecutor,
      CoverageReportActionFactory coverageReportActionFactory) {
    this.directories = directories;
    this.coverageReportActionFactory = coverageReportActionFactory;
    this.ruleClassProvider = ruleClassProvider;
    this.skyframeExecutor = Preconditions.checkNotNull(skyframeExecutor);
    this.skyframeBuildView = skyframeExecutor.getSkyframeBuildView();
  }

  /** The number of configured targets freshly evaluated in the last analysis run. */
  public int getTargetsConfigured() {
    return skyframeBuildView.getEvaluatedTargetKeys().size();
  }

  /** The number of targets (not configured targets) loaded in the last analysis run. */
  public int getTargetsLoaded() {
    return skyframeBuildView.getEvaluatedTargetKeys().stream()
        .map(key -> ((ConfiguredTargetKey) key).getLabel())
        .collect(toSet())
        .size();
  }

  public int getActionsConstructed() {
    return skyframeBuildView.getEvaluatedActionCount();
  }

  public PackageManagerStatistics getAndClearPkgManagerStatistics() {
    return skyframeExecutor.getPackageManager().getAndClearStatistics();
  }

  private ArtifactFactory getArtifactFactory() {
    return skyframeBuildView.getArtifactFactory();
  }

  /** Returns the collection of configured targets corresponding to any of the provided targets. */
  @VisibleForTesting
  static LinkedHashSet<ConfiguredTarget> filterTestsByTargets(
      Collection<ConfiguredTarget> targets, Set<Label> allowedTargetLabels) {
    return targets
        .stream()
        .filter(ct -> allowedTargetLabels.contains(ct.getLabel()))
        .collect(Collectors.toCollection(LinkedHashSet::new));
  }

  @ThreadCompatible
  public AnalysisResult update(
      TargetPatternPhaseValue loadingResult,
      BuildOptions targetOptions,
      Set<String> multiCpu,
      List<String> aspects,
      AnalysisOptions viewOptions,
      boolean keepGoing,
      int loadingPhaseThreads,
      TopLevelArtifactContext topLevelOptions,
      ExtendedEventHandler eventHandler,
      EventBus eventBus)
      throws ViewCreationFailedException, InvalidConfigurationException, InterruptedException {
    logger.info("Starting analysis");
    pollInterruptedStatus();

    skyframeBuildView.resetEvaluatedConfiguredTargetKeysSet();
    skyframeBuildView.resetEvaluationActionCount();

    // TODO(ulfjack): Expensive. Maybe we don't actually need the targets, only the labels?
    Collection<Target> targets =
        loadingResult.getTargets(eventHandler, skyframeExecutor.getPackageManager());
    eventBus.post(new AnalysisPhaseStartedEvent(targets));

    // Prepare the analysis phase
    BuildConfigurationCollection configurations;
    Collection<TargetAndConfiguration> topLevelTargetsWithConfigs;
    if (viewOptions.skyframePrepareAnalysis) {
      PrepareAnalysisPhaseValue prepareAnalysisPhaseValue;
      try (SilentCloseable c = Profiler.instance().profile("Prepare analysis phase")) {
        prepareAnalysisPhaseValue = skyframeExecutor.prepareAnalysisPhase(
            eventHandler, targetOptions, multiCpu, loadingResult.getTargetLabels());
  
        // Determine the configurations
        configurations =
            prepareAnalysisPhaseValue.getConfigurations(eventHandler, skyframeExecutor);
        topLevelTargetsWithConfigs =
            prepareAnalysisPhaseValue.getTopLevelCts(eventHandler, skyframeExecutor);
      }
    } else {
      // Configuration creation.
      // TODO(gregce): Consider dropping this phase and passing on-the-fly target / host configs as
      // needed. This requires cleaning up the invalidation in SkyframeBuildView.setConfigurations.
      try (SilentCloseable c = Profiler.instance().profile("createConfigurations")) {
        configurations =
            skyframeExecutor
                .createConfigurations(
                    eventHandler,
                    targetOptions,
                    multiCpu,
                    keepGoing);
      }
      try (SilentCloseable c = Profiler.instance().profile("AnalysisUtils.getTargetsWithConfigs")) {
        topLevelTargetsWithConfigs =
            AnalysisUtils.getTargetsWithConfigs(
                configurations, targets, eventHandler, ruleClassProvider, skyframeExecutor);
      }
    }

    skyframeBuildView.setConfigurations(
        eventHandler, configurations, viewOptions.maxConfigChangesToShow);

    if (configurations.getTargetConfigurations().size() == 1) {
      eventBus
          .post(
              new MakeEnvironmentEvent(
                  configurations.getTargetConfigurations().get(0).getMakeEnvironment()));
    }

    // Report the generated association of targets to configurations
    Multimap<Label, BuildConfiguration> byLabel =
        ArrayListMultimap.<Label, BuildConfiguration>create();
    for (TargetAndConfiguration pair : topLevelTargetsWithConfigs) {
      byLabel.put(pair.getLabel(), pair.getConfiguration());
    }
    for (Target target : targets) {
      eventBus.post(new TargetConfiguredEvent(target, byLabel.get(target.getLabel())));
    }

    List<ConfiguredTargetKey> topLevelCtKeys =
        topLevelTargetsWithConfigs
            .stream()
            .map(node -> ConfiguredTargetKey.of(node.getLabel(), node.getConfiguration()))
            .collect(Collectors.toList());

    Multimap<Pair<Label, String>, BuildConfiguration> aspectConfigurations =
        ArrayListMultimap.create();

    List<AspectValueKey> aspectKeys = new ArrayList<>();
    for (String aspect : aspects) {
      // Syntax: label%aspect
      int delimiterPosition = aspect.indexOf('%');
      if (delimiterPosition >= 0) {
        // TODO(jfield): For consistency with Skylark loads, the aspect should be specified
        // as an absolute label.
        // We convert it for compatibility reasons (this will be removed in the future).
        String bzlFileLoadLikeString = aspect.substring(0, delimiterPosition);
        if (!bzlFileLoadLikeString.startsWith("//") && !bzlFileLoadLikeString.startsWith("@")) {
          // "Legacy" behavior of '--aspects' parameter.
          if (bzlFileLoadLikeString.startsWith("/")) {
            bzlFileLoadLikeString = bzlFileLoadLikeString.substring(1);
          }
          int lastSlashPosition = bzlFileLoadLikeString.lastIndexOf('/');
          if (lastSlashPosition >= 0) {
            bzlFileLoadLikeString =
                "//"
                    + bzlFileLoadLikeString.substring(0, lastSlashPosition)
                    + ":"
                    + bzlFileLoadLikeString.substring(lastSlashPosition + 1);
          } else {
            bzlFileLoadLikeString = "//:" + bzlFileLoadLikeString;
          }
          if (!bzlFileLoadLikeString.endsWith(".bzl")) {
            bzlFileLoadLikeString = bzlFileLoadLikeString + ".bzl";
          }
        }
        SkylarkImport skylarkImport;
        try {
          skylarkImport =
              SkylarkImports.create(
                  bzlFileLoadLikeString, /* repositoryMapping= */ ImmutableMap.of());
        } catch (SkylarkImportSyntaxException e) {
          throw new ViewCreationFailedException(
              String.format("Invalid aspect '%s': %s", aspect, e.getMessage()), e);
        }

        String skylarkFunctionName = aspect.substring(delimiterPosition + 1);
        for (TargetAndConfiguration targetSpec : topLevelTargetsWithConfigs) {
          aspectConfigurations.put(
              Pair.of(targetSpec.getLabel(), aspect), targetSpec.getConfiguration());
          aspectKeys.add(
              AspectValue.createSkylarkAspectKey(
                  targetSpec.getLabel(),
                  // For invoking top-level aspects, use the top-level configuration for both the
                  // aspect and the base target while the top-level configuration is untrimmed.
                  targetSpec.getConfiguration(),
                  targetSpec.getConfiguration(),
                  skylarkImport,
                  skylarkFunctionName));
        }
      } else {
        final NativeAspectClass aspectFactoryClass =
            ruleClassProvider.getNativeAspectClassMap().get(aspect);

        if (aspectFactoryClass != null) {
          for (TargetAndConfiguration targetSpec : topLevelTargetsWithConfigs) {
            // For invoking top-level aspects, use the top-level configuration for both the
            // aspect and the base target while the top-level configuration is untrimmed.
            BuildConfiguration configuration = targetSpec.getConfiguration();
            aspectConfigurations.put(Pair.of(targetSpec.getLabel(), aspect), configuration);
            aspectKeys.add(
                AspectValue.createAspectKey(
                    targetSpec.getLabel(),
                    configuration,
                    new AspectDescriptor(aspectFactoryClass, AspectParameters.EMPTY),
                    configuration
                ));
          }
        } else {
          throw new ViewCreationFailedException("Aspect '" + aspect + "' is unknown");
        }
      }
    }

    for (Pair<Label, String> target : aspectConfigurations.keys()) {
      eventBus.post(
          new AspectConfiguredEvent(
              target.getFirst(), target.getSecond(), aspectConfigurations.get(target)));
    }

    getArtifactFactory().noteAnalysisStarting();
    SkyframeAnalysisResult skyframeAnalysisResult;
    try {
      skyframeAnalysisResult =
          skyframeBuildView.configureTargets(
              eventHandler, topLevelCtKeys, aspectKeys, eventBus, keepGoing, loadingPhaseThreads);
      setArtifactRoots(skyframeAnalysisResult.getPackageRoots());
    } finally {
      skyframeBuildView.clearInvalidatedConfiguredTargets();
    }

    int numTargetsToAnalyze = topLevelTargetsWithConfigs.size();
    int numSuccessful = skyframeAnalysisResult.getConfiguredTargets().size();
    if (0 < numSuccessful && numSuccessful < numTargetsToAnalyze) {
      String msg = String.format("Analysis succeeded for only %d of %d top-level targets",
                                    numSuccessful, numTargetsToAnalyze);
      eventHandler.handle(Event.info(msg));
      logger.info(msg);
    }

    Set<ConfiguredTarget> targetsToSkip =
        new TopLevelConstraintSemantics(
                skyframeExecutor.getPackageManager(),
                input -> skyframeExecutor.getConfiguration(eventHandler, input),
                eventHandler)
            .checkTargetEnvironmentRestrictions(skyframeAnalysisResult.getConfiguredTargets());

    AnalysisResult result =
        createResult(
            eventHandler,
            loadingResult,
            configurations,
            topLevelOptions,
            viewOptions,
            skyframeAnalysisResult,
            targetsToSkip,
            topLevelTargetsWithConfigs);
    logger.info("Finished analysis");
    return result;
  }

  private AnalysisResult createResult(
      ExtendedEventHandler eventHandler,
      TargetPatternPhaseValue loadingResult,
      BuildConfigurationCollection configurations,
      TopLevelArtifactContext topLevelOptions,
      AnalysisOptions viewOptions,
      SkyframeAnalysisResult skyframeAnalysisResult,
      Set<ConfiguredTarget> targetsToSkip,
      Collection<TargetAndConfiguration> topLevelTargetsWithConfigs)
      throws InterruptedException {
    Set<Label> testsToRun = loadingResult.getTestsToRunLabels();
    Set<ConfiguredTarget> configuredTargets =
        Sets.newLinkedHashSet(skyframeAnalysisResult.getConfiguredTargets());
    ImmutableSet<AspectValue> aspects = ImmutableSet.copyOf(skyframeAnalysisResult.getAspects());

    Set<ConfiguredTarget> allTargetsToTest = null;
    if (testsToRun != null) {
      // Determine the subset of configured targets that are meant to be run as tests.
      allTargetsToTest = filterTestsByTargets(configuredTargets, testsToRun);
    }

    ArtifactsToOwnerLabels.Builder artifactsToOwnerLabelsBuilder =
        new ArtifactsToOwnerLabels.Builder();
    Set<ConfiguredTarget> parallelTests = new HashSet<>();
    Set<ConfiguredTarget> exclusiveTests = new HashSet<>();

    // build-info and build-changelist.
    Collection<Artifact> buildInfoArtifacts =
        skyframeExecutor.getWorkspaceStatusArtifacts(eventHandler);
    Preconditions.checkState(buildInfoArtifacts.size() == 2, buildInfoArtifacts);
    buildInfoArtifacts.forEach(artifactsToOwnerLabelsBuilder::addArtifact);

    // Extra actions
    addExtraActionsIfRequested(
        viewOptions, configuredTargets, aspects, artifactsToOwnerLabelsBuilder, eventHandler);

    // Coverage
    NestedSet<Artifact> baselineCoverageArtifacts =
        getBaselineCoverageArtifacts(configuredTargets, artifactsToOwnerLabelsBuilder);
    if (coverageReportActionFactory != null) {
      CoverageReportActionsWrapper actionsWrapper;
      actionsWrapper =
          coverageReportActionFactory.createCoverageReportActionsWrapper(
              eventHandler,
              directories,
              allTargetsToTest,
              baselineCoverageArtifacts,
              getArtifactFactory(),
              CoverageReportValue.COVERAGE_REPORT_KEY,
              loadingResult.getWorkspaceName());
      if (actionsWrapper != null) {
        ImmutableList<ActionAnalysisMetadata> actions = actionsWrapper.getActions();
        skyframeExecutor.injectCoverageReportData(actions);
        actionsWrapper.getCoverageOutputs().forEach(artifactsToOwnerLabelsBuilder::addArtifact);
      }
    }

    // Tests. This must come last, so that the exclusive tests are scheduled after everything else.
    scheduleTestsIfRequested(
        parallelTests,
        exclusiveTests,
        topLevelOptions,
        allTargetsToTest,
        skyframeExecutor,
        eventHandler);

    String error = createErrorMessage(loadingResult, skyframeAnalysisResult);

    final WalkableGraph graph = skyframeAnalysisResult.getWalkableGraph();
    final ActionGraph actionGraph =
        new ActionGraph() {
          @Nullable
          @Override
          public ActionAnalysisMetadata getGeneratingAction(Artifact artifact) {
            ArtifactOwner artifactOwner = artifact.getArtifactOwner();
            if (artifactOwner instanceof ActionLookupValue.ActionLookupKey) {
              SkyKey key = (ActionLookupValue.ActionLookupKey) artifactOwner;
              ActionLookupValue val;
              try {
                val = (ActionLookupValue) graph.getValue(key);
              } catch (InterruptedException e) {
                throw new IllegalStateException(
                    "Interruption not expected from this graph: " + key, e);
              }
              return val == null ? null : val.getGeneratingActionDangerousReadJavadoc(artifact);
            }
            return null;
          }
        };
    return new AnalysisResult(
        configurations,
        configuredTargets,
        aspects,
        allTargetsToTest,
        targetsToSkip,
        error,
        actionGraph,
        artifactsToOwnerLabelsBuilder.build(),
        parallelTests,
        exclusiveTests,
        topLevelOptions,
        skyframeAnalysisResult.getPackageRoots(),
        loadingResult.getWorkspaceName(),
        topLevelTargetsWithConfigs);
  }

  @Nullable
  public static String createErrorMessage(
      TargetPatternPhaseValue loadingResult,
      @Nullable SkyframeAnalysisResult skyframeAnalysisResult) {
    return loadingResult.hasError()
        ? "command succeeded, but there were errors parsing the target pattern"
        : loadingResult.hasPostExpansionError()
                || (skyframeAnalysisResult != null && skyframeAnalysisResult.hasLoadingError())
            ? "command succeeded, but there were loading phase errors"
            : (skyframeAnalysisResult != null && skyframeAnalysisResult.hasAnalysisError())
                ? "command succeeded, but not all targets were analyzed"
                : null;
  }

  private static NestedSet<Artifact> getBaselineCoverageArtifacts(
      Collection<ConfiguredTarget> configuredTargets,
      ArtifactsToOwnerLabels.Builder topLevelArtifactsToOwnerLabels) {
    NestedSetBuilder<Artifact> baselineCoverageArtifacts = NestedSetBuilder.stableOrder();
    for (ConfiguredTarget target : configuredTargets) {
      InstrumentedFilesInfo provider = target.get(InstrumentedFilesInfo.SKYLARK_CONSTRUCTOR);
      if (provider != null) {
        TopLevelArtifactHelper.addArtifactsWithOwnerLabel(
            provider.getBaselineCoverageArtifacts(),
            null,
            target.getLabel(),
            topLevelArtifactsToOwnerLabels);
        baselineCoverageArtifacts.addTransitive(provider.getBaselineCoverageArtifacts());
      }
    }
    return baselineCoverageArtifacts.build();
  }

  private void addExtraActionsIfRequested(
      AnalysisOptions viewOptions,
      Collection<ConfiguredTarget> configuredTargets,
      Collection<AspectValue> aspects,
      ArtifactsToOwnerLabels.Builder artifactsToTopLevelLabelsMap,
      ExtendedEventHandler eventHandler) {
    RegexFilter filter = viewOptions.extraActionFilter;
    for (ConfiguredTarget target : configuredTargets) {
      ExtraActionArtifactsProvider provider =
          target.getProvider(ExtraActionArtifactsProvider.class);
      if (provider != null) {
        if (viewOptions.extraActionTopLevelOnly) {
          // Collect all aspect-classes that topLevel might inject.
          Set<AspectClass> aspectClasses = new HashSet<>();
          Target actualTarget = null;
          try {
            actualTarget =
                skyframeExecutor.getPackageManager().getTarget(eventHandler, target.getLabel());
          } catch (NoSuchPackageException | NoSuchTargetException | InterruptedException e) {
            eventHandler.handle(Event.error(""));
          }
          for (Attribute attr : actualTarget.getAssociatedRule().getAttributes()) {
            aspectClasses.addAll(attr.getAspectClasses());
          }
          TopLevelArtifactHelper.addArtifactsWithOwnerLabel(
              provider.getExtraActionArtifacts(),
              filter,
              target.getLabel(),
              artifactsToTopLevelLabelsMap);
          if (!aspectClasses.isEmpty()) {
            TopLevelArtifactHelper.addArtifactsWithOwnerLabel(
                filterTransitiveExtraActions(provider, aspectClasses),
                filter,
                target.getLabel(),
                artifactsToTopLevelLabelsMap);
          }
        } else {
          TopLevelArtifactHelper.addArtifactsWithOwnerLabel(
              provider.getTransitiveExtraActionArtifacts(),
              filter,
              target.getLabel(),
              artifactsToTopLevelLabelsMap);
        }
      }
    }
    for (AspectValue aspect : aspects) {
      ExtraActionArtifactsProvider provider =
          aspect.getConfiguredAspect().getProvider(ExtraActionArtifactsProvider.class);
      if (provider != null) {
        if (viewOptions.extraActionTopLevelOnly) {
          TopLevelArtifactHelper.addArtifactsWithOwnerLabel(
              provider.getExtraActionArtifacts(),
              filter,
              aspect.getLabel(),
              artifactsToTopLevelLabelsMap);
        } else {
          TopLevelArtifactHelper.addArtifactsWithOwnerLabel(
              provider.getTransitiveExtraActionArtifacts(),
              filter,
              aspect.getLabel(),
              artifactsToTopLevelLabelsMap);
        }
      }
    }
  }

  /**
   * Returns a list of actions from 'provider' that were registered by an aspect from
   * 'aspectClasses'. All actions in 'provider' are considered - both direct and transitive.
   */
  private ImmutableList<Artifact> filterTransitiveExtraActions(
      ExtraActionArtifactsProvider provider, Set<AspectClass> aspectClasses) {
    ImmutableList.Builder<Artifact> artifacts = ImmutableList.builder();
    // Add to 'artifacts' all extra-actions which were registered by aspects which 'topLevel'
    // might have injected.
    for (Artifact artifact : provider.getTransitiveExtraActionArtifacts()) {
      ArtifactOwner owner = artifact.getArtifactOwner();
      if (owner instanceof AspectKey) {
        if (aspectClasses.contains(((AspectKey) owner).getAspectClass())) {
          artifacts.add(artifact);
        }
      }
    }
    return artifacts.build();
  }

  private static void scheduleTestsIfRequested(
      Collection<ConfiguredTarget> targetsToTest,
      Collection<ConfiguredTarget> targetsToTestExclusive,
      TopLevelArtifactContext topLevelOptions,
      Collection<ConfiguredTarget> allTestTargets,
      SkyframeExecutor skyframeExecutor,
      ExtendedEventHandler eventHandler)
      throws InterruptedException {
    Set<String> outputGroups = topLevelOptions.outputGroups();
    if (!outputGroups.contains(OutputGroupInfo.FILES_TO_COMPILE)
        && !outputGroups.contains(OutputGroupInfo.COMPILATION_PREREQUISITES)
        && allTestTargets != null) {
      scheduleTests(
          targetsToTest,
          targetsToTestExclusive,
          allTestTargets,
          topLevelOptions.runTestsExclusively(),
          skyframeExecutor,
          eventHandler);
    }
  }

  /**
   * Returns set of artifacts representing test results, writing into targetsToTest and
   * targetsToTestExclusive.
   */
  private static void scheduleTests(
      Collection<ConfiguredTarget> targetsToTest,
      Collection<ConfiguredTarget> targetsToTestExclusive,
      Collection<ConfiguredTarget> allTestTargets,
      boolean isExclusive,
      SkyframeExecutor skyframeExecutor,
      ExtendedEventHandler eventHandler)
      throws InterruptedException {
    for (ConfiguredTarget configuredTarget : allTestTargets) {
      Target target = null;
      try {
        target =
            skyframeExecutor
                .getPackageManager()
                .getTarget(eventHandler, configuredTarget.getLabel());
      } catch (NoSuchTargetException | NoSuchPackageException e) {
        eventHandler.handle(Event.error("Failed to get target when scheduling tests"));
        continue;
      }
      if (target instanceof Rule) {
        boolean exclusive = isExclusive || TargetUtils.isExclusiveTestRule((Rule) target);
        Collection<ConfiguredTarget> testCollection = exclusive
            ? targetsToTestExclusive
            : targetsToTest;
        testCollection.add(configuredTarget);
      }
    }
  }

  /**
   * Sets the possible artifact roots in the artifact factory. This allows the factory to resolve
   * paths with unknown roots to artifacts.
   */
  private void setArtifactRoots(PackageRoots packageRoots) {
    getArtifactFactory().setPackageRoots(packageRoots.getPackageRootLookup());
  }

  /**
   * Tests and clears the current thread's pending "interrupted" status, and
   * throws InterruptedException iff it was set.
   */
  private final void pollInterruptedStatus() throws InterruptedException {
    if (Thread.interrupted()) {
      throw new InterruptedException();
    }
  }
}
