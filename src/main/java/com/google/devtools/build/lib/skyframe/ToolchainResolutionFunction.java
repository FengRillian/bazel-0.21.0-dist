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

package com.google.devtools.build.lib.skyframe;

import static java.util.stream.Collectors.joining;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.analysis.PlatformOptions;
import com.google.devtools.build.lib.analysis.config.BuildConfiguration;
import com.google.devtools.build.lib.analysis.platform.ConstraintCollection;
import com.google.devtools.build.lib.analysis.platform.ConstraintSettingInfo;
import com.google.devtools.build.lib.analysis.platform.ConstraintValueInfo;
import com.google.devtools.build.lib.analysis.platform.DeclaredToolchainInfo;
import com.google.devtools.build.lib.analysis.platform.PlatformInfo;
import com.google.devtools.build.lib.analysis.platform.ToolchainTypeInfo;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.EventHandler;
import com.google.devtools.build.lib.packages.NoSuchThingException;
import com.google.devtools.build.lib.skyframe.PlatformLookupUtil.InvalidPlatformException;
import com.google.devtools.build.lib.skyframe.RegisteredToolchainsFunction.InvalidToolchainLabelException;
import com.google.devtools.build.skyframe.SkyFunction;
import com.google.devtools.build.skyframe.SkyFunctionException;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;

/** {@link SkyFunction} which performs toolchain resolution for a class of rules. */
public class ToolchainResolutionFunction implements SkyFunction {

  @Nullable
  @Override
  public SkyValue compute(SkyKey skyKey, Environment env)
      throws ToolchainResolutionFunctionException, InterruptedException {
    ToolchainResolutionValue.Key key = (ToolchainResolutionValue.Key) skyKey.argument();

    // This call could be combined with the call below, but this SkyFunction is evaluated so rarely
    // it's not worth optimizing.
    BuildConfigurationValue value = (BuildConfigurationValue) env.getValue(key.configurationKey());
    if (env.valuesMissing()) {
      return null;
    }
    BuildConfiguration configuration = value.getConfiguration();

    // Get all toolchains.
    RegisteredToolchainsValue toolchains;
    try {
      toolchains =
          (RegisteredToolchainsValue)
              env.getValueOrThrow(
                  RegisteredToolchainsValue.key(key.configurationKey()),
                  InvalidToolchainLabelException.class);
      if (toolchains == null) {
        return null;
      }
    } catch (InvalidToolchainLabelException e) {
      throw new ToolchainResolutionFunctionException(e);
    }

    // Find the right one.
    boolean debug = configuration.getOptions().get(PlatformOptions.class).toolchainResolutionDebug;
    return resolveConstraints(
        key.toolchainTypeLabel(),
        key.availableExecutionPlatformKeys(),
        key.targetPlatformKey(),
        toolchains.registeredToolchains(),
        env,
        debug ? env.getListener() : null);
  }

  /**
   * Given the available execution platforms and toolchains, find the set of platform, toolchain
   * pairs that are compatible a) with each other, and b) with the toolchain type and target
   * platform.
   */
  @Nullable
  private static ToolchainResolutionValue resolveConstraints(
      Label toolchainTypeLabel,
      List<ConfiguredTargetKey> availableExecutionPlatformKeys,
      ConfiguredTargetKey targetPlatformKey,
      ImmutableList<DeclaredToolchainInfo> toolchains,
      Environment env,
      @Nullable EventHandler eventHandler)
      throws ToolchainResolutionFunctionException, InterruptedException {

    // Load the PlatformInfo needed to check constraints.
    Map<ConfiguredTargetKey, PlatformInfo> platforms;
    try {

      platforms =
          PlatformLookupUtil.getPlatformInfo(
              new ImmutableList.Builder<ConfiguredTargetKey>()
                  .add(targetPlatformKey)
                  .addAll(availableExecutionPlatformKeys)
                  .build(),
              env);
      if (env.valuesMissing()) {
        return null;
      }
    } catch (InvalidPlatformException e) {
      throw new ToolchainResolutionFunctionException(e);
    }

    PlatformInfo targetPlatform = platforms.get(targetPlatformKey);

    // Platforms may exist multiple times in availableExecutionPlatformKeys. The Set lets this code
    // check whether a platform has already been seen during processing.
    Set<ConfiguredTargetKey> platformKeysSeen = new HashSet<>();
    ImmutableMap.Builder<ConfiguredTargetKey, Label> builder = ImmutableMap.builder();
    ToolchainTypeInfo toolchainType = null;

    debugMessage(eventHandler, "Looking for toolchain of type %s...", toolchainTypeLabel);
    for (DeclaredToolchainInfo toolchain : toolchains) {
      // Make sure the type matches.
      if (!toolchain.toolchainType().typeLabel().equals(toolchainTypeLabel)) {
        continue;
      }
      debugMessage(eventHandler, "  Considering toolchain %s...", toolchain.toolchainLabel());

      // Make sure the target platform matches.
      if (!checkConstraints(
          eventHandler, toolchain.targetConstraints(), "target", targetPlatform)) {
        debugMessage(
            eventHandler,
            "  Rejected toolchain %s, because of target platform mismatch",
            toolchain.toolchainLabel());
        continue;
      }

      // Find the matching execution platforms.
      for (ConfiguredTargetKey executionPlatformKey : availableExecutionPlatformKeys) {
        PlatformInfo executionPlatform = platforms.get(executionPlatformKey);
        if (!checkConstraints(
            eventHandler, toolchain.execConstraints(), "execution", executionPlatform)) {
          continue;
        }

        // Only add the toolchains if this is a new platform.
        if (!platformKeysSeen.contains(executionPlatformKey)) {
          toolchainType = toolchain.toolchainType();
          builder.put(executionPlatformKey, toolchain.toolchainLabel());
          platformKeysSeen.add(executionPlatformKey);
        }
      }
    }

    ImmutableMap<ConfiguredTargetKey, Label> resolvedToolchainLabels = builder.build();
    if (resolvedToolchainLabels.isEmpty()) {
      debugMessage(eventHandler, "  No toolchains found");
    } else {
      debugMessage(
          eventHandler,
          "  For toolchain type %s, possible execution platforms and toolchains: {%s}",
          toolchainTypeLabel,
          resolvedToolchainLabels.entrySet().stream()
              .map(e -> String.format("%s -> %s", e.getKey().getLabel(), e.getValue()))
              .collect(joining(", ")));
    }

    if (toolchainType == null || resolvedToolchainLabels.isEmpty()) {
      throw new ToolchainResolutionFunctionException(
          new NoToolchainFoundException(toolchainTypeLabel));
    }

    return ToolchainResolutionValue.create(toolchainType, resolvedToolchainLabels);
  }

  /**
   * Helper method to print a debugging message, if the given {@link EventHandler} is not {@code
   * null}.
   */
  private static void debugMessage(
      @Nullable EventHandler eventHandler, String template, Object... args) {
    if (eventHandler == null) {
      return;
    }

    eventHandler.handle(Event.info("ToolchainResolution: " + String.format(template, args)));
  }

  /**
   * Returns {@code true} iff all constraints set by the toolchain and in the {@link PlatformInfo}
   * match.
   */
  private static boolean checkConstraints(
      @Nullable EventHandler eventHandler,
      ConstraintCollection toolchainConstraints,
      String platformType,
      PlatformInfo platform) {

    // Check every constraint_setting in either the toolchain or the platform.
    ImmutableSet<ConstraintSettingInfo> constraints =
        new ImmutableSet.Builder<ConstraintSettingInfo>()
            .addAll(toolchainConstraints.constraintSettings())
            .addAll(platform.constraints().constraintSettings())
            .build();
    for (ConstraintSettingInfo constraintSetting : constraints) {
      ConstraintValueInfo toolchainConstraint = toolchainConstraints.get(constraintSetting);
      ConstraintValueInfo found = platform.constraints().get(constraintSetting);

      // Does the toolchain care about this constraint (possibly due to a default), and is it
      // different?
      if (toolchainConstraint != null && !toolchainConstraint.equals(found)) {
        debugMessage(
            eventHandler,
            "    Toolchain constraint %s has value %s, "
                + "which does not match value %s from the %s platform %s",
            constraintSetting.label(),
            toolchainConstraint != null ? toolchainConstraint.label() : "<missing>",
            found != null ? found.label() : "<missing>",
            platformType,
            platform.label());
        return false;
      }
    }
    return true;
  }

  @Nullable
  @Override
  public String extractTag(SkyKey skyKey) {
    return null;
  }

  /** Used to indicate that a toolchain was not found for the current request. */
  public static final class NoToolchainFoundException extends NoSuchThingException {
    private final Label missingToolchainTypeLabel;

    public NoToolchainFoundException(Label missingToolchainTypeLabel) {
      super(String.format("no matching toolchain found for %s", missingToolchainTypeLabel));
      this.missingToolchainTypeLabel = missingToolchainTypeLabel;
    }

    public Label missingToolchainTypeLabel() {
      return missingToolchainTypeLabel;
    }
  }

  /** Used to indicate errors during the computation of an {@link ToolchainResolutionValue}. */
  private static final class ToolchainResolutionFunctionException extends SkyFunctionException {
    public ToolchainResolutionFunctionException(NoToolchainFoundException e) {
      super(e, Transience.PERSISTENT);
    }

    public ToolchainResolutionFunctionException(InvalidToolchainLabelException e) {
      super(e, Transience.PERSISTENT);
    }

    public ToolchainResolutionFunctionException(InvalidPlatformException e) {
      super(e, Transience.PERSISTENT);
    }
  }
}
