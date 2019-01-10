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

package com.google.devtools.build.skydoc.fakebuildapi;

import com.google.devtools.build.lib.events.Location;
import com.google.devtools.build.lib.skylarkbuildapi.LateBoundDefaultApi;
import com.google.devtools.build.lib.skylarkbuildapi.SkylarkBuildApiGlobals;
import com.google.devtools.build.lib.syntax.Environment;
import com.google.devtools.build.lib.syntax.EvalException;

/**
 * Fake implementation of {@link SkylarkBuildApiGlobals}.
 */
public class FakeBuildApiGlobals implements SkylarkBuildApiGlobals {

  @Override
  public LateBoundDefaultApi configurationField(String fragment, String name, Location loc,
      Environment env) throws EvalException {
    return new FakeLateBoundDefaultApi();
  }
}
