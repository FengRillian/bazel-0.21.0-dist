// Copyright 2016 The Bazel Authors. All rights reserved.
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

import static com.google.devtools.build.lib.packages.Attribute.attr;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.analysis.config.BuildConfiguration;
import com.google.devtools.build.lib.analysis.config.BuildOptions;
import com.google.devtools.build.lib.analysis.config.ConfigurationFragmentFactory;
import com.google.devtools.build.lib.analysis.config.FragmentOptions;
import com.google.devtools.build.lib.analysis.config.transitions.SplitTransition;
import com.google.devtools.build.lib.analysis.util.MockRule;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.packages.Attribute;
import com.google.devtools.build.lib.packages.BuildType;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.AutoCodec;
import com.google.devtools.build.lib.testutil.TestRuleClassProvider;
import com.google.devtools.build.lib.util.FileTypeSet;
import com.google.devtools.common.options.Option;
import com.google.devtools.common.options.OptionDocumentationCategory;
import com.google.devtools.common.options.OptionEffectTag;

/**
 * Rule and configuration class definitions for testing late-bound split attributes.
 */
public class LateBoundSplitUtil {
  /** A custom {@link FragmentOptions} with the option to be split. */
  public static class TestOptions extends FragmentOptions { // public for options loader
    @Option(
      name = "foo",
      documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
      effectTags = {OptionEffectTag.NO_OP},
      defaultValue = ""
    )
    public String fooFlag;
  }

  /** The split. */
  @AutoCodec @AutoCodec.VisibleForSerialization
  static final SplitTransition SIMPLE_SPLIT =
      (SplitTransition)
          buildOptions -> {
            BuildOptions split1 = buildOptions.clone();
            split1.get(TestOptions.class).fooFlag = "one";
            BuildOptions split2 = buildOptions.clone();
            split2.get(TestOptions.class).fooFlag = "two";
            return ImmutableList.of(split1, split2);
          };

  /** The {@link BuildConfiguration.Fragment} that contains the options. */
  @AutoCodec
  static class TestFragment extends BuildConfiguration.Fragment {
  }

  /**
   * The fragment's loader.
   */
  static class FragmentLoader implements ConfigurationFragmentFactory {
    @Override
    public BuildConfiguration.Fragment create(BuildOptions buildOptions) {
      return new TestFragment();
    }

    @Override
    public Class<? extends BuildConfiguration.Fragment> creates() {
     return TestFragment.class;
    }

    @Override
    public ImmutableSet<Class<? extends FragmentOptions>> requiredOptions() {
      return ImmutableSet.<Class<? extends FragmentOptions>>of(TestOptions.class);
    }
  }

  /** A custom rule that applies a late-bound split attribute. */
  static final RuleDefinition RULE_WITH_LATEBOUND_SPLIT_ATTR =
      (MockRule)
          () ->
              MockRule.define(
                  "rule_with_latebound_split",
                  (builder, env) -> {
                    builder
                        .add(
                            attr(":latebound_split_attr", BuildType.LABEL)
                                .allowedFileTypes(FileTypeSet.ANY_FILE)
                                .allowedRuleClasses(Attribute.ANY_RULE)
                                .cfg(SIMPLE_SPLIT)
                                .value(
                                    Attribute.LateBoundDefault.fromConstantForTesting(
                                        Label.parseAbsoluteUnchecked("//foo:latebound_dep"))))
                        .requiresConfigurationFragments(TestFragment.class);
                  });

  /**
   * A custom rule that requires {@link TestFragment}.
   */
  static final RuleDefinition RULE_WITH_TEST_FRAGMENT = (MockRule) () -> MockRule.define(
      "rule_with_test_fragment",
      (builder, env) -> builder.requiresConfigurationFragments(TestFragment.class));

  /**
   * Returns a rule class provider with standard test setup plus the above rules/configs.
   */
  static ConfiguredRuleClassProvider getRuleClassProvider() {
    ConfiguredRuleClassProvider.Builder builder = new ConfiguredRuleClassProvider.Builder();
    TestRuleClassProvider.addStandardRules(builder);
    builder.addRuleDefinition(RULE_WITH_LATEBOUND_SPLIT_ATTR);
    builder.addRuleDefinition(RULE_WITH_TEST_FRAGMENT);
    builder.addConfigurationFragment(new FragmentLoader());
    builder.addConfigurationOptions(TestOptions.class);
    return builder.build();
  }

  /**
   * Returns the {@link TestOptions} from the given configuration.
   */
  static TestOptions getOptions(BuildConfiguration config) {
    return config.getOptions().get(TestOptions.class);
  }
}
