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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.devtools.build.lib.packages.RuleClass.Builder.RuleClassType.ABSTRACT;
import static com.google.devtools.build.lib.packages.RuleClass.Builder.RuleClassType.TEST;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.devtools.build.lib.actions.ActionEnvironment;
import com.google.devtools.build.lib.analysis.buildinfo.BuildInfoFactory;
import com.google.devtools.build.lib.analysis.config.BuildConfiguration;
import com.google.devtools.build.lib.analysis.config.BuildConfiguration.Fragment;
import com.google.devtools.build.lib.analysis.config.BuildOptions;
import com.google.devtools.build.lib.analysis.config.ComposingRuleTransitionFactory;
import com.google.devtools.build.lib.analysis.config.ConfigurationFragmentFactory;
import com.google.devtools.build.lib.analysis.config.DefaultsPackage;
import com.google.devtools.build.lib.analysis.config.FragmentOptions;
import com.google.devtools.build.lib.analysis.constraints.ConstraintSemantics;
import com.google.devtools.build.lib.analysis.skylark.SkylarkModules;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.LabelSyntaxException;
import com.google.devtools.build.lib.cmdline.PackageIdentifier;
import com.google.devtools.build.lib.events.EventHandler;
import com.google.devtools.build.lib.graph.Digraph;
import com.google.devtools.build.lib.graph.Node;
import com.google.devtools.build.lib.packages.Attribute;
import com.google.devtools.build.lib.packages.NativeAspectClass;
import com.google.devtools.build.lib.packages.NonconfigurableAttributeMapper;
import com.google.devtools.build.lib.packages.OutputFile;
import com.google.devtools.build.lib.packages.Rule;
import com.google.devtools.build.lib.packages.RuleClass;
import com.google.devtools.build.lib.packages.RuleClassProvider;
import com.google.devtools.build.lib.packages.RuleErrorConsumer;
import com.google.devtools.build.lib.packages.RuleTransitionFactory;
import com.google.devtools.build.lib.packages.Target;
import com.google.devtools.build.lib.runtime.proto.InvocationPolicyOuterClass.InvocationPolicy;
import com.google.devtools.build.lib.skyframe.ConfiguredTargetAndData;
import com.google.devtools.build.lib.skylarkbuildapi.Bootstrap;
import com.google.devtools.build.lib.skylarkinterface.SkylarkInterfaceUtils;
import com.google.devtools.build.lib.skylarkinterface.SkylarkModule;
import com.google.devtools.build.lib.syntax.Environment;
import com.google.devtools.build.lib.syntax.Environment.Extension;
import com.google.devtools.build.lib.syntax.Environment.GlobalFrame;
import com.google.devtools.build.lib.syntax.Mutability;
import com.google.devtools.build.lib.syntax.SkylarkSemantics;
import com.google.devtools.build.lib.syntax.SkylarkUtils;
import com.google.devtools.build.lib.syntax.SkylarkUtils.Phase;
import com.google.devtools.build.lib.syntax.Type;
import com.google.devtools.common.options.OptionDefinition;
import com.google.devtools.common.options.OptionsProvider;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import javax.annotation.Nullable;

/**
 * Knows about every rule Blaze supports and the associated configuration options.
 *
 * <p>This class is initialized on server startup and the set of rules, build info factories
 * and configuration options is guaranteed not to change over the life time of the Blaze server.
 */
public class ConfiguredRuleClassProvider implements RuleClassProvider {

  /**
   * Predicate for determining whether the analysis cache should be cleared, given the new and old
   * value of an option which has changed and the values of the other new options.
   */
  @FunctionalInterface
  public interface OptionsDiffPredicate {
    public static final OptionsDiffPredicate ALWAYS_INVALIDATE =
        (options, option, oldValue, newValue) -> true;

    public boolean apply(
        BuildOptions newOptions, OptionDefinition option, Object oldValue, Object newValue);
  }

  /**
   * Custom dependency validation logic.
   */
  public interface PrerequisiteValidator {
    /**
     * Checks whether the rule in {@code contextBuilder} is allowed to depend on {@code
     * prerequisite} through the attribute {@code attribute}.
     *
     * <p>Can be used for enforcing any organization-specific policies about the layout of the
     * workspace.
     */
    void validate(
        RuleContext.Builder contextBuilder,
        ConfiguredTargetAndData prerequisite,
        Attribute attribute);
  }

  /** Validator to check for and warn on the deprecation of dependencies. */
  public static final class DeprecationValidator implements PrerequisiteValidator {
    /** Checks if the given prerequisite is deprecated and prints a warning if so. */
    @Override
    public void validate(
        RuleContext.Builder contextBuilder,
        ConfiguredTargetAndData prerequisite,
        Attribute attribute) {
      validateDirectPrerequisiteForDeprecation(
          contextBuilder, contextBuilder.getRule(), prerequisite, contextBuilder.forAspect());
    }

    /**
     * Returns whether two packages are considered the same for purposes of deprecation warnings.
     * Dependencies within the same package do not print deprecation warnings; a package in the
     * javatests directory may also depend on its corresponding java package without a warning.
     */
    public static boolean isSameLogicalPackage(
        PackageIdentifier thisPackage, PackageIdentifier prerequisitePackage) {
      if (thisPackage.equals(prerequisitePackage)) {
        // If the packages are equal, they are the same logical package (and just the same package).
        return true;
      }
      if (!thisPackage.getRepository().equals(prerequisitePackage.getRepository())) {
        // If the packages are in different repositories, they are not the same logical package.
        return false;
      }
      // If the packages are in the same repository, it's allowed iff this package is the javatests
      // companion to the prerequisite java package.
      String thisPackagePath = thisPackage.getPackageFragment().getPathString();
      String prerequisitePackagePath = prerequisitePackage.getPackageFragment().getPathString();
      return thisPackagePath.startsWith("javatests/")
          && prerequisitePackagePath.startsWith("java/")
          && thisPackagePath.substring("javatests/".length()).equals(
              prerequisitePackagePath.substring("java/".length()));
    }

    /** Returns whether a deprecation warning should be printed for the prerequisite described. */
    private static boolean shouldEmitDeprecationWarningFor(
        String thisDeprecation, PackageIdentifier thisPackage,
        String prerequisiteDeprecation, PackageIdentifier prerequisitePackage,
        boolean forAspect) {
      // Don't report deprecation edges from javatests to java or within a package;
      // otherwise tests of deprecated code generate nuisance warnings.
      // Don't report deprecation if the current target is also deprecated,
      // or if the current context is evaluating an aspect,
      // as the base target would have already printed the deprecation warnings.
      return (!forAspect
          && prerequisiteDeprecation != null
          && !isSameLogicalPackage(thisPackage, prerequisitePackage)
          && thisDeprecation == null);
    }

    /** Checks if the given prerequisite is deprecated and prints a warning if so. */
    public static void validateDirectPrerequisiteForDeprecation(
        RuleErrorConsumer errors,
        Rule rule,
        ConfiguredTargetAndData prerequisite,
        boolean forAspect) {
      Target prerequisiteTarget = prerequisite.getTarget();
      Label prerequisiteLabel = prerequisiteTarget.getLabel();
      PackageIdentifier thatPackage = prerequisiteLabel.getPackageIdentifier();
      PackageIdentifier thisPackage = rule.getLabel().getPackageIdentifier();

      if (prerequisiteTarget instanceof Rule) {
        Rule prerequisiteRule = (Rule) prerequisiteTarget;
        String thisDeprecation =
            NonconfigurableAttributeMapper.of(rule).has("deprecation", Type.STRING)
                ? NonconfigurableAttributeMapper.of(rule).get("deprecation", Type.STRING)
                : null;
        String thatDeprecation =
            NonconfigurableAttributeMapper.of(prerequisiteRule).has("deprecation", Type.STRING)
                ? NonconfigurableAttributeMapper.of(prerequisiteRule)
                    .get("deprecation", Type.STRING)
                : null;
        if (shouldEmitDeprecationWarningFor(
            thisDeprecation, thisPackage, thatDeprecation, thatPackage, forAspect)) {
          errors.ruleWarning("target '" + rule.getLabel() +  "' depends on deprecated target '"
              + prerequisiteLabel + "': " + thatDeprecation);
        }
      }

      if (prerequisiteTarget instanceof OutputFile) {
        Rule generatingRule = ((OutputFile) prerequisiteTarget).getGeneratingRule();
        String thisDeprecation =
            NonconfigurableAttributeMapper.of(rule).get("deprecation", Type.STRING);
        String thatDeprecation =
            NonconfigurableAttributeMapper.of(generatingRule).get("deprecation", Type.STRING);
        if (shouldEmitDeprecationWarningFor(
            thisDeprecation, thisPackage, thatDeprecation, thatPackage, forAspect)) {
          errors.ruleWarning("target '" + rule.getLabel() + "' depends on the output file "
              + prerequisiteLabel + " of a deprecated rule " + generatingRule.getLabel()
              + "': " + thatDeprecation);
        }
      }
    }
  }

  /**
   * A coherent set of options, fragments, aspects and rules; each of these may declare a dependency
   * on other such sets.
   */
  public interface RuleSet {
    /** Add stuff to the configured rule class provider builder. */
    void init(ConfiguredRuleClassProvider.Builder builder);

    /** List of required modules. */
    ImmutableList<RuleSet> requires();
  }

  /** Builder for {@link ConfiguredRuleClassProvider}. */
  public static class Builder implements RuleDefinitionEnvironment {
    private final StringBuilder defaultWorkspaceFilePrefix = new StringBuilder();
    private final StringBuilder defaultWorkspaceFileSuffix = new StringBuilder();
    private Label preludeLabel;
    private String runfilesPrefix;
    private String toolsRepository;
    private final List<ConfigurationFragmentFactory> configurationFragmentFactories =
        new ArrayList<>();
    private final List<BuildInfoFactory> buildInfoFactories = new ArrayList<>();
    private final List<Class<? extends FragmentOptions>> configurationOptions = new ArrayList<>();

    private final Map<String, RuleClass> ruleClassMap = new HashMap<>();
    private final Map<String, RuleDefinition> ruleDefinitionMap = new HashMap<>();
    private final Map<String, NativeAspectClass> nativeAspectClassMap =
        new HashMap<>();
    private final Map<Class<? extends RuleDefinition>, RuleClass> ruleMap = new HashMap<>();
    private final Digraph<Class<? extends RuleDefinition>> dependencyGraph =
        new Digraph<>();
    private List<Class<? extends BuildConfiguration.Fragment>> universalFragments =
        new ArrayList<>();
    @Nullable private RuleTransitionFactory trimmingTransitionFactory;
    private OptionsDiffPredicate shouldInvalidateCacheForOptionDiff =
        OptionsDiffPredicate.ALWAYS_INVALIDATE;
    private PrerequisiteValidator prerequisiteValidator;
    private ImmutableList.Builder<Bootstrap> skylarkBootstraps =
        ImmutableList.<Bootstrap>builder();
    private ImmutableMap.Builder<String, Object> skylarkAccessibleTopLevels =
        ImmutableMap.builder();
    private Set<String> reservedActionMnemonics = new TreeSet<>();
    private BuildConfiguration.ActionEnvironmentProvider actionEnvironmentProvider =
        (BuildOptions options) -> ActionEnvironment.EMPTY;
    private ConstraintSemantics constraintSemantics = new ConstraintSemantics();

    public Builder addWorkspaceFilePrefix(String contents) {
      defaultWorkspaceFilePrefix.append(contents);
      return this;
    }

    public Builder addWorkspaceFileSuffix(String contents) {
      defaultWorkspaceFileSuffix.append(contents);
      return this;
    }

    @VisibleForTesting
    public Builder clearWorkspaceFileSuffixForTesting() {
      defaultWorkspaceFileSuffix.delete(0, defaultWorkspaceFileSuffix.length());
      return this;
    }

    public Builder setPrelude(String preludeLabelString) {
      try {
        this.preludeLabel = Label.parseAbsolute(preludeLabelString, ImmutableMap.of());
      } catch (LabelSyntaxException e) {
        String errorMsg =
            String.format("Prelude label '%s' is invalid: %s", preludeLabelString, e.getMessage());
        throw new IllegalArgumentException(errorMsg);
      }
      return this;
    }

    public Builder setRunfilesPrefix(String runfilesPrefix) {
      this.runfilesPrefix = runfilesPrefix;
      return this;
    }

    public Builder setToolsRepository(String toolsRepository) {
      this.toolsRepository = toolsRepository;
      return this;
    }

    public Builder setPrerequisiteValidator(PrerequisiteValidator prerequisiteValidator) {
      this.prerequisiteValidator = prerequisiteValidator;
      return this;
    }

    public Builder addBuildInfoFactory(BuildInfoFactory factory) {
      buildInfoFactories.add(factory);
      return this;
    }

    public Builder addRuleDefinition(RuleDefinition ruleDefinition) {
      Class<? extends RuleDefinition> ruleDefinitionClass = ruleDefinition.getClass();
      ruleDefinitionMap.put(ruleDefinitionClass.getName(), ruleDefinition);
      dependencyGraph.createNode(ruleDefinitionClass);
      for (Class<? extends RuleDefinition> ancestor : ruleDefinition.getMetadata().ancestors()) {
        dependencyGraph.addEdge(ancestor, ruleDefinitionClass);
      }

      return this;
    }

    public Builder addNativeAspectClass(NativeAspectClass aspectFactoryClass) {
      nativeAspectClassMap.put(aspectFactoryClass.getName(), aspectFactoryClass);
      return this;
    }

    public Builder addConfigurationOptions(Class<? extends FragmentOptions> configurationOptions) {
      this.configurationOptions.add(configurationOptions);
      return this;
    }

    /**
     * Adds an options class and a corresponding factory. There's usually a 1:1:1 correspondence
     * between option classes, factories, and fragments, such that the factory depends only on the
     * options class and creates the fragment. This method provides a convenient way of adding both
     * the options class and the factory in a single call.
     *
     * <p>Note that configuration fragments annotated with a Skylark name must have a unique
     * name; no two different configuration fragments can share the same name.
     */
    public Builder addConfig(
        Class<? extends FragmentOptions> options, ConfigurationFragmentFactory factory) {
      // Enforce that the factory requires the options.
      Preconditions.checkState(factory.requiredOptions().contains(options));
      this.configurationOptions.add(options);
      this.configurationFragmentFactories.add(factory);
      return this;
    }

    public Builder addConfigurationOptions(
        Collection<Class<? extends FragmentOptions>> optionsClasses) {
      this.configurationOptions.addAll(optionsClasses);
      return this;
    }

    /**
     * Adds a configuration fragment factory.
     *
     * <p>Note that configuration fragments annotated with a Skylark name must have a unique
     * name; no two different configuration fragments can share the same name.
     */
    public Builder addConfigurationFragment(ConfigurationFragmentFactory factory) {
      configurationFragmentFactories.add(factory);
      return this;
    }

    public Builder addUniversalConfigurationFragment(
        Class<? extends BuildConfiguration.Fragment> fragment) {
      this.universalFragments.add(fragment);
      return this;
    }

    public Builder addSkylarkBootstrap(Bootstrap bootstrap) {
      this.skylarkBootstraps.add(bootstrap);
      return this;
    }

    public Builder addSkylarkAccessibleTopLevels(String name, Object object) {
      this.skylarkAccessibleTopLevels.put(name, object);
      return this;
    }

    public Builder addReservedActionMnemonic(String mnemonic) {
      this.reservedActionMnemonics.add(mnemonic);
      return this;
    }

    public Builder setActionEnvironmentProvider(
        BuildConfiguration.ActionEnvironmentProvider actionEnvironmentProvider) {
      this.actionEnvironmentProvider = actionEnvironmentProvider;
      return this;
    }

    /**
     * Sets the logic that lets rules declare which environments they support and validates rules
     * don't depend on rules that aren't compatible with the same environments. Defaults to
     * {@ConstraintSemantics}. See {@ConstraintSemantics} for more details.
     */
    public Builder setConstraintSemantics(ConstraintSemantics constraintSemantics) {
      this.constraintSemantics = constraintSemantics;
      return this;
    }

    /**
     * Adds a transition factory that produces a trimming transition to be run over all targets
     * after other transitions.
     *
     * <p>Transitions are run in the order they're added.
     *
     * <p>This is a temporary measure for supporting trimming of test rules and manual trimming of
     * feature flags, and support for this transition factory will likely be removed at some point
     * in the future (whenever automatic trimming is sufficiently workable).
     */
    public Builder addTrimmingTransitionFactory(RuleTransitionFactory factory) {
      if (trimmingTransitionFactory == null) {
        trimmingTransitionFactory = Preconditions.checkNotNull(factory);
      } else {
        trimmingTransitionFactory = new ComposingRuleTransitionFactory(
            trimmingTransitionFactory, Preconditions.checkNotNull(factory));
      }
      return this;
    }

    /**
     * Overrides the transition factory run over all targets.
     *
     * @see {@link #addTrimmingTransitionFactory(RuleTransitionFactory)}
     */
    @VisibleForTesting(/* for testing trimming transition factories without relying on prod use */ )
    public Builder overrideTrimmingTransitionFactoryForTesting(RuleTransitionFactory factory) {
      trimmingTransitionFactory = null;
      return this.addTrimmingTransitionFactory(factory);
    }

    /**
     * Sets the predicate which determines whether the analysis cache should be invalidated for the
     * given options diff.
     */
    public Builder setShouldInvalidateCacheForOptionDiff(
        OptionsDiffPredicate shouldInvalidateCacheForOptionDiff) {
      Preconditions.checkState(
          this.shouldInvalidateCacheForOptionDiff.equals(OptionsDiffPredicate.ALWAYS_INVALIDATE),
          "Cache invalidation function was already set");
      this.shouldInvalidateCacheForOptionDiff = shouldInvalidateCacheForOptionDiff;
      return this;
    }

    /**
     * Overrides the predicate which determines whether the analysis cache should be invalidated for
     * the given options diff.
     */
    @VisibleForTesting(/* for testing cache invalidation without relying on prod use */ )
    public Builder overrideShouldInvalidateCacheForOptionDiffForTesting(
        OptionsDiffPredicate shouldInvalidateCacheForOptionDiff) {
      this.shouldInvalidateCacheForOptionDiff = OptionsDiffPredicate.ALWAYS_INVALIDATE;
      return this.setShouldInvalidateCacheForOptionDiff(shouldInvalidateCacheForOptionDiff);
    }

    private RuleConfiguredTargetFactory createFactory(
        Class<? extends RuleConfiguredTargetFactory> factoryClass) {
      try {
        Constructor<? extends RuleConfiguredTargetFactory> ctor = factoryClass.getConstructor();
        return ctor.newInstance();
      } catch (NoSuchMethodException | IllegalAccessException | InstantiationException
          | InvocationTargetException e) {
        throw new IllegalStateException(e);
      }
    }

    private RuleClass commitRuleDefinition(Class<? extends RuleDefinition> definitionClass) {
      RuleDefinition instance = checkNotNull(ruleDefinitionMap.get(definitionClass.getName()),
          "addRuleDefinition(new %s()) should be called before build()", definitionClass.getName());

      RuleDefinition.Metadata metadata = instance.getMetadata();
      checkArgument(
          ruleClassMap.get(metadata.name()) == null,
          "The rule " + metadata.name() + " was committed already, use another name");

      List<Class<? extends RuleDefinition>> ancestors = metadata.ancestors();

      checkArgument(
          metadata.type() == ABSTRACT ^ metadata.factoryClass()
              != RuleConfiguredTargetFactory.class);
      checkArgument(
          (metadata.type() != TEST)
          || ancestors.contains(BaseRuleClasses.TestBaseRule.class));

      RuleClass[] ancestorClasses = new RuleClass[ancestors.size()];
      for (int i = 0; i < ancestorClasses.length; i++) {
        ancestorClasses[i] = ruleMap.get(ancestors.get(i));
        if (ancestorClasses[i] == null) {
          // Ancestors should have been initialized by now
          throw new IllegalStateException("Ancestor " + ancestors.get(i) + " of "
              + metadata.name() + " is not initialized");
        }
      }

      RuleConfiguredTargetFactory factory = null;
      if (metadata.type() != ABSTRACT) {
        factory = createFactory(metadata.factoryClass());
      }

      RuleClass.Builder builder = new RuleClass.Builder(
          metadata.name(), metadata.type(), false, ancestorClasses);
      builder.factory(factory);
      RuleClass ruleClass = instance.build(builder, this);
      ruleMap.put(definitionClass, ruleClass);
      ruleClassMap.put(ruleClass.getName(), ruleClass);
      ruleDefinitionMap.put(ruleClass.getName(), instance);

      return ruleClass;
    }

    public ConfiguredRuleClassProvider build() {
      for (Node<Class<? extends RuleDefinition>> ruleDefinition :
          dependencyGraph.getTopologicalOrder()) {
        commitRuleDefinition(ruleDefinition.getLabel());
      }

      return new ConfiguredRuleClassProvider(
          preludeLabel,
          runfilesPrefix,
          toolsRepository,
          ImmutableMap.copyOf(ruleClassMap),
          ImmutableMap.copyOf(ruleDefinitionMap),
          ImmutableMap.copyOf(nativeAspectClassMap),
          defaultWorkspaceFilePrefix.toString(),
          defaultWorkspaceFileSuffix.toString(),
          ImmutableList.copyOf(buildInfoFactories),
          ImmutableList.copyOf(configurationOptions),
          ImmutableList.copyOf(configurationFragmentFactories),
          ImmutableList.copyOf(universalFragments),
          trimmingTransitionFactory,
          shouldInvalidateCacheForOptionDiff,
          prerequisiteValidator,
          skylarkAccessibleTopLevels.build(),
          skylarkBootstraps.build(),
          ImmutableSet.copyOf(reservedActionMnemonics),
          actionEnvironmentProvider,
          constraintSemantics);
    }

    @Override
    public Label getToolsLabel(String labelValue) {
      return Label.parseAbsoluteUnchecked(toolsRepository + labelValue);
    }

    @Override
    public String getToolsRepository() {
      return toolsRepository;
    }
  }

  /**
   * Default content that should be added at the beginning of the WORKSPACE file.
   */
  private final String defaultWorkspaceFilePrefix;

  /**
   * Default content that should be added at the end of the WORKSPACE file.
   */
  private final String defaultWorkspaceFileSuffix;


  /**
   * Label for the prelude file.
   */
  private final Label preludeLabel;

  /**
   * The default runfiles prefix.
   */
  private final String runfilesPrefix;

  /**
   * The path to the tools repository.
   */
  private final String toolsRepository;

  /**
   * Maps rule class name to the metaclass instance for that rule.
   */
  private final ImmutableMap<String, RuleClass> ruleClassMap;

  /**
   * Maps rule class name to the rule definition objects.
   */
  private final ImmutableMap<String, RuleDefinition> ruleDefinitionMap;

  /**
   * Maps aspect name to the aspect factory meta class.
   */
  private final ImmutableMap<String, NativeAspectClass> nativeAspectClassMap;

  /**
   * The configuration options that affect the behavior of the rules.
   */
  private final ImmutableList<Class<? extends FragmentOptions>> configurationOptions;

  /** The set of configuration fragment factories. */
  private final ImmutableList<ConfigurationFragmentFactory> configurationFragmentFactories;

  /** The transition factory used to produce the transition that will trim targets. */
  @Nullable private final RuleTransitionFactory trimmingTransitionFactory;

  /** The predicate used to determine whether a diff requires the cache to be invalidated. */
  private final OptionsDiffPredicate shouldInvalidateCacheForOptionDiff;

  /**
   * Configuration fragments that should be available to all rules even when they don't
   * explicitly require it.
   */
  private final ImmutableList<Class<? extends BuildConfiguration.Fragment>> universalFragments;

  private final ImmutableList<BuildInfoFactory> buildInfoFactories;

  private final PrerequisiteValidator prerequisiteValidator;

  private final Environment.GlobalFrame globals;

  private final ImmutableSet<String> reservedActionMnemonics;

  private final BuildConfiguration.ActionEnvironmentProvider actionEnvironmentProvider;

  private final ImmutableMap<String, Class<?>> configurationFragmentMap;

  private final ConstraintSemantics constraintSemantics;

  private ConfiguredRuleClassProvider(
      Label preludeLabel,
      String runfilesPrefix,
      String toolsRepository,
      ImmutableMap<String, RuleClass> ruleClassMap,
      ImmutableMap<String, RuleDefinition> ruleDefinitionMap,
      ImmutableMap<String, NativeAspectClass> nativeAspectClassMap,
      String defaultWorkspaceFilePrefix,
      String defaultWorkspaceFileSuffix,
      ImmutableList<BuildInfoFactory> buildInfoFactories,
      ImmutableList<Class<? extends FragmentOptions>> configurationOptions,
      ImmutableList<ConfigurationFragmentFactory> configurationFragments,
      ImmutableList<Class<? extends BuildConfiguration.Fragment>> universalFragments,
      @Nullable RuleTransitionFactory trimmingTransitionFactory,
      OptionsDiffPredicate shouldInvalidateCacheForOptionDiff,
      PrerequisiteValidator prerequisiteValidator,
      ImmutableMap<String, Object> skylarkAccessibleJavaClasses,
      ImmutableList<Bootstrap> skylarkBootstraps,
      ImmutableSet<String> reservedActionMnemonics,
      BuildConfiguration.ActionEnvironmentProvider actionEnvironmentProvider,
      ConstraintSemantics constraintSemantics) {
    this.preludeLabel = preludeLabel;
    this.runfilesPrefix = runfilesPrefix;
    this.toolsRepository = toolsRepository;
    this.ruleClassMap = ruleClassMap;
    this.ruleDefinitionMap = ruleDefinitionMap;
    this.nativeAspectClassMap = nativeAspectClassMap;
    this.defaultWorkspaceFilePrefix = defaultWorkspaceFilePrefix;
    this.defaultWorkspaceFileSuffix = defaultWorkspaceFileSuffix;
    this.buildInfoFactories = buildInfoFactories;
    this.configurationOptions = configurationOptions;
    this.configurationFragmentFactories = configurationFragments;
    this.universalFragments = universalFragments;
    this.trimmingTransitionFactory = trimmingTransitionFactory;
    this.shouldInvalidateCacheForOptionDiff = shouldInvalidateCacheForOptionDiff;
    this.prerequisiteValidator = prerequisiteValidator;
    this.globals = createGlobals(skylarkAccessibleJavaClasses, skylarkBootstraps);
    this.reservedActionMnemonics = reservedActionMnemonics;
    this.actionEnvironmentProvider = actionEnvironmentProvider;
    this.configurationFragmentMap = createFragmentMap(configurationFragmentFactories);
    this.constraintSemantics = constraintSemantics;
  }

  public PrerequisiteValidator getPrerequisiteValidator() {
    return prerequisiteValidator;
  }

  @Override
  public Label getPreludeLabel() {
    return preludeLabel;
  }

  @Override
  public String getRunfilesPrefix() {
    return runfilesPrefix;
  }

  @Override
  public String getToolsRepository() {
    return toolsRepository;
  }

  @Override
  public Map<String, RuleClass> getRuleClassMap() {
    return ruleClassMap;
  }

  @Override
  public Map<String, NativeAspectClass> getNativeAspectClassMap() {
    return nativeAspectClassMap;
  }

  @Override
  public NativeAspectClass getNativeAspectClass(String key) {
    return nativeAspectClassMap.get(key);
  }

  /**
   * Returns a list of build info factories that are needed for the supported languages.
   */
  public ImmutableList<BuildInfoFactory> getBuildInfoFactories() {
    return buildInfoFactories;
  }

  /**
   * Returns the set of configuration fragments provided by this module.
   */
  public ImmutableList<ConfigurationFragmentFactory> getConfigurationFragments() {
    return configurationFragmentFactories;
  }

  /**
   * Returns the transition factory used to produce the transition to trim targets.
   *
   * <p>This is a temporary measure for supporting manual trimming of feature flags, and support
   * for this transition factory will likely be removed at some point in the future (whenever
   * automatic trimming is sufficiently workable
   */
  @Nullable
  public RuleTransitionFactory getTrimmingTransitionFactory() {
    return trimmingTransitionFactory;
  }

  /** Returns whether the analysis cache should be invalidated for the given option diff. */
  public boolean shouldInvalidateCacheForOptionDiff(
      BuildOptions newOptions, OptionDefinition changedOption, Object oldValue, Object newValue) {
    return shouldInvalidateCacheForOptionDiff.apply(newOptions, changedOption, oldValue, newValue);
  }

  /**
   * Returns the set of configuration options that are supported in this module.
   */
  public ImmutableList<Class<? extends FragmentOptions>> getConfigurationOptions() {
    return configurationOptions;
  }

  /**
   * Returns the definition of the rule class definition with the specified name.
   */
  public RuleDefinition getRuleClassDefinition(String ruleClassName) {
    return ruleDefinitionMap.get(ruleClassName);
  }

  /**
   * Returns the configuration fragment that should be available to all rules even when they
   * don't explicitly require it.
   */
  public ImmutableList<Class<? extends BuildConfiguration.Fragment>> getUniversalFragments() {
    return universalFragments;
  }

  /**
   * Returns the defaults package for the default settings.
   */
  public String getDefaultsPackageContent(InvocationPolicy invocationPolicy) {
    return DefaultsPackage.getDefaultsPackageContent(configurationOptions, invocationPolicy);
  }

  /**
   * Returns the defaults package for the given options taken from an optionsProvider.
   */
  public String getDefaultsPackageContent(OptionsProvider optionsProvider) {
    return DefaultsPackage.getDefaultsPackageContent(
        BuildOptions.of(configurationOptions, optionsProvider));
  }

  /**
   * Creates a BuildOptions class for the given options taken from an optionsProvider.
   */
  public BuildOptions createBuildOptions(OptionsProvider optionsProvider) {
    return BuildOptions.of(configurationOptions, optionsProvider);
  }

  private Environment.GlobalFrame createGlobals(
      ImmutableMap<String, Object> skylarkAccessibleTopLevels,
      ImmutableList<Bootstrap> bootstraps) {
    ImmutableMap.Builder<String, Object> envBuilder = ImmutableMap.builder();

    SkylarkModules.addSkylarkGlobalsToBuilder(envBuilder);
    envBuilder.putAll(skylarkAccessibleTopLevels.entrySet());
    for (Bootstrap bootstrap : bootstraps) {
      bootstrap.addBindingsToBuilder(envBuilder);
    }

    return GlobalFrame.createForBuiltins(envBuilder.build());
  }

  private static ImmutableMap<String, Class<?>> createFragmentMap(
      Iterable<ConfigurationFragmentFactory> configurationFragmentFactories) {
    ImmutableMap.Builder<String, Class<?>> mapBuilder = ImmutableMap.builder();
    for (ConfigurationFragmentFactory fragmentFactory : configurationFragmentFactories) {
      Class<? extends Fragment> fragmentClass = fragmentFactory.creates();
      SkylarkModule fragmentModule = SkylarkInterfaceUtils.getSkylarkModule((fragmentClass));
      if (fragmentModule != null) {
        mapBuilder.put(fragmentModule.name(), fragmentClass);
      }
    }
    return mapBuilder.build();
  }

  private Environment createSkylarkRuleClassEnvironment(
      Mutability mutability,
      Environment.GlobalFrame globals,
      SkylarkSemantics skylarkSemantics,
      EventHandler eventHandler,
      String astFileContentHashCode,
      Map<String, Extension> importMap) {
    Environment env =
        Environment.builder(mutability)
            .setGlobals(globals)
            .setSemantics(skylarkSemantics)
            .setEventHandler(eventHandler)
            .setFileContentHashCode(astFileContentHashCode)
            .setImportedExtensions(importMap)
            .build();
    SkylarkUtils.setToolsRepository(env, toolsRepository);
    SkylarkUtils.setFragmentMap(env, configurationFragmentMap);
    SkylarkUtils.setPhase(env, Phase.LOADING);
    return env;
  }

  @Override
  public Environment createSkylarkRuleClassEnvironment(
      Label extensionLabel,
      Mutability mutability,
      SkylarkSemantics skylarkSemantics,
      EventHandler eventHandler,
      String astFileContentHashCode,
      Map<String, Extension> importMap) {
    return createSkylarkRuleClassEnvironment(
        mutability,
        globals.withLabel(extensionLabel),
        skylarkSemantics,
        eventHandler,
        astFileContentHashCode,
        importMap);
  }

  @Override
  public String getDefaultWorkspacePrefix() {
    return defaultWorkspaceFilePrefix;
  }

  @Override
  public String getDefaultWorkspaceSuffix() {
    return defaultWorkspaceFileSuffix;
  }

  @Override
  public Map<String, Class<?>> getConfigurationFragmentMap() {
    return configurationFragmentMap;
  }

  public ConstraintSemantics getConstraintSemantics() {
    return constraintSemantics;
  }

  /** Returns all skylark objects in global scope for this RuleClassProvider. */
  public Map<String, Object> getTransitiveGlobalBindings() {
    return globals.getTransitiveBindings();
  }

  public Object getGlobalsForConstantRegistration() {
    return globals;
  }

  /** Returns all registered {@link BuildConfiguration.Fragment} classes. */
  public ImmutableSortedSet<Class<? extends BuildConfiguration.Fragment>> getAllFragments() {
    ImmutableSortedSet.Builder<Class<? extends BuildConfiguration.Fragment>> fragmentsBuilder =
        ImmutableSortedSet.orderedBy(BuildConfiguration.lexicalFragmentSorter);
    for (ConfigurationFragmentFactory factory : getConfigurationFragments()) {
      fragmentsBuilder.add(factory.creates());
    }
    fragmentsBuilder.addAll(getUniversalFragments());
    return fragmentsBuilder.build();
  }

  /** Returns a reserved set of action mnemonics. These cannot be used from a Skylark action. */
  public ImmutableSet<String> getReservedActionMnemonics() {
    return reservedActionMnemonics;
  }

  public BuildConfiguration.ActionEnvironmentProvider getActionEnvironmentProvider() {
    return actionEnvironmentProvider;
  }
}
