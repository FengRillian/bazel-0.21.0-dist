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

package com.google.devtools.build.lib.actions;

import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.AutoCodec;
import com.google.devtools.build.lib.util.Fingerprint;

/**
 * FailAction is an Action that always fails to execute. (Used as scaffolding for rules we haven't
 * yet implemented. Also useful for testing.)
 */
@AutoCodec
@Immutable
public final class FailAction extends AbstractAction {

  private static final String GUID = "626cb78a-810f-4af3-979c-ee194955f04c";

  private final String errorMessage;

  public FailAction(ActionOwner owner, Iterable<Artifact> outputs, String errorMessage) {
    super(owner, ImmutableList.<Artifact>of(), outputs);
    this.errorMessage = errorMessage;
  }

  @Override
  public Artifact getPrimaryInput() {
    return null;
  }

  @Override
  public ActionResult execute(ActionExecutionContext actionExecutionContext)
      throws ActionExecutionException {
    throw new ActionExecutionException(errorMessage, this, false);
  }

  @Override
  protected void computeKey(ActionKeyContext actionKeyContext, Fingerprint fp) {
    fp.addString(GUID);
  }

  @Override
  protected String getRawProgressMessage() {
    return "Building unsupported rule " + getOwner().getLabel()
        + " located at " + getOwner().getLocation();
  }

  @Override
  public String getMnemonic() {
    return "Fail";
  }
}
