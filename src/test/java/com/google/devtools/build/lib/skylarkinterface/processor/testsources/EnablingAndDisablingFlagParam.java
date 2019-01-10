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

package com.google.devtools.build.lib.skylarkinterface.processor.testsources;

import com.google.devtools.build.lib.skylarkinterface.Param;
import com.google.devtools.build.lib.skylarkinterface.SkylarkCallable;
import com.google.devtools.build.lib.syntax.SkylarkSemantics.FlagIdentifier;

/**
 * Test case for a SkylarkCallable method which has a parameter with both enableOnlyWithFlag and
 * disableWithFlag specified.
 */
public class EnablingAndDisablingFlagParam {

  @SkylarkCallable(
      name = "someMethod",
      documented = false,
      parameters = {
        @Param(name = "one", type = String.class, named = true),
        @Param(
            name = "two",
            type = Integer.class,
            named = true,
            enableOnlyWithFlag = FlagIdentifier.EXPERIMENTAL_BUILD_SETTING_API,
            disableWithFlag = FlagIdentifier.EXPERIMENTAL_BUILD_SETTING_API),
      })
  public String someMethod(String one, Integer two) {
    return "foo";
  }
}
