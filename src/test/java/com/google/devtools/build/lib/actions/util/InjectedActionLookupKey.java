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

package com.google.devtools.build.lib.actions.util;

import com.google.devtools.build.lib.actions.ActionLookupValue.ActionLookupKey;
import com.google.devtools.build.skyframe.SkyFunctionName;

/**
 * An {@link ActionLookupKey} with a non-hermetic {@link SkyFunctionName} so that its value can be
 * directly injected during tests.
 */
public class InjectedActionLookupKey extends ActionLookupKey {
  public static final SkyFunctionName INJECTED_ACTION_LOOKUP =
      SkyFunctionName.createNonHermetic("INJECTED_ACTION_LOOKUP");

  private final String name;

  public InjectedActionLookupKey(String name) {
    this.name = name;
  }

  @Override
  public SkyFunctionName functionName() {
    return INJECTED_ACTION_LOOKUP;
  }

  @Override
  public int hashCode() {
    return name.hashCode();
  }

  @Override
  public boolean equals(Object obj) {
    return obj instanceof InjectedActionLookupKey
        && ((InjectedActionLookupKey) obj).name.equals(name);
  }

  @Override
  public String toString() {
    return "InjectedActionLookupKey:" + name;
  }
}
