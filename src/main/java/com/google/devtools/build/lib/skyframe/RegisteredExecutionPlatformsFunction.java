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

package com.google.devtools.build.lib.skyframe;

import static com.google.common.collect.ImmutableList.toImmutableList;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.PlatformConfiguration;
import com.google.devtools.build.lib.analysis.config.BuildConfiguration;
import com.google.devtools.build.lib.analysis.platform.PlatformInfo;
import com.google.devtools.build.lib.analysis.platform.PlatformProviderUtils;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.TargetParsingException;
import com.google.devtools.build.lib.packages.Package;
import com.google.devtools.build.lib.skyframe.ConfiguredTargetFunction.ConfiguredValueCreationException;
import com.google.devtools.build.lib.skyframe.PlatformLookupUtil.InvalidPlatformException;
import com.google.devtools.build.skyframe.SkyFunction;
import com.google.devtools.build.skyframe.SkyFunctionException;
import com.google.devtools.build.skyframe.SkyFunctionException.Transience;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import com.google.devtools.build.skyframe.ValueOrException;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;

/** {@link SkyFunction} that returns all registered execution platforms available. */
public class RegisteredExecutionPlatformsFunction implements SkyFunction {

  @Nullable
  @Override
  public SkyValue compute(SkyKey skyKey, Environment env)
      throws SkyFunctionException, InterruptedException {

    BuildConfigurationValue buildConfigurationValue =
        (BuildConfigurationValue)
            env.getValue(((RegisteredExecutionPlatformsValue.Key) skyKey).getConfigurationKey());
    if (env.valuesMissing()) {
      return null;
    }
    BuildConfiguration configuration = buildConfigurationValue.getConfiguration();

    ImmutableList.Builder<String> targetPatternBuilder = new ImmutableList.Builder<>();

    // Get the execution platforms from the configuration.
    PlatformConfiguration platformConfiguration =
        configuration.getFragment(PlatformConfiguration.class);
    if (platformConfiguration != null) {
      targetPatternBuilder.addAll(platformConfiguration.getExtraExecutionPlatforms());
    }

    // Get the registered execution platforms from the WORKSPACE.
    List<String> workspaceExecutionPlatforms = getWorkspaceExecutionPlatforms(env);
    if (workspaceExecutionPlatforms == null) {
      return null;
    }
    targetPatternBuilder.addAll(workspaceExecutionPlatforms);
    ImmutableList<String> targetPatterns = targetPatternBuilder.build();

    // Expand target patterns.
    ImmutableList<Label> platformLabels;
    try {
      platformLabels = TargetPatternUtil.expandTargetPatterns(env, targetPatterns);
      if (env.valuesMissing()) {
        return null;
      }
    } catch (TargetPatternUtil.InvalidTargetPatternException e) {
      throw new RegisteredExecutionPlatformsFunctionException(
          new InvalidExecutionPlatformLabelException(e), Transience.PERSISTENT);
    }

    // Load the configured target for each, and get the declared execution platforms providers.
    ImmutableList<ConfiguredTargetKey> registeredExecutionPlatformKeys =
        configureRegisteredExecutionPlatforms(env, configuration, targetPatterns, platformLabels);
    if (env.valuesMissing()) {
      return null;
    }

    return RegisteredExecutionPlatformsValue.create(registeredExecutionPlatformKeys);
  }

  /**
   * Loads the external package and then returns the registered execution platform labels.
   *
   * @param env the environment to use for lookups
   */
  @Nullable
  @VisibleForTesting
  public static List<String> getWorkspaceExecutionPlatforms(Environment env)
      throws InterruptedException {
    PackageValue externalPackageValue =
        (PackageValue) env.getValue(PackageValue.key(Label.EXTERNAL_PACKAGE_IDENTIFIER));
    if (externalPackageValue == null) {
      return null;
    }

    Package externalPackage = externalPackageValue.getPackage();
    return externalPackage.getRegisteredExecutionPlatforms();
  }

  private ImmutableList<ConfiguredTargetKey> configureRegisteredExecutionPlatforms(
      Environment env,
      BuildConfiguration configuration,
      ImmutableList<String> targetPatterns,
      List<Label> labels)
      throws InterruptedException, RegisteredExecutionPlatformsFunctionException {

    ImmutableList<ConfiguredTargetKey> keys =
        labels.stream()
            .map(label -> ConfiguredTargetKey.of(label, configuration))
            .collect(toImmutableList());

    Map<SkyKey, ValueOrException<ConfiguredValueCreationException>> values =
        env.getValuesOrThrow(keys, ConfiguredValueCreationException.class);
    ImmutableList.Builder<ConfiguredTargetKey> validPlatformKeys = new ImmutableList.Builder<>();
    boolean valuesMissing = false;
    for (ConfiguredTargetKey platformKey : keys) {
      Label platformLabel = platformKey.getLabel();
      try {
        ValueOrException<ConfiguredValueCreationException> valueOrException =
            values.get(platformKey);
        if (valueOrException.get() == null) {
          valuesMissing = true;
          continue;
        }
        ConfiguredTarget target =
            ((ConfiguredTargetValue) valueOrException.get()).getConfiguredTarget();
        PlatformInfo platformInfo = PlatformProviderUtils.platform(target);
        if (platformInfo == null) {
          if (TargetPatternUtil.isTargetExplicit(targetPatterns, platformLabel)) {
            // Only report an error if the label was explicitly requested.
            throw new RegisteredExecutionPlatformsFunctionException(
                new InvalidPlatformException(platformLabel), Transience.PERSISTENT);
          }
          continue;
        }
        validPlatformKeys.add(platformKey);
      } catch (ConfiguredValueCreationException e) {
        if (TargetPatternUtil.isTargetExplicit(targetPatterns, platformLabel)) {
          // Only report an error if the label was explicitly requested.
          throw new RegisteredExecutionPlatformsFunctionException(
              new InvalidPlatformException(platformLabel, e), Transience.PERSISTENT);
        }
      }
    }

    if (valuesMissing) {
      return null;
    }
    return validPlatformKeys.build();
  }

  @Nullable
  @Override
  public String extractTag(SkyKey skyKey) {
    return null;
  }

  /**
   * Used to indicate that the given {@link Label} represents a {@link ConfiguredTarget} which is
   * not a valid {@link PlatformInfo} provider.
   */
  static final class InvalidExecutionPlatformLabelException extends Exception {

    public InvalidExecutionPlatformLabelException(
        TargetPatternUtil.InvalidTargetPatternException e) {
      this(e.getInvalidPattern(), e.getTpe());
    }

    public InvalidExecutionPlatformLabelException(String invalidPattern, TargetParsingException e) {
      super(
          String.format(
              "invalid registered execution platform '%s': %s", invalidPattern, e.getMessage()),
          e);
    }
  }

  /**
   * Used to declare all the exception types that can be wrapped in the exception thrown by {@link
   * #compute}.
   */
  private static class RegisteredExecutionPlatformsFunctionException extends SkyFunctionException {

    private RegisteredExecutionPlatformsFunctionException(
        InvalidExecutionPlatformLabelException cause, Transience transience) {
      super(cause, transience);
    }

    private RegisteredExecutionPlatformsFunctionException(
        InvalidPlatformException cause, Transience transience) {
      super(cause, transience);
    }
  }
}
