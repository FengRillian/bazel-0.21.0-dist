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
package com.google.devtools.build.lib.skyframe;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static com.google.devtools.build.skyframe.EvaluationProgressReceiver.EvaluationState.BUILT;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.common.eventbus.EventBus;
import com.google.devtools.build.lib.actions.ActionAnalysisMetadata;
import com.google.devtools.build.lib.actions.ActionKeyContext;
import com.google.devtools.build.lib.actions.ActionLookupValue;
import com.google.devtools.build.lib.actions.ArtifactFactory;
import com.google.devtools.build.lib.actions.ArtifactOwner;
import com.google.devtools.build.lib.actions.ArtifactPrefixConflictException;
import com.google.devtools.build.lib.actions.MutableActionGraph.ActionConflictException;
import com.google.devtools.build.lib.actions.PackageRoots;
import com.google.devtools.build.lib.analysis.AnalysisFailureEvent;
import com.google.devtools.build.lib.analysis.BlazeDirectories;
import com.google.devtools.build.lib.analysis.CachingAnalysisEnvironment;
import com.google.devtools.build.lib.analysis.ConfiguredRuleClassProvider;
import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.ConfiguredTargetFactory;
import com.google.devtools.build.lib.analysis.ToolchainContext;
import com.google.devtools.build.lib.analysis.ViewCreationFailedException;
import com.google.devtools.build.lib.analysis.buildinfo.BuildInfoFactory;
import com.google.devtools.build.lib.analysis.buildinfo.BuildInfoFactory.BuildInfoKey;
import com.google.devtools.build.lib.analysis.config.BuildConfiguration;
import com.google.devtools.build.lib.analysis.config.BuildConfigurationCollection;
import com.google.devtools.build.lib.analysis.config.BuildOptions;
import com.google.devtools.build.lib.analysis.config.BuildOptions.OptionsDiff;
import com.google.devtools.build.lib.analysis.config.ConfigMatchingProvider;
import com.google.devtools.build.lib.analysis.config.FragmentClassSet;
import com.google.devtools.build.lib.buildeventstream.BuildEventId;
import com.google.devtools.build.lib.buildtool.BuildRequestOptions;
import com.google.devtools.build.lib.causes.Cause;
import com.google.devtools.build.lib.causes.LabelCause;
import com.google.devtools.build.lib.causes.LoadingFailedCause;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.PackageIdentifier;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.EventHandler;
import com.google.devtools.build.lib.events.ExtendedEventHandler;
import com.google.devtools.build.lib.packages.Attribute;
import com.google.devtools.build.lib.packages.NoSuchPackageException;
import com.google.devtools.build.lib.packages.NoSuchTargetException;
import com.google.devtools.build.lib.packages.Package;
import com.google.devtools.build.lib.packages.Target;
import com.google.devtools.build.lib.pkgcache.LoadingFailureEvent;
import com.google.devtools.build.lib.profiler.Profiler;
import com.google.devtools.build.lib.profiler.SilentCloseable;
import com.google.devtools.build.lib.skyframe.AspectFunction.AspectCreationException;
import com.google.devtools.build.lib.skyframe.AspectValue.AspectValueKey;
import com.google.devtools.build.lib.skyframe.ConfiguredTargetFunction.ConfiguredValueCreationException;
import com.google.devtools.build.lib.skyframe.SkyframeActionExecutor.ConflictException;
import com.google.devtools.build.lib.skyframe.SkylarkImportLookupFunction.SkylarkImportFailedException;
import com.google.devtools.build.lib.util.OrderedSetMultimap;
import com.google.devtools.build.lib.vfs.Root;
import com.google.devtools.build.skyframe.CycleInfo;
import com.google.devtools.build.skyframe.ErrorInfo;
import com.google.devtools.build.skyframe.EvaluationProgressReceiver;
import com.google.devtools.build.skyframe.EvaluationResult;
import com.google.devtools.build.skyframe.SkyFunction.Environment;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import com.google.devtools.build.skyframe.WalkableGraph;
import com.google.devtools.common.options.OptionDefinition;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Stream;
import javax.annotation.Nullable;

/**
 * Skyframe-based driver of analysis.
 *
 * <p>Covers enough functionality to work as a substitute for {@code BuildView#configureTargets}.
 */
public final class SkyframeBuildView {
  private final ConfiguredTargetFactory factory;
  private final ArtifactFactory artifactFactory;
  private final SkyframeExecutor skyframeExecutor;
  private final SkyframeActionExecutor skyframeActionExecutor;
  private boolean enableAnalysis = false;

  // This hack allows us to see when a configured target has been invalidated, and thus when the set
  // of artifact conflicts needs to be recomputed (whenever a configured target has been invalidated
  // or newly evaluated).
  private final EvaluationProgressReceiver progressReceiver =
      new ConfiguredTargetValueProgressReceiver();
  private final Set<SkyKey> evaluatedConfiguredTargets = Sets.newConcurrentHashSet();
  // Used to see if checks of graph consistency need to be done after analysis.
  private volatile boolean someConfiguredTargetEvaluated = false;

  // We keep the set of invalidated configuration target keys so that we can know if something
  // has been invalidated after graph pruning has been executed.
  private Set<SkyKey> dirtiedConfiguredTargetKeys = Sets.newConcurrentHashSet();
  private volatile boolean anyConfiguredTargetDeleted = false;

  private final AtomicInteger evaluatedActionCount = new AtomicInteger();

  private final ConfiguredRuleClassProvider ruleClassProvider;

  // The host configuration containing all fragments used by this build's transitive closure.
  private BuildConfiguration topLevelHostConfiguration;
  // Fragment-limited versions of the host configuration. It's faster to create/cache these here
  // than to store them in Skyframe.
  private Map<FragmentClassSet, BuildConfiguration> hostConfigurationCache =
      Maps.newConcurrentMap();

  private BuildConfigurationCollection configurations;

  /**
   * If the last build was executed with {@code Options#discard_analysis_cache} and we are not
   * running Skyframe full, we should clear the legacy data since it is out-of-sync.
   */
  private boolean skyframeAnalysisWasDiscarded;

  private ImmutableSet<SkyKey> largestTopLevelKeySetCheckedForConflicts = ImmutableSet.of();

  public SkyframeBuildView(
      BlazeDirectories directories,
      SkyframeExecutor skyframeExecutor,
      ConfiguredRuleClassProvider ruleClassProvider,
      SkyframeActionExecutor skyframeActionExecutor) {
    this.skyframeActionExecutor = skyframeActionExecutor;
    this.factory =
        new ConfiguredTargetFactory(ruleClassProvider, skyframeExecutor.getDefaultBuildOptions());
    this.artifactFactory =
        new ArtifactFactory(directories.getExecRoot(), directories.getRelativeOutputPath());
    this.skyframeExecutor = skyframeExecutor;
    this.ruleClassProvider = ruleClassProvider;
  }

  public void resetEvaluatedConfiguredTargetKeysSet() {
    evaluatedConfiguredTargets.clear();
  }

  public Set<SkyKey> getEvaluatedTargetKeys() {
    return ImmutableSet.copyOf(evaluatedConfiguredTargets);
  }

  ConfiguredTargetFactory getConfiguredTargetFactory() {
    return factory;
  }

  public int getEvaluatedActionCount() {
    return evaluatedActionCount.get();
  }

  public void resetEvaluationActionCount() {
    evaluatedActionCount.set(0);
  }

  /**
   * Describes the change between the current configuration collection and the incoming one,
   * limiting the number of options listed based on maxDifferencesToShow. Returns {@code null} if
   * the configurations have not changed in a way that requires the analysis cache to be
   * invalidated.
   */
  private String describeConfigurationDifference(
      BuildConfigurationCollection configurations, int maxDifferencesToShow) {
    if (this.configurations == null) {
      // no configurations currently, no need to drop anything
      return null;
    }
    if (configurations.equals(this.configurations)) {
      // exact same configurations, no need to drop anything
      return null;
    }
    ImmutableList<BuildConfiguration> oldTargetConfigs =
        this.configurations.getTargetConfigurations();
    ImmutableList<BuildConfiguration> newTargetConfigs = configurations.getTargetConfigurations();
    // Only compare the first configurations of each set regardless of whether there are more. All
    // options other than cpu will be identical across configurations; only --experimental_multi_cpu
    // (which is checked for below) can add more configurations, and it only sets --cpu.
    // We don't need to check the host configuration because it's derived from the target options.
    BuildConfiguration oldConfig = oldTargetConfigs.get(0);
    BuildConfiguration newConfig = newTargetConfigs.get(0);
    OptionsDiff diff = BuildOptions.diff(oldConfig.getOptions(), newConfig.getOptions());
    Stream<OptionDefinition> optionsWithCacheInvalidatingDifferences =
        diff.getFirst().keySet().stream()
            .filter(
                (definition) ->
                    ruleClassProvider.shouldInvalidateCacheForOptionDiff(
                        newConfig.getOptions(),
                        definition,
                        diff.getFirst().get(definition),
                        Iterables.getOnlyElement(diff.getSecond().get(definition))));

    // --experimental_multi_cpu is currently the only way to have multiple configurations, but this
    // code is unable to see whether or how it is set, only infer it from the presence of multiple
    // configurations before or after the values changed and look at what the cpus of those
    // configurations are set to.
    if (Math.max(oldTargetConfigs.size(), newTargetConfigs.size()) > 1) {
      // Ignore changes to --cpu for consistency - depending on the old and new values of
      // --experimental_multi_cpu and how the order of configurations falls, we may or may not
      // register a --cpu change in the diff, and --experimental_multi_cpu overrides --cpu
      // anyway so it's redundant information as long as we have --experimental_multi_cpu change
      // detection.
      optionsWithCacheInvalidatingDifferences =
          optionsWithCacheInvalidatingDifferences.filter(
              (definition) -> !BuildConfiguration.Options.CPU.equals(definition));
      ImmutableSet<String> oldCpus =
          oldTargetConfigs.stream().map(BuildConfiguration::getCpu).collect(toImmutableSet());
      ImmutableSet<String> newCpus =
          newTargetConfigs.stream().map(BuildConfiguration::getCpu).collect(toImmutableSet());
      if (!Objects.equals(oldCpus, newCpus)) {
        // --experimental_multi_cpu has changed, so inject that in the diff stream.
        optionsWithCacheInvalidatingDifferences =
            Stream.concat(
                Stream.of(BuildRequestOptions.EXPERIMENTAL_MULTI_CPU),
                optionsWithCacheInvalidatingDifferences);
      }
    }
    if (maxDifferencesToShow == 0) {
      // with maxDifferencesToShow = 0, we're only concerned with whether _any_ option has changed,
      // so we don't need to do the option name transformation, and we only need to apply the
      // predicate until it finds one option that needs to be invalidated. Laziness go!
      return optionsWithCacheInvalidatingDifferences.findAny().isPresent()
          ? "Build options have changed"
          : null;
    }
    // Otherwise, we go through the entire diff to generate a complete sorted list of option names.
    // Being lazy by applying a limit here could lead to inconsistent options being shown for
    // different values of maxDifferencesToShow here - the options displayed in the truncated list
    // would be dependent on the order in which the diff items are returned from keySet(), even
    // though the list is sorted.
    ImmutableList<String> relevantDifferences =
        optionsWithCacheInvalidatingDifferences
            .map((definition) -> "--" + definition.getOptionName())
            .sorted()
            .collect(toImmutableList());
    if (relevantDifferences.isEmpty()) {
      // The configuration may have changed, but the predicate didn't think any of the changes
      // required a cache reset. For example, test trimming was turned on and a test option changed.
      // In this case, nothing needs to be done.
      return null;
    } else if (maxDifferencesToShow > 0 && relevantDifferences.size() > maxDifferencesToShow) {
      return String.format(
          "Build options %s%s and %d more have changed",
          Joiner.on(", ").join(relevantDifferences.subList(0, maxDifferencesToShow)),
          maxDifferencesToShow == 1 ? "" : ",",
          relevantDifferences.size() - maxDifferencesToShow);
    } else if (relevantDifferences.size() == 1) {
      return String.format(
          "Build option %s has changed", Iterables.getOnlyElement(relevantDifferences));
    } else if (relevantDifferences.size() == 2) {
      return String.format(
          "Build options %s have changed", Joiner.on(" and ").join(relevantDifferences));
    } else {
      return String.format(
          "Build options %s, and %s have changed",
          Joiner.on(", ").join(relevantDifferences.subList(0, relevantDifferences.size() - 1)),
          Iterables.getLast(relevantDifferences));
    }
  }

  /** Sets the configurations. Not thread-safe. DO NOT CALL except from tests! */
  @VisibleForTesting
  public void setConfigurations(
      EventHandler eventHandler,
      BuildConfigurationCollection configurations,
      int maxDifferencesToShow) {
    if (skyframeAnalysisWasDiscarded) {
      eventHandler.handle(
          Event.info(
              "--discard_analysis_cache was used in the previous build, "
              + "discarding analysis cache."));
      skyframeExecutor.handleConfiguredTargetChange();
    } else {
      String diff = describeConfigurationDifference(configurations, maxDifferencesToShow);
      if (diff != null) {
        // Clearing cached ConfiguredTargets when the configuration changes is not required for
        // correctness, but prevents unbounded memory usage.
        eventHandler.handle(Event.info(diff + ", discarding analysis cache."));
        skyframeExecutor.handleConfiguredTargetChange();
      }
    }
    skyframeAnalysisWasDiscarded = false;
    this.configurations = configurations;
    setTopLevelHostConfiguration(configurations.getHostConfiguration());
  }

  @VisibleForTesting
  public BuildConfigurationCollection getBuildConfigurationCollection() {
    return configurations;
  }

  /**
   * Sets the host configuration consisting of all fragments that will be used by the top level
   * targets' transitive closures.
   *
   * <p>This is used to power {@link #getHostConfiguration} during analysis, which computes
   * fragment-trimmed host configurations from the top-level one.
   */
  private void setTopLevelHostConfiguration(BuildConfiguration topLevelHostConfiguration) {
    if (topLevelHostConfiguration.equals(this.topLevelHostConfiguration)) {
      return;
    }
    hostConfigurationCache.clear();
    this.topLevelHostConfiguration = topLevelHostConfiguration;
  }

  /**
   * Drops the analysis cache. If building with Skyframe, targets in {@code topLevelTargets} may
   * remain in the cache for use during the execution phase.
   *
   * @see com.google.devtools.build.lib.analysis.AnalysisOptions#discardAnalysisCache
   */
  public void clearAnalysisCache(
      Collection<ConfiguredTarget> topLevelTargets, Collection<AspectValue> topLevelAspects) {
    // TODO(bazel-team): Consider clearing packages too to save more memory.
    skyframeAnalysisWasDiscarded = true;
    skyframeExecutor.clearAnalysisCache(topLevelTargets, topLevelAspects);
  }

  /**
   * Analyzes the specified targets using Skyframe as the driving framework.
   *
   * @return the configured targets that should be built along with a WalkableGraph of the analysis.
   */
  public SkyframeAnalysisResult configureTargets(
      ExtendedEventHandler eventHandler,
      List<ConfiguredTargetKey> values,
      List<AspectValueKey> aspectKeys,
      EventBus eventBus,
      boolean keepGoing,
      int numThreads)
      throws InterruptedException, ViewCreationFailedException {
    enableAnalysis(true);
    EvaluationResult<ActionLookupValue> result;
    try (SilentCloseable c = Profiler.instance().profile("skyframeExecutor.configureTargets")) {
      result =
          skyframeExecutor.configureTargets(
              eventHandler, values, aspectKeys, keepGoing, numThreads);
    } finally {
      enableAnalysis(false);
    }
    try (SilentCloseable c =
        Profiler.instance().profile("skyframeExecutor.findArtifactConflicts")) {
      ImmutableSet<SkyKey> newKeys =
          ImmutableSet.<SkyKey>builderWithExpectedSize(values.size() + aspectKeys.size())
              .addAll(values)
              .addAll(aspectKeys)
              .build();
      if (someConfiguredTargetEvaluated
          || anyConfiguredTargetDeleted
          || !dirtiedConfiguredTargetKeys.isEmpty()
          || !largestTopLevelKeySetCheckedForConflicts.containsAll(newKeys)
          || !skyframeActionExecutor.badActions().isEmpty()) {
        largestTopLevelKeySetCheckedForConflicts = newKeys;
        // This operation is somewhat expensive, so we only do it if the graph might have changed in
        // some way -- either we analyzed a new target or we invalidated an old one or are building
        // targets together that haven't been built before.
        skyframeActionExecutor.findAndStoreArtifactConflicts(
            // If we do not track incremental state we do not have graph edges,
            // so we cannot traverse the graph and find only actions in the current build.
            // In this case we can simply return all ActionLookupValues in the graph,
            // since the graph's lifetime is a single build anyway.
            skyframeExecutor.tracksStateForIncrementality()
                ? getActionLookupValuesInBuild(values, aspectKeys)
                : getActionLookupValuesInGraph());
        someConfiguredTargetEvaluated = false;
      }
    }
    ImmutableMap<ActionAnalysisMetadata, ConflictException> badActions =
        skyframeActionExecutor.badActions();

    Collection<AspectValue> goodAspects = Lists.newArrayListWithCapacity(values.size());
    Root singleSourceRoot = skyframeExecutor.getForcedSingleSourceRootIfNoExecrootSymlinkCreation();
    NestedSetBuilder<Package> packages =
        singleSourceRoot == null ? NestedSetBuilder.stableOrder() : null;
    for (AspectValueKey aspectKey : aspectKeys) {
      AspectValue value = (AspectValue) result.get(aspectKey);
      if (value == null) {
        // Skip aspects that couldn't be applied to targets.
        continue;
      }
      goodAspects.add(value);
      if (packages != null) {
        packages.addTransitive(value.getTransitivePackagesForPackageRootResolution());
      }
    }

    // Filter out all CTs that have a bad action and convert to a list of configured targets. This
    // code ensures that the resulting list of configured targets has the same order as the incoming
    // list of values, i.e., that the order is deterministic.
    Collection<ConfiguredTarget> goodCts = Lists.newArrayListWithCapacity(values.size());
    for (ConfiguredTargetKey value : values) {
      ConfiguredTargetValue ctValue = (ConfiguredTargetValue) result.get(value);
      if (ctValue == null) {
        continue;
      }
      goodCts.add(ctValue.getConfiguredTarget());
      if (packages != null) {
        packages.addTransitive(ctValue.getTransitivePackagesForPackageRootResolution());
      }
    }
    PackageRoots packageRoots =
        singleSourceRoot == null
            ? new MapAsPackageRoots(collectPackageRoots(packages.build().toCollection()))
            : new PackageRootsNoSymlinkCreation(singleSourceRoot);

    if (!result.hasError() && badActions.isEmpty()) {
      return new SkyframeAnalysisResult(
          /*hasLoadingError=*/false, /*hasAnalysisError=*/false,
          ImmutableList.copyOf(goodCts),
          result.getWalkableGraph(),
          ImmutableList.copyOf(goodAspects),
          packageRoots);
    }

    boolean hasLoadingError = false;
    ViewCreationFailedException noKeepGoingException = null;
    for (Map.Entry<SkyKey, ErrorInfo> errorEntry : result.errorMap().entrySet()) {
      SkyKey errorKey = errorEntry.getKey();
      ErrorInfo errorInfo = errorEntry.getValue();
      assertSaneAnalysisError(errorInfo, errorKey);
      skyframeExecutor
          .getCyclesReporter().reportCycles(errorInfo.getCycleInfo(), errorKey, eventHandler);
      Exception cause = errorInfo.getException();
      Preconditions.checkState(
          cause != null || !Iterables.isEmpty(errorInfo.getCycleInfo()), errorInfo);

      if (errorKey.argument() instanceof AspectValueKey) {
        // We skip Aspects in the keepGoing case; the failures should already have been reported to
        // the event handler.
        if (!keepGoing) {
          AspectValueKey aspectKey = (AspectValueKey) errorKey.argument();
          String errorMsg =
              String.format(
                  "Analysis of aspect '%s' failed; build aborted", aspectKey.getDescription());
          if (noKeepGoingException == null) {
            if (cause != null) {
              noKeepGoingException = new ViewCreationFailedException(errorMsg, cause);
            } else {
              noKeepGoingException = new ViewCreationFailedException(errorMsg);
            }
          }
        }
        continue;
      }

      Preconditions.checkState(
          errorKey.argument() instanceof ConfiguredTargetKey,
          "expected '%s' to be a AspectValueKey or ConfiguredTargetKey",
          errorKey.argument());
      ConfiguredTargetKey label = (ConfiguredTargetKey) errorKey.argument();
      Label topLevelLabel = label.getLabel();

      BuildEventId configuration = null;
      Iterable<Cause> rootCauses;
      if (cause instanceof ConfiguredValueCreationException) {
        ConfiguredValueCreationException ctCause = (ConfiguredValueCreationException) cause;
        // Previously, the nested set was de-duplicating loading root cause labels. Now that we
        // track Cause instances including a message, we get one event per label and message. In
        // order to keep backwards compatibility, we de-duplicate root cause labels here.
        // TODO(ulfjack): Remove this code once we've migrated to the BEP.
        Set<Label> loadingRootCauses = new HashSet<>();
        for (Cause rootCause : ctCause.getRootCauses()) {
          if (rootCause instanceof LoadingFailedCause) {
            hasLoadingError = true;
            loadingRootCauses.add(rootCause.getLabel());
          }
        }
        for (Label loadingRootCause : loadingRootCauses) {
          // This event is only for backwards compatibility with the old event protocol. Remove
          // once we've migrated to the build event protocol.
          eventBus.post(new LoadingFailureEvent(topLevelLabel, loadingRootCause));
        }
        rootCauses = ctCause.getRootCauses();
        configuration = ctCause.getConfiguration();
      } else if (!Iterables.isEmpty(errorInfo.getCycleInfo())) {
        Label analysisRootCause = maybeGetConfiguredTargetCycleCulprit(
            topLevelLabel, errorInfo.getCycleInfo());
        rootCauses = analysisRootCause != null
            ? ImmutableList.of(new LabelCause(analysisRootCause, "Dependency cycle"))
            // TODO(ulfjack): We need to report the dependency cycle here. How?
            : ImmutableList.of();
      } else if (cause instanceof ActionConflictException) {
        ((ActionConflictException) cause).reportTo(eventHandler);
        // TODO(ulfjack): Report the action conflict.
        rootCauses = ImmutableList.of();
      } else {
        // TODO(ulfjack): Report something!
        rootCauses = ImmutableList.of();
      }
      if (keepGoing) {
        eventHandler.handle(
            Event.warn(
                "errors encountered while analyzing target '"
                    + topLevelLabel
                    + "': it will not be built"));
      } else if (noKeepGoingException == null) {
        String errorMsg =
            String.format("Analysis of target '%s' failed; build aborted", topLevelLabel);
        if (cause != null) {
          noKeepGoingException = new ViewCreationFailedException(errorMsg, cause);
        } else {
          noKeepGoingException = new ViewCreationFailedException(errorMsg);
        }
      }
      ConfiguredTargetKey configuredTargetKey =
          ConfiguredTargetKey.of(
              topLevelLabel, label.getConfigurationKey(), label.isHostConfiguration());
      eventBus.post(new AnalysisFailureEvent(configuredTargetKey, configuration, rootCauses));
    }

    Collection<Exception> reportedExceptions = Sets.newHashSet();
    for (Map.Entry<ActionAnalysisMetadata, ConflictException> bad : badActions.entrySet()) {
      ConflictException ex = bad.getValue();
      try {
        ex.rethrowTyped();
      } catch (ActionConflictException ace) {
        ace.reportTo(eventHandler);
        if (keepGoing) {
          eventHandler.handle(
              Event.warn(
                  "errors encountered while analyzing target '"
                      + bad.getKey().getOwner().getLabel()
                      + "': it will not be built"));
        }
      } catch (ArtifactPrefixConflictException apce) {
        if (reportedExceptions.add(apce)) {
          eventHandler.handle(Event.error(apce.getMessage()));
        }
      }
      // TODO(ulfjack): Don't throw here in the nokeep_going case, but report all known issues.
      if (!keepGoing) {
        throw new ViewCreationFailedException(ex.getMessage());
      }
    }

    // This is here for backwards compatibility. The keep_going and nokeep_going code paths were
    // checking action conflicts and analysis errors in different orders, so we only throw the
    // analysis error here after first throwing action conflicts.
    if (!keepGoing) {
      throw noKeepGoingException;
    }

    if (!badActions.isEmpty()) {
      // In order to determine the set of configured targets transitively error free from action
      // conflict issues, we run a post-processing update() that uses the bad action map.
      EvaluationResult<PostConfiguredTargetValue> actionConflictResult =
          skyframeExecutor.postConfigureTargets(eventHandler, values, keepGoing, badActions);

      goodCts = Lists.newArrayListWithCapacity(values.size());
      for (ConfiguredTargetKey value : values) {
        PostConfiguredTargetValue postCt =
            actionConflictResult.get(PostConfiguredTargetValue.key(value));
        if (postCt != null) {
          goodCts.add(postCt.getCt());
        }
      }
    }

    return new SkyframeAnalysisResult(
        hasLoadingError,
        result.hasError() || !badActions.isEmpty(),
        ImmutableList.copyOf(goodCts),
        result.getWalkableGraph(),
        ImmutableList.copyOf(goodAspects),
        packageRoots);
  }

  /** Returns a map of collected package names to root paths. */
  private static ImmutableMap<PackageIdentifier, Root> collectPackageRoots(
      Collection<Package> packages) {
    // Make a map of the package names to their root paths.
    ImmutableMap.Builder<PackageIdentifier, Root> packageRoots = ImmutableMap.builder();
    for (Package pkg : packages) {
      packageRoots.put(pkg.getPackageIdentifier(), pkg.getSourceRoot());
    }
    return packageRoots.build();
  }

  @Nullable
  private static Label maybeGetConfiguredTargetCycleCulprit(
      Label labelToLoad, Iterable<CycleInfo> cycleInfos) {
    for (CycleInfo cycleInfo : cycleInfos) {
      SkyKey culprit = Iterables.getFirst(cycleInfo.getCycle(), null);
      if (culprit == null) {
        continue;
      }
      if (culprit.functionName().equals(SkyFunctions.CONFIGURED_TARGET)) {
        return ((ConfiguredTargetKey) culprit.argument()).getLabel();
      } else if (culprit.functionName().equals(SkyFunctions.TRANSITIVE_TARGET)) {
        return ((TransitiveTargetKey) culprit).getLabel();
      } else {
        return labelToLoad;
      }
    }
    return null;
  }

  private static void assertSaneAnalysisError(ErrorInfo errorInfo, SkyKey key) {
    Throwable cause = errorInfo.getException();
    if (cause != null) {
      // We should only be trying to configure targets when the loading phase succeeds, meaning
      // that the only errors should be analysis errors.
      Preconditions.checkState(
          cause instanceof ConfiguredValueCreationException
              || cause instanceof ActionConflictException
              || cause instanceof CcCrosstoolException
              // For top-level aspects
              || cause instanceof AspectCreationException
              || cause instanceof SkylarkImportFailedException
              // Only if we run the reduced loading phase and then analyze with --nokeep_going.
              || cause instanceof NoSuchTargetException
              || cause instanceof NoSuchPackageException,
          "%s -> %s",
          key,
          errorInfo);
    }
  }

  /** Special flake for error cases when loading CROSSTOOL for C++ rules */
  // TODO(b/110087561): Remove when CROSSTOOL file is not loaded anymore
  public static class CcCrosstoolException extends Exception {

    public CcCrosstoolException(String message) {
      super(message);
    }
  }

  public ArtifactFactory getArtifactFactory() {
    return artifactFactory;
  }

  /**
   * Because we don't know what build-info artifacts this configured target may request, we
   * conservatively register a dep on all of them.
   */
  // TODO(bazel-team): Allow analysis to return null so the value builder can exit and wait for a
  // restart deps are not present.
  private static boolean getWorkspaceStatusValues(
      Environment env,
      BuildConfiguration config,
      ImmutableMap<BuildInfoKey, BuildInfoFactory> buildInfoFactories)
      throws InterruptedException {
    env.getValue(WorkspaceStatusValue.BUILD_INFO_KEY);
    // These factories may each create their own build info artifacts, all depending on the basic
    // build-info.txt and build-changelist.txt.
    List<SkyKey> depKeys = Lists.newArrayList();
    for (BuildInfoKey key : buildInfoFactories.keySet()) {
      if (buildInfoFactories.get(key).isEnabled(config)) {
        depKeys.add(BuildInfoCollectionValue.key(key, config));
      }
    }
    env.getValues(depKeys);
    return !env.valuesMissing();
  }

  /** Returns null if any build-info values are not ready. */
  @Nullable
  CachingAnalysisEnvironment createAnalysisEnvironment(
      ArtifactOwner owner,
      boolean isSystemEnv,
      ExtendedEventHandler eventHandler,
      Environment env,
      BuildConfiguration config)
      throws InterruptedException {
    if (config != null
        && !getWorkspaceStatusValues(env, config, skyframeExecutor.getBuildInfoFactories())) {
      return null;
    }
    boolean extendedSanityChecks = config != null && config.extendedSanityChecks();
    return new CachingAnalysisEnvironment(
        artifactFactory,
        skyframeExecutor.getActionKeyContext(),
        owner,
        isSystemEnv,
        extendedSanityChecks,
        eventHandler,
        env);
  }

  /**
   * Invokes the appropriate constructor to create a {@link ConfiguredTarget} instance.
   *
   * <p>For use in {@code ConfiguredTargetFunction}.
   *
   * <p>Returns null if Skyframe deps are missing or upon certain errors.
   */
  @Nullable
  ConfiguredTarget createConfiguredTarget(
      Target target,
      BuildConfiguration configuration,
      CachingAnalysisEnvironment analysisEnvironment,
      OrderedSetMultimap<Attribute, ConfiguredTargetAndData> prerequisiteMap,
      ImmutableMap<Label, ConfigMatchingProvider> configConditions,
      @Nullable ToolchainContext toolchainContext)
      throws InterruptedException, ActionConflictException {
    Preconditions.checkState(
        enableAnalysis, "Already in execution phase %s %s", target, configuration);
    Preconditions.checkNotNull(analysisEnvironment);
    Preconditions.checkNotNull(target);
    Preconditions.checkNotNull(prerequisiteMap);
    return factory.createConfiguredTarget(
        analysisEnvironment,
        artifactFactory,
        target,
        configuration,
        getHostConfiguration(configuration),
        prerequisiteMap,
        configConditions,
        toolchainContext);
  }

  /**
   * Returns the host configuration trimmed to the same fragments as the input configuration. If
   * the input is null, returns the top-level host configuration.
   *
   * <p>This may only be called after {@link #setTopLevelHostConfiguration} has set the
   * correct host configuration at the top-level.
   */
  public BuildConfiguration getHostConfiguration(BuildConfiguration config) {
    if (config == null) {
      return topLevelHostConfiguration;
    }
    // TODO(bazel-team): have the fragment classes be those required by the consuming target's
    // transitive closure. This isn't the same as the input configuration's fragment classes -
    // the latter may be a proper subset of the former.
    //
    // ConfigurationFactory.getConfiguration provides the reason why: if a declared required
    // fragment is evaluated and returns null, it never gets added to the configuration. So if we
    // use the configuration's fragments as the source of truth, that excludes required fragments
    // that never made it in.
    //
    // If we're just trimming an existing configuration, this is no big deal (if the original
    // configuration doesn't need the fragment, the trimmed one doesn't either). But this method
    // trims a host configuration to the same scope as a target configuration. Since their options
    // are different, the host instance may actually be able to produce the fragment. So it's
    // wrong and potentially dangerous to unilaterally exclude it.
    FragmentClassSet fragmentClasses =
        config.trimConfigurations()
            ? config.fragmentClasses()
            : FragmentClassSet.of(ruleClassProvider.getAllFragments());
    BuildConfiguration hostConfig = hostConfigurationCache.get(fragmentClasses);
    if (hostConfig != null) {
      return hostConfig;
    }
    // TODO(bazel-team): investigate getting the trimmed config from Skyframe instead of cloning.
    // This is the only place we instantiate BuildConfigurations outside of Skyframe, This can
    // produce surprising effects, such as requesting a configuration that's in the Skyframe cache
    // but still produces a unique instance because we don't check that cache. It'd be nice to
    // guarantee that *all* instantiations happen through Skyframe. That could, for example,
    // guarantee that config1.equals(config2) implies config1 == config2, which is nice for
    // verifying we don't accidentally create extra configurations. But unfortunately,
    // hostConfigurationCache was specifically created because Skyframe is too slow for this use
    // case. So further optimization is necessary to make that viable (proto_library in particular
    // contributes to much of the difference).
    BuildConfiguration trimmedConfig =
        topLevelHostConfiguration.clone(
            fragmentClasses, ruleClassProvider, skyframeExecutor.getDefaultBuildOptions());
    hostConfigurationCache.put(fragmentClasses, trimmedConfig);
    return trimmedConfig;
  }

  SkyframeDependencyResolver createDependencyResolver(Environment env) {
    return new SkyframeDependencyResolver(env);
  }

  /**
   * Workaround to clear all legacy data, like the artifact factory. We need
   * to clear them to avoid conflicts.
   * TODO(bazel-team): Remove this workaround. [skyframe-execution]
   */
  void clearLegacyData() {
    artifactFactory.clear();
  }

  /**
   * Clears any data cached in this BuildView. To be called when the attached SkyframeExecutor is
   * reset.
   */
  void reset() {
    configurations = null;
    skyframeAnalysisWasDiscarded = false;
    clearLegacyData();
  }

  /**
   * Hack to invalidate actions in legacy action graph when their values are invalidated in
   * skyframe.
   */
  EvaluationProgressReceiver getProgressReceiver() {
    return progressReceiver;
  }

  /** Clear the invalidated configured targets detected during loading and analysis phases. */
  public void clearInvalidatedConfiguredTargets() {
    dirtiedConfiguredTargetKeys = Sets.newConcurrentHashSet();
    anyConfiguredTargetDeleted = false;
  }

  /**
   * {@link #createConfiguredTarget} will only create configured targets if this is set to true. It
   * should be set to true before any Skyframe update call that might call into {@link
   * #createConfiguredTarget}, and false immediately after the call. Use it to fail-fast in the case
   * that a target is requested for analysis not during the analysis phase.
   */
  public void enableAnalysis(boolean enable) {
    this.enableAnalysis = enable;
  }

  public ActionKeyContext getActionKeyContext() {
    return skyframeExecutor.getActionKeyContext();
  }

  private class ConfiguredTargetValueProgressReceiver
      extends EvaluationProgressReceiver.NullEvaluationProgressReceiver {
    @Override
    public void invalidated(SkyKey skyKey, InvalidationState state) {
      if (skyKey.functionName().equals(SkyFunctions.CONFIGURED_TARGET)) {
        if (state == InvalidationState.DELETED) {
          anyConfiguredTargetDeleted = true;
        } else {
          // If the value was just dirtied and not deleted, then it may not be truly invalid, since
          // it may later get re-validated. Therefore adding the key to dirtiedConfiguredTargetKeys
          // is provisional--if the key is later evaluated and the value found to be clean, then we
          // remove it from the set.
          dirtiedConfiguredTargetKeys.add(skyKey);
        }
      }
    }

    @Override
    public void evaluated(
        SkyKey skyKey,
        @Nullable SkyValue value,
        Supplier<EvaluationSuccessState> evaluationSuccessState,
        EvaluationState state) {
      if (skyKey.functionName().equals(SkyFunctions.CONFIGURED_TARGET)) {
        switch (state) {
          case BUILT:
            if (evaluationSuccessState.get().succeeded()) {
              evaluatedConfiguredTargets.add(skyKey);
              // During multithreaded operation, this is only set to true, so no concurrency issues.
              someConfiguredTargetEvaluated = true;
            }
            if (value instanceof ConfiguredTargetValue) {
              evaluatedActionCount.addAndGet(((ConfiguredTargetValue) value).getNumActions());
            }
            break;
          case CLEAN:
            // If the configured target value did not need to be rebuilt, then it wasn't truly
            // invalid.
            dirtiedConfiguredTargetKeys.remove(skyKey);
            break;
        }
      } else if (skyKey.functionName().equals(SkyFunctions.ASPECT)
          && state == BUILT
          && value instanceof AspectValue) {
        evaluatedActionCount.addAndGet(((AspectValue) value).getNumActions());
      }
    }
  }

  // Finds every ActionLookupValue reachable from the top-level targets of the current build
  private Iterable<ActionLookupValue> getActionLookupValuesInBuild(
      Iterable<ConfiguredTargetKey> ctKeys, Iterable<AspectValueKey> aspectKeys)
      throws InterruptedException {
    WalkableGraph walkableGraph = SkyframeExecutorWrappingWalkableGraph.of(skyframeExecutor);
    Set<SkyKey> seen = new HashSet<>();
    List<ActionLookupValue> result = new ArrayList<>();
    for (ConfiguredTargetKey key : ctKeys) {
      findActionsRecursively(walkableGraph, key, seen, result);
    }
    for (AspectValueKey key : aspectKeys) {
      findActionsRecursively(walkableGraph, key, seen, result);
    }
    return result;
  }

  private static void findActionsRecursively(
      WalkableGraph walkableGraph, SkyKey key, Set<SkyKey> seen, List<ActionLookupValue> result)
      throws InterruptedException {
    if (!(key instanceof ActionLookupValue.ActionLookupKey) || !seen.add(key)) {
      // The subgraph of dependencies of ActionLookupValues never has a non-ActionLookupValue
      // depending on an ActionLookupValue. So we can skip any non-ActionLookupValues in the
      // traversal as an optimization.
      return;
    }
    SkyValue value = walkableGraph.getValue(key);
    if (value == null) {
      // This means the value failed to evaluate
      return;
    }
    if (value instanceof ActionLookupValue) {
      result.add((ActionLookupValue) value);
    }
    for (Map.Entry<SkyKey, Iterable<SkyKey>> deps :
        walkableGraph.getDirectDeps(ImmutableList.of(key)).entrySet()) {
      for (SkyKey dep : deps.getValue()) {
        findActionsRecursively(walkableGraph, dep, seen, result);
      }
    }
  }

  // Returns every ActionLookupValue currently contained in the whole action graph
  private Iterable<ActionLookupValue> getActionLookupValuesInGraph() {
    return Iterables.filter(
        skyframeExecutor.memoizingEvaluator.getDoneValues().values(), ActionLookupValue.class);
  }
}
