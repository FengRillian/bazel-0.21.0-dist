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

package com.google.devtools.build.lib.analysis.configuredtargets;

import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.analysis.AnalysisUtils;
import com.google.devtools.build.lib.analysis.FileProvider;
import com.google.devtools.build.lib.analysis.FilesToRunProvider;
import com.google.devtools.build.lib.analysis.LicensesProvider;
import com.google.devtools.build.lib.analysis.TransitiveInfoProvider;
import com.google.devtools.build.lib.analysis.TransitiveInfoProviderMap;
import com.google.devtools.build.lib.analysis.TransitiveInfoProviderMapBuilder;
import com.google.devtools.build.lib.analysis.VisibilityProvider;
import com.google.devtools.build.lib.analysis.test.InstrumentedFilesInfo;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.collect.nestedset.Order;
import com.google.devtools.build.lib.packages.InfoInterface;
import com.google.devtools.build.lib.packages.PackageSpecification.PackageGroupContents;
import com.google.devtools.build.lib.packages.Provider;
import com.google.devtools.build.lib.skyframe.BuildConfigurationValue;
import com.google.devtools.build.lib.util.FileType;
import javax.annotation.Nullable;

/**
 * A ConfiguredTarget for a source FileTarget. (Generated files use a subclass,
 * OutputFileConfiguredTarget.)
 */
public abstract class FileConfiguredTarget extends AbstractConfiguredTarget
    implements FileType.HasFileType, LicensesProvider {

  private final TransitiveInfoProviderMap providers;

  FileConfiguredTarget(
      Label label,
      BuildConfigurationValue.Key configurationKey,
      NestedSet<PackageGroupContents> visibility,
      Artifact artifact,
      @Nullable InstrumentedFilesInfo instrumentedFilesInfo) {
    super(label, configurationKey, visibility);
    NestedSet<Artifact> filesToBuild = NestedSetBuilder.create(Order.STABLE_ORDER, artifact);
    FileProvider fileProvider = new FileProvider(filesToBuild);
    FilesToRunProvider filesToRunProvider =
        FilesToRunProvider.fromSingleExecutableArtifact(artifact);
    TransitiveInfoProviderMapBuilder builder =
        new TransitiveInfoProviderMapBuilder()
            .put(VisibilityProvider.class, this)
            .put(LicensesProvider.class, this)
            .add(fileProvider)
            .add(filesToRunProvider);

    if (instrumentedFilesInfo != null) {
      builder.put(instrumentedFilesInfo);
    }
    this.providers = builder.build();
  }

  public abstract Artifact getArtifact();

  /**
   *  Returns the file name of this file target.
   */
  public String getFilename() {
    return getLabel().getName();
  }

  @Override
  public String filePathForFileTypeMatcher() {
    return getFilename();
  }

  @Override
  public <P extends TransitiveInfoProvider> P getProvider(Class<P> provider) {
    AnalysisUtils.checkProvider(provider);
    return providers.getProvider(provider);
  }

  @Override
  protected InfoInterface rawGetSkylarkProvider(Provider.Key providerKey) {
    return providers.get(providerKey);
  }

  @Override
  protected Object rawGetSkylarkProvider(String providerKey) {
    return providers.get(providerKey);
  }
}
