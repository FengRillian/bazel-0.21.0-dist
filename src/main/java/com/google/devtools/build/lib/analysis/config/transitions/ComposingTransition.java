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

package com.google.devtools.build.lib.analysis.config.transitions;

import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.analysis.config.BuildOptions;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.AutoCodec;
import java.util.List;
import java.util.Objects;

/**
 * A configuration transition that composes two other transitions in an ordered sequence.
 *
 * <p>Example:
 *
 * <pre>
 *   transition1: { someSetting = $oldVal + " foo" }
 *   transition2: { someSetting = $oldVal + " bar" }
 *   ComposingTransition(transition1, transition2): { someSetting = $oldVal + " foo bar" }
 * </pre>
 */
@AutoCodec
public class ComposingTransition implements ConfigurationTransition {
  private ConfigurationTransition transition1;
  private ConfigurationTransition transition2;

  /**
   * Creates a {@link ComposingTransition} that applies the sequence: {@code fromOptions ->
   * transition1 -> transition2 -> toOptions }.
   *
   * <p>Note that it's possible to create silly transitions with this constructor (e.g., if one or
   * both of the transitions is {@link NoTransition}). Use
   * {@link com.google.devtools.build.lib.analysis.config.TransitionResolver#composeTransitions}
   * for these cases - it checks for for these states and avoids instantiation appropriately.
   */
  @AutoCodec.Instantiator
  public ComposingTransition(
      ConfigurationTransition transition1, ConfigurationTransition transition2) {
    this.transition1 = transition1;
    this.transition2 = transition2;
  }

  @Override
  public List<BuildOptions> apply(BuildOptions buildOptions) {
    ImmutableList.Builder<BuildOptions> toOptions = ImmutableList.builder();
    for (BuildOptions transition1Options : transition1.apply(buildOptions)) {
      toOptions.addAll(transition2.apply(transition1Options));
    }
    return toOptions.build();
  }

  @Override
  public String reasonForOverride() {
    return "Basic abstraction for combining other transitions";
  }

  @Override
  public String getName() {
    return "(" + transition1.getName() + " + " + transition2.getName() + ")";
  }

  @Override
  public int hashCode() {
    return Objects.hash(transition1, transition2);
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof ComposingTransition
        && ((ComposingTransition) other).transition1.equals(this.transition1)
        && ((ComposingTransition) other).transition2.equals(this.transition2);
  }
}
