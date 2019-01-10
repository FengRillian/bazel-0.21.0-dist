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

package com.google.devtools.build.lib.actions;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * An {@link ExecException} thrown when an action fails to execute because one or more of its inputs
 * was lost. In some cases, Bazel may know how to fix this on its own.
 */
public class LostInputsExecException extends ExecException {

  private final ImmutableList<ActionInput> lostInputs;
  private final InputOwners inputOwners;

  public LostInputsExecException(ImmutableList<ActionInput> lostInputs, InputOwners inputOwners) {
    super("");
    this.lostInputs = lostInputs;
    this.inputOwners = inputOwners;
  }

  @VisibleForTesting
  public ImmutableList<ActionInput> getLostInputs() {
    return lostInputs;
  }

  @VisibleForTesting
  public InputOwners getInputOwners() {
    return inputOwners;
  }

  @Override
  public ActionExecutionException toActionExecutionException(
      String messagePrefix, boolean verboseFailures, Action action) {
    String message = messagePrefix + " failed";
    return new LostInputsActionExecutionException(message, this, action);
  }

  /** An {@link ActionExecutionException} wrapping a {@link LostInputsExecException}. */
  public static class LostInputsActionExecutionException extends ActionExecutionException {

    private LostInputsActionExecutionException(
        String message, LostInputsExecException cause, Action action) {
      super(message, cause, action, /*catastrophe=*/ false);
    }

    public ImmutableList<ActionInput> getLostInputs() {
      return ((LostInputsExecException) getCause()).getLostInputs();
    }

    public InputOwners getInputOwners() {
      return ((LostInputsExecException) getCause()).getInputOwners();
    }
  }

  /**
   * Specifies the owning {@link Artifact}s that were responsible for the lost inputs and whether
   * the inputs came from runfiles.
   */
  public interface InputOwners {

    /**
     * Returns the owning {@link Artifact} that was responsible for the lost {@link ActionInput} or
     * {@code null} if there is no such owner. Throws if {@code input} was not lost.
     */
    @Nullable
    Artifact getOwner(ActionInput input);

    /** Returns the lost {@link ActionInput}s that came from runfiles along with their owners. */
    Set<ActionInput> getRunfilesInputsAndOwners();
  }
}
