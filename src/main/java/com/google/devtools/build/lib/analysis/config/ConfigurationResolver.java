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

package com.google.devtools.build.lib.analysis.config;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.base.Verify;
import com.google.common.base.VerifyException;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.LinkedHashMultimap;
import com.google.common.collect.LinkedListMultimap;
import com.google.common.collect.Multimap;
import com.google.common.collect.Sets;
import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.Dependency;
import com.google.devtools.build.lib.analysis.TargetAndConfiguration;
import com.google.devtools.build.lib.analysis.config.transitions.ConfigurationTransition;
import com.google.devtools.build.lib.analysis.config.transitions.NoTransition;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.concurrent.ThreadSafety;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.ExtendedEventHandler;
import com.google.devtools.build.lib.packages.Attribute;
import com.google.devtools.build.lib.packages.RuleClassProvider;
import com.google.devtools.build.lib.packages.RuleTransitionFactory;
import com.google.devtools.build.lib.packages.Target;
import com.google.devtools.build.lib.skyframe.BuildConfigurationValue;
import com.google.devtools.build.lib.skyframe.ConfiguredTargetFunction;
import com.google.devtools.build.lib.skyframe.ConfiguredTargetValue;
import com.google.devtools.build.lib.skyframe.SkyframeExecutor;
import com.google.devtools.build.lib.skyframe.TransitiveTargetKey;
import com.google.devtools.build.lib.skyframe.TransitiveTargetValue;
import com.google.devtools.build.lib.util.OrderedSetMultimap;
import com.google.devtools.build.skyframe.SkyFunction;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.ValueOrException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * Turns configuration transition requests into actual configurations.
 *
 * <p>This involves:
 * <ol>
 *   <li>Patching a source configuration's options with the transition
 *   <li>If {@link BuildConfiguration#trimConfigurations} is true, trimming configuration fragments
 *       to only those needed by the destination target and its transitive dependencies
 *   <li>Getting the destination configuration from Skyframe
 *   </ol>
 *
 * <p>For the work of determining the transition requests themselves, see
 * {@link TransitionResolver}.
 */
public final class ConfigurationResolver {
  /**
   * Translates a set of {@link Dependency} objects with configuration transition requests to the
   * same objects with resolved configurations.
   *
   * <p>If {@link BuildConfiguration.Options#trimConfigurations()} is true, these configurations
   * only contain the fragments needed by the dep and its transitive closure. Else they
   * unconditionally include all fragments.
   *
   * <p>This method is heavily performance-optimized. Because {@link ConfiguredTargetFunction} calls
   * it over every edge in the configured target graph, small inefficiencies can have observable
   * impact on analysis time. Keep this in mind when making modifications and performance-test any
   * changes you make.
   *
   * @param env Skyframe evaluation environment
   * @param ctgValue the label and configuration of the source target
   * @param originalDeps the transition requests for each dep under this target's attributes
   * @param hostConfiguration the host configuration
   * @param ruleClassProvider provider for determining the right configuration fragments for deps
   * @return a mapping from each attribute in the source target to the {@link BuildConfiguration}s
   *     and {@link Label}s for the deps under that attribute. Returns null if not all Skyframe
   *     dependencies are available.
   */
  @Nullable
  public static OrderedSetMultimap<Attribute, Dependency> resolveConfigurations(
      SkyFunction.Environment env,
      TargetAndConfiguration ctgValue,
      OrderedSetMultimap<Attribute, Dependency> originalDeps,
      BuildConfiguration hostConfiguration,
      RuleClassProvider ruleClassProvider,
      BuildOptions defaultBuildOptions)
      throws ConfiguredTargetFunction.DependencyEvaluationException, InterruptedException {

    // Maps each Skyframe-evaluated BuildConfiguration to the dependencies that need that
    // configuration. For cases where Skyframe isn't needed to get the configuration (e.g. when
    // we just re-used the original rule's configuration), we should skip this outright.
    Multimap<SkyKey, Map.Entry<Attribute, Dependency>> keysToEntries = LinkedListMultimap.create();

    // Stores the result of applying a transition to the current configuration using a
    // particular subset of fragments. By caching this, we save from redundantly computing the
    // same transition for every dependency edge that requests that transition. This can have
    // real effect on analysis time for commonly triggered transitions.
    //
    // Split transitions may map to multiple values. All other transitions map to one.
    Map<FragmentsAndTransition, List<BuildOptions>> transitionsMap = new LinkedHashMap<>();

    // The fragments used by the current target's configuration.
    FragmentClassSet ctgFragments = ctgValue.getConfiguration().fragmentClasses();
    BuildOptions ctgOptions = ctgValue.getConfiguration().getOptions();

    // Stores the configuration-resolved versions of each dependency. This method must preserve the
    // original label ordering of each attribute. For example, if originalDeps.get("data") is
    // [":a", ":b"], the resolved variant must also be [":a", ":b"] in the same order. Because we
    // may not actualize the results in order (some results need Skyframe-evaluated configurations
    // while others can be computed trivially), we dump them all into this map, then as a final step
    // iterate through the original list and pluck out values from here for the final value.
    //
    // For split transitions, originaldeps.get("data") = [":a", ":b"] can produce the output
    // [":a"<config1>, ":a"<config2>, ..., ":b"<config1>, ":b"<config2>, ...]. All instances of ":a"
    // still appear before all instances of ":b". But the [":a"<config1>, ":a"<config2>"] subset may
    // be in any (deterministic) order. In particular, this may not be the same order as
    // SplitTransition.split. If needed, this code can be modified to use that order, but that
    // involves more runtime in performance-critical code, so we won't make that change without a
    // clear need.
    //
    // This map is used heavily by all builds. Inserts and gets should be as fast as possible.
    Multimap<AttributeAndLabel, Dependency> resolvedDeps = LinkedHashMultimap.create();

    // Performance optimization: This method iterates over originalDeps twice. By storing
    // AttributeAndLabel instances in this list, we avoid having to recreate them the second time
    // (particularly avoid recomputing their hash codes). Profiling shows this shaves 25% off this
    // method's execution time (at the time of this comment).
    ArrayList<AttributeAndLabel> attributesAndLabels = new ArrayList<>(originalDeps.size());

    for (Map.Entry<Attribute, Dependency> depsEntry : originalDeps.entries()) {
      Dependency dep = depsEntry.getValue();
      AttributeAndLabel attributeAndLabel =
          new AttributeAndLabel(depsEntry.getKey(), dep.getLabel());
      attributesAndLabels.add(attributeAndLabel);
      // Certain targets (like output files) trivially re-use their input configuration. Likewise,
      // deps with null configurations (e.g. source files), can be trivially computed. So we skip
      // all logic in this method for these cases and just reinsert their original configurations
      // when preparing final results. Note that null-configured deps are received with
      // NullConfigurationDependency instead of
      // Dependency(label, transition=Attribute.Configuration.Transition.NULL)).
      //
      // A *lot* of targets have null deps, so this produces real savings. Profiling tests over a
      // simple cc_binary show this saves ~1% of total analysis phase time.
      if (dep.hasExplicitConfiguration()) {
        continue;
      }

      // Figure out the required fragments for this dep and its transitive closure.
      Set<Class<? extends BuildConfiguration.Fragment>> depFragments =
          getTransitiveFragments(env, dep.getLabel(), ctgValue.getConfiguration());
      if (depFragments == null) {
        return null;
      }
      // TODO(gregce): remove the below call once we have confidence trimmed configurations always
      // provide needed fragments. This unnecessarily drags performance on the critical path (up
      // to 0.5% of total analysis time as profiled over a simple cc_binary).
      if (ctgValue.getConfiguration().trimConfigurations()) {
        checkForMissingFragments(env, ctgValue, attributeAndLabel.attribute.getName(), dep,
            depFragments);
      }

      boolean sameFragments = depFragments.equals(ctgFragments.fragmentClasses());
      ConfigurationTransition transition = dep.getTransition();

      if (sameFragments) {
        if (transition == NoTransition.INSTANCE) {
          // The dep uses the same exact configuration.
          putOnlyEntry(
              resolvedDeps,
              attributeAndLabel,
              Dependency.withConfigurationAndAspects(
                  dep.getLabel(), ctgValue.getConfiguration(), dep.getAspects()));
          continue;
        } else if (transition == HostTransition.INSTANCE) {
          // The current rule's host configuration can also be used for the dep. We short-circuit
          // the standard transition logic for host transitions because these transitions are
          // uniquely frequent. It's possible, e.g., for every node in the configured target graph
          // to incur multiple host transitions. So we aggressively optimize to avoid hurting
          // analysis time.
          putOnlyEntry(
              resolvedDeps,
              attributeAndLabel,
              Dependency.withConfigurationAndAspects(
                  dep.getLabel(), hostConfiguration, dep.getAspects()));
          continue;
        }
      }

      // Apply the transition or use the cached result if it was already applied.
      FragmentsAndTransition transitionKey = new FragmentsAndTransition(depFragments, transition);
      List<BuildOptions> toOptions = transitionsMap.get(transitionKey);
      if (toOptions == null) {
        toOptions = applyTransition(ctgOptions, transition, depFragments, ruleClassProvider,
            !sameFragments);
        transitionsMap.put(transitionKey, toOptions);
      }

      // If the transition doesn't change the configuration, trivially re-use the original
      // configuration.
      if (sameFragments && toOptions.size() == 1
          && Iterables.getOnlyElement(toOptions).equals(ctgOptions)) {
        putOnlyEntry(
            resolvedDeps,
            attributeAndLabel,
            Dependency.withConfigurationAndAspects(
                dep.getLabel(), ctgValue.getConfiguration(), dep.getAspects()));
        continue;
      }

      // If we get here, we have to get the configuration from Skyframe.
      for (BuildOptions options : toOptions) {
        if (sameFragments) {
          keysToEntries.put(
              BuildConfigurationValue.key(
                  ctgFragments, BuildOptions.diffForReconstruction(defaultBuildOptions, options)),
              depsEntry);

        } else {
          keysToEntries.put(
              BuildConfigurationValue.key(
                  depFragments, BuildOptions.diffForReconstruction(defaultBuildOptions, options)),
              depsEntry);
        }
      }
    }

    // Get all BuildConfigurations we need from Skyframe. While not every value might be available,
    // we don't call env.valuesMissing() here because that could be true from the earlier
    // resolver.dependentNodeMap call in computeDependencies, which also calls Skyframe. This method
    // doesn't need those missing values, but it still has to be called after
    // resolver.dependentNodeMap because it consumes that method's output. The reason the missing
    // values don't matter is because resolver.dependentNodeMap still returns "partial" results
    // and this method runs over whatever's available.
    //
    // While there would be no *correctness* harm in nulling out early, there's significant
    // *performance* harm. Profiling shows that putting "if (env.valuesMissing()) { return null; }"
    // here (or even after resolver.dependentNodeMap) produces a ~30% performance hit on the
    // analysis phase. That's because resolveConfiguredTargetDependencies and
    // resolveAspectDependencies don't get a chance to make their own Skyframe requests before
    // bailing out of this ConfiguredTargetFunction call. Ideally we could batch all requests
    // from all methods into a single Skyframe call, but there are enough subtle data flow
    // dependencies in ConfiguredTargetFunction to make that impractical.
    Map<SkyKey, ValueOrException<InvalidConfigurationException>> depConfigValues =
        env.getValuesOrThrow(keysToEntries.keySet(), InvalidConfigurationException.class);

    // Now fill in the remaining unresolved deps with the now-resolved configurations.
    try {
      for (Map.Entry<SkyKey, ValueOrException<InvalidConfigurationException>> entry :
          depConfigValues.entrySet()) {
        SkyKey key = entry.getKey();
        ValueOrException<InvalidConfigurationException> valueOrException = entry.getValue();
        if (valueOrException.get() == null) {
          // Instead of env.missingValues(), check for missing values here. This guarantees we only
          // null out on missing values from *this specific Skyframe request*.
          return null;
        }
        BuildConfigurationValue trimmedConfig = (BuildConfigurationValue) valueOrException.get();
        for (Map.Entry<Attribute, Dependency> info : keysToEntries.get(key)) {
          Dependency originalDep = info.getValue();
          AttributeAndLabel attr = new AttributeAndLabel(info.getKey(), originalDep.getLabel());
          Dependency resolvedDep = Dependency.withConfigurationAndAspects(originalDep.getLabel(),
              trimmedConfig.getConfiguration(), originalDep.getAspects());
          if (attr.attribute.hasSplitConfigurationTransition()) {
            resolvedDeps.put(attr, resolvedDep);
          } else {
            putOnlyEntry(resolvedDeps, attr, resolvedDep);
          }
        }
      }
    } catch (InvalidConfigurationException e) {
      throw new ConfiguredTargetFunction.DependencyEvaluationException(e);
    }

    return sortResolvedDeps(originalDeps, resolvedDeps, attributesAndLabels);
  }

  /**
   * Encapsulates a set of config fragments and a config transition. This can be used to determine
   * the exact build options needed to set a configuration.
   */
  @ThreadSafety.Immutable
  private static final class FragmentsAndTransition {
    // Treat this as immutable. The only reason this isn't an ImmutableSet is because it
    // gets bound to a NestedSet.toSet() reference, which returns a Set interface.
    final Set<Class<? extends BuildConfiguration.Fragment>> fragments;
    final ConfigurationTransition transition;
    private final int hashCode;

    FragmentsAndTransition(Set<Class<? extends BuildConfiguration.Fragment>> fragments,
        ConfigurationTransition transition) {
      this.fragments = fragments;
      this.transition = transition;
      hashCode = Objects.hash(this.fragments, this.transition);
    }

    @Override
    public boolean equals(Object o) {
      if (o == this) {
        return true;
      } else if (o == null) {
        return false;
      } else {
        FragmentsAndTransition other = (FragmentsAndTransition) o;
        return other.transition.equals(transition) && other.fragments.equals(fragments);
      }
    }

    @Override
    public int hashCode() {
      return hashCode;
    }
  }

  /**
   * Encapsulates an <attribute, label> pair that can be used to map from an input dependency to a
   * trimmed dependency.
   */
  @ThreadSafety.Immutable
  private static final class AttributeAndLabel {
    final Attribute attribute;
    final Label label;
    Integer hashCode;

    AttributeAndLabel(Attribute attribute, Label label) {
      this.attribute = attribute;
      this.label = label;
    }

    @Override
    public boolean equals(Object o) {
      if (!(o instanceof AttributeAndLabel)) {
        return false;
      }
      AttributeAndLabel other = (AttributeAndLabel) o;
      return Objects.equals(other.attribute, attribute) && other.label.equals(label);
    }

    @Override
    public int hashCode() {
      if (hashCode == null) {
        // Not every <Attribute, Label> pair gets hashed. So only evaluate for the instances that
        // need it. This can significantly reduce the number of evaluations.
        hashCode = Objects.hash(this.attribute, this.label);
      }
      return hashCode;
    }

    @Override
    public String toString() {
      return "AttributeAndLabel{attribute="
          + attribute.toString()
          + ", label="
          + label.toString()
          + "}";
    }
  }

  /**
   * Variation of {@link Multimap#put} that triggers an exception if a value already exists.
   */
  @VisibleForTesting
  public static <K, V> void putOnlyEntry(Multimap<K, V> map, K key, V value) {
    // Performance note: while "Verify.verify(!map.containsKey(key, value), String.format(...)))"
    // is simpler code, profiling shows a substantial performance penalty to that approach
    // (~10% extra analysis phase time on a simple cc_binary). Most of that is from the cost of
    // evaluating value.toString() on every call. This approach essentially eliminates the overhead.
    if (map.containsKey(key)) {
      throw new VerifyException(
          String.format("couldn't insert %s: map already has values for key %s: %s",
              value.toString(), key.toString(), map.get(key).toString()));
    }
    map.put(key, value);
  }


  /**
   * Returns the configuration fragments required by a dep and its transitive closure.
   * Returns null if Skyframe dependencies aren't yet available.
   *
   * @param env Skyframe evaluation environment
   * @param dep label of the dep to check
   * @param parentConfig configuration of the rule depending on the dep
   */
  @Nullable
  private static Set<Class<? extends BuildConfiguration.Fragment>> getTransitiveFragments(
      SkyFunction.Environment env, Label dep, BuildConfiguration parentConfig)
      throws InterruptedException {
    if (!parentConfig.trimConfigurations()) {
      return parentConfig.getFragmentsMap().keySet();
    }
    SkyKey fragmentsKey = TransitiveTargetKey.of(dep);
    TransitiveTargetValue transitiveDepInfo = (TransitiveTargetValue) env.getValue(fragmentsKey);
    if (transitiveDepInfo == null) {
      // This should only be possible for tests. In actual runs, this was already called
      // as a routine part of the loading phase.
      // TODO(bazel-team): check this only occurs in a test context.
      return null;
    }
    return transitiveDepInfo.getTransitiveConfigFragments().toSet();
  }

  /**
   * Applies a configuration transition over a set of build options.
   *
   * @return the build options for the transitioned configuration. If trimResults is true,
   *     only options needed by the required fragments are included. Else the same options as the
   *     original input are included (with different possible values, of course).
   */
  @VisibleForTesting
  public static List<BuildOptions> applyTransition(BuildOptions fromOptions,
      ConfigurationTransition transition,
      Iterable<Class<? extends BuildConfiguration.Fragment>> requiredFragments,
      RuleClassProvider ruleClassProvider, boolean trimResults) {

    // TODO(bazel-team): safety-check that this never mutates fromOptions.
    List<BuildOptions> result = transition.apply(fromOptions);

    if (!trimResults) {
      return result;
    } else {
      ImmutableList.Builder<BuildOptions> trimmedOptions = ImmutableList.builder();
      for (BuildOptions toOptions : result) {
        trimmedOptions.add(toOptions.trim(
            BuildConfiguration.getOptionsClasses(requiredFragments, ruleClassProvider)));
      }
      return trimmedOptions.build();
    }
  }

  /**
   * Checks the config fragments required by a dep against the fragments in its actual
   * configuration. If any are missing, triggers a descriptive "missing fragments" error.
   */
  private static void checkForMissingFragments(SkyFunction.Environment env,
      TargetAndConfiguration ctgValue, String attribute, Dependency dep,
      Set<Class<? extends BuildConfiguration.Fragment>> expectedDepFragments)
      throws ConfiguredTargetFunction.DependencyEvaluationException {
    Set<String> ctgFragmentNames = new HashSet<>();
    for (BuildConfiguration.Fragment fragment :
        ctgValue.getConfiguration().getFragmentsMap().values()) {
      ctgFragmentNames.add(fragment.getClass().getSimpleName());
    }
    Set<String> depFragmentNames = new HashSet<>();
    for (Class<? extends BuildConfiguration.Fragment> fragmentClass : expectedDepFragments) {
     depFragmentNames.add(fragmentClass.getSimpleName());
    }
    Set<String> missing = Sets.difference(depFragmentNames, ctgFragmentNames);
    if (!missing.isEmpty()) {
      String msg = String.format(
          "%s: dependency %s from attribute \"%s\" is missing required config fragments: %s",
          ctgValue.getLabel(), dep.getLabel(), attribute, Joiner.on(", ").join(missing));
      env.getListener().handle(Event.error(msg));
      throw new ConfiguredTargetFunction.DependencyEvaluationException(
          new InvalidConfigurationException(msg));
    }
  }

  /**
   * Determines the output ordering of each <attribute, depLabel> ->
   * [dep<config1>, dep<config2>, ...] collection produced by a split transition.
   */
  @VisibleForTesting
  public static final Comparator<Dependency> SPLIT_DEP_ORDERING =
      new Comparator<Dependency>() {
        @Override
        public int compare(Dependency d1, Dependency d2) {
          return d1.getConfiguration().getMnemonic().compareTo(d2.getConfiguration().getMnemonic());
        }
      };

  /**
   * Returns a copy of the output deps using the same key and value ordering as the input deps.
   *
   * @param originalDeps the input deps with the ordering to preserve
   * @param resolvedDeps the unordered output deps
   * @param attributesAndLabels collection of <attribute, depLabel> pairs guaranteed to match
   *   the ordering of originalDeps.entries(). This is a performance optimization: see
   *   {@link #resolveConfigurations#attributesAndLabels} for details.
   */
  private static OrderedSetMultimap<Attribute, Dependency> sortResolvedDeps(
      OrderedSetMultimap<Attribute, Dependency> originalDeps,
      Multimap<AttributeAndLabel, Dependency> resolvedDeps,
      ArrayList<AttributeAndLabel> attributesAndLabels) {
    Iterator<AttributeAndLabel> iterator = attributesAndLabels.iterator();
    OrderedSetMultimap<Attribute, Dependency> result = OrderedSetMultimap.create();
    for (Map.Entry<Attribute, Dependency> depsEntry : originalDeps.entries()) {
      AttributeAndLabel attrAndLabel = iterator.next();
      if (depsEntry.getValue().hasExplicitConfiguration()) {
        result.put(attrAndLabel.attribute, depsEntry.getValue());
      } else {
        Collection<Dependency> resolvedDepWithSplit = resolvedDeps.get(attrAndLabel);
        Verify.verify(!resolvedDepWithSplit.isEmpty());
        if (resolvedDepWithSplit.size() > 1) {
          List<Dependency> sortedSplitList = new ArrayList<>(resolvedDepWithSplit);
          Collections.sort(sortedSplitList, SPLIT_DEP_ORDERING);
          resolvedDepWithSplit = sortedSplitList;
        }
        result.putAll(depsEntry.getKey(), resolvedDepWithSplit);
      }
    }
    return result;
  }

  /**
   * This method allows resolution of configurations outside of a skyfunction call.
   *
   * Unlike {@link #resolveConfigurations}, this doesn't expect the current context to be evaluating
   * dependencies of a parent target. So this method is also suitable for top-level targets.
   *
   * Resolution consists of two steps:
   *
   * <ol>
   *   <li>Apply the per-target transitions specified in {@code asDeps}. This can be used, e.g., to
   *       apply {@link RuleTransitionFactory}s over global top-level configurations.
   *   <li>(Optionally) trim configurations to only the fragments the targets actually need. This
   *       is triggered by {@link BuildConfiguration.Options#trimConfigurations}.
   * </ol>
   *
   * <p>Preserves the original input order (but merges duplicate nodes that might occur due to
   * top-level configuration transitions) . Uses original (untrimmed, pre-transition) configurations
   * for targets that can't be evaluated (e.g. due to loading phase errors).
   *
   * <p>This is suitable for feeding {@link ConfiguredTargetValue} keys: as general principle {@link
   * ConfiguredTarget}s should have exactly as much information in their configurations as they need
   * to evaluate and no more (e.g. there's no need for Android settings in a C++ configured target).
   *
   * @param defaultContext the original targets and starting configurations before applying rule
   *   transitions and trimming. When actual configurations can't be evaluated, these values are
   *   returned as defaults. See TODO below.
   * @param targetsToEvaluate the inputs repackaged as dependencies, including rule-specific
   *   transitions
   * @param eventHandler the error event handler
   * @param skyframeExecutor the executor used for resolving Skyframe keys
   */
  // TODO(bazel-team): error out early for targets that fail - failed configuration evaluations
  //   should never make it through analysis (and especially not seed ConfiguredTargetValues)
  // TODO(gregce): merge this more with resolveConfigurations? One crucial difference is
  //   resolveConfigurations can null-return on missing deps since it executes inside Skyfunctions.
  // Keep this in sync with {@link PrepareAnalysisPhaseFunction#resolveConfigurations}.
  public static LinkedHashSet<TargetAndConfiguration> getConfigurationsFromExecutor(
      Iterable<TargetAndConfiguration> defaultContext,
      Multimap<BuildConfiguration, Dependency> targetsToEvaluate,
      ExtendedEventHandler eventHandler,
      SkyframeExecutor skyframeExecutor) {

    Map<Label, Target> labelsToTargets = new LinkedHashMap<>();
    for (TargetAndConfiguration targetAndConfig : defaultContext) {
      labelsToTargets.put(targetAndConfig.getLabel(), targetAndConfig.getTarget());
    }

    // Maps <target, originalConfig> pairs to <target, finalConfig> pairs for targets that
    // could be successfully Skyframe-evaluated.
    Map<TargetAndConfiguration, TargetAndConfiguration> successfullyEvaluatedTargets =
        new LinkedHashMap<>();
    if (!targetsToEvaluate.isEmpty()) {
      for (BuildConfiguration fromConfig : targetsToEvaluate.keySet()) {
        Multimap<Dependency, BuildConfiguration> evaluatedTargets =
            skyframeExecutor.getConfigurations(
                eventHandler, fromConfig.getOptions(), targetsToEvaluate.get(fromConfig));
        for (Map.Entry<Dependency, BuildConfiguration> evaluatedTarget :
            evaluatedTargets.entries()) {
          Target target = labelsToTargets.get(evaluatedTarget.getKey().getLabel());
          successfullyEvaluatedTargets.put(
              new TargetAndConfiguration(target, fromConfig),
              new TargetAndConfiguration(target, evaluatedTarget.getValue()));
        }
      }
    }

    LinkedHashSet<TargetAndConfiguration> result = new LinkedHashSet<>();
    for (TargetAndConfiguration originalInput : defaultContext) {
      if (successfullyEvaluatedTargets.containsKey(originalInput)) {
        // The configuration was successfully evaluated.
        result.add(successfullyEvaluatedTargets.get(originalInput));
      } else {
        // Either the configuration couldn't be determined (e.g. loading phase error) or it's null.
        result.add(originalInput);
      }
    }
    return result;
  }
}
