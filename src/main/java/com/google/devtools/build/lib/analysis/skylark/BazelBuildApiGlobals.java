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

package com.google.devtools.build.lib.analysis.skylark;

import com.google.devtools.build.lib.events.Location;
import com.google.devtools.build.lib.skylarkbuildapi.SkylarkBuildApiGlobals;
import com.google.devtools.build.lib.syntax.Environment;
import com.google.devtools.build.lib.syntax.EvalException;
import com.google.devtools.build.lib.syntax.SkylarkUtils;

/**
 * Bazel implementation of {@link SkylarkBuildApiGlobals}: a collection of global skylark build
 * API functions that belong in the global namespace.
 */
public class BazelBuildApiGlobals implements SkylarkBuildApiGlobals {

  @Override
  public SkylarkLateBoundDefault<?> configurationField(
      String fragment, String name, Location loc, Environment env)
      throws EvalException {
    Class<?> fragmentClass = SkylarkUtils.getFragmentMap(env).get(fragment);

    if (fragmentClass == null) {
      throw new EvalException(
          loc,
          String.format("invalid configuration fragment name '%s'", fragment));
    }
    try {
      return SkylarkLateBoundDefault.forConfigurationField(
          fragmentClass, name, SkylarkUtils.getToolsRepository(env));
    } catch (SkylarkLateBoundDefault.InvalidConfigurationFieldException exception) {
      throw new EvalException(loc, exception);
    }
  }
}
