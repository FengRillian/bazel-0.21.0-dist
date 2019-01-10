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

package com.google.devtools.build.lib.util;

/**
 * An exception thrown by various error conditions that are severe enough to halt the command (e.g.
 * even a --keep_going build). These typically need to signal to the handling code what happened.
 * Therefore, these exceptions contain a recommended ExitCode allowing the exception to "set" a
 * returned numeric exit code.
 *
 * When an instance of this exception is thrown, Blaze will try to halt as soon as reasonably
 * possible.
 */
public class AbruptExitException extends Exception {

  private final ExitCode exitCode;

  public AbruptExitException(String message, ExitCode exitCode) {
    super(message);
    this.exitCode = exitCode;
  }

  public AbruptExitException(String message, ExitCode exitCode, Throwable cause) {
    super(message, cause);
    this.exitCode = exitCode;
  }

  public AbruptExitException(ExitCode exitCode, Throwable cause) {
    super(cause);
    this.exitCode = exitCode;
  }

  public ExitCode getExitCode() {
    return exitCode;
  }
}
