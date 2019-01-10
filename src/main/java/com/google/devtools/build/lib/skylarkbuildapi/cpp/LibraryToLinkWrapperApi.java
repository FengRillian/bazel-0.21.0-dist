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

package com.google.devtools.build.lib.skylarkbuildapi.cpp;

import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.skylarkinterface.SkylarkCallable;
import com.google.devtools.build.lib.skylarkinterface.SkylarkModule;
import com.google.devtools.build.lib.skylarkinterface.SkylarkModuleCategory;

/**
 * A library the user can link to. This is different from a simple linker input in that it also has
 * a library identifier.
 */
@SkylarkModule(
    name = "LibraryToLink",
    category = SkylarkModuleCategory.BUILTIN,
    documented = false,
    doc = "A library the user can link against.")
public interface LibraryToLinkWrapperApi {
  @SkylarkCallable(
      name = "static_library",
      documented = false,
      allowReturnNones = true,
      structField = true)
  Artifact getStaticLibrary();

  @SkylarkCallable(
      name = "pic_static_library",
      documented = false,
      allowReturnNones = true,
      structField = true)
  Artifact getPicStaticLibrary();

  @SkylarkCallable(
      name = "dynamic_library",
      documented = false,
      allowReturnNones = true,
      structField = true)
  Artifact getDynamicLibrary();

  @SkylarkCallable(
      name = "interface_library",
      documented = false,
      allowReturnNones = true,
      structField = true)
  Artifact getInterfaceLibrary();
}
