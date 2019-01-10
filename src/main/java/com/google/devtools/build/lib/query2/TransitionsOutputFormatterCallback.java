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

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import com.google.common.collect.Streams;
import com.google.devtools.build.lib.analysis.AspectCollection;
import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.Dependency;
import com.google.devtools.build.lib.analysis.DependencyResolver;
import com.google.devtools.build.lib.analysis.DependencyResolver.InconsistentAspectOrderException;
import com.google.devtools.build.lib.analysis.PlatformSemantics;
import com.google.devtools.build.lib.analysis.TargetAndConfiguration;
import com.google.devtools.build.lib.analysis.config.BuildConfiguration;
import com.google.devtools.build.lib.analysis.config.BuildOptions;
import com.google.devtools.build.lib.analysis.config.BuildOptions.OptionsDiff;
import com.google.devtools.build.lib.analysis.config.ConfigMatchingProvider;
import com.google.devtools.build.lib.analysis.config.FragmentClassSet;
import com.google.devtools.build.lib.analysis.config.InvalidConfigurationException;
import com.google.devtools.build.lib.analysis.config.transitions.ConfigurationTransition;
import com.google.devtools.build.lib.analysis.config.transitions.NoTransition;
import com.google.devtools.build.lib.analysis.configuredtargets.RuleConfiguredTarget;
import com.google.devtools.build.lib.causes.Cause;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.ExtendedEventHandler;
import com.google.devtools.build.lib.packages.Attribute;
import com.google.devtools.build.lib.packages.BuildType;
import com.google.devtools.build.lib.packages.ConfiguredAttributeMapper;
import com.google.devtools.build.lib.packages.NoSuchThingException;
import com.google.devtools.build.lib.packages.RuleTransitionFactory;
import com.google.devtools.build.lib.packages.Target;
import com.google.devtools.build.lib.packages.TargetUtils;
import com.google.devtools.build.lib.query2.engine.QueryEnvironment.TargetAccessor;
import com.google.devtools.build.lib.query2.output.CqueryOptions;
import com.google.devtools.build.lib.skyframe.SkyframeExecutor;
import com.google.devtools.build.lib.syntax.EvalException;
import com.google.devtools.build.lib.util.OrderedSetMultimap;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.annotation.Nullable;

/**
 * Output formatter that prints {@link ConfigurationTransition} information for rule configured
 * targets in the results of a cquery call.
 */
public class TransitionsOutputFormatterCallback extends CqueryThreadsafeCallback {

  protected final BuildConfiguration hostConfiguration;

  private final HashMap<Label, Target> partialResultMap;
  @Nullable private final RuleTransitionFactory trimmingTransitionFactory;

  @Override
  public String getName() {
    return "transitions";
  }

  /**
   * @param accessor provider of query result configured targets.
   * @param hostConfiguration host configuration for this query.
   */
  TransitionsOutputFormatterCallback(
      ExtendedEventHandler eventHandler,
      CqueryOptions options,
      OutputStream out,
      SkyframeExecutor skyframeExecutor,
      TargetAccessor<ConfiguredTarget> accessor,
      BuildConfiguration hostConfiguration,
      @Nullable RuleTransitionFactory trimmingTransitionFactory) {
    super(eventHandler, options, out, skyframeExecutor, accessor);
    this.hostConfiguration = hostConfiguration;
    this.trimmingTransitionFactory = trimmingTransitionFactory;
    this.partialResultMap = Maps.newHashMap();
  }

  @Override
  public void processOutput(Iterable<ConfiguredTarget> partialResult) throws InterruptedException {
    CqueryOptions.Transitions verbosity = options.transitions;
    if (verbosity.equals(CqueryOptions.Transitions.NONE)) {
      eventHandler.handle(
          Event.error(
              "Instead of using --output=transitions, set the --transitions"
                  + " flag explicitly to 'lite' or 'full'"));
      return;
    }
    partialResult.forEach(
        ct -> partialResultMap.put(ct.getLabel(), accessor.getTargetFromConfiguredTarget(ct)));
    for (ConfiguredTarget configuredTarget : partialResult) {
      Target target = partialResultMap.get(configuredTarget.getLabel());
      BuildConfiguration config =
          skyframeExecutor.getConfiguration(
              eventHandler, configuredTarget.getConfigurationKey());
      addResult(
          getRuleClassTransition(configuredTarget, target)
              + configuredTarget.getLabel()
              + " ("
              + (config != null && config.isHostConfiguration() ? "HOST" : config)
              + ")");
      if (!(configuredTarget instanceof RuleConfiguredTarget)) {
        continue;
      }
      OrderedSetMultimap<Attribute, Dependency> deps;
      ImmutableMap<Label, ConfigMatchingProvider> configConditions =
          ((RuleConfiguredTarget) configuredTarget).getConfigConditions();
      BuildOptions fromOptions = config.getOptions();
      try {
        // Note: Being able to pull the $resolved_toolchain_internal attr unconditionally from the
        // mapper relies on the fact that {@link PlatformSemantics.RESOLVED_TOOLCHAINS_ATTR} exists
        // in every rule. Also, we don't actually use fromOptions in our implementation of
        // DependencyResolver but passing to avoid passing a null and since we have the information
        // anyway.
        deps =
            new FormatterDependencyResolver(configuredTarget, eventHandler)
                .dependentNodeMap(
                    new TargetAndConfiguration(target, config),
                    hostConfiguration,
                    /*aspect=*/ null,
                    configConditions,
                    ImmutableSet.copyOf(
                        ConfiguredAttributeMapper.of(target.getAssociatedRule(), configConditions)
                            .get(PlatformSemantics.RESOLVED_TOOLCHAINS_ATTR, BuildType.LABEL_LIST)),
                    fromOptions,
                    trimmingTransitionFactory);
      } catch (EvalException | InvalidConfigurationException | InconsistentAspectOrderException e) {
        throw new InterruptedException(e.getMessage());
      }
      for (Map.Entry<Attribute, Dependency> attributeAndDep : deps.entries()) {
        if (attributeAndDep.getValue().hasExplicitConfiguration()
            || attributeAndDep.getValue().getTransition() instanceof NoTransition) {
          continue;
        }
        Dependency dep = attributeAndDep.getValue();
        List<BuildOptions> toOptions = dep.getTransition().apply(fromOptions);
        String hostConfigurationChecksum = hostConfiguration.checksum();
        addResult(
            "  "
                .concat(attributeAndDep.getKey().getName())
                .concat("#")
                .concat(dep.getLabel().toString())
                .concat("#")
                .concat(dep.getTransition().getName())
                .concat(" ( -> ")
                .concat(
                    toOptions
                        .stream()
                        .map(
                            options -> {
                              String checksum = options.computeChecksum();
                              return checksum.equals(hostConfigurationChecksum) ? "HOST" : checksum;
                            })
                        .collect(Collectors.joining(", ")))
                .concat(")"));
        if (verbosity == CqueryOptions.Transitions.LITE) {
          continue;
        }
        OptionsDiff diff = new OptionsDiff();
        for (BuildOptions options : toOptions) {
          diff = BuildOptions.diff(diff, fromOptions, options);
        }
        diff.getPrettyPrintList().forEach(singleDiff -> addResult("    " + singleDiff));
      }
    }
  }

  private String getRuleClassTransition(ConfiguredTarget ct, Target target) {
    String output = "";
    if (ct instanceof RuleConfiguredTarget) {
      RuleTransitionFactory factory =
          target.getAssociatedRule().getRuleClassObject().getTransitionFactory();
      if (factory != null) {
        output =
            factory
                .buildTransitionFor(target.getAssociatedRule())
                .getClass()
                .getSimpleName()
                .concat(" -> ");
      }
    }
    return output;
  }

  private class FormatterDependencyResolver extends DependencyResolver {

    private ConfiguredTarget ct;
    private final ExtendedEventHandler eventHandler;

    private FormatterDependencyResolver(ConfiguredTarget ct, ExtendedEventHandler eventHandler) {
      this.ct = ct;
      this.eventHandler = eventHandler;
    }

    protected FormatterDependencyResolver setCt(ConfiguredTarget ct) {
      this.ct = ct;
      return this;
    }

    @Override
    protected void invalidVisibilityReferenceHook(TargetAndConfiguration node, Label label) {
      eventHandler.handle(
          Event.error(
              TargetUtils.getLocationMaybe(node.getTarget()),
              String.format(
                  "Label '%s' in visibility attribute does not refer to a package group", label)));
    }

    @Override
    protected void invalidPackageGroupReferenceHook(TargetAndConfiguration node, Label label) {
      eventHandler.handle(
          Event.error(
              TargetUtils.getLocationMaybe(node.getTarget()),
              String.format("label '%s' does not refer to a package group", label)));
    }

    @Override
    protected void missingEdgeHook(Target from, Label to, NoSuchThingException e) {
      eventHandler.handle(
          Event.error(
              "missing dependency from " + from.getLabel() + " to " + to + ": " + e.getMessage()));
    }

    @Override
    protected Map<Label, Target> getTargets(
        Iterable<Label> labels,
        Target fromTarget,
        NestedSetBuilder<Cause> rootCauses,
        int labelsSizeHint) {
      return Streams.stream(labels)
          .distinct()
          .filter(Objects::nonNull)
          .filter(partialResultMap::containsKey)
          .collect(Collectors.toMap(Function.identity(), partialResultMap::get));
    }

    @Override
    protected List<BuildConfiguration> getConfigurations(
        FragmentClassSet fragments,
        Iterable<BuildOptions> buildOptions,
        BuildOptions defaultOptions) {
      Preconditions.checkArgument(
          ct.getConfigurationKey().getFragments().equals(fragments.fragmentClasses()),
          "Mismatch: %s %s",
          ct,
          fragments);
      Dependency asDep =
          Dependency.withTransitionAndAspects(
              ct.getLabel(), NoTransition.INSTANCE, AspectCollection.EMPTY);
      ImmutableList.Builder<BuildConfiguration> builder = ImmutableList.builder();
      for (BuildOptions options : buildOptions) {
        builder.add(
            Iterables.getOnlyElement(
                skyframeExecutor
                    .getConfigurations(eventHandler, options, ImmutableList.<Dependency>of(asDep))
                    .values()));
      }
      return builder.build();
    }
  }
}

