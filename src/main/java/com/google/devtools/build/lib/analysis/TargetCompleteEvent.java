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

import static com.google.devtools.build.lib.buildeventstream.TestFileNameConstants.BASELINE_COVERAGE;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.ArtifactPathResolver;
import com.google.devtools.build.lib.actions.EventReportingArtifacts;
import com.google.devtools.build.lib.analysis.TopLevelArtifactHelper.ArtifactsInOutputGroup;
import com.google.devtools.build.lib.analysis.config.BuildConfiguration;
import com.google.devtools.build.lib.analysis.configuredtargets.RuleConfiguredTarget;
import com.google.devtools.build.lib.analysis.test.InstrumentedFilesInfo;
import com.google.devtools.build.lib.analysis.test.TestConfiguration;
import com.google.devtools.build.lib.analysis.test.TestProvider;
import com.google.devtools.build.lib.buildeventstream.ArtifactGroupNamer;
import com.google.devtools.build.lib.buildeventstream.BuildEvent;
import com.google.devtools.build.lib.buildeventstream.BuildEvent.LocalFile.LocalFileType;
import com.google.devtools.build.lib.buildeventstream.BuildEventContext;
import com.google.devtools.build.lib.buildeventstream.BuildEventId;
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos;
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.File;
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.OutputGroup;
import com.google.devtools.build.lib.buildeventstream.BuildEventWithConfiguration;
import com.google.devtools.build.lib.buildeventstream.BuildEventWithOrderConstraint;
import com.google.devtools.build.lib.buildeventstream.GenericBuildEvent;
import com.google.devtools.build.lib.causes.Cause;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.collect.nestedset.NestedSetView;
import com.google.devtools.build.lib.collect.nestedset.Order;
import com.google.devtools.build.lib.packages.AttributeMap;
import com.google.devtools.build.lib.packages.ConfiguredAttributeMapper;
import com.google.devtools.build.lib.packages.Rule;
import com.google.devtools.build.lib.packages.TestTimeout;
import com.google.devtools.build.lib.rules.AliasConfiguredTarget;
import com.google.devtools.build.lib.skyframe.ConfiguredTargetAndData;
import com.google.devtools.build.lib.skyframe.ConfiguredTargetKey;
import com.google.devtools.build.lib.syntax.Type;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.skyframe.SkyValue;
import java.util.Collection;
import java.util.function.Function;
import javax.annotation.Nullable;

/** This event is fired as soon as a target is either built or fails. */
public final class TargetCompleteEvent
    implements SkyValue,
        BuildEventWithOrderConstraint,
        EventReportingArtifacts,
        BuildEventWithConfiguration {

  /** Lightweight data needed about the configured target in this event. */
  public static class ExecutableTargetData {
    @Nullable private final Path runfilesDirectory;
    @Nullable private final Artifact executable;

    private ExecutableTargetData(ConfiguredTargetAndData targetAndData) {
      FilesToRunProvider provider =
          targetAndData.getConfiguredTarget().getProvider(FilesToRunProvider.class);
      if (provider != null) {
        this.executable = provider.getExecutable();
        if (null != provider.getRunfilesSupport()) {
          this.runfilesDirectory = provider.getRunfilesSupport().getRunfilesDirectory();
        } else {
          this.runfilesDirectory = null;
        }
      } else {
        this.executable = null;
        this.runfilesDirectory = null;
      }
    }

    @Nullable
    public Path getRunfilesDirectory() {
      return runfilesDirectory;
    }

    @Nullable
    public Artifact getExecutable() {
      return executable;
    }
  }

  private final Label label;
  private final ConfiguredTargetKey configuredTargetKey;
  private final NestedSet<Cause> rootCauses;
  private final ImmutableList<BuildEventId> postedAfter;
  private final ArtifactPathResolver pathResolver;
  private final NestedSet<ArtifactsInOutputGroup> outputs;
  private final NestedSet<Artifact> baselineCoverageArtifacts;
  private final Label aliasLabel;
  private final boolean isTest;
  @Nullable private final Long testTimeoutSeconds;
  @Nullable private final TestProvider.TestParams testParams;
  private final BuildEvent configurationEvent;
  private final BuildEventId configEventId;
  private final Iterable<String> tags;
  private final ExecutableTargetData executableTargetData;

  private TargetCompleteEvent(
      ConfiguredTargetAndData targetAndData,
      NestedSet<Cause> rootCauses,
      ArtifactPathResolver pathResolver,
      NestedSet<ArtifactsInOutputGroup> outputs,
      boolean isTest) {
    this.rootCauses =
        (rootCauses == null) ? NestedSetBuilder.<Cause>emptySet(Order.STABLE_ORDER) : rootCauses;
    this.executableTargetData = new ExecutableTargetData(targetAndData);
    ImmutableList.Builder<BuildEventId> postedAfterBuilder = ImmutableList.builder();
    this.label = targetAndData.getConfiguredTarget().getLabel();
    if (targetAndData.getConfiguredTarget() instanceof AliasConfiguredTarget) {
      this.aliasLabel =
          ((AliasConfiguredTarget) targetAndData.getConfiguredTarget()).getOriginalLabel();
    } else {
      this.aliasLabel = label;
    }
    this.configuredTargetKey =
        ConfiguredTargetKey.of(
            targetAndData.getConfiguredTarget(), targetAndData.getConfiguration());
    postedAfterBuilder.add(BuildEventId.targetConfigured(aliasLabel));
    for (Cause cause : getRootCauses()) {
      postedAfterBuilder.add(BuildEventId.fromCause(cause));
    }
    this.postedAfter = postedAfterBuilder.build();
    this.pathResolver = pathResolver;
    this.outputs = outputs;
    this.isTest = isTest;
    this.testTimeoutSeconds = isTest ? getTestTimeoutSeconds(targetAndData) : null;
    BuildConfiguration configuration = targetAndData.getConfiguration();
    this.configEventId =
        configuration != null ? configuration.getEventId() : BuildEventId.nullConfigurationId();
    this.configurationEvent = configuration != null ? configuration.toBuildEvent() : null;
    this.testParams =
        isTest
            ? targetAndData.getConfiguredTarget().getProvider(TestProvider.class).getTestParams()
            : null;
    InstrumentedFilesInfo instrumentedFilesProvider =
        targetAndData.getConfiguredTarget().get(InstrumentedFilesInfo.SKYLARK_CONSTRUCTOR);
    if (instrumentedFilesProvider == null) {
      this.baselineCoverageArtifacts = null;
    } else {
      NestedSet<Artifact> baselineCoverageArtifacts =
          instrumentedFilesProvider.getBaselineCoverageArtifacts();
      if (!baselineCoverageArtifacts.isEmpty()) {
        this.baselineCoverageArtifacts = baselineCoverageArtifacts;
      } else {
        this.baselineCoverageArtifacts = null;
      }
    }
    // For tags, we are only interested in targets that are rules.
    if (!(targetAndData.getConfiguredTarget() instanceof RuleConfiguredTarget)) {
      this.tags = ImmutableList.of();
    } else {
      AttributeMap attributes =
          ConfiguredAttributeMapper.of(
              (Rule) targetAndData.getTarget(),
              ((RuleConfiguredTarget) targetAndData.getConfiguredTarget()).getConfigConditions());
      // Every rule (implicitly) has a "tags" attribute.
      this.tags = attributes.get("tags", Type.STRING_LIST);
    }
  }

  /** Construct a successful target completion event. */
  public static TargetCompleteEvent successfulBuild(
      ConfiguredTargetAndData ct,
      ArtifactPathResolver pathResolver,
      NestedSet<ArtifactsInOutputGroup> outputs) {
    return new TargetCompleteEvent(ct, null, pathResolver, outputs, false);
  }

  /** Construct a successful target completion event for a target that will be tested. */
  public static TargetCompleteEvent successfulBuildSchedulingTest(
      ConfiguredTargetAndData ct,
      ArtifactPathResolver pathResolver,
      NestedSet<ArtifactsInOutputGroup> outputs) {
    return new TargetCompleteEvent(ct, null, pathResolver, outputs, true);
  }

  /**
   * Construct a target completion event for a failed target, with the given non-empty root causes.
   */
  public static TargetCompleteEvent createFailed(
      ConfiguredTargetAndData ct, NestedSet<Cause> rootCauses) {
    Preconditions.checkArgument(!Iterables.isEmpty(rootCauses));
    return new TargetCompleteEvent(
        ct,
        rootCauses,
        ArtifactPathResolver.IDENTITY,
        NestedSetBuilder.emptySet(Order.STABLE_ORDER),
        false);
  }

  /** Returns the label of the target associated with the event. */
  public Label getLabel() {
    return label;
  }

  public ConfiguredTargetKey getConfiguredTargetKey() {
    return configuredTargetKey;
  }

  public ExecutableTargetData getExecutableTargetData() {
    return executableTargetData;
  }

  /** Determines whether the target has failed or succeeded. */
  public boolean failed() {
    return !rootCauses.isEmpty();
  }

  /** Get the root causes of the target. May be empty. */
  public Iterable<Cause> getRootCauses() {
    return rootCauses;
  }

  public Iterable<Artifact> getLegacyFilteredImportantArtifacts() {
    // TODO(ulfjack): This duplicates code in ArtifactsToBuild.
    NestedSetBuilder<Artifact> builder = new NestedSetBuilder<>(outputs.getOrder());
    for (ArtifactsInOutputGroup artifactsInOutputGroup : outputs) {
      if (artifactsInOutputGroup.areImportant()) {
        builder.addTransitive(artifactsInOutputGroup.getArtifacts());
      }
    }
    return Iterables.filter(
        builder.build(),
        (artifact) -> !artifact.isSourceArtifact() && !artifact.isMiddlemanArtifact());
  }

  @Override
  public BuildEventId getEventId() {
    return BuildEventId.targetCompleted(aliasLabel, configEventId);
  }

  @Override
  public Collection<BuildEventId> getChildrenEvents() {
    ImmutableList.Builder<BuildEventId> childrenBuilder = ImmutableList.builder();
    for (Cause cause : getRootCauses()) {
      childrenBuilder.add(BuildEventId.fromCause(cause));
    }
    if (isTest) {
      // For tests, announce all the test actions that will minimally happen (except for
      // interruption). If after the result of a test action another attempt is necessary,
      // it will be announced with the action that made the new attempt necessary.
      Label label = getLabel();
      for (int run = 0; run < Math.max(testParams.getRuns(), 1); run++) {
        for (int shard = 0; shard < Math.max(testParams.getShards(), 1); shard++) {
          childrenBuilder.add(BuildEventId.testResult(label, run, shard, configEventId));
        }
      }
      childrenBuilder.add(BuildEventId.testSummary(label, configEventId));
    }
    return childrenBuilder.build();
  }

  // TODO(aehlig): remove as soon as we managed to get rid of the deprecated "important_output"
  // field.
  private static void addImportantOutputs(
      ArtifactPathResolver pathResolver,
      BuildEventStreamProtos.TargetComplete.Builder builder,
      BuildEventContext converters,
      Iterable<Artifact> artifacts) {
    addImportantOutputs(
        pathResolver, builder, Artifact::getRootRelativePathString, converters, artifacts);
  }

  private static void addImportantOutputs(
      ArtifactPathResolver pathResolver,
      BuildEventStreamProtos.TargetComplete.Builder builder,
      Function<Artifact, String> artifactNameFunction,
      BuildEventContext converters,
      Iterable<Artifact> artifacts) {
    for (Artifact artifact : artifacts) {
      String name = artifactNameFunction.apply(artifact);
      String uri = converters.pathConverter().apply(pathResolver.toPath(artifact));
      if (uri != null) {
        builder.addImportantOutput(File.newBuilder().setName(name).setUri(uri).build());
      }
    }
  }

  @Override
  public Collection<LocalFile> referencedLocalFiles() {
    ImmutableList.Builder<LocalFile> builder = ImmutableList.builder();
    for (ArtifactsInOutputGroup group : outputs) {
      if (group.areImportant()) {
        for (Artifact artifact : group.getArtifacts()) {
          builder.add(new LocalFile(pathResolver.toPath(artifact), LocalFileType.OUTPUT));
        }
      }
    }
    if (baselineCoverageArtifacts != null) {
      for (Artifact artifact : baselineCoverageArtifacts) {
        builder.add(new LocalFile(pathResolver.toPath(artifact), LocalFileType.OUTPUT));
      }
    }
    return builder.build();
  }

  @Override
  public BuildEventStreamProtos.BuildEvent asStreamProto(BuildEventContext converters) {
    BuildEventStreamProtos.TargetComplete.Builder builder =
        BuildEventStreamProtos.TargetComplete.newBuilder();

    builder.setSuccess(!failed());
    builder.addAllTag(getTags());
    builder.addAllOutputGroup(getOutputFilesByGroup(converters.artifactGroupNamer()));

    if (isTest) {
      builder.setTestTimeoutSeconds(testTimeoutSeconds);
    }

    // TODO(aehlig): remove direct reporting of artifacts as soon as clients no longer
    // need it.
    if (converters.getOptions().legacyImportantOutputs) {
      addImportantOutputs(pathResolver, builder, converters, getLegacyFilteredImportantArtifacts());
      if (baselineCoverageArtifacts != null) {
        addImportantOutputs(
            pathResolver,
            builder,
            (artifact -> BASELINE_COVERAGE),
            converters,
            baselineCoverageArtifacts);
      }
    }

    BuildEventStreamProtos.TargetComplete complete = builder.build();
    return GenericBuildEvent.protoChaining(this).setCompleted(complete).build();
  }

  @Override
  public Collection<BuildEventId> postedAfter() {
    return postedAfter;
  }

  @Override
  public ReportedArtifacts reportedArtifacts() {
    ImmutableSet.Builder<NestedSet<Artifact>> builder = ImmutableSet.builder();
    for (ArtifactsInOutputGroup artifactsInGroup : outputs) {
      builder.add(artifactsInGroup.getArtifacts());
    }
    if (baselineCoverageArtifacts != null) {
      builder.add(baselineCoverageArtifacts);
    }
    return new ReportedArtifacts(builder.build(), pathResolver);
  }

  @Override
  public Collection<BuildEvent> getConfigurations() {
    return configurationEvent != null ? ImmutableList.of(configurationEvent) : ImmutableList.of();
  }

  private Iterable<String> getTags() {
    return tags;
  }

  private Iterable<OutputGroup> getOutputFilesByGroup(ArtifactGroupNamer namer) {
    ImmutableList.Builder<OutputGroup> groups = ImmutableList.builder();
    for (ArtifactsInOutputGroup artifactsInOutputGroup : outputs) {
      OutputGroup.Builder groupBuilder = OutputGroup.newBuilder();
      groupBuilder.setName(artifactsInOutputGroup.getOutputGroup());
      groupBuilder.addFileSets(
          namer.apply(
              (new NestedSetView<Artifact>(artifactsInOutputGroup.getArtifacts())).identifier()));
      groups.add(groupBuilder.build());
    }
    if (baselineCoverageArtifacts != null) {
      groups.add(
          OutputGroup.newBuilder()
              .setName(BASELINE_COVERAGE)
              .addFileSets(
                  namer.apply(
                      (new NestedSetView<Artifact>(baselineCoverageArtifacts).identifier())))
              .build());
    }
    return groups.build();
  }

  /**
   * Returns timeout value in seconds that should be used for all test actions under this configured
   * target. We always use the "categorical timeouts" which are based on the --test_timeout flag. A
   * rule picks its timeout but ends up with the same effective value as all other rules in that
   * category and configuration.
   */
  private static Long getTestTimeoutSeconds(ConfiguredTargetAndData targetAndData) {
    BuildConfiguration configuration = targetAndData.getConfiguration();
    Rule associatedRule = targetAndData.getTarget().getAssociatedRule();
    TestTimeout categoricalTimeout = TestTimeout.getTestTimeout(associatedRule);
    return configuration
        .getFragment(TestConfiguration.class)
        .getTestTimeout()
        .get(categoricalTimeout)
        .getSeconds();
  }
}
