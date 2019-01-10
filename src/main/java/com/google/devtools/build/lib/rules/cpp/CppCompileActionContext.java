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
package com.google.devtools.build.lib.rules.cpp;

import com.google.devtools.build.lib.actions.ActionContext;
import com.google.devtools.build.lib.actions.ActionContextMarker;
import com.google.devtools.build.lib.actions.ActionExecutionContext;
import com.google.devtools.build.lib.actions.ExecException;
import java.io.IOException;

/**
 * Context for compiling plain C++.
 */
@ActionContextMarker(name = "C++")
public interface CppCompileActionContext extends ActionContext {
  /**
   * Reply for the execution of a C++ compilation.
   */
  public interface Reply {
    /**
     * Returns the contents of the .d file.
     */
    byte[] getContents() throws IOException;
  }

  /**
   * Executes the given action and return the reply of the executor.
   *
   * @return a CppCompileActionResult with information resulting from the action's execution
   */
  CppCompileActionResult execWithReply(
      CppCompileAction action, ActionExecutionContext actionExecutionContext)
      throws ExecException, InterruptedException;
}
