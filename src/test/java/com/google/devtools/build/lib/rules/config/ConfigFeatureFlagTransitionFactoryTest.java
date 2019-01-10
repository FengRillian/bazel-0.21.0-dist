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

package com.google.devtools.build.lib.rules.config;

import static com.google.common.truth.Truth.assertThat;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.testing.EqualsTester;
import com.google.devtools.build.lib.analysis.ConfiguredRuleClassProvider;
import com.google.devtools.build.lib.analysis.config.BuildOptions;
import com.google.devtools.build.lib.analysis.config.FragmentOptions;
import com.google.devtools.build.lib.analysis.config.transitions.NoTransition;
import com.google.devtools.build.lib.analysis.config.transitions.PatchTransition;
import com.google.devtools.build.lib.analysis.util.BuildViewTestCase;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.packages.Rule;
import com.google.devtools.build.lib.testutil.TestRuleClassProvider;
import com.google.devtools.common.options.OptionsBase;
import com.google.devtools.common.options.OptionsProvider;
import java.util.Map;
import javax.annotation.Nullable;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for the ConfigFeatureFlagTransitionFactory. */
@RunWith(JUnit4.class)
public final class ConfigFeatureFlagTransitionFactoryTest extends BuildViewTestCase {

  private static final class ConfigFeatureFlagsOptionsProvider implements OptionsProvider {
    private final ImmutableMap<Label, String> flagValues;

    public ConfigFeatureFlagsOptionsProvider(Map<Label, String> flagValues) {
      this.flagValues = ImmutableMap.copyOf(flagValues);
    }

    @Override
    @Nullable
    public <O extends OptionsBase> O getOptions(Class<O> optionsClass) {
      if (optionsClass.equals(ConfigFeatureFlagOptions.class)) {
        ConfigFeatureFlagOptions options =
            (ConfigFeatureFlagOptions) new ConfigFeatureFlagOptions().getDefault();
        options.replaceFlagValues(flagValues);
        return optionsClass.cast(options);
      }
      return null;
    }

    @Override
    public Map<String, Object> getStarlarkOptions() {
      return ImmutableMap.of();
    }
  }

  private static BuildOptions getOptionsWithoutFlagFragment() {
    return BuildOptions.of(
        ImmutableList.<Class<? extends FragmentOptions>>of(), OptionsProvider.EMPTY);
  }

  private static BuildOptions getOptionsWithFlagFragment(Map<Label, String> values) {
    return BuildOptions.of(
        ImmutableList.<Class<? extends FragmentOptions>>of(ConfigFeatureFlagOptions.class),
        new ConfigFeatureFlagsOptionsProvider(values));
  }

  @Override
  protected ConfiguredRuleClassProvider getRuleClassProvider() {
    ConfiguredRuleClassProvider.Builder builder =
        new ConfiguredRuleClassProvider.Builder().addRuleDefinition(new FeatureFlagSetterRule());
    TestRuleClassProvider.addStandardRules(builder);
    return builder.build();
  }

  @Test
  public void emptyTransition_returnsOriginalOptionsIfFragmentNotPresent() throws Exception {
    Rule rule = scratchRule("a", "empty", "feature_flag_setter(name = 'empty', flag_values = {})");
    PatchTransition transition =
        new ConfigFeatureFlagTransitionFactory("flag_values").buildTransitionFor(rule);

    BuildOptions original = getOptionsWithoutFlagFragment();
    BuildOptions converted = transition.patch(original);

    assertThat(converted).isSameAs(original);
    assertThat(original.contains(ConfigFeatureFlagOptions.class)).isFalse();
  }

  @Test
  public void populatedTransition_returnsOriginalOptionsIfFragmentNotPresent() throws Exception {
    Rule rule =
        scratchRule(
            "a",
            "flag_setter_a",
            "feature_flag_setter(",
            "    name = 'flag_setter_a',",
            "    flag_values = {':flag': 'a'})",
            "config_feature_flag(",
            "    name = 'flag',",
            "    allowed_values = ['a', 'b'],",
            "    default_value = 'a')");
    PatchTransition transition =
        new ConfigFeatureFlagTransitionFactory("flag_values").buildTransitionFor(rule);

    BuildOptions original = getOptionsWithoutFlagFragment();
    BuildOptions converted = transition.patch(original);

    assertThat(converted).isSameAs(original);
    assertThat(original.contains(ConfigFeatureFlagOptions.class)).isFalse();
  }

  @Test
  public void emptyTransition_returnsClearedOptionsIfFragmentPresent() throws Exception {
    Rule rule = scratchRule("a", "empty", "feature_flag_setter(name = 'empty', flag_values = {})");
    PatchTransition transition =
        new ConfigFeatureFlagTransitionFactory("flag_values").buildTransitionFor(rule);
    Map<Label, String> originalFlagMap =
        ImmutableMap.of(Label.parseAbsolute("//a:flag", ImmutableMap.of()), "value");

    BuildOptions original = getOptionsWithFlagFragment(originalFlagMap);
    BuildOptions converted = transition.patch(original);

    assertThat(converted).isNotSameAs(original);
    assertThat(original.get(ConfigFeatureFlagOptions.class).getFlagValues())
        .containsExactlyEntriesIn(originalFlagMap);
    assertThat(converted.get(ConfigFeatureFlagOptions.class).getFlagValues()).isEmpty();
  }

  @Test
  public void populatedTransition_setsOptionsAndClearsNonPresentOptionsIfFragmentPresent()
      throws Exception {
    Rule rule =
        scratchRule(
            "a",
            "flag_setter_a",
            "feature_flag_setter(",
            "    name = 'flag_setter_a',",
            "    flag_values = {':flag': 'a'})",
            "config_feature_flag(",
            "    name = 'flag',",
            "    allowed_values = ['a', 'b'],",
            "    default_value = 'a')");
    PatchTransition transition =
        new ConfigFeatureFlagTransitionFactory("flag_values").buildTransitionFor(rule);
    Map<Label, String> originalFlagMap =
        ImmutableMap.of(Label.parseAbsolute("//a:old", ImmutableMap.of()), "value");
    Map<Label, String> expectedFlagMap =
        ImmutableMap.of(Label.parseAbsolute("//a:flag", ImmutableMap.of()), "a");

    BuildOptions original = getOptionsWithFlagFragment(originalFlagMap);
    BuildOptions converted = transition.patch(original);

    assertThat(converted).isNotSameAs(original);
    assertThat(original.get(ConfigFeatureFlagOptions.class).getFlagValues())
        .containsExactlyEntriesIn(originalFlagMap);
    assertThat(converted.get(ConfigFeatureFlagOptions.class).getFlagValues())
        .containsExactlyEntriesIn(expectedFlagMap);
  }

  @Test
  public void transition_equalsTester() throws Exception {
    scratch.file(
        "a/BUILD",
        "filegroup(",
        "    name = 'not_a_flagsetter',",
        "    srcs = [])",
        "feature_flag_setter(",
        "    name = 'empty',",
        "    flag_values = {})",
        "feature_flag_setter(",
        "    name = 'empty2',",
        "    flag_values = {})",
        "feature_flag_setter(",
        "    name = 'flag_setter_a',",
        "    flag_values = {':flag': 'a'})",
        "feature_flag_setter(",
        "    name = 'flag_setter_a2',",
        "    flag_values = {':flag': 'a'})",
        "feature_flag_setter(",
        "    name = 'flag_setter_b',",
        "    flag_values = {':flag': 'b'})",
        "feature_flag_setter(",
        "    name = 'flag2_setter',",
        "    flag_values = {':flag2': 'a'})",
        "feature_flag_setter(",
        "    name = 'both_setter',",
        "    flag_values = {':flag': 'a', ':flag2': 'a'})",
        "config_feature_flag(",
        "    name = 'flag',",
        "    allowed_values = ['a', 'b'],",
        "    default_value = 'a')",
        "config_feature_flag(",
        "    name = 'flag2',",
        "    allowed_values = ['a', 'b'],",
        "    default_value = 'a')");

    Rule nonflag = (Rule) getTarget("//a:not_a_flagsetter");
    Rule empty = (Rule) getTarget("//a:empty");
    Rule empty2 = (Rule) getTarget("//a:empty2");
    Rule flagSetterA = (Rule) getTarget("//a:flag_setter_a");
    Rule flagSetterA2 = (Rule) getTarget("//a:flag_setter_a2");
    Rule flagSetterB = (Rule) getTarget("//a:flag_setter_b");
    Rule flag2Setter = (Rule) getTarget("//a:flag2_setter");
    Rule bothSetter = (Rule) getTarget("//a:both_setter");

    ConfigFeatureFlagTransitionFactory factory =
        new ConfigFeatureFlagTransitionFactory("flag_values");
    ConfigFeatureFlagTransitionFactory factory2 =
        new ConfigFeatureFlagTransitionFactory("flag_values");

    new EqualsTester()
        .addEqualityGroup(
            // transition for non flags target
            factory.buildTransitionFor(nonflag),
            NoTransition.INSTANCE)
        .addEqualityGroup(
            // transition with empty map
            factory.buildTransitionFor(empty),
            // transition produced by same factory on same rule
            factory.buildTransitionFor(empty),
            // transition produced by similar factory on same rule
            factory2.buildTransitionFor(empty),
            // transition produced by same factory on similar rule
            factory.buildTransitionFor(empty2),
            // transition produced by similar factory on similar rule
            factory2.buildTransitionFor(empty2))
        .addEqualityGroup(
            // transition with flag -> a
            factory.buildTransitionFor(flagSetterA),
            // same map, different rule
            factory.buildTransitionFor(flagSetterA2),
            // same map, different factory
            factory2.buildTransitionFor(flagSetterA))
        .addEqualityGroup(
            // transition with flag set to different value
            factory.buildTransitionFor(flagSetterB))
        .addEqualityGroup(
            // transition with different flag set to same value
            factory.buildTransitionFor(flag2Setter))
        .addEqualityGroup(
            // transition with more flags set
            factory.buildTransitionFor(bothSetter))
        .testEquals();
  }

  @Test
  public void factory_equalsTester() throws Exception {
    new EqualsTester()
        .addEqualityGroup(
            new ConfigFeatureFlagTransitionFactory("flag_values"),
            new ConfigFeatureFlagTransitionFactory("flag_values"))
        .addEqualityGroup(new ConfigFeatureFlagTransitionFactory("other_flag_values"))
        .testEquals();
  }
}
