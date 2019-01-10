// Copyright 2015 The Bazel Authors. All rights reserved.
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

import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.skylarkinterface.SkylarkCallable;
import com.google.devtools.build.lib.skylarkinterface.SkylarkModule;
import com.google.devtools.build.lib.skylarkinterface.SkylarkModuleCategory;

/** Configuration fragment for Android rules. */
@SkylarkModule(
    name = "android",
    doc =
        "Do not use this module. It is intended for migration purposes only. If you depend on it, "
            + "you will be broken when it is removed. "
            + "A configuration fragment for Android.",
    documented = false,
    category = SkylarkModuleCategory.CONFIGURATION_FRAGMENT)
public interface AndroidConfigurationApi {

  @SkylarkCallable(
      name = "android_cpu",
      structField = true,
      doc = "The Android target CPU.",
      documented = false)
  String getCpu();

  @SkylarkCallable(
      name = "use_incremental_native_libs",
      structField = true,
      doc = "",
      documented = false)
  boolean useIncrementalNativeLibs();

  @SkylarkCallable(
      name = "use_incremental_dexing",
      structField = true,
      doc = "",
      documented = false)
  boolean useIncrementalDexing();

  @SkylarkCallable(
      name = "incremental_dexing_shards_after_proguard",
      structField = true,
      doc = "",
      documented = false)
  int incrementalDexingShardsAfterProguard();

  @SkylarkCallable(
      name = "incremental_dexing_use_dex_sharder",
      structField = true,
      doc = "",
      documented = false)
  boolean incrementalDexingUseDexSharder();

  @SkylarkCallable(
      name = "incremental_dexing_after_proguard_by_default",
      structField = true,
      doc = "",
      documented = false)
  boolean incrementalDexingAfterProguardByDefault();

  @SkylarkCallable(
      name = "assume_min_sdk_version",
      structField = true,
      doc = "",
      documented = false)
  boolean assumeMinSdkVersion();

  @SkylarkCallable(
      name = "get_dexopts_supported_in_incremental_dexing",
      structField = true,
      doc = "",
      documented = false)
  ImmutableList<String> getDexoptsSupportedInIncrementalDexing();

  @SkylarkCallable(
      name = "get_dexopts_supported_in_dex_merger",
      structField = true,
      doc = "",
      documented = false)
  ImmutableList<String> getDexoptsSupportedInDexMerger();

  @SkylarkCallable(
      name = "get_target_dexopts_that_prevent_incremental_dexing",
      structField = true,
      doc = "",
      documented = false)
  ImmutableList<String> getTargetDexoptsThatPreventIncrementalDexing();

  @SkylarkCallable(
      name = "use_workers_with_dexbuilder",
      structField = true,
      doc = "",
      documented = false)
  boolean useWorkersWithDexbuilder();

  @SkylarkCallable(name = "desugar_java8", structField = true, doc = "", documented = false)
  boolean desugarJava8();

  @SkylarkCallable(name = "get_output_driecot", structField = true, doc = "", documented = false)
  boolean desugarJava8Libs();

  @SkylarkCallable(name = "check_desugar_deps", structField = true, doc = "", documented = false)
  boolean checkDesugarDeps();

  @SkylarkCallable(
      name = "use_rex_to_compress_dex_files",
      structField = true,
      doc = "",
      documented = false)
  boolean useRexToCompressDexFiles();

  @SkylarkCallable(
      name = "use_android_resource_shrinking",
      structField = true,
      doc = "",
      documented = false)
  boolean useAndroidResourceShrinking();

  @SkylarkCallable(
      name = "use_android_resource_cycle_shrinking",
      structField = true,
      doc = "",
      documented = false)
  boolean useAndroidResourceCycleShrinking();

  @SkylarkCallable(
      name = "use_single_jar_apk_builder",
      structField = true,
      doc = "",
      documented = false)
  boolean useSingleJarApkBuilder();

  @SkylarkCallable(name = "use_parallel_dex2oat", structField = true, doc = "", documented = false)
  boolean useParallelDex2Oat();

  @SkylarkCallable(
      name = "compress_java_resources",
      structField = true,
      doc = "",
      documented = false)
  boolean compressJavaResources();

  @SkylarkCallable(
      name = "get_exports_manifest_default",
      structField = true,
      doc = "",
      documented = false)
  boolean getExportsManifestDefault();

  @SkylarkCallable(
      name = "use_aapt2_for_robolectric",
      structField = true,
      doc = "",
      documented = false)
  boolean useAapt2ForRobolectric();

  @SkylarkCallable(
      name = "throw_on_resource_conflict",
      structField = true,
      doc = "",
      documented = false)
  boolean throwOnResourceConflict();

  @SkylarkCallable(name = "skip_parsing_action", structField = true, doc = "", documented = false)
  boolean skipParsingAction();

  @SkylarkCallable(
      name = "fixed_resource_neverlinking",
      structField = true,
      doc = "",
      documented = false)
  boolean fixedResourceNeverlinking();

  @SkylarkCallable(
      name = "check_for_migration_tag",
      structField = true,
      doc = "",
      documented = false)
  boolean checkForMigrationTag();

  @SkylarkCallable(
      name = "get_one_version_enforcement_use_transitive_jars_for_binary_under_test",
      structField = true,
      doc = "",
      documented = false)
  boolean getOneVersionEnforcementUseTransitiveJarsForBinaryUnderTest();

  @SkylarkCallable(name = "use_databinding_v2", structField = true, doc = "", documented = false)
  boolean useDataBindingV2();

  @SkylarkCallable(
      name = "persistent_busybox_tools",
      structField = true,
      doc = "",
      documented = false)
  boolean persistentBusyboxTools();

  @SkylarkCallable(
      name = "get_output_directory_name",
      structField = true,
      doc = "",
      documented = false)
  String getOutputDirectoryName();
}
