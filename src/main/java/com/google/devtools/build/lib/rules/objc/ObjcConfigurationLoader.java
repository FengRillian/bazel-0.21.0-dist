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

package com.google.devtools.build.lib.rules.objc;

import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.analysis.config.BuildConfiguration;
import com.google.devtools.build.lib.analysis.config.BuildConfiguration.Options;
import com.google.devtools.build.lib.analysis.config.BuildOptions;
import com.google.devtools.build.lib.analysis.config.ConfigurationFragmentFactory;
import com.google.devtools.build.lib.analysis.config.FragmentOptions;
import com.google.devtools.build.lib.analysis.config.InvalidConfigurationException;

/**
 * A loader that creates ObjcConfiguration instances based on Objective-C configurations and
 * command-line options.
 */
public class ObjcConfigurationLoader implements ConfigurationFragmentFactory {
  @Override
  public ObjcConfiguration create(BuildOptions buildOptions) throws InvalidConfigurationException {
    Options options = buildOptions.get(BuildConfiguration.Options.class);
    ObjcCommandLineOptions objcOptions = buildOptions.get(ObjcCommandLineOptions.class);
    validate(objcOptions);
    return new ObjcConfiguration(objcOptions, options);
  }

  private static void validate(ObjcCommandLineOptions objcOptions)
      throws InvalidConfigurationException {
    if (objcOptions.experimentalObjcHeaderThinning && !objcOptions.useDotdPruning) {
      throw new InvalidConfigurationException(
          "Experimental Objective-C header thinning (--experimental_objc_header_thinning) requires "
              + "Objective-C dotd pruning (--objc_use_dotd_pruning).");
    }
  }

  @Override
  public Class<? extends BuildConfiguration.Fragment> creates() {
    return ObjcConfiguration.class;
  }

  @Override
  public ImmutableSet<Class<? extends FragmentOptions>> requiredOptions() {
    return ImmutableSet.<Class<? extends FragmentOptions>>of(ObjcCommandLineOptions.class);
  }
}
