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

import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.skylarkinterface.Param;
import com.google.devtools.build.lib.skylarkinterface.ParamType;
import com.google.devtools.build.lib.skylarkinterface.SkylarkCallable;
import com.google.devtools.build.lib.skylarkinterface.SkylarkModule;
import com.google.devtools.build.lib.skylarkinterface.SkylarkModuleCategory;
import com.google.devtools.build.lib.syntax.EvalException;
import com.google.devtools.build.lib.syntax.SkylarkList;
import com.google.devtools.build.lib.syntax.SkylarkNestedSet;

/**
 * Interface for an object representing the type of file.
 */
@SkylarkModule(
    name = "FileType",
    category = SkylarkModuleCategory.NONE,
    doc =
        "Deprecated. File type for file filtering. Can be used to filter collections of labels "
            + "for certain file types."
)
public interface FileTypeApi<FileApiT extends FileApi> {

  @SkylarkCallable(
    name = "filter",
    doc =
      "Returns a list created from the elements of the parameter containing all the "
          + "<a href=\"File.html\"><code>File</code></a>s that match the FileType.",
      parameters = {
        @Param(
            name = "files",
            positional = true,
            named = false,
            allowedTypes = {
                @ParamType(type = SkylarkNestedSet.class),
                @ParamType(type = SkylarkList.class)
            },
            doc = "The files to match. This parameter "
                + "must be a <a href=\"depset.html\"><code>depset</code></a> or a "
                + "<a href=\"list.html\"><code>list</code></a>."
        )
      }
  )
  public ImmutableList<FileApiT> filter(Object filesUnchecked) throws EvalException;
}
