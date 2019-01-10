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
package com.google.devtools.build.lib.skylarkbuildapi.android;

import com.google.devtools.build.lib.events.Location;
import com.google.devtools.build.lib.skylarkbuildapi.FileApi;
import com.google.devtools.build.lib.skylarkbuildapi.ProviderApi;
import com.google.devtools.build.lib.skylarkbuildapi.StructApi;
import com.google.devtools.build.lib.skylarkinterface.Param;
import com.google.devtools.build.lib.skylarkinterface.SkylarkCallable;
import com.google.devtools.build.lib.skylarkinterface.SkylarkModule;
import com.google.devtools.build.lib.skylarkinterface.SkylarkModuleCategory;
import com.google.devtools.build.lib.syntax.EvalException;
import com.google.devtools.build.lib.syntax.SkylarkDict;

/** A provider for targets that produce an apk file. */
@SkylarkModule(
    name = "ApkInfo",
    doc =
        "Do not use this module. It is intended for migration purposes only. If you depend on it, "
            + "you will be broken when it is removed."
            + "APKs provided by a rule",
    documented = false,
    category = SkylarkModuleCategory.PROVIDER)
public interface ApkInfoApi<FileT extends FileApi> extends StructApi {

  /**
   * Name of this info object.
   */
  public static String NAME = "ApkInfo";

  /** Returns the APK file built in the transitive closure. */
  @SkylarkCallable(
      name = "signed_apk",
      doc = "Returns a signed APK built from the target.",
      documented = false,
      structField = true)
  FileT getApk();

  /** Provider for {@link ApkInfoApi}. */
  @SkylarkModule(
      name = "ApkInfoApiProvider",
      doc =
          "Do not use this module. It is intended for migration purposes only. If you depend on "
              + "it, you will be broken when it is removed.",
      documented = false)
  public interface ApkInfoApiProvider extends ProviderApi {

    @SkylarkCallable(
        name = "ApkInfo",
        // This is left undocumented as it throws a "not-implemented in Skylark" error when invoked.
        documented = false,
        extraKeywords = @Param(name = "kwargs"),
        useLocation = true,
        selfCall = true)
    public ApkInfoApi<?> createInfo(SkylarkDict<?, ?> kwargs, Location loc) throws EvalException;
  }
}
