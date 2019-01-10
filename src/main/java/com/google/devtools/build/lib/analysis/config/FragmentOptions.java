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

package com.google.devtools.build.lib.analysis.config;

import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.common.options.Options;
import com.google.devtools.common.options.OptionsBase;
import java.io.Serializable;
import java.util.Map;
import java.util.Set;

/** Command-line build options for a Blaze module. */
public abstract class FragmentOptions extends OptionsBase implements Cloneable, Serializable {
  /**
   * Returns the labels contributed to the defaults package by this fragment.
   *
   * <p>The set of keys returned by this function should be constant, however, the values are
   * allowed to change depending on the value of the options.
   */
  @SuppressWarnings("unused")
  public Map<String, Set<Label>> getDefaultsLabels() {
    return ImmutableMap.of();
  }

  @Override
  public FragmentOptions clone() {
    try {
      return (FragmentOptions) super.clone();
    } catch (CloneNotSupportedException e) {
      // This can't happen.
      throw new IllegalStateException(e);
    }
  }

  /**
   * Creates a new FragmentOptions instance with all flags set to default.
   */
  public FragmentOptions getDefault() {
    return Options.getDefaults(getClass());
  }

  /**
   * Creates a new FragmentOptions instance with flags adjusted to host platform.
   */
  @SuppressWarnings("unused")
  public FragmentOptions getHost() {
    return getDefault();
  }
}
