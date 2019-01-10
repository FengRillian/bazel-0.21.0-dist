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
package com.google.devtools.build.lib.actions;

import com.google.common.base.MoreObjects;
import com.google.common.collect.Interner;
import com.google.devtools.build.lib.actions.ActionLookupValue.ActionLookupKey;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.concurrent.BlazeInterners;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.AutoCodec;
import com.google.devtools.build.skyframe.FunctionHermeticity;
import com.google.devtools.build.skyframe.ShareabilityOfValue;
import com.google.devtools.build.skyframe.SkyFunctionName;
import com.google.devtools.build.skyframe.SkyKey;

/** Data that uniquely identifies an action. */
@AutoCodec
public class ActionLookupData implements SkyKey {
  private static final Interner<ActionLookupData> INTERNER = BlazeInterners.newWeakInterner();
  // Test actions are not shareable.
  // Action execution writes to disk and can be invalidated by disk state, so is non-hermetic.
  public static final SkyFunctionName NAME =
      SkyFunctionName.create(
          "ACTION_EXECUTION", ShareabilityOfValue.SOMETIMES, FunctionHermeticity.NONHERMETIC);

  private final ActionLookupKey actionLookupKey;
  private final int actionIndex;

  private ActionLookupData(ActionLookupKey actionLookupKey, int actionIndex) {
    this.actionLookupKey = actionLookupKey;
    this.actionIndex = actionIndex;
  }

  @AutoCodec.Instantiator
  public static ActionLookupData create(ActionLookupKey actionLookupKey, int actionIndex) {
    return INTERNER.intern(new ActionLookupData(actionLookupKey, actionIndex));
  }

  public ActionLookupKey getActionLookupKey() {
    return actionLookupKey;
  }

  /**
   * Index of the action in question in the node keyed by {@link #getActionLookupKey}. Should be
   * passed to {@link ActionLookupValue#getAction}.
   */
  public int getActionIndex() {
    return actionIndex;
  }

  public Label getLabelForErrors() {
    return ((ActionLookupKey) actionLookupKey.argument()).getLabel();
  }

  @Override
  public int hashCode() {
    return 37 * actionLookupKey.hashCode() + actionIndex;
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj) {
      return true;
    }
    if (!(obj instanceof ActionLookupData)) {
      return false;
    }
    ActionLookupData that = (ActionLookupData) obj;
    return this.actionIndex == that.actionIndex
        && this.actionLookupKey.equals(that.actionLookupKey);
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this)
        .add("actionLookupKey", actionLookupKey)
        .add("actionIndex", actionIndex)
        .toString();
  }

  @Override
  public SkyFunctionName functionName() {
    return NAME;
  }
}
