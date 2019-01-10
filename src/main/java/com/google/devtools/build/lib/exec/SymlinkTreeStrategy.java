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
package com.google.devtools.build.lib.exec;

import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.actions.ActionExecutionContext;
import com.google.devtools.build.lib.actions.ActionExecutionException;
import com.google.devtools.build.lib.actions.ExecException;
import com.google.devtools.build.lib.actions.ExecutionStrategy;
import com.google.devtools.build.lib.analysis.actions.SymlinkTreeAction;
import com.google.devtools.build.lib.analysis.actions.SymlinkTreeActionContext;
import com.google.devtools.build.lib.profiler.AutoProfiler;
import com.google.devtools.build.lib.vfs.OutputService;
import java.util.logging.Logger;

/**
 * Implements SymlinkTreeAction by using the output service or by running an embedded script to
 * create the symlink tree.
 */
@ExecutionStrategy(contextType = SymlinkTreeActionContext.class)
public final class SymlinkTreeStrategy implements SymlinkTreeActionContext {
  private static final Logger logger = Logger.getLogger(SymlinkTreeStrategy.class.getName());

  private final OutputService outputService;
  private final BinTools binTools;

  public SymlinkTreeStrategy(OutputService outputService, BinTools binTools) {
    this.outputService = outputService;
    this.binTools = binTools;
  }

  @Override
  public void createSymlinks(
      SymlinkTreeAction action,
      ActionExecutionContext actionExecutionContext,
      ImmutableMap<String, String> shellEnvironment,
      boolean enableRunfiles)
      throws ActionExecutionException, InterruptedException {
    try (AutoProfiler p =
        AutoProfiler.logged(
            "running " + action.prettyPrint(), logger, /*minTimeForLoggingInMilliseconds=*/ 100)) {
      try {
        if (outputService != null && outputService.canCreateSymlinkTree()) {
          outputService.createSymlinkTree(
              actionExecutionContext.getInputPath(action.getInputManifest()),
              actionExecutionContext.getInputPath(action.getOutputManifest()),
              action.isFilesetTree(),
              action.getOutputManifest().getExecPath().getParentDirectory());
        } else {
          SymlinkTreeHelper helper =
              new SymlinkTreeHelper(
                  actionExecutionContext.getInputPath(action.getInputManifest()),
                  actionExecutionContext
                      .getInputPath(action.getOutputManifest())
                      .getParentDirectory(),
                  action.isFilesetTree());
          helper.createSymlinks(actionExecutionContext, binTools, shellEnvironment, enableRunfiles);
        }
      } catch (ExecException e) {
        throw e.toActionExecutionException(
            action.getProgressMessage(), actionExecutionContext.getVerboseFailures(), action);
      }
    }
  }
}
