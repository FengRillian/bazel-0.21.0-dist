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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimaps;
import com.google.common.collect.Sets;
import com.google.devtools.build.lib.actions.Action;
import com.google.devtools.build.lib.actions.ActionAnalysisMetadata;
import com.google.devtools.build.lib.actions.ActionOwner;
import com.google.devtools.build.lib.actions.ActionRegistry;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.Artifact.SpecialArtifact;
import com.google.devtools.build.lib.actions.ArtifactOwner;
import com.google.devtools.build.lib.actions.ArtifactRoot;
import com.google.devtools.build.lib.analysis.AliasProvider.TargetMode;
import com.google.devtools.build.lib.analysis.ConfiguredRuleClassProvider.PrerequisiteValidator;
import com.google.devtools.build.lib.analysis.actions.ActionConstructionContext;
import com.google.devtools.build.lib.analysis.buildinfo.BuildInfoFactory.BuildInfoKey;
import com.google.devtools.build.lib.analysis.config.BuildConfiguration;
import com.google.devtools.build.lib.analysis.config.BuildConfiguration.Fragment;
import com.google.devtools.build.lib.analysis.config.BuildOptions;
import com.google.devtools.build.lib.analysis.config.ConfigMatchingProvider;
import com.google.devtools.build.lib.analysis.config.FragmentCollection;
import com.google.devtools.build.lib.analysis.config.transitions.ConfigurationTransition;
import com.google.devtools.build.lib.analysis.config.transitions.NoTransition;
import com.google.devtools.build.lib.analysis.config.transitions.PatchTransition;
import com.google.devtools.build.lib.analysis.config.transitions.SplitTransition;
import com.google.devtools.build.lib.analysis.configuredtargets.RuleConfiguredTarget.Mode;
import com.google.devtools.build.lib.analysis.constraints.ConstraintSemantics;
import com.google.devtools.build.lib.analysis.platform.PlatformInfo;
import com.google.devtools.build.lib.analysis.stringtemplate.TemplateContext;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.lib.collect.ImmutableSortedKeyListMultimap;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable;
import com.google.devtools.build.lib.events.ExtendedEventHandler.Postable;
import com.google.devtools.build.lib.events.Location;
import com.google.devtools.build.lib.packages.Aspect;
import com.google.devtools.build.lib.packages.AspectDescriptor;
import com.google.devtools.build.lib.packages.Attribute;
import com.google.devtools.build.lib.packages.AttributeMap;
import com.google.devtools.build.lib.packages.BuildType;
import com.google.devtools.build.lib.packages.BuiltinProvider;
import com.google.devtools.build.lib.packages.ConfigurationFragmentPolicy;
import com.google.devtools.build.lib.packages.ConfiguredAttributeMapper;
import com.google.devtools.build.lib.packages.FileTarget;
import com.google.devtools.build.lib.packages.FilesetEntry;
import com.google.devtools.build.lib.packages.ImplicitOutputsFunction;
import com.google.devtools.build.lib.packages.Info;
import com.google.devtools.build.lib.packages.InfoInterface;
import com.google.devtools.build.lib.packages.InputFile;
import com.google.devtools.build.lib.packages.NativeProvider;
import com.google.devtools.build.lib.packages.OutputFile;
import com.google.devtools.build.lib.packages.PackageSpecification.PackageGroupContents;
import com.google.devtools.build.lib.packages.RawAttributeMapper;
import com.google.devtools.build.lib.packages.RequiredProviders;
import com.google.devtools.build.lib.packages.Rule;
import com.google.devtools.build.lib.packages.RuleClass;
import com.google.devtools.build.lib.packages.RuleErrorConsumer;
import com.google.devtools.build.lib.packages.Target;
import com.google.devtools.build.lib.packages.TargetUtils;
import com.google.devtools.build.lib.skyframe.ConfiguredTargetAndData;
import com.google.devtools.build.lib.syntax.EvalException;
import com.google.devtools.build.lib.syntax.Type;
import com.google.devtools.build.lib.syntax.Type.LabelClass;
import com.google.devtools.build.lib.util.FileTypeSet;
import com.google.devtools.build.lib.util.OS;
import com.google.devtools.build.lib.util.OrderedSetMultimap;
import com.google.devtools.build.lib.util.StringUtil;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import javax.annotation.Nullable;

/**
 * The totality of data available during the analysis of a rule.
 *
 * <p>These objects should not outlast the analysis phase. Do not pass them to {@link Action}
 * objects or other persistent objects. There are internal tests to ensure that RuleContext objects
 * are not persisted that check the name of this class, so update those tests if you change this
 * class's name.
 *
 * @see com.google.devtools.build.lib.analysis.RuleConfiguredTargetFactory
 */
public final class RuleContext extends TargetContext
    implements ActionConstructionContext, ActionRegistry, RuleErrorConsumer {

  /**
   * The configured version of FilesetEntry.
   */
  @Immutable
  public static final class ConfiguredFilesetEntry {
    private final FilesetEntry entry;
    private final TransitiveInfoCollection src;
    private final ImmutableList<TransitiveInfoCollection> files;

    ConfiguredFilesetEntry(FilesetEntry entry, TransitiveInfoCollection src) {
      this.entry = entry;
      this.src = src;
      this.files = null;
    }

    ConfiguredFilesetEntry(FilesetEntry entry, ImmutableList<TransitiveInfoCollection> files) {
      this.entry = entry;
      this.src = null;
      this.files = files;
    }

    public FilesetEntry getEntry() {
      return entry;
    }

    public TransitiveInfoCollection getSrc() {
      return src;
    }

    /**
     * Targets from FilesetEntry.files, or null if the user omitted it.
     */
    @Nullable
    public ImmutableList<TransitiveInfoCollection> getFiles() {
      return files;
    }
  }

  private static final String HOST_CONFIGURATION_PROGRESS_TAG = "for host";

  private final Rule rule;
  /**
   * A list of all aspects applied to the target. If this <code>RuleContext</code>
   * is for a rule implementation, <code>aspects</code> is an empty list.
   *
   * Otherwise, the last aspect in <code>aspects</code> list is the aspect which
   * this <code>RuleCointext</code> is for.
   */
  private final ImmutableList<Aspect> aspects;
  private final ImmutableList<AspectDescriptor> aspectDescriptors;
  private final ListMultimap<String, ConfiguredTargetAndData> targetMap;
  private final ListMultimap<String, ConfiguredFilesetEntry> filesetEntryMap;
  private final ImmutableMap<Label, ConfigMatchingProvider> configConditions;
  private final AspectAwareAttributeMapper attributes;
  private final ImmutableSet<String> enabledFeatures;
  private final ImmutableSet<String> disabledFeatures;
  private final String ruleClassNameForLogging;
  private final BuildConfiguration hostConfiguration;
  private final ConfigurationFragmentPolicy configurationFragmentPolicy;
  private final ImmutableList<Class<? extends BuildConfiguration.Fragment>> universalFragments;
  private final RuleErrorConsumer reporter;
  @Nullable private final ToolchainContext toolchainContext;
  private final ConstraintSemantics constraintSemantics;

  private ActionOwner actionOwner;

  /* lazily computed cache for Make variables, computed from the above. See get... method */
  private transient ConfigurationMakeVariableContext configurationMakeVariableContext = null;

  private RuleContext(
      Builder builder,
      AttributeMap attributes,
      ListMultimap<String, ConfiguredTargetAndData> targetMap,
      ListMultimap<String, ConfiguredFilesetEntry> filesetEntryMap,
      ImmutableMap<Label, ConfigMatchingProvider> configConditions,
      ImmutableList<Class<? extends BuildConfiguration.Fragment>> universalFragments,
      String ruleClassNameForLogging,
      ImmutableMap<String, Attribute> aspectAttributes,
      @Nullable ToolchainContext toolchainContext,
      ConstraintSemantics constraintSemantics) {
    super(
        builder.env,
        builder.target.getAssociatedRule(),
        builder.configuration,
        builder.prerequisiteMap.get(null),
        builder.visibility);
    this.rule = builder.target.getAssociatedRule();
    this.aspects = builder.aspects;
    this.aspectDescriptors =
        builder
            .aspects
            .stream()
            .map(a -> a.getDescriptor())
            .collect(ImmutableList.toImmutableList());
    this.configurationFragmentPolicy = builder.configurationFragmentPolicy;
    this.universalFragments = universalFragments;
    this.targetMap = targetMap;
    this.filesetEntryMap = filesetEntryMap;
    this.configConditions = configConditions;
    this.attributes = new AspectAwareAttributeMapper(attributes, aspectAttributes);
    Set<String> allEnabledFeatures = new HashSet<>();
    Set<String> allDisabledFeatures = new HashSet<>();
    getAllFeatures(allEnabledFeatures, allDisabledFeatures);
    this.enabledFeatures = ImmutableSortedSet.copyOf(allEnabledFeatures);
    this.disabledFeatures = ImmutableSortedSet.copyOf(allDisabledFeatures);
    this.ruleClassNameForLogging = ruleClassNameForLogging;
    this.hostConfiguration = builder.hostConfiguration;
    reporter = builder.reporter;
    this.toolchainContext = toolchainContext;
    this.constraintSemantics = constraintSemantics;
  }

  private void getAllFeatures(Set<String> allEnabledFeatures, Set<String> allDisabledFeatures) {
    Set<String> globallyEnabled = new HashSet<>();
    Set<String> globallyDisabled = new HashSet<>();
    parseFeatures(getConfiguration().getDefaultFeatures(), globallyEnabled, globallyDisabled);
    Set<String> packageEnabled = new HashSet<>();
    Set<String> packageDisabled = new HashSet<>();
    parseFeatures(getRule().getPackage().getFeatures(), packageEnabled, packageDisabled);
    Set<String> ruleEnabled = new HashSet<>();
    Set<String> ruleDisabled = new HashSet<>();
    if (attributes().has("features", Type.STRING_LIST)) {
      parseFeatures(attributes().get("features", Type.STRING_LIST), ruleEnabled, ruleDisabled);
    }

    Set<String> ruleDisabledFeatures =
        Sets.union(ruleDisabled, Sets.difference(packageDisabled, ruleEnabled));
    allDisabledFeatures.addAll(Sets.union(ruleDisabledFeatures, globallyDisabled));

    Set<String> packageFeatures =
        Sets.difference(Sets.union(globallyEnabled, packageEnabled), packageDisabled);
    Set<String> ruleFeatures =
        Sets.difference(Sets.union(packageFeatures, ruleEnabled), ruleDisabled);
    allEnabledFeatures.addAll(Sets.difference(ruleFeatures, globallyDisabled));
  }

  private void parseFeatures(Iterable<String> features, Set<String> enabled, Set<String> disabled) {
    for (String feature : features) {
      if (feature.startsWith("-")) {
        disabled.add(feature.substring(1));
      } else if (feature.equals("no_layering_check")) {
        // TODO(bazel-team): Remove once we do not have BUILD files left that contain
        // 'no_layering_check'.
        disabled.add(feature.substring(3));
      } else {
        enabled.add(feature);
      }
    }
  }

  public RepositoryName getRepository() {
    return rule.getRepository();
  }

  @Override
  public ArtifactRoot getBinDirectory() {
    return getConfiguration().getBinDirectory(rule.getRepository());
  }

  @Override
  public ArtifactRoot getMiddlemanDirectory() {
    return getConfiguration().getMiddlemanDirectory(rule.getRepository());
  }

  public Rule getRule() {
    return rule;
  }

  public ImmutableList<Aspect> getAspects() {
    return aspects;
  }

  /**
   * If this target's configuration suppresses analysis failures, this returns a list
   * of strings, where each string corresponds to a description of an error that occurred during
   * processing this target.
   *
   * @throws IllegalStateException if this target's configuration does not suppress analysis
   *     failures (if {@code getConfiguration().allowAnalysisFailures()} is false)
   */
  public List<String> getSuppressedErrorMessages() {
    Preconditions.checkState(getConfiguration().allowAnalysisFailures(),
        "Error messages can only be retrieved via RuleContext if allow_analysis_failures is true");
    Preconditions.checkState(reporter instanceof SuppressingErrorReporter,
        "Unexpected error reporter");
    return ((SuppressingErrorReporter) reporter).getErrorMessages();
  }

  /**
   * If this <code>RuleContext</code> is for an aspect implementation, returns that aspect.
   * (it is the last aspect in the list of aspects applied to a target; all other aspects
   * are the ones main aspect sees as specified by its "required_aspect_providers")
   * Otherwise returns <code>null</code>.
   */
  @Nullable
  public Aspect getMainAspect() {
    return aspects.isEmpty() ? null : aspects.get(aspects.size() - 1);
  }

  /**
   * Returns a rule class name suitable for log messages, including an aspect name if applicable.
   */
  public String getRuleClassNameForLogging() {
    return ruleClassNameForLogging;
  }

  /**
   * Returns the workspace name for the rule.
   */
  public String getWorkspaceName() {
    return rule.getRepository().strippedName();
  }

  /**
   * The configuration conditions that trigger this rule's configurable attributes.
   */
  public ImmutableMap<Label, ConfigMatchingProvider> getConfigConditions() {
    return configConditions;
  }

  /**
   * Returns the host configuration for this rule.
   */
  public BuildConfiguration getHostConfiguration() {
    return hostConfiguration;
  }

  /**
   * All aspects applied to the rule.
   */
  public ImmutableList<AspectDescriptor> getAspectDescriptors() {
    return aspectDescriptors;
  }

  /**
   * Accessor for the attributes of the rule and its aspects.
   *
   * <p>The rule's native attributes can be queried both on their structure / existence and values
   * Aspect attributes can only be queried on their structure.
   *
   * <p>This should be the sole interface for reading rule/aspect attributes in {@link RuleContext}.
   * Don't expose other access points through new public methods.
   */
  public AttributeMap attributes() {
    return attributes;
  }

  @Override
  public boolean hasErrors() {
    return getAnalysisEnvironment().hasErrors();
  }

  /**
   * Returns an immutable map from attribute name to list of configured targets for that attribute.
   */
  public ListMultimap<String, ? extends TransitiveInfoCollection> getConfiguredTargetMap() {
    return Multimaps.transformValues(targetMap, ConfiguredTargetAndData::getConfiguredTarget);
  }

  /**
   * Returns an immutable map from attribute name to list of {@link ConfiguredTargetAndData} objects
   * for that attribute.
   */
  public ListMultimap<String, ConfiguredTargetAndData> getConfiguredTargetAndDataMap() {
    return targetMap;
  }

  private List<ConfiguredTargetAndData> getConfiguredTargetAndTargetDeps(String key) {
    return targetMap.get(key);
  }

  /**
   * Returns an immutable map from attribute name to list of fileset entries.
   */
  public ListMultimap<String, ConfiguredFilesetEntry> getFilesetEntryMap() {
    return filesetEntryMap;
  }

  @Override
  public ActionOwner getActionOwner() {
    if (actionOwner == null) {
      actionOwner =
          createActionOwner(rule, aspectDescriptors, getConfiguration(), getExecutionPlatform());
    }
    return actionOwner;
  }

  /**
   * Returns a configuration fragment for this this target.
   */
  @Nullable
  public <T extends Fragment> T getFragment(Class<T> fragment, ConfigurationTransition transition) {
    return getFragment(fragment, fragment.getSimpleName(), "", transition);
  }

  @Nullable
  protected <T extends Fragment> T getFragment(Class<T> fragment, String name,
      String additionalErrorMessage, ConfigurationTransition transition) {
    // TODO(bazel-team): The fragments can also be accessed directly through BuildConfiguration.
    // Can we lock that down somehow?
    Preconditions.checkArgument(isLegalFragment(fragment, transition),
        "%s has to declare '%s' as a required fragment "
        + "in %s configuration in order to access it.%s",
        getRuleClassNameForLogging(), name, FragmentCollection.getConfigurationName(transition),
        additionalErrorMessage);
    return getConfiguration(transition).getFragment(fragment);
  }

  @Nullable
  public <T extends Fragment> T getFragment(Class<T> fragment) {
    // No transition means target configuration.
    return getFragment(fragment, NoTransition.INSTANCE);
  }

  @Nullable
  public Fragment getSkylarkFragment(String name, ConfigurationTransition transition) {
    Class<? extends Fragment> fragmentClass =
        getConfiguration(transition).getSkylarkFragmentByName(name);
    if (fragmentClass == null) {
      return null;
    }
    return getFragment(fragmentClass, name,
        String.format(
            " Please update the '%1$sfragments' argument of the rule definition "
            + "(for example: %1$sfragments = [\"%2$s\"])",
            (transition.isHostTransition()) ? "host_" : "", name),
        transition);
  }

  public ImmutableCollection<String> getSkylarkFragmentNames(ConfigurationTransition transition) {
    return getConfiguration(transition).getSkylarkFragmentNames();
  }

  public <T extends Fragment> boolean isLegalFragment(
      Class<T> fragment, ConfigurationTransition transition) {
    return universalFragments.contains(fragment)
        || fragment == PlatformConfiguration.class
        || configurationFragmentPolicy.isLegalConfigurationFragment(fragment, transition);
  }

  public <T extends Fragment> boolean isLegalFragment(Class<T> fragment) {
    // No transition means target configuration.
    return isLegalFragment(fragment, NoTransition.INSTANCE);
  }

  private BuildConfiguration getConfiguration(ConfigurationTransition transition) {
    return transition.isHostTransition() ? hostConfiguration : getConfiguration();
  }

  @Override
  public ArtifactOwner getOwner() {
    return getAnalysisEnvironment().getOwner();
  }

  public ImmutableList<Artifact> getBuildInfo(BuildInfoKey key) throws InterruptedException {
    return getAnalysisEnvironment().getBuildInfo(this, key, getConfiguration());
  }

  @VisibleForTesting
  public static ActionOwner createActionOwner(
      Rule rule,
      ImmutableList<AspectDescriptor> aspectDescriptors,
      BuildConfiguration configuration,
      @Nullable PlatformInfo executionPlatform) {
    return ActionOwner.create(
        rule.getLabel(),
        aspectDescriptors,
        rule.getLocation(),
        configuration.getMnemonic(),
        rule.getTargetKind(),
        configuration.checksum(),
        configuration.toBuildEvent(),
        configuration.isHostConfiguration() ? HOST_CONFIGURATION_PROGRESS_TAG : null,
        executionPlatform);
  }

  @Override
  public void registerAction(ActionAnalysisMetadata... action) {
    getAnalysisEnvironment().registerAction(action);
  }

  /**
   * Convenience function for subclasses to report non-attribute-specific
   * errors in the current rule.
   */
  @Override
  public void ruleError(String message) {
    reporter.ruleError(message);
  }

  /**
   * Convenience function for subclasses to report non-attribute-specific
   * warnings in the current rule.
   */
  @Override
  public void ruleWarning(String message) {
    reporter.ruleWarning(message);
  }

  /**
   * Convenience function for subclasses to report attribute-specific errors in
   * the current rule.
   *
   * <p>If the name of the attribute starts with <code>$</code>
   * it is replaced with a string <code>(an implicit dependency)</code>.
   */
  @Override
  public void attributeError(String attrName, String message) {
    reporter.attributeError(attrName, message);
  }

  /**
   * Like attributeError, but does not mark the configured target as errored.
   *
   * <p>If the name of the attribute starts with <code>$</code>
   * it is replaced with a string <code>(an implicit dependency)</code>.
   */
  @Override
  public void attributeWarning(String attrName, String message) {
    reporter.attributeWarning(attrName, message);
  }

  /**
   * Returns an artifact beneath the root of either the "bin" or "genfiles"
   * tree, whose path is based on the name of this target and the current
   * configuration.  The choice of which tree to use is based on the rule with
   * which this target (which must be an OutputFile or a Rule) is associated.
   */
  public Artifact createOutputArtifact() {
    Target target = getTarget();
    PathFragment rootRelativePath = getPackageDirectory()
        .getRelative(PathFragment.create(target.getName()));

    return internalCreateOutputArtifact(rootRelativePath, target, OutputFile.Kind.FILE);
  }

  /**
   * Returns an artifact beneath the root of either the "bin" or "genfiles"
   * tree, whose path is based on the name of this target and the current
   * configuration, with a script suffix appropriate for the current host platform. ({@code .cmd}
   * for Windows, otherwise {@code .sh}). The choice of which tree to use is based on the rule with
   * which this target (which must be an OutputFile or a Rule) is associated.
   */
  public Artifact createOutputArtifactScript() {
    Target target = getTarget();
    // TODO(laszlocsomor): Use the execution platform, not the host platform.
    boolean isExecutedOnWindows = OS.getCurrent() == OS.WINDOWS;

    String fileExtension = isExecutedOnWindows ? ".cmd" : ".sh";

    PathFragment rootRelativePath = getPackageDirectory()
        .getRelative(PathFragment.create(target.getName() + fileExtension));

    return internalCreateOutputArtifact(rootRelativePath, target, OutputFile.Kind.FILE);
  }

  /**
   * Returns the output artifact of an {@link OutputFile} of this target.
   *
   * @see #createOutputArtifact()
   */
  public Artifact createOutputArtifact(OutputFile out) {
    PathFragment packageRelativePath = getPackageDirectory()
        .getRelative(PathFragment.create(out.getName()));
    return internalCreateOutputArtifact(packageRelativePath, out, out.getKind());
  }

  /**
   * Implementation for {@link #createOutputArtifact()} and
   * {@link #createOutputArtifact(OutputFile)}. This is private so that
   * {@link #createOutputArtifact(OutputFile)} can have a more specific
   * signature.
   */
  private Artifact internalCreateOutputArtifact(PathFragment rootRelativePath,
      Target target, OutputFile.Kind outputFileKind) {
    Preconditions.checkState(
        target.getLabel().getPackageIdentifier().equals(getLabel().getPackageIdentifier()),
        "Creating output artifact for target '%s' in different package than the rule '%s' "
            + "being analyzed", target.getLabel(), getLabel());
    ArtifactRoot root = getBinOrGenfilesDirectory();

    switch (outputFileKind) {
      case FILE:
        return getDerivedArtifact(rootRelativePath, root);
      case FILESET:
        return getAnalysisEnvironment().getFilesetArtifact(rootRelativePath, root);
      default:
        throw new IllegalStateException();
    }
  }

  /**
   * Returns the root of either the "bin" or "genfiles" tree, based on this target and the current
   * configuration. The choice of which tree to use is based on the rule with which this target
   * (which must be an OutputFile or a Rule) is associated.
   */
  public ArtifactRoot getBinOrGenfilesDirectory() {
    return rule.hasBinaryOutput()
        ? getConfiguration().getBinDirectory(rule.getRepository())
        : getConfiguration().getGenfilesDirectory(rule.getRepository());
  }

  /**
   * Creates an artifact in a directory that is unique to the package that contains the rule, thus
   * guaranteeing that it never clashes with artifacts created by rules in other packages.
   */
  public Artifact getPackageRelativeArtifact(String relative, ArtifactRoot root) {
    return getPackageRelativeArtifact(PathFragment.create(relative), root);
  }

  /**
   * Creates an artifact in a directory that is unique to the package that contains the rule, thus
   * guaranteeing that it never clashes with artifacts created by rules in other packages.
   */
  public Artifact getPackageRelativeTreeArtifact(String relative, ArtifactRoot root) {
    return getPackageRelativeTreeArtifact(PathFragment.create(relative), root);
  }

  /**
   * Creates an artifact in a directory that is unique to the package that contains the rule, thus
   * guaranteeing that it never clashes with artifacts created by rules in other packages.
   */
  public Artifact getBinArtifact(String relative) {
    return getBinArtifact(PathFragment.create(relative));
  }

  public Artifact getBinArtifact(PathFragment relative) {
    return getPackageRelativeArtifact(
        relative, getConfiguration().getBinDirectory(rule.getRepository()));
  }

  /**
   * Creates an artifact in a directory that is unique to the package that contains the rule, thus
   * guaranteeing that it never clashes with artifacts created by rules in other packages.
   */
  public Artifact getGenfilesArtifact(String relative) {
    return getGenfilesArtifact(PathFragment.create(relative));
  }

  public Artifact getGenfilesArtifact(PathFragment relative) {
    return getPackageRelativeArtifact(
        relative, getConfiguration().getGenfilesDirectory(rule.getRepository()));
  }

  @Override
  public Artifact getShareableArtifact(PathFragment rootRelativePath, ArtifactRoot root) {
    return getAnalysisEnvironment().getDerivedArtifact(rootRelativePath, root);
  }

  @Override
  public Artifact getPackageRelativeArtifact(PathFragment relative, ArtifactRoot root) {
    return getDerivedArtifact(getPackageDirectory().getRelative(relative), root);
  }

  @Override
  public PathFragment getPackageDirectory() {
    return getLabel().getPackageIdentifier().getSourceRoot();
  }

  /**
   * Creates an artifact under a given root with the given root-relative path.
   *
   * <p>Verifies that it is in the root-relative directory corresponding to the package of the rule,
   * thus ensuring that it doesn't clash with other artifacts generated by other rules using this
   * method.
   */
  @Override
  public Artifact getDerivedArtifact(PathFragment rootRelativePath, ArtifactRoot root) {
    Preconditions.checkState(rootRelativePath.startsWith(getPackageDirectory()),
        "Output artifact '%s' not under package directory '%s' for target '%s'",
        rootRelativePath, getPackageDirectory(), getLabel());
    return getAnalysisEnvironment().getDerivedArtifact(rootRelativePath, root);
  }

  /**
   * Creates a TreeArtifact under a given root with the given root-relative path.
   *
   * <p>Verifies that it is in the root-relative directory corresponding to the package of the rule,
   * thus ensuring that it doesn't clash with other artifacts generated by other rules using this
   * method.
   */
  public SpecialArtifact getTreeArtifact(PathFragment rootRelativePath, ArtifactRoot root) {
    Preconditions.checkState(rootRelativePath.startsWith(getPackageDirectory()),
        "Output artifact '%s' not under package directory '%s' for target '%s'",
        rootRelativePath, getPackageDirectory(), getLabel());
    return getAnalysisEnvironment().getTreeArtifact(rootRelativePath, root);
  }

  /**
   * Creates a tree artifact in a directory that is unique to the package that contains the rule,
   * thus guaranteeing that it never clashes with artifacts created by rules in other packages.
   */
  public Artifact getPackageRelativeTreeArtifact(PathFragment relative, ArtifactRoot root) {
    return getTreeArtifact(getPackageDirectory().getRelative(relative), root);
  }

  /**
   * Creates an artifact in a directory that is unique to the rule, thus guaranteeing that it never
   * clashes with artifacts created by other rules.
   */
  public Artifact getUniqueDirectoryArtifact(
      String uniqueDirectory, String relative, ArtifactRoot root) {
    return getUniqueDirectoryArtifact(uniqueDirectory, PathFragment.create(relative), root);
  }

  @Override
  public Artifact getUniqueDirectoryArtifact(String uniqueDirectorySuffix, String relative) {
    return getUniqueDirectoryArtifact(uniqueDirectorySuffix, relative, getBinOrGenfilesDirectory());
  }

  @Override
  public Artifact getUniqueDirectoryArtifact(String uniqueDirectorySuffix, PathFragment relative) {
    return getUniqueDirectoryArtifact(uniqueDirectorySuffix, relative, getBinOrGenfilesDirectory());
  }

  /**
   * Creates an artifact in a directory that is unique to the rule, thus guaranteeing that it never
   * clashes with artifacts created by other rules.
   */
  public Artifact getUniqueDirectoryArtifact(
      String uniqueDirectory, PathFragment relative, ArtifactRoot root) {
    return getDerivedArtifact(getUniqueDirectory(uniqueDirectory).getRelative(relative), root);
  }

  /**
   * Returns true iff the rule, or any attached aspect, has an attribute with the given name and
   * type.
   */
  public boolean isAttrDefined(String attrName, Type<?> type) {
    return attributes().has(attrName, type);
  }

  /**
   * Returns the dependencies through a {@code LABEL_DICT_UNARY} attribute as a map from
   * a string to a {@link TransitiveInfoCollection}.
   */
  public Map<String, TransitiveInfoCollection> getPrerequisiteMap(String attributeName) {
    Preconditions.checkState(attributes().has(attributeName, BuildType.LABEL_DICT_UNARY));

    ImmutableMap.Builder<String, TransitiveInfoCollection> result = ImmutableMap.builder();
    Map<String, Label> dict = attributes().get(attributeName, BuildType.LABEL_DICT_UNARY);
    Map<Label, ConfiguredTarget> labelToDep = new HashMap<>();
    for (ConfiguredTargetAndData dep : targetMap.get(attributeName)) {
      labelToDep.put(dep.getTarget().getLabel(), dep.getConfiguredTarget());
    }

    for (Map.Entry<String, Label> entry : dict.entrySet()) {
      result.put(entry.getKey(), Preconditions.checkNotNull(labelToDep.get(entry.getValue())));
    }

    return result.build();
  }

  /**
   * Returns the prerequisites keyed by the CPU of their configurations. If the split transition
   * is not active (e.g. split() returned an empty list), the key is an empty Optional.
   */
  public Map<Optional<String>, ? extends List<? extends TransitiveInfoCollection>>
      getSplitPrerequisites(String attributeName) {
    return Maps.transformValues(
        getSplitPrerequisiteConfiguredTargetAndTargets(attributeName),
        (ctatList) -> Lists.transform(ctatList, ConfiguredTargetAndData::getConfiguredTarget));
  }

  /**
   * Returns the list of ConfiguredTargetsAndTargets that feed into the target through the specified
   * attribute. Note that you need to specify the correct mode for the attribute otherwise an
   * exception will be raised.
   */
  public List<ConfiguredTargetAndData> getPrerequisiteConfiguredTargetAndTargets(
      String attributeName, Mode mode) {
    Attribute attributeDefinition = attributes().getAttributeDefinition(attributeName);
    if ((mode == Mode.TARGET) && (attributeDefinition.hasSplitConfigurationTransition())) {
      // TODO(bazel-team): If you request a split-configured attribute in the target configuration,
      // we return only the list of configured targets for the first architecture; this is for
      // backwards compatibility with existing code in cases where the call to getPrerequisites is
      // deeply nested and we can't easily inject the behavior we want. However, we should fix all
      // such call sites.
      checkAttribute(attributeName, Mode.SPLIT);
      Map<Optional<String>, List<ConfiguredTargetAndData>> map =
          getSplitPrerequisiteConfiguredTargetAndTargets(attributeName);
      return map.isEmpty() ? ImmutableList.of() : map.entrySet().iterator().next().getValue();
    }

    checkAttribute(attributeName, mode);
    return getConfiguredTargetAndTargetDeps(attributeName);
  }

  /**
   * Returns the prerequisites keyed by the CPU of their configurations. If the split transition is
   * not active (e.g. split() returned an empty list), the key is an empty Optional.
   */
  public Map<Optional<String>, List<ConfiguredTargetAndData>>
      getSplitPrerequisiteConfiguredTargetAndTargets(String attributeName) {
    checkAttribute(attributeName, Mode.SPLIT);
    Attribute attributeDefinition = attributes().getAttributeDefinition(attributeName);
    SplitTransition transition =
        attributeDefinition.getSplitTransition(
            ConfiguredAttributeMapper.of(rule, configConditions));
    BuildOptions fromOptions = getConfiguration().getOptions();
    List<BuildOptions> splitOptions = transition.split(fromOptions);
    List<ConfiguredTargetAndData> deps = getConfiguredTargetAndTargetDeps(attributeName);

    if (SplitTransition.equals(fromOptions, splitOptions)) {
      // The split transition is not active. Defer the decision on which CPU to use.
      return ImmutableMap.of(Optional.<String>absent(), deps);
    }

    Set<String> cpus = new HashSet<>();
    for (BuildOptions options : splitOptions) {
      // This method should only be called when the split config is enabled on the command line, in
      // which case this cpu can't be null.
      cpus.add(options.get(BuildConfiguration.Options.class).cpu);
    }

    // Use an ImmutableListMultimap.Builder here to preserve ordering.
    ImmutableListMultimap.Builder<Optional<String>, ConfiguredTargetAndData> result =
        ImmutableListMultimap.builder();
    for (ConfiguredTargetAndData t : deps) {
      if (t.getConfiguration() != null) {
        result.put(Optional.of(t.getConfiguration().getCpu()), t);
      } else {
        // Source files don't have a configuration, so we add them to all architecture entries.
        for (String cpu : cpus) {
          result.put(Optional.of(cpu), t);
        }
      }
    }
    return Multimaps.asMap(result.build());
  }

  /**
   * Returns the specified provider of the prerequisite referenced by the attribute in the
   * argument. Note that you need to specify the correct mode for the attribute, otherwise an
   * assertion will be raised. If the attribute is empty or it does not support the specified
   * provider, returns null.
   */
  public <C extends TransitiveInfoProvider> C getPrerequisite(
      String attributeName, Mode mode, Class<C> provider) {
    TransitiveInfoCollection prerequisite = getPrerequisite(attributeName, mode);
    return prerequisite == null ? null : prerequisite.getProvider(provider);
  }

  /**
   * Returns the transitive info collection that feeds into this target through the specified
   * attribute. Note that you need to specify the correct mode for the attribute, otherwise an
   * assertion will be raised. Returns null if the attribute is empty.
   */
  public TransitiveInfoCollection getPrerequisite(String attributeName, Mode mode) {
    ConfiguredTargetAndData result = getPrerequisiteConfiguredTargetAndData(attributeName, mode);
    return result == null ? null : result.getConfiguredTarget();
  }

  /**
   * Returns the {@link ConfiguredTargetAndData} that feeds ino this target through the specified
   * attribute. Note that you need to specify the correct mode for the attribute, otherwise an
   * assertion will be raised. Returns null if the attribute is empty.
   */
  public ConfiguredTargetAndData getPrerequisiteConfiguredTargetAndData(
      String attributeName, Mode mode) {
    checkAttribute(attributeName, mode);
    List<ConfiguredTargetAndData> elements = getConfiguredTargetAndTargetDeps(attributeName);
    if (elements.size() > 1) {
      throw new IllegalStateException(getRuleClassNameForLogging() + " attribute " + attributeName
          + " produces more than one prerequisite");
    }
    return elements.isEmpty() ? null : elements.get(0);
  }

  /**
   * For a given attribute, returns all the ConfiguredTargetAndTargets of that attribute. Each
   * ConfiguredTargetAndData is keyed by the {@link BuildConfiguration} that created it.
   */
  public ImmutableListMultimap<BuildConfiguration, ConfiguredTargetAndData>
      getPrerequisiteCofiguredTargetAndTargetsByConfiguration(String attributeName, Mode mode) {
    List<ConfiguredTargetAndData> ctatCollection =
        getPrerequisiteConfiguredTargetAndTargets(attributeName, mode);
    ImmutableListMultimap.Builder<BuildConfiguration, ConfiguredTargetAndData> result =
        ImmutableListMultimap.builder();
    for (ConfiguredTargetAndData ctad : ctatCollection) {
      result.put(ctad.getConfiguration(), ctad);
    }
    return result.build();
  }

  /**
   * For a given attribute, returns all declared provider provided by targets of that attribute.
   * Each declared provider is keyed by the {@link BuildConfiguration} under which the provider was
   * created.
   *
   * @deprecated use {@link #getPrerequisitesByConfiguration(String, Mode, BuiltinProvider)}
   *     instead
   */
  @Deprecated
  public <C extends Info>
  ImmutableListMultimap<BuildConfiguration, C> getPrerequisitesByConfiguration(
      String attributeName, Mode mode, final NativeProvider<C> provider) {
    ImmutableListMultimap.Builder<BuildConfiguration, C> result =
        ImmutableListMultimap.builder();
    for (ConfiguredTargetAndData prerequisite :
        getPrerequisiteConfiguredTargetAndTargets(attributeName, mode)) {
      C prerequisiteProvider = prerequisite.getConfiguredTarget().get(provider);
      if (prerequisiteProvider != null) {
        result.put(prerequisite.getConfiguration(), prerequisiteProvider);
      }
    }
    return result.build();
  }

  /**
   * For a given attribute, returns all declared provider provided by targets of that attribute.
   * Each declared provider is keyed by the {@link BuildConfiguration} under which the provider was
   * created.
   */
  public <C extends Info>
      ImmutableListMultimap<BuildConfiguration, C> getPrerequisitesByConfiguration(
          String attributeName, Mode mode, final BuiltinProvider<C> provider) {
    ImmutableListMultimap.Builder<BuildConfiguration, C> result =
        ImmutableListMultimap.builder();
    for (ConfiguredTargetAndData prerequisite :
        getPrerequisiteConfiguredTargetAndTargets(attributeName, mode)) {
      C prerequisiteProvider = prerequisite.getConfiguredTarget().get(provider);
      if (prerequisiteProvider != null) {
        result.put(prerequisite.getConfiguration(), prerequisiteProvider);
      }
    }
    return result.build();
  }

  /**
   * For a given attribute, returns all {@link TransitiveInfoCollection}s provided by targets
   * of that attribute. Each {@link TransitiveInfoCollection} is keyed by the
   * {@link BuildConfiguration} under which the collection was created.
   */
  public ImmutableListMultimap<BuildConfiguration, TransitiveInfoCollection>
      getPrerequisitesByConfiguration(String attributeName, Mode mode) {
    ImmutableListMultimap.Builder<BuildConfiguration, TransitiveInfoCollection> result =
        ImmutableListMultimap.builder();
    for (ConfiguredTargetAndData prerequisite :
        getPrerequisiteConfiguredTargetAndTargets(attributeName, mode)) {
      result.put(prerequisite.getConfiguration(), prerequisite.getConfiguredTarget());
    }
    return result.build();
  }

  /**
   * Returns the list of transitive info collections that feed into this target through the
   * specified attribute. Note that you need to specify the correct mode for the attribute,
   * otherwise an assertion will be raised.
   */
  public List<? extends TransitiveInfoCollection> getPrerequisites(String attributeName,
      Mode mode) {
    return Lists.transform(
        getPrerequisiteConfiguredTargetAndTargets(attributeName, mode),
        ConfiguredTargetAndData::getConfiguredTarget);
  }

  /**
   * Returns all the providers of the specified type that are listed under the specified attribute
   * of this target in the BUILD file.
   */
  public <C extends TransitiveInfoProvider> Iterable<C> getPrerequisites(String attributeName,
      Mode mode, final Class<C> classType) {
    AnalysisUtils.checkProvider(classType);
    return AnalysisUtils.getProviders(getPrerequisites(attributeName, mode), classType);
  }

  /**
   * Returns all the declared providers (native and Skylark) for the specified constructor under the
   * specified attribute of this target in the BUILD file.
   */
  public <T extends InfoInterface> Iterable<T> getPrerequisites(
      String attributeName, Mode mode, final NativeProvider<T> skylarkKey) {
    return AnalysisUtils.getProviders(getPrerequisites(attributeName, mode), skylarkKey);
  }

  /**
   * Returns all the declared providers (native and Skylark) for the specified constructor under the
   * specified attribute of this target in the BUILD file.
   */
  public <T extends InfoInterface> Iterable<T> getPrerequisites(
      String attributeName, Mode mode, final BuiltinProvider<T> skylarkKey) {
    return AnalysisUtils.getProviders(getPrerequisites(attributeName, mode), skylarkKey);
  }

  /**
   * Returns the declared provider (native and Skylark) for the specified constructor under the
   * specified attribute of this target in the BUILD file. May return null if there is no
   * TransitiveInfoCollection under the specified attribute.
   */
  @Nullable
  public <T extends InfoInterface> T getPrerequisite(
      String attributeName, Mode mode, final NativeProvider<T> skylarkKey) {
    TransitiveInfoCollection prerequisite = getPrerequisite(attributeName, mode);
    return prerequisite == null ? null : prerequisite.get(skylarkKey);
  }

  /**
   * Returns the declared provider (native and Skylark) for the specified constructor under the
   * specified attribute of this target in the BUILD file. May return null if there is no
   * TransitiveInfoCollection under the specified attribute.
   */
  @Nullable
  public <T extends InfoInterface> T getPrerequisite(
      String attributeName, Mode mode, final BuiltinProvider<T> skylarkKey) {
    TransitiveInfoCollection prerequisite = getPrerequisite(attributeName, mode);
    return prerequisite == null ? null : prerequisite.get(skylarkKey);
  }


  /**
   * Returns all the providers of the specified type that are listed under the specified attribute
   * of this target in the BUILD file, and that contain the specified provider.
   */
  public <C extends TransitiveInfoProvider> Iterable<? extends TransitiveInfoCollection>
      getPrerequisitesIf(String attributeName, Mode mode, final Class<C> classType) {
    AnalysisUtils.checkProvider(classType);
    return AnalysisUtils.filterByProvider(getPrerequisites(attributeName, mode), classType);
  }

  /**
   * Returns all the providers of the specified type that are listed under the specified attribute
   * of this target in the BUILD file, and that contain the specified provider.
   */
  public <C extends Info> Iterable<? extends TransitiveInfoCollection> getPrerequisitesIf(
      String attributeName, Mode mode, final NativeProvider<C> classType) {
    return AnalysisUtils.filterByProvider(getPrerequisites(attributeName, mode), classType);
  }

  /**
   * Returns the prerequisite referred to by the specified attribute. Also checks whether
   * the attribute is marked as executable and that the target referred to can actually be
   * executed.
   *
   * <p>The {@code mode} argument must match the configuration transition specified in the
   * definition of the attribute.
   *
   * @param attributeName the name of the attribute
   * @param mode the configuration transition of the attribute
   *
   * @return the {@link FilesToRunProvider} interface of the prerequisite.
   */
  @Nullable
  public FilesToRunProvider getExecutablePrerequisite(String attributeName, Mode mode) {
    Attribute ruleDefinition = attributes().getAttributeDefinition(attributeName);

    if (ruleDefinition == null) {
      throw new IllegalStateException(getRuleClassNameForLogging() + " attribute " + attributeName
          + " is not defined");
    }
    if (!ruleDefinition.isExecutable()) {
      throw new IllegalStateException(getRuleClassNameForLogging() + " attribute " + attributeName
          + " is not configured to be executable");
    }

    TransitiveInfoCollection prerequisite = getPrerequisite(attributeName, mode);
    if (prerequisite == null) {
      return null;
    }

    FilesToRunProvider result = prerequisite.getProvider(FilesToRunProvider.class);
    if (result == null || result.getExecutable() == null) {
      attributeError(
          attributeName, prerequisite.getLabel() + " does not refer to a valid executable target");
    }
    return result;
  }

  public void initConfigurationMakeVariableContext(
      Iterable<? extends MakeVariableSupplier> makeVariableSuppliers) {
    Preconditions.checkState(configurationMakeVariableContext == null);
    configurationMakeVariableContext =
        new ConfigurationMakeVariableContext(
            this, getRule().getPackage(), getConfiguration(), makeVariableSuppliers);
  }

  public void initConfigurationMakeVariableContext(MakeVariableSupplier... makeVariableSuppliers) {
    initConfigurationMakeVariableContext(ImmutableList.copyOf(makeVariableSuppliers));
  }

  public Expander getExpander(TemplateContext templateContext) {
    return new Expander(this, templateContext);
  }

  public Expander getExpander() {
    return new Expander(this, getConfigurationMakeVariableContext());
  }

  /**
   * Returns a cached context that maps Make variable names (string) to values (string) without any
   * extra {@link MakeVariableSupplier}.
   */
  public ConfigurationMakeVariableContext getConfigurationMakeVariableContext() {
    if (configurationMakeVariableContext == null) {
      initConfigurationMakeVariableContext(ImmutableList.<MakeVariableSupplier>of());
    }
    return configurationMakeVariableContext;
  }

  @Nullable
  public ToolchainContext getToolchainContext() {
    return toolchainContext;
  }

  public ConstraintSemantics getConstraintSemantics() {
    return constraintSemantics;
  }

  @Override
  @Nullable
  public PlatformInfo getExecutionPlatform() {
    if (getToolchainContext() == null) {
      return null;
    }
    return getToolchainContext().executionPlatform();
  }

  private void checkAttribute(String attributeName, Mode mode) {
    Attribute attributeDefinition = attributes.getAttributeDefinition(attributeName);
    if (attributeDefinition == null) {
      throw new IllegalStateException(getRule().getLocation() + ": " + getRuleClassNameForLogging()
        + " attribute " + attributeName + " is not defined");
    }
    if (attributeDefinition.getType().getLabelClass() != LabelClass.DEPENDENCY) {
      throw new IllegalStateException(getRuleClassNameForLogging() + " attribute " + attributeName
        + " is not a label type attribute");
    }
    ConfigurationTransition transition = attributeDefinition.getConfigurationTransition();
    if (mode == Mode.HOST) {
      if (!(transition instanceof PatchTransition)) {
        throw new IllegalStateException(getRule().getLocation() + ": "
            + getRuleClassNameForLogging() + " attribute " + attributeName
            + " is not configured for the host configuration");
      }
    } else if (mode == Mode.TARGET) {
      if (!(transition instanceof PatchTransition) && transition != NoTransition.INSTANCE) {
        throw new IllegalStateException(getRule().getLocation() + ": "
            + getRuleClassNameForLogging() + " attribute " + attributeName
            + " is not configured for the target configuration");
      }
    } else if (mode == Mode.DATA) {
      throw new IllegalStateException(getRule().getLocation() + ": "
          + getRuleClassNameForLogging() + " attribute " + attributeName
          + ": DATA transition no longer supported"); // See b/80157700.
    } else if (mode == Mode.SPLIT) {
      if (!(attributeDefinition.hasSplitConfigurationTransition())) {
        throw new IllegalStateException(getRule().getLocation() + ": "
            + getRuleClassNameForLogging() + " attribute " + attributeName
            + " is not configured for a split transition");
      }
    }
  }

  /**
   * For the specified attribute "attributeName" (which must be of type
   * list(label)), resolve all the labels into ConfiguredTargets (for the
   * configuration appropriate to the attribute) and return their build
   * artifacts as a {@link PrerequisiteArtifacts} instance.
   *
   * @param attributeName the name of the attribute to traverse
   */
  public PrerequisiteArtifacts getPrerequisiteArtifacts(String attributeName, Mode mode) {
    return PrerequisiteArtifacts.get(this, attributeName, mode);
  }

  /**
   * For the specified attribute "attributeName" (which must be of type label),
   * resolves the ConfiguredTarget and returns its single build artifact.
   *
   * <p>If the attribute is optional, has no default and was not specified, then
   * null will be returned. Note also that null is returned (and an attribute
   * error is raised) if there wasn't exactly one build artifact for the target.
   */
  public Artifact getPrerequisiteArtifact(String attributeName, Mode mode) {
    TransitiveInfoCollection target = getPrerequisite(attributeName, mode);
    return transitiveInfoCollectionToArtifact(attributeName, target);
  }

  /**
   * Equivalent to getPrerequisiteArtifact(), but also asserts that
   * host-configuration is appropriate for the specified attribute.
   */
  public Artifact getHostPrerequisiteArtifact(String attributeName) {
    TransitiveInfoCollection target = getPrerequisite(attributeName, Mode.HOST);
    return transitiveInfoCollectionToArtifact(attributeName, target);
  }

  private Artifact transitiveInfoCollectionToArtifact(
      String attributeName, TransitiveInfoCollection target) {
    if (target != null) {
      Iterable<Artifact> artifacts = target.getProvider(FileProvider.class).getFilesToBuild();
      if (Iterables.size(artifacts) == 1) {
        return Iterables.getOnlyElement(artifacts);
      } else {
        attributeError(attributeName, target.getLabel() + " expected a single artifact");
      }
    }
    return null;
  }

  /**
   * Returns the sole file in the "srcs" attribute. Reports an error and
   * (possibly) returns null if "srcs" does not identify a single file of the
   * expected type.
   */
  public Artifact getSingleSource(String fileTypeName) {
    List<Artifact> srcs = PrerequisiteArtifacts.get(this, "srcs", Mode.TARGET).list();
    switch (srcs.size()) {
      case 0 : // error already issued by getSrc()
        return null;
      case 1 : // ok
        return Iterables.getOnlyElement(srcs);
      default :
        attributeError("srcs", "only a single " + fileTypeName + " is allowed here");
        return srcs.get(0);
    }
  }

  public Artifact getSingleSource() {
    return getSingleSource(getRuleClassNameForLogging() + " source file");
  }

  /**
   * Returns a path fragment qualified by the rule name and unique fragment to
   * disambiguate artifacts produced from the source file appearing in
   * multiple rules.
   *
   * <p>For example "pkg/dir/name" -> "pkg/&lt;fragment>/rule/dir/name.
   */
  public final PathFragment getUniqueDirectory(String fragment) {
    return getUniqueDirectory(PathFragment.create(fragment));
  }

  /**
   * Returns a path fragment qualified by the rule name and unique fragment to
   * disambiguate artifacts produced from the source file appearing in
   * multiple rules.
   *
   * <p>For example "pkg/dir/name" -> "pkg/&lt;fragment>/rule/dir/name.
   */
  public final PathFragment getUniqueDirectory(PathFragment fragment) {
    return AnalysisUtils.getUniqueDirectory(getLabel(), fragment);
  }

  /**
   * Check that all targets that were specified as sources are from the same
   * package as this rule. Output a warning or an error for every target that is
   * imported from a different package.
   */
  public void checkSrcsSamePackage(boolean onlyWarn) {
    PathFragment packageName = getLabel().getPackageFragment();
    for (Artifact srcItem : PrerequisiteArtifacts.get(this, "srcs", Mode.TARGET).list()) {
      if (!srcItem.isSourceArtifact()) {
        // In theory, we should not do this check. However, in practice, we
        // have a couple of rules that do not obey the "srcs must contain
        // files and only files" rule. Thus, we are stuck with this hack here :(
        continue;
      }
      Label associatedLabel = srcItem.getOwner();
      PathFragment itemPackageName = associatedLabel.getPackageFragment();
      if (!itemPackageName.equals(packageName)) {
        String message = "please do not import '" + associatedLabel + "' directly. "
            + "You should either move the file to this package or depend on "
            + "an appropriate rule there";
        if (onlyWarn) {
          attributeWarning("srcs", message);
        } else {
          attributeError("srcs", message);
        }
      }
    }
  }


  /**
   * Returns the label to which the {@code NODEP_LABEL} attribute
   * {@code attrName} refers, checking that it is a valid label, and that it is
   * referring to a local target. Reports a warning otherwise.
   */
  public Label getLocalNodepLabelAttribute(String attrName) {
    Label label = attributes().get(attrName, BuildType.NODEP_LABEL);
    if (label == null) {
      return null;
    }

    if (!getTarget().getLabel().getPackageFragment().equals(label.getPackageFragment())) {
      attributeWarning(attrName, "does not reference a local rule");
    }

    return label;
  }

  @Override
  public Artifact getImplicitOutputArtifact(ImplicitOutputsFunction function)
      throws InterruptedException {
    Iterable<String> result;
    try {
      result =
          function.getImplicitOutputs(
              getAnalysisEnvironment().getEventHandler(), RawAttributeMapper.of(rule));
    } catch (EvalException e) {
      // It's ok as long as we don't use this method from Skylark.
      throw new IllegalStateException(e);
    }
    return getImplicitOutputArtifact(Iterables.getOnlyElement(result));
  }

  /**
   * Only use from Skylark. Returns the implicit output artifact for a given output path.
   */
  public Artifact getImplicitOutputArtifact(String path) {
    return getPackageRelativeArtifact(path, getBinOrGenfilesDirectory());
  }

  /**
   * Convenience method to return a host configured target for the "compiler"
   * attribute. Allows caller to decide whether a warning should be printed if
   * the "compiler" attribute is not set to the default value.
   *
   * @param warnIfNotDefault if true, print a warning if the value for the
   *        "compiler" attribute is set to something other than the default
   * @return a ConfiguredTarget using the host configuration for the "compiler"
   *         attribute
   */
  public final FilesToRunProvider getCompiler(boolean warnIfNotDefault) {
    Label label = attributes().get("compiler", BuildType.LABEL);
    if (warnIfNotDefault && !label.equals(getRule().getAttrDefaultValue("compiler"))) {
      attributeWarning("compiler", "setting the compiler is strongly discouraged");
    }
    return getExecutablePrerequisite("compiler", Mode.HOST);
  }

  /**
   * Returns the (unmodifiable, ordered) list of artifacts which are the outputs
   * of this target.
   *
   * <p>Each element in this list is associated with a single output, either
   * declared implicitly (via setImplicitOutputsFunction()) or explicitly
   * (listed in the 'outs' attribute of our rule).
   */
  public final ImmutableList<Artifact> getOutputArtifacts() {
    ImmutableList.Builder<Artifact> artifacts = ImmutableList.builder();
    for (OutputFile out : getRule().getOutputFiles()) {
      artifacts.add(createOutputArtifact(out));
    }
    return artifacts.build();
  }

  /**
   * Like {@link #getOutputArtifacts()} but for a singular output item.
   * Reports an error if the "out" attribute is not a singleton.
   *
   * @return null if the output list is empty, the artifact for the first item
   *         of the output list otherwise
   */
  public Artifact getOutputArtifact() {
    List<Artifact> outs = getOutputArtifacts();
    if (outs.size() != 1) {
      attributeError("out", "exactly one output file required");
      if (outs.isEmpty()) {
        return null;
      }
    }
    return outs.get(0);
  }

  /**
   * Returns an artifact with a given file extension. All other path components
   * are the same as in {@code pathFragment}.
   */
  public final Artifact getRelatedArtifact(PathFragment pathFragment, String extension) {
    PathFragment file = FileSystemUtils.replaceExtension(pathFragment, extension);
    return getDerivedArtifact(file, getConfiguration().getBinDirectory(rule.getRepository()));
  }

  /**
   * Returns true if the target for this context is a test target.
   */
  public boolean isTestTarget() {
    return TargetUtils.isTestRule(getTarget());
  }

  /** Returns true if the testonly attribute is set on this context. */
  public boolean isTestOnlyTarget() {
    return attributes().has("testonly", Type.BOOLEAN) && attributes().get("testonly", Type.BOOLEAN);
  }

  /**
   * @return true if {@code rule} is visible from {@code prerequisite}.
   *
   * <p>This only computes the logic as implemented by the visibility system. The final decision
   * whether a dependency is allowed is made by
   * {@link ConfiguredRuleClassProvider.PrerequisiteValidator}.
   */
  public static boolean isVisible(Rule rule, TransitiveInfoCollection prerequisite) {
    // Check visibility attribute
    for (PackageGroupContents specification :
        prerequisite.getProvider(VisibilityProvider.class).getVisibility()) {
      if (specification.containsPackage(rule.getLabel().getPackageIdentifier())) {
        return true;
      }
    }

    return false;
  }

  /**
   * @return the set of features applicable for the current rule's package.
   */
  public ImmutableSet<String> getFeatures() {
    return enabledFeatures;
  }

  /** @return the set of features that are disabled for the current rule's package. */
  public ImmutableSet<String> getDisabledFeatures() {
    return disabledFeatures;
  }

  @Override
  public String toString() {
    return "RuleContext(" + getLabel() + ", " + getConfiguration() + ")";
  }

  /**
   * Builder class for a RuleContext.
   */
  @VisibleForTesting
  public static final class Builder implements RuleErrorConsumer  {
    private final AnalysisEnvironment env;
    private final Target target;
    private final ConfigurationFragmentPolicy configurationFragmentPolicy;
    private ImmutableList<Class<? extends BuildConfiguration.Fragment>> universalFragments;
    private final BuildConfiguration configuration;
    private final BuildConfiguration hostConfiguration;
    private final PrerequisiteValidator prerequisiteValidator;
    private final RuleErrorConsumer reporter;
    private OrderedSetMultimap<Attribute, ConfiguredTargetAndData> prerequisiteMap;
    private ImmutableMap<Label, ConfigMatchingProvider> configConditions;
    private NestedSet<PackageGroupContents> visibility;
    private ImmutableMap<String, Attribute> aspectAttributes;
    private ImmutableList<Aspect> aspects;
    private ToolchainContext toolchainContext;
    private ConstraintSemantics constraintSemantics;

    @VisibleForTesting
    public Builder(
        AnalysisEnvironment env,
        Target target,
        ImmutableList<Aspect> aspects,
        BuildConfiguration configuration,
        BuildConfiguration hostConfiguration,
        PrerequisiteValidator prerequisiteValidator,
        ConfigurationFragmentPolicy configurationFragmentPolicy) {
      this.env = Preconditions.checkNotNull(env);
      this.target = Preconditions.checkNotNull(target);
      this.aspects = aspects;
      this.configurationFragmentPolicy = Preconditions.checkNotNull(configurationFragmentPolicy);
      this.configuration = Preconditions.checkNotNull(configuration);
      this.hostConfiguration = Preconditions.checkNotNull(hostConfiguration);
      this.prerequisiteValidator = prerequisiteValidator;
      if (configuration.allowAnalysisFailures()) {
        reporter = new SuppressingErrorReporter();
      } else {
        reporter = new ErrorReporter(env, target.getAssociatedRule(), getRuleClassNameForLogging());
      }
    }

    @VisibleForTesting
    public RuleContext build() {
      Preconditions.checkNotNull(prerequisiteMap);
      Preconditions.checkNotNull(configConditions);
      Preconditions.checkNotNull(visibility);
      Preconditions.checkNotNull(constraintSemantics);
      AttributeMap attributes =
          ConfiguredAttributeMapper.of(target.getAssociatedRule(), configConditions);
      validateAttributes(attributes);
      ListMultimap<String, ConfiguredTargetAndData> targetMap = createTargetMap();
      ListMultimap<String, ConfiguredFilesetEntry> filesetEntryMap =
          createFilesetEntryMap(target.getAssociatedRule(), configConditions);
      return new RuleContext(
          this,
          attributes,
          targetMap,
          filesetEntryMap,
          configConditions,
          universalFragments,
          getRuleClassNameForLogging(),
          aspectAttributes != null ? aspectAttributes : ImmutableMap.<String, Attribute>of(),
          toolchainContext,
          constraintSemantics);
    }

    private void validateAttributes(AttributeMap attributes) {
      target
          .getAssociatedRule()
          .getRuleClassObject()
          .checkAttributesNonEmpty(reporter, attributes);
    }

    public Builder setVisibility(NestedSet<PackageGroupContents> visibility) {
      this.visibility = visibility;
      return this;
    }

    /**
     * Sets the prerequisites and checks their visibility. It also generates appropriate error or
     * warning messages and sets the error flag as appropriate.
     */
    public Builder setPrerequisites(
        OrderedSetMultimap<Attribute, ConfiguredTargetAndData> prerequisiteMap) {
      this.prerequisiteMap = Preconditions.checkNotNull(prerequisiteMap);
      return this;
    }

    /**
     * Adds attributes which are defined by an Aspect (and not by RuleClass).
     */
    public Builder setAspectAttributes(Map<String, Attribute> aspectAttributes) {
      this.aspectAttributes = ImmutableMap.copyOf(aspectAttributes);
      return this;
    }

    /**
     * Sets the configuration conditions needed to determine which paths to follow for this
     * rule's configurable attributes.
     */
    public Builder setConfigConditions(
        ImmutableMap<Label, ConfigMatchingProvider> configConditions) {
      this.configConditions = Preconditions.checkNotNull(configConditions);
      return this;
    }

    /**
     * Sets the fragment that can be legally accessed even when not explicitly declared.
     */
    public Builder setUniversalFragments(
        ImmutableList<Class<? extends BuildConfiguration.Fragment>> fragments) {
      // TODO(bazel-team): Add this directly to ConfigurationFragmentPolicy, so we
      // don't need separate logic specifically for checking this fragment. The challenge is
      // that we need RuleClassProvider to figure out what this fragment is, and not every
      // call state that creates ConfigurationFragmentPolicy has access to that.
      this.universalFragments = fragments;
      return this;
    }

    /** Sets the {@link ToolchainContext} used to access toolchains used by this rule. */
    public Builder setToolchainContext(ToolchainContext toolchainContext) {
      this.toolchainContext = toolchainContext;
      return this;
    }

    public Builder setConstraintSemantics(ConstraintSemantics constraintSemantics) {
      this.constraintSemantics = constraintSemantics;
      return this;
    }

    private boolean validateFilesetEntry(FilesetEntry filesetEntry, ConfiguredTargetAndData src) {
      NestedSet<Artifact> filesToBuild =
          src.getConfiguredTarget().getProvider(FileProvider.class).getFilesToBuild();
      if (filesToBuild.isSingleton() && Iterables.getOnlyElement(filesToBuild).isFileset()) {
        return true;
      }

      if (filesetEntry.isSourceFileset()) {
        return true;
      }

      Target srcTarget = src.getTarget();
      if (!(srcTarget instanceof FileTarget)) {
        attributeError("entries", String.format(
            "Invalid 'srcdir' target '%s'. Must be another Fileset or package",
            srcTarget.getLabel()));
        return false;
      }

      if (srcTarget instanceof OutputFile) {
        attributeWarning("entries", String.format("'srcdir' target '%s' is not an input file. "
            + "This forces the Fileset to be executed unconditionally",
            srcTarget.getLabel()));
      }

      return true;
    }

    /**
     * Determines and returns a map from attribute name to list of configured fileset entries, based
     * on a PrerequisiteMap instance.
     */
    private ListMultimap<String, ConfiguredFilesetEntry> createFilesetEntryMap(
        final Rule rule, ImmutableMap<Label, ConfigMatchingProvider> configConditions) {
      if (!target.getTargetKind().equals("Fileset rule")) {
        return ImmutableSortedKeyListMultimap.<String, ConfiguredFilesetEntry>builder().build();
      }

      final ImmutableSortedKeyListMultimap.Builder<String, ConfiguredFilesetEntry> mapBuilder =
          ImmutableSortedKeyListMultimap.builder();
      for (Attribute attr : rule.getAttributes()) {
        if (attr.getType() != BuildType.FILESET_ENTRY_LIST) {
          continue;
        }
        String attributeName = attr.getName();
        Map<Label, ConfiguredTargetAndData> ctMap = new HashMap<>();
        for (ConfiguredTargetAndData prerequisite : prerequisiteMap.get(attr)) {
          ctMap.put(
              AliasProvider.getDependencyLabel(prerequisite.getConfiguredTarget()), prerequisite);
        }
        List<FilesetEntry> entries = ConfiguredAttributeMapper.of(rule, configConditions)
            .get(attributeName, BuildType.FILESET_ENTRY_LIST);
        for (FilesetEntry entry : entries) {
          if (entry.getFiles() == null) {
            Label label = entry.getSrcLabel();
            ConfiguredTargetAndData src = ctMap.get(label);
            if (!validateFilesetEntry(entry, src)) {
              continue;
            }

            mapBuilder.put(
                attributeName, new ConfiguredFilesetEntry(entry, src.getConfiguredTarget()));
          } else {
            ImmutableList.Builder<TransitiveInfoCollection> files = ImmutableList.builder();
            for (Label file : entry.getFiles()) {
              files.add(ctMap.get(file).getConfiguredTarget());
            }
            mapBuilder.put(attributeName, new ConfiguredFilesetEntry(entry, files.build()));
          }
        }
      }
      return mapBuilder.build();
    }

    /** Determines and returns a map from attribute name to list of configured targets. */
    private ImmutableSortedKeyListMultimap<String, ConfiguredTargetAndData> createTargetMap() {
      ImmutableSortedKeyListMultimap.Builder<String, ConfiguredTargetAndData> mapBuilder =
          ImmutableSortedKeyListMultimap.builder();

      for (Map.Entry<Attribute, Collection<ConfiguredTargetAndData>> entry :
          prerequisiteMap.asMap().entrySet()) {
        Attribute attribute = entry.getKey();
        if (attribute == null) {
          continue;
        }

        if (attribute.isSingleArtifact() && entry.getValue().size() > 1) {
          attributeError(attribute.getName(), "must contain a single dependency");
          continue;
        }

        if (attribute.isSilentRuleClassFilter()) {
          Predicate<RuleClass> filter = attribute.getAllowedRuleClassesPredicate();
          for (ConfiguredTargetAndData configuredTarget : entry.getValue()) {
            Target prerequisiteTarget = configuredTarget.getTarget();
            if ((prerequisiteTarget instanceof Rule)
                && filter.apply(((Rule) prerequisiteTarget).getRuleClassObject())) {
              validateDirectPrerequisite(attribute, configuredTarget);
              mapBuilder.put(attribute.getName(), configuredTarget);
            }
          }
        } else {
          for (ConfiguredTargetAndData configuredTarget : entry.getValue()) {
            validateDirectPrerequisite(attribute, configuredTarget);
            mapBuilder.put(attribute.getName(), configuredTarget);
          }
        }
      }
      return mapBuilder.build();
    }

    /**
     * Post a raw event to the analysis environment's event handler. This circumvents normal
     * error and warning reporting functionality to post events, and should only be used
     * in rare cases where a custom event needs to be handled.
     */
    public void post(Postable event) {
      env.getEventHandler().post(event);
    }

    @Override
    public void ruleError(String message) {
      reporter.ruleError(message);
    }

    @Override
    public void attributeError(String attrName, String message) {
      reporter.attributeError(attrName, message);
    }

    @Override
    public void ruleWarning(String message) {
      reporter.ruleWarning(message);
    }

    @Override
    public void attributeWarning(String attrName, String message) {
      reporter.attributeWarning(attrName, message);
    }

    @Override
    public boolean hasErrors() {
      return reporter.hasErrors();
    }

    private String badPrerequisiteMessage(
        ConfiguredTargetAndData prerequisite, String reason, boolean isWarning) {
      String msgReason = reason != null ? " (" + reason + ")" : "";
      if (isWarning) {
        return String.format(
            "%s is unexpected here%s; continuing anyway",
            AliasProvider.describeTargetWithAliases(prerequisite, TargetMode.WITH_KIND),
            msgReason);
      }
      return String.format("%s is misplaced here%s",
          AliasProvider.describeTargetWithAliases(prerequisite, TargetMode.WITH_KIND), msgReason);
    }

    private void reportBadPrerequisite(
        Attribute attribute,
        ConfiguredTargetAndData prerequisite,
        String reason,
        boolean isWarning) {
      String message = badPrerequisiteMessage(prerequisite, reason, isWarning);
      if (isWarning) {
        attributeWarning(attribute.getName(), message);
      } else {
        attributeError(attribute.getName(), message);
      }
    }

    private void validateDirectPrerequisiteType(
        ConfiguredTargetAndData prerequisite, Attribute attribute) {
      Target prerequisiteTarget = prerequisite.getTarget();
      Label prerequisiteLabel = prerequisiteTarget.getLabel();

      if (prerequisiteTarget instanceof Rule) {
        Rule prerequisiteRule = (Rule) prerequisiteTarget;

        String reason =
            attribute
                .getValidityPredicate()
                .checkValid(target.getAssociatedRule(), prerequisiteRule);
        if (reason != null) {
          reportBadPrerequisite(attribute, prerequisite, reason, false);
        }
      }

      if (prerequisiteTarget instanceof Rule) {
        validateRuleDependency(prerequisite, attribute);
      } else if (prerequisiteTarget instanceof FileTarget) {
        if (attribute.isStrictLabelCheckingEnabled()) {
          if (!attribute.getAllowedFileTypesPredicate()
              .apply(((FileTarget) prerequisiteTarget).getFilename())) {
            if (prerequisiteTarget instanceof InputFile
                && !((InputFile) prerequisiteTarget).getPath().exists()) {
              // Misplaced labels, no corresponding target exists
              if (attribute.getAllowedFileTypesPredicate().isNone()
                  && !((InputFile) prerequisiteTarget).getFilename().contains(".")) {
                // There are no allowed files in the attribute but it's not a valid rule,
                // and the filename doesn't contain a dot --> probably a misspelled rule
                attributeError(attribute.getName(),
                    "rule '" + prerequisiteLabel + "' does not exist");
              } else {
                attributeError(attribute.getName(),
                    "target '" + prerequisiteLabel + "' does not exist");
              }
            } else {
              // The file exists but has a bad extension
              reportBadPrerequisite(attribute, prerequisite,
                  "expected " + attribute.getAllowedFileTypesPredicate(), false);
            }
          }
        }
      }
    }

    /** Returns whether the context being constructed is for the evaluation of an aspect. */
    public boolean forAspect() {
      return !aspects.isEmpty();
    }

    public Rule getRule() {
      return target.getAssociatedRule();
    }

    /**
     * Returns a rule class name suitable for log messages, including an aspect name if applicable.
     */
    public String getRuleClassNameForLogging() {
      if (aspects.isEmpty()) {
        return target.getAssociatedRule().getRuleClass();
      }

      return Joiner.on(",")
              .join(aspects.stream().map(a -> a.getDescriptor()).collect(Collectors.toList()))
          + " aspect on "
          + target.getAssociatedRule().getRuleClass();
    }

    public BuildConfiguration getConfiguration() {
      return configuration;
    }

    /**
     * @return true if {@code rule} is visible from {@code prerequisite}.
     *
     * <p>This only computes the logic as implemented by the visibility system. The final decision
     * whether a dependency is allowed is made by
     * {@link ConfiguredRuleClassProvider.PrerequisiteValidator}, who is supposed to call this
     * method to determine whether a dependency is allowed as per visibility rules.
     */
    public boolean isVisible(TransitiveInfoCollection prerequisite) {
      return RuleContext.isVisible(target.getAssociatedRule(), prerequisite);
    }

    private void validateDirectPrerequisiteFileTypes(
        ConfiguredTargetAndData prerequisite, Attribute attribute) {
      if (attribute.isSkipAnalysisTimeFileTypeCheck()) {
        return;
      }
      FileTypeSet allowedFileTypes = attribute.getAllowedFileTypesPredicate();
      if (allowedFileTypes == null) {
        // It's not a label or label_list attribute.
        return;
      }
      if (allowedFileTypes == FileTypeSet.ANY_FILE && !attribute.isNonEmpty()
          && !attribute.isSingleArtifact()) {
        return;
      }

      // If we allow any file we still need to check if there are actually files generated
      // Note that this check only runs for ANY_FILE predicates if the attribute is NON_EMPTY
      // or SINGLE_ARTIFACT
      // If we performed this check when allowedFileTypes == NO_FILE this would
      // always throw an error in those cases
      if (allowedFileTypes != FileTypeSet.NO_FILE) {
        Iterable<Artifact> artifacts =
            prerequisite.getConfiguredTarget().getProvider(FileProvider.class).getFilesToBuild();
        if (attribute.isSingleArtifact() && Iterables.size(artifacts) != 1) {
          attributeError(
              attribute.getName(),
              "'" + prerequisite.getTarget().getLabel() + "' must produce a single file");
          return;
        }
        for (Artifact sourceArtifact : artifacts) {
          if (allowedFileTypes.apply(sourceArtifact.getFilename())) {
            return;
          }
          if (sourceArtifact.isTreeArtifact()) {
            return;
          }
        }
        attributeError(
            attribute.getName(),
            "'"
                + prerequisite.getTarget().getLabel()
                + "' does not produce any "
                + getRuleClassNameForLogging()
                + " "
                + attribute.getName()
                + " files (expected "
                + allowedFileTypes
                + ")");
      }
    }

    /**
     * Because some rules still have to use allowedRuleClasses to do rule dependency validation. A
     * dependency is valid if it is from a rule in allowedRuledClasses, OR if all of the providers
     * in requiredProviders are provided by the target.
     */
    private void validateRuleDependency(ConfiguredTargetAndData prerequisite, Attribute attribute) {

      Set<String> unfulfilledRequirements = new LinkedHashSet<>();
      if (checkRuleDependencyClass(prerequisite, attribute, unfulfilledRequirements)) {
        return;
      }

      if (checkRuleDependencyClassWarnings(prerequisite, attribute)) {
        return;
      }

      if (checkRuleDependencyMandatoryProviders(prerequisite, attribute, unfulfilledRequirements)) {
        return;
      }

      // not allowed rule class and some mandatory providers missing => reject.
      if (!unfulfilledRequirements.isEmpty()) {
        attributeError(
            attribute.getName(), StringUtil.joinEnglishList(unfulfilledRequirements, "and"));
      }
    }

    /** Check if prerequisite should be allowed based on its rule class. */
    private boolean checkRuleDependencyClass(
        ConfiguredTargetAndData prerequisite,
        Attribute attribute,
        Set<String> unfulfilledRequirements) {
      if (attribute.getAllowedRuleClassesPredicate() != Predicates.<RuleClass>alwaysTrue()) {
        if (attribute
            .getAllowedRuleClassesPredicate()
            .apply(((Rule) prerequisite.getTarget()).getRuleClassObject())) {
          // prerequisite has an allowed rule class => accept.
          return true;
        }
        // remember that the rule class that was not allowed;
        // but maybe prerequisite provides required providers? do not reject yet.
        unfulfilledRequirements.add(
            badPrerequisiteMessage(
                prerequisite,
                "expected " + attribute.getAllowedRuleClassesPredicate(),
                false));
      }
      return false;
    }

    /**
     * Check if prerequisite should be allowed with warning based on its rule class.
     *
     * <p>If yes, also issues said warning.
     */
    private boolean checkRuleDependencyClassWarnings(
        ConfiguredTargetAndData prerequisite, Attribute attribute) {
      if (attribute
          .getAllowedRuleClassesWarningPredicate()
          .apply(((Rule) prerequisite.getTarget()).getRuleClassObject())) {
        Predicate<RuleClass> allowedRuleClasses = attribute.getAllowedRuleClassesPredicate();
        reportBadPrerequisite(
            attribute,
            prerequisite,
            allowedRuleClasses == Predicates.<RuleClass>alwaysTrue()
                ? null
                : "expected " + allowedRuleClasses,
            true);
        // prerequisite has a rule class allowed with a warning => accept, emitting a warning.
        return true;
      }
      return false;
    }

    /** Check if prerequisite should be allowed based on required providers on the attribute. */
    private boolean checkRuleDependencyMandatoryProviders(
        ConfiguredTargetAndData prerequisite,
        Attribute attribute,
        Set<String> unfulfilledRequirements) {
      RequiredProviders requiredProviders = attribute.getRequiredProviders();

      if (requiredProviders.acceptsAny()) {
        // If no required providers specified, we do not know if we should accept.
        return false;
      }

      if (prerequisite.getConfiguredTarget().satisfies(requiredProviders)) {
        return true;
      }

      unfulfilledRequirements.add(
          String.format(
              "'%s' does not have mandatory providers: %s",
              prerequisite.getTarget().getLabel(),
              prerequisite
                  .getConfiguredTarget()
                  .missingProviders(requiredProviders)
                  .getDescription()));

      return false;
    }

    private void validateDirectPrerequisite(
        Attribute attribute, ConfiguredTargetAndData prerequisite) {
      validateDirectPrerequisiteType(prerequisite, attribute);
      validateDirectPrerequisiteFileTypes(prerequisite, attribute);
      if (attribute.performPrereqValidatorCheck()) {
        prerequisiteValidator.validate(this, prerequisite, attribute);
      }
    }
  }

  /** Helper class for reporting errors and warnings. */
  private static final class ErrorReporter extends EventHandlingErrorReporter
      implements RuleErrorConsumer {
    private final Rule rule;

    ErrorReporter(AnalysisEnvironment env, Rule rule, String ruleClassNameForLogging) {
      super(ruleClassNameForLogging, env);
      this.rule = rule;
    }

    @Override
    protected String getMacroMessageAppendix(String attrName) {
      return rule.wasCreatedByMacro()
          ? String.format(
              ". Since this rule was created by the macro '%s', the error might have been "
                  + "caused by the macro implementation in %s",
              getGeneratorFunction(), rule.getAttributeLocationWithoutMacro(attrName))
          : "";
    }

    private String getGeneratorFunction() {
      return (String) rule.getAttributeContainer().getAttr("generator_function");
    }

    @Override
    protected Label getLabel() {
      return rule.getLabel();
    }

    @Override
    protected Location getRuleLocation() {
      return rule.getLocation();
    }

    @Override
    protected Location getAttributeLocation(String attrName) {
      return rule.getAttributeLocation(attrName);
    }
  }

  /**
   * Implementation of an error consumer which does not post any events, saves rule and attribute
   * errors for future consumption, and drops warnings.
   */
  public static final class SuppressingErrorReporter implements RuleErrorConsumer {
    private final List<String> errorMessages = Lists.newArrayList();

    @Override
    public void ruleWarning(String message) {}

    @Override
    public void ruleError(String message) {
      errorMessages.add(message);
    }

    @Override
    public void attributeWarning(String attrName, String message) {}

    @Override
    public void attributeError(String attrName, String message) {
      errorMessages.add(message);
    }

    @Override
    public boolean hasErrors() {
      return !errorMessages.isEmpty();
    }

    /**
     * Returns the error message strings reported to this error consumer.
     */
    public List<String> getErrorMessages() {
      return errorMessages;
    }
  }
}
