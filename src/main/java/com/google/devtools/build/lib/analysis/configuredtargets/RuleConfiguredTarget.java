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
package com.google.devtools.build.lib.analysis.configuredtargets;

import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Interner;
import com.google.devtools.build.lib.actions.ActionAnalysisMetadata;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.FileProvider;
import com.google.devtools.build.lib.analysis.FilesToRunProvider;
import com.google.devtools.build.lib.analysis.RuleConfiguredTargetBuilder;
import com.google.devtools.build.lib.analysis.RuleContext;
import com.google.devtools.build.lib.analysis.RunfilesProvider;
import com.google.devtools.build.lib.analysis.TransitiveInfoProvider;
import com.google.devtools.build.lib.analysis.TransitiveInfoProviderMap;
import com.google.devtools.build.lib.analysis.TransitiveInfoProviderMapBuilder;
import com.google.devtools.build.lib.analysis.Util;
import com.google.devtools.build.lib.analysis.config.ConfigMatchingProvider;
import com.google.devtools.build.lib.analysis.config.RunUnder;
import com.google.devtools.build.lib.analysis.skylark.SkylarkApiProvider;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.concurrent.BlazeInterners;
import com.google.devtools.build.lib.packages.InfoInterface;
import com.google.devtools.build.lib.packages.OutputFile;
import com.google.devtools.build.lib.packages.PackageSpecification.PackageGroupContents;
import com.google.devtools.build.lib.packages.Provider;
import com.google.devtools.build.lib.skyframe.BuildConfigurationValue;
import com.google.devtools.build.lib.skyframe.ConfiguredTargetKey;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.AutoCodec;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.AutoCodec.Instantiator;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.AutoCodec.VisibleForSerialization;
import com.google.devtools.build.lib.skylarkinterface.SkylarkPrinter;
import com.google.devtools.build.lib.syntax.Printer;
import java.util.function.Consumer;
import javax.annotation.Nullable;

/**
 * A {@link ConfiguredTarget} that is produced by a rule.
 *
 * <p>Created by {@link RuleConfiguredTargetBuilder}. There is an instance of this class for every
 * analyzed rule. For more information about how analysis works, see {@link
 * com.google.devtools.build.lib.analysis.RuleConfiguredTargetFactory}.
 */
@AutoCodec(checkClassExplicitlyAllowed = true)
public final class RuleConfiguredTarget extends AbstractConfiguredTarget {
  /**
   * The name of the key for the 'actions' synthesized provider.
   *
   * <p>If you respond to this key you are expected to return a list of actions belonging to this
   * configured target.
   */
  public static final String ACTIONS_FIELD_NAME = "actions";

  /**
   * The configuration transition for an attribute through which a prerequisite
   * is requested.
   */
  public enum Mode {
    TARGET,
    HOST,
    DATA,
    SPLIT,
    DONT_CHECK
  }
  /** A set of this target's implicitDeps. */
  private final ImmutableSet<ConfiguredTargetKey> implicitDeps;

  /*
   * An interner for the implicitDeps set. {@link Util.findImplicitDeps} is called upon every
   * construction of a RuleConfiguredTarget and we expect many of these targets to contain the same
   * set of implicit deps so this reduces the memory load per build.
   */
  private static final Interner<ImmutableSet<ConfiguredTargetKey>> IMPLICIT_DEPS_INTERNER =
      BlazeInterners.newWeakInterner();

  private final TransitiveInfoProviderMap providers;
  private final ImmutableMap<Label, ConfigMatchingProvider> configConditions;
  private final String ruleClassString;
  private final ImmutableList<ActionAnalysisMetadata> actions;
  private final ImmutableMap<Artifact, Integer> generatingActionIndex;

  @Instantiator
  @VisibleForSerialization
  RuleConfiguredTarget(
      Label label,
      BuildConfigurationValue.Key configurationKey,
      NestedSet<PackageGroupContents> visibility,
      TransitiveInfoProviderMap providers,
      ImmutableMap<Label, ConfigMatchingProvider> configConditions,
      ImmutableSet<ConfiguredTargetKey> implicitDeps,
      String ruleClassString,
      ImmutableList<ActionAnalysisMetadata> actions,
      ImmutableMap<Artifact, Integer> generatingActionIndex) {
    super(label, configurationKey, visibility);

    // We don't use ImmutableMap.Builder here to allow augmenting the initial list of 'default'
    // providers by passing them in.
    TransitiveInfoProviderMapBuilder providerBuilder =
        new TransitiveInfoProviderMapBuilder().addAll(providers);
    Preconditions.checkState(providerBuilder.contains(RunfilesProvider.class), label);
    Preconditions.checkState(providerBuilder.contains(FileProvider.class), label);
    Preconditions.checkState(providerBuilder.contains(FilesToRunProvider.class), label);

    // Initialize every SkylarkApiProvider
    for (int i = 0; i < providers.getProviderCount(); i++) {
      Object obj = providers.getProviderInstanceAt(i);
      if (obj instanceof SkylarkApiProvider) {
        ((SkylarkApiProvider) obj).init(this);
      }
    }

    this.providers = providerBuilder.build();
    this.configConditions = configConditions;
    this.implicitDeps = IMPLICIT_DEPS_INTERNER.intern(implicitDeps);
    this.ruleClassString = ruleClassString;
    this.actions = actions;
    this.generatingActionIndex = generatingActionIndex;
  }

  public RuleConfiguredTarget(
      RuleContext ruleContext,
      TransitiveInfoProviderMap providers,
      ImmutableList<ActionAnalysisMetadata> actions,
      ImmutableMap<Artifact, Integer> generatingActionIndex) {
    this(
        ruleContext.getLabel(),
        ruleContext.getConfigurationKey(),
        ruleContext.getVisibility(),
        providers,
        ruleContext.getConfigConditions(),
        Util.findImplicitDeps(ruleContext),
        ruleContext.getRule().getRuleClass(),
        actions,
        generatingActionIndex);

    // If this rule is the run_under target, then check that we have an executable; note that
    // run_under is only set in the target configuration, and the target must also be analyzed for
    // the target configuration.
    RunUnder runUnder = ruleContext.getConfiguration().getRunUnder();
    if (runUnder != null && getLabel().equals(runUnder.getLabel())) {
      if (getProvider(FilesToRunProvider.class).getExecutable() == null) {
        ruleContext.ruleError("run_under target " + runUnder.getLabel() + " is not executable");
      }
    }

    // Make sure that all declared output files are also created as artifacts. The
    // CachingAnalysisEnvironment makes sure that they all have generating actions.
    if (!ruleContext.hasErrors()) {
      for (OutputFile out : ruleContext.getRule().getOutputFiles()) {
        ruleContext.createOutputArtifact(out);
      }
    }
  }

  /**
   * The configuration conditions that trigger this rule's configurable attributes.
   */
  public ImmutableMap<Label, ConfigMatchingProvider> getConfigConditions() {
    return configConditions;
  }

  public ImmutableSet<ConfiguredTargetKey> getImplicitDeps() {
    return implicitDeps;
  }

  @Override
  public String getRuleClassString() {
    return ruleClassString;
  }

  @Nullable
  @Override
  public <P extends TransitiveInfoProvider> P getProvider(Class<P> providerClass) {
    // TODO(bazel-team): Should aspects be allowed to override providers on the configured target
    // class?
    return providers.getProvider(providerClass);
  }

  @Override
  public String getErrorMessageForUnknownField(String name) {
    return Printer.format(
        "%r (rule '%s') doesn't have provider '%s'", this, getRuleClassString(), name);
  }

  @Override
  protected void addExtraSkylarkKeys(Consumer<String> result) {
    for (int i = 0; i < providers.getProviderCount(); i++) {
      Object classAt = providers.getProviderKeyAt(i);
      if (classAt instanceof String) {
        result.accept((String) classAt);
      }
    }
    result.accept(ACTIONS_FIELD_NAME);
  }

  @Override
  protected InfoInterface rawGetSkylarkProvider(Provider.Key providerKey) {
    return providers.get(providerKey);
  }

  @Override
  protected Object rawGetSkylarkProvider(String providerKey) {
    if (providerKey.equals(ACTIONS_FIELD_NAME)) {
      return actions;
    }
    return providers.get(providerKey);
  }

  @Override
  public void repr(SkylarkPrinter printer) {
    printer.append("<target " + getLabel() + ">");
  }

  @Override
  public void debugPrint(SkylarkPrinter printer) {
    // Show the names of the provider keys that this target propagates.
    // Provider key names might potentially be *private* information, and thus a comprehensive
    // list of provider keys should not be exposed in any way other than for debug information.
    printer.append("<target " + getLabel() + ", keys:[");
    ImmutableList.Builder<String> skylarkProviderKeyStrings = ImmutableList.builder();
    for (int providerIndex = 0; providerIndex < providers.getProviderCount(); providerIndex++) {
      Object providerKey = providers.getProviderKeyAt(providerIndex);
      if (providerKey instanceof Provider.Key) {
        skylarkProviderKeyStrings.add(providerKey.toString());
      }
    }
    printer.append(Joiner.on(", ").join(skylarkProviderKeyStrings.build()));
    printer.append("]>");
  }

  /** Returns a list of actions that this configured target generated. */
  public ImmutableList<ActionAnalysisMetadata> getActions() {
    return actions;
  }

  /**
   * Returns a map where keys are artifacts that are action outputs of this rule, and values are the
   * index of the action that generates that artifact.
   */
  public ImmutableMap<Artifact, Integer> getGeneratingActionIndex() {
    return generatingActionIndex;
  }
}
