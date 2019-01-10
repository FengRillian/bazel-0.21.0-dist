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

import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.skylarkinterface.SkylarkCallable;
import com.google.devtools.build.lib.skylarkinterface.SkylarkModule;
import com.google.devtools.build.lib.skylarkinterface.SkylarkModuleCategory;

/** Interface for a configuration object which holds information about the build environment. */
@SkylarkModule(
    name = "configuration",
    category = SkylarkModuleCategory.BUILTIN,
    doc = "This object holds information about the environment in which the build is running. See "
        + "the <a href='../rules.$DOC_EXT#configurations'>Rules page</a> for more on the general "
        + "concept of configurations."
)
public interface BuildConfigurationApi {

  @SkylarkCallable(name = "bin_dir", structField = true, documented = false)
  @Deprecated
  public FileRootApi getBinDir();

  @SkylarkCallable(name = "genfiles_dir", structField = true, documented = false)
  @Deprecated
  public FileRootApi getGenfilesDir();

  @SkylarkCallable(name = "host_path_separator", structField = true,
      doc = "Returns the separator for PATH environment variable, which is ':' on Unix.")
  public String getHostPathSeparator();

  @SkylarkCallable(
    name = "default_shell_env",
    structField = true,
    doc =
        "A dictionary representing the static local shell environment. It maps variables "
            + "to their values (strings)."
  )
  @Deprecated // Use getActionEnvironment instead.
  public ImmutableMap<String, String> getLocalShellEnvironment();

  @SkylarkCallable(
    name = "test_env",
    structField = true,
    doc =
        "A dictionary containing user-specified test environment variables and their values, "
            + "as set by the --test_env options. DO NOT USE! This is not the complete environment!"
  )
  public ImmutableMap<String, String> getTestEnv();

  @SkylarkCallable(name = "coverage_enabled", structField = true,
      doc = "A boolean that tells whether code coverage is enabled for this run. Note that this "
          + "does not compute whether a specific rule should be instrumented for code coverage "
          + "data collection. For that, see the <a href=\"ctx.html#coverage_instrumented\"><code>"
          + "ctx.coverage_instrumented</code></a> function.")
  public boolean isCodeCoverageEnabled();
}
