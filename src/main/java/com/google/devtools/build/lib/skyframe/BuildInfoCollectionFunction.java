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
package com.google.devtools.build.lib.skyframe;

import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.actions.ActionKeyContext;
import com.google.devtools.build.lib.actions.Actions;
import com.google.devtools.build.lib.actions.Actions.GeneratingActions;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.ArtifactFactory;
import com.google.devtools.build.lib.actions.ArtifactRoot;
import com.google.devtools.build.lib.actions.MutableActionGraph.ActionConflictException;
import com.google.devtools.build.lib.analysis.buildinfo.BuildInfoCollection;
import com.google.devtools.build.lib.analysis.buildinfo.BuildInfoFactory;
import com.google.devtools.build.lib.analysis.buildinfo.BuildInfoFactory.BuildInfoContext;
import com.google.devtools.build.lib.analysis.buildinfo.BuildInfoFactory.BuildInfoKey;
import com.google.devtools.build.lib.analysis.buildinfo.BuildInfoFactory.BuildInfoType;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.lib.skyframe.BuildInfoCollectionValue.BuildInfoKeyAndConfig;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.skyframe.SkyFunction;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import java.util.Map;

/**
 * Creates a {@link BuildInfoCollectionValue}. Only depends on the unique
 * {@link WorkspaceStatusValue} and the constant {@link PrecomputedValue#BUILD_INFO_FACTORIES}
 * injected value.
 */
public class BuildInfoCollectionFunction implements SkyFunction {
  private final ActionKeyContext actionKeyContext;
  // Supplier only because the artifact factory has not yet been created at constructor time.
  private final Supplier<ArtifactFactory> artifactFactory;
  private final ImmutableMap<BuildInfoKey, BuildInfoFactory> buildInfoFactories;

  BuildInfoCollectionFunction(
      ActionKeyContext actionKeyContext,
      Supplier<ArtifactFactory> artifactFactory,
      ImmutableMap<BuildInfoKey, BuildInfoFactory> buildInfoFactories) {
    this.actionKeyContext = actionKeyContext;
    this.artifactFactory = artifactFactory;
    this.buildInfoFactories = buildInfoFactories;
  }

  @Override
  public SkyValue compute(SkyKey skyKey, Environment env) throws InterruptedException {
    final BuildInfoKeyAndConfig keyAndConfig = (BuildInfoKeyAndConfig) skyKey.argument();
    ImmutableSet<SkyKey> keysToRequest =
        ImmutableSet.of(
            WorkspaceStatusValue.BUILD_INFO_KEY,
            WorkspaceNameValue.key(),
            keyAndConfig.getConfigKey());
    Map<SkyKey, SkyValue> result = env.getValues(keysToRequest);
    if (env.valuesMissing()) {
      return null;
    }
    WorkspaceStatusValue infoArtifactValue =
        (WorkspaceStatusValue) result.get(WorkspaceStatusValue.BUILD_INFO_KEY);
    WorkspaceNameValue nameValue = (WorkspaceNameValue) result.get(WorkspaceNameValue.key());
    RepositoryName repositoryName = RepositoryName.createFromValidStrippedName(
        nameValue.getName());

    final ArtifactFactory factory = artifactFactory.get();
    BuildInfoContext context =
        new BuildInfoContext() {
          @Override
          public Artifact getBuildInfoArtifact(
              PathFragment rootRelativePath, ArtifactRoot root, BuildInfoType type) {
            return type == BuildInfoType.NO_REBUILD
                ? factory.getConstantMetadataArtifact(rootRelativePath, root, keyAndConfig)
                : factory.getDerivedArtifact(rootRelativePath, root, keyAndConfig);
          }
        };
    BuildInfoCollection collection =
        buildInfoFactories
            .get(keyAndConfig.getInfoKey())
            .create(
                context,
                ((BuildConfigurationValue) result.get(keyAndConfig.getConfigKey()))
                    .getConfiguration(),
                infoArtifactValue.getStableArtifact(),
                infoArtifactValue.getVolatileArtifact(),
                repositoryName);
    GeneratingActions generatingActions;
    try {
      generatingActions =
          Actions.filterSharedActionsAndThrowActionConflict(
              actionKeyContext, collection.getActions());
    } catch (ActionConflictException e) {
      throw new IllegalStateException("Action conflicts not expected in build info: " + skyKey, e);
    }
    return new BuildInfoCollectionValue(collection, generatingActions);
  }

  @Override
  public String extractTag(SkyKey skyKey) {
    return null;
  }
}
