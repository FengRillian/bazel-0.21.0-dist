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

import com.google.devtools.build.lib.causes.ActionFailed;
import com.google.devtools.build.lib.causes.Cause;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.collect.nestedset.Order;
import com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadSafe;
import com.google.devtools.build.lib.events.Location;
import com.google.devtools.build.lib.util.ExitCode;
import javax.annotation.Nullable;

/**
 * This exception gets thrown if {@link Action#execute(ActionExecutionContext)} is unsuccessful.
 * Typically these are re-raised ExecException throwables.
 */
@ThreadSafe
public class ActionExecutionException extends Exception {

  private final Action action;
  private final NestedSet<Cause> rootCauses;
  private final boolean catastrophe;
  @Nullable private final ExitCode exitCode;

  public ActionExecutionException(Throwable cause, Action action, boolean catastrophe) {
    super(cause.getMessage(), cause);
    this.action = action;
    this.rootCauses = rootCausesFromAction(action);
    this.catastrophe = catastrophe;
    this.exitCode = null;
  }

  public ActionExecutionException(String message,
                                  Throwable cause, Action action, boolean catastrophe) {
    super(message + ": " + cause.getMessage(), cause);
    this.action = action;
    this.rootCauses = rootCausesFromAction(action);
    this.catastrophe = catastrophe;
    this.exitCode = null;
  }

  public ActionExecutionException(String message,
                                  Throwable cause, Action action, boolean catastrophe,
                                  ExitCode exitCode) {
    super(message + ": " + cause.getMessage(), cause);
    this.action = action;
    this.rootCauses = rootCausesFromAction(action);
    this.catastrophe = catastrophe;
    this.exitCode = exitCode;
  }

  public ActionExecutionException(String message, Action action, boolean catastrophe) {
    super(message);
    this.action = action;
    this.rootCauses = rootCausesFromAction(action);
    this.catastrophe = catastrophe;
    this.exitCode = null;
  }

  public ActionExecutionException(String message, Action action, boolean catastrophe,
                                  ExitCode exitCode) {
    super(message);
    this.action = action;
    this.rootCauses = rootCausesFromAction(action);
    this.catastrophe = catastrophe;
    this.exitCode = exitCode;
  }

  public ActionExecutionException(
      String message, Action action, NestedSet<Cause> rootCauses, boolean catastrophe) {
    super(message);
    this.action = action;
    this.rootCauses = rootCauses;
    this.catastrophe = catastrophe;
    this.exitCode = null;
  }

  public ActionExecutionException(
      String message,
      Throwable cause,
      Action action,
      NestedSet<Cause> rootCauses,
      boolean catastrophe) {
    super(message, cause);
    this.action = action;
    this.rootCauses = rootCauses;
    this.catastrophe = catastrophe;
    this.exitCode = null;
  }

  public ActionExecutionException(
      String message,
      Throwable cause,
      Action action,
      NestedSet<Cause> rootCauses,
      boolean catastrophe,
      ExitCode exitCode) {
    super(message, cause);
    this.action = action;
    this.rootCauses = rootCauses;
    this.catastrophe = catastrophe;
    this.exitCode = exitCode;
  }

  static NestedSet<Cause> rootCausesFromAction(Action action) {
    return action == null || action.getOwner() == null || action.getOwner().getLabel() == null
        ? NestedSetBuilder.<Cause>emptySet(Order.STABLE_ORDER)
        : NestedSetBuilder.<Cause>create(
            Order.STABLE_ORDER,
            new ActionFailed(
                action.getPrimaryOutput().getExecPath(),
                action.getOwner().getLabel(),
                action.getOwner().getConfigurationChecksum()));
  }

  /**
   * Returns the action that failed.
   */
  public Action getAction() {
    return action;
  }

  /**
   * Return the root causes that should be reported. Usually the owner of the action, but it can be
   * the label of a missing artifact.
   */
  public NestedSet<Cause> getRootCauses() {
    return rootCauses;
  }

  /**
   * Returns the location of the owner of this action.  May be null.
   */
  public Location getLocation() {
    return action.getOwner().getLocation();
  }

  /**
   * Catastrophic exceptions should stop builds, even if --keep_going.
   */
  public boolean isCatastrophe() {
    return catastrophe;
  }

  @Nullable public ExitCode getExitCode() {
    return exitCode;
  }

  /**
   * Returns true if the error should be shown.
   */
  public boolean showError() {
    return getMessage() != null;
  }
}
