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

package com.google.devtools.build.lib.skylarkbuildapi.java;

import com.google.devtools.build.lib.skylarkbuildapi.FileApi;
import com.google.devtools.build.lib.skylarkinterface.SkylarkCallable;
import com.google.devtools.build.lib.skylarkinterface.SkylarkModule;

/**
 * Provides access to information about the Java toolchain rule.
 * Accessible as a 'java_toolchain' field on a Target struct.
 */
@SkylarkModule(
    name = "JavaToolchainSkylarkApiProvider",
    doc =
        "Provides access to information about the Java toolchain rule. "
            + "Accessible as a 'java_toolchain' field on a Target struct."
)
public interface JavaToolchainSkylarkApiProviderApi {

  @SkylarkCallable(name = "source_version", doc = "The java source version.", structField = true)
  public String getSourceVersion();

  @SkylarkCallable(name = "target_version", doc = "The java target version.", structField = true)
  public String getTargetVersion();

  @SkylarkCallable(
      name = "javac_jar",
      doc = "The javac jar.",
      structField = true
  )
  public FileApi getJavacJar();
}
