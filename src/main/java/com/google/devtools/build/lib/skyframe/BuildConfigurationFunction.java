// Copyright 2015 The Bazel Authors. All rights reserved.
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

import static com.google.devtools.build.lib.analysis.config.BuildConfiguration.Fragment;

import com.google.common.collect.ClassToInstanceMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.MutableClassToInstanceMap;
import com.google.devtools.build.lib.actions.ActionEnvironment;
import com.google.devtools.build.lib.analysis.BlazeDirectories;
import com.google.devtools.build.lib.analysis.ConfiguredRuleClassProvider;
import com.google.devtools.build.lib.analysis.config.BuildConfiguration;
import com.google.devtools.build.lib.analysis.config.BuildOptions;
import com.google.devtools.build.lib.analysis.config.InvalidConfigurationException;
import com.google.devtools.build.lib.packages.RuleClassProvider;
import com.google.devtools.build.skyframe.SkyFunction;
import com.google.devtools.build.skyframe.SkyFunctionException;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import com.google.devtools.build.skyframe.ValueOrException;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * A builder for {@link BuildConfigurationValue} instances.
 */
public class BuildConfigurationFunction implements SkyFunction {

  private final BlazeDirectories directories;
  private final ConfiguredRuleClassProvider ruleClassProvider;
  private final BuildOptions defaultBuildOptions;

  public BuildConfigurationFunction(
      BlazeDirectories directories,
      RuleClassProvider ruleClassProvider,
      BuildOptions defaultBuildOptions) {
    this.directories = directories;
    this.ruleClassProvider = (ConfiguredRuleClassProvider) ruleClassProvider;
    this.defaultBuildOptions = defaultBuildOptions;
  }

  @Override
  public SkyValue compute(SkyKey skyKey, Environment env)
      throws InterruptedException, BuildConfigurationFunctionException {
    WorkspaceNameValue workspaceNameValue = (WorkspaceNameValue) env
        .getValue(WorkspaceNameValue.key());
    if (workspaceNameValue == null) {
      return null;
    }

    BuildConfigurationValue.Key key = (BuildConfigurationValue.Key) skyKey.argument();
    Set<Fragment> fragments;
    try {
      fragments = getConfigurationFragments(key, env);
    } catch (InvalidConfigurationException e) {
      throw new BuildConfigurationFunctionException(e);
    }
    if (fragments == null) {
      return null;
    }

    ClassToInstanceMap<Fragment> fragmentsMap = MutableClassToInstanceMap.create();
    for (Fragment fragment : fragments) {
      fragmentsMap.put(fragment.getClass(), fragment);
    }

    BuildOptions options = defaultBuildOptions.applyDiff(key.getOptionsDiff());
    ActionEnvironment actionEnvironment =
      ruleClassProvider.getActionEnvironmentProvider().getActionEnvironment(options);

    BuildConfiguration config =
        new BuildConfiguration(
            directories,
            fragmentsMap,
            options,
            key.getOptionsDiff(),
            ruleClassProvider.getReservedActionMnemonics(),
            actionEnvironment,
            workspaceNameValue.getName());
    return new BuildConfigurationValue(config);
  }

  private Set<Fragment> getConfigurationFragments(BuildConfigurationValue.Key key, Environment env)
      throws InvalidConfigurationException, InterruptedException {

    // Get SkyKeys for the fragments we need to load.
    Set<SkyKey> fragmentKeys = new LinkedHashSet<>();
    BuildOptions options = defaultBuildOptions.applyDiff(key.getOptionsDiff());
    for (Class<? extends BuildConfiguration.Fragment> fragmentClass : key.getFragments()) {
      fragmentKeys.add(ConfigurationFragmentValue.key(options, fragmentClass, ruleClassProvider));
    }

    // Load them as Skyframe deps.
    Map<SkyKey, ValueOrException<InvalidConfigurationException>> fragmentDeps =
        env.getValuesOrThrow(fragmentKeys, InvalidConfigurationException.class);
    if (env.valuesMissing()) {
      return null;
    }

    // Collect and return the results.
    ImmutableSet.Builder<Fragment> fragments = ImmutableSet.builder();
    for (ValueOrException<InvalidConfigurationException> value : fragmentDeps.values()) {
      BuildConfiguration.Fragment fragment =
          ((ConfigurationFragmentValue) value.get()).getFragment();
      if (fragment != null) {
        fragments.add(fragment);
      }
    }
    return fragments.build();
  }

  @Override
  public String extractTag(SkyKey skyKey) {
    return null;
  }

  private static final class BuildConfigurationFunctionException extends SkyFunctionException {
    public BuildConfigurationFunctionException(InvalidConfigurationException e) {
      super(e, Transience.PERSISTENT);
    }
  }
}
