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

package com.google.devtools.build.lib.skylarkbuildapi;

import com.google.devtools.build.lib.skylarkinterface.Param;
import com.google.devtools.build.lib.skylarkinterface.SkylarkCallable;
import com.google.devtools.build.lib.skylarkinterface.SkylarkModule;
import com.google.devtools.build.lib.skylarkinterface.SkylarkModuleCategory;
import com.google.devtools.build.lib.syntax.SkylarkNestedSet;

/**
 * Interface for a module associated with creating efficient command lines.
 */
@SkylarkModule(
    name = "cmd_helper",
    namespace = true,
    category = SkylarkModuleCategory.BUILTIN,
    doc = "Deprecated. Module for creating memory efficient command lines."
)
public interface SkylarkCommandLineApi {

  @SkylarkCallable(
    name = "join_paths",
    doc =
        "Deprecated. Creates a single command line argument joining the paths of a set "
            + "of files on the separator string.",
    parameters = {
      @Param(name = "separator", type = String.class, doc = "the separator string to join on."),
      @Param(
        name = "files",
        type = SkylarkNestedSet.class,
        generic1 = FileApi.class,
        doc = "the files to concatenate."
      )
    }
  )
  public String joinPaths(String separator, SkylarkNestedSet files);
}
