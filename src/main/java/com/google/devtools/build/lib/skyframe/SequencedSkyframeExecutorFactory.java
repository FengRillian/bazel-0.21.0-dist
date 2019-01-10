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

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.actions.ActionKeyContext;
import com.google.devtools.build.lib.analysis.BlazeDirectories;
import com.google.devtools.build.lib.analysis.WorkspaceStatusAction.Factory;
import com.google.devtools.build.lib.analysis.buildinfo.BuildInfoFactory;
import com.google.devtools.build.lib.analysis.config.BuildOptions;
import com.google.devtools.build.lib.packages.PackageFactory;
import com.google.devtools.build.lib.skyframe.SkyframeExecutor.MutableArtifactFactorySupplier;
import com.google.devtools.build.lib.vfs.FileSystem;
import com.google.devtools.build.skyframe.InMemoryMemoizingEvaluator;
import com.google.devtools.build.skyframe.SkyFunction;
import com.google.devtools.build.skyframe.SkyFunctionName;

/**
 * A factory of SkyframeExecutors that returns SequencedSkyframeExecutor.
 */
public class SequencedSkyframeExecutorFactory implements SkyframeExecutorFactory {

  private final BuildOptions defaultBuildOptions;

  public SequencedSkyframeExecutorFactory(BuildOptions defaultBuildOptions) {
    this.defaultBuildOptions = defaultBuildOptions;
  }

  @Override
  public SkyframeExecutor create(
      PackageFactory pkgFactory,
      FileSystem fileSystem,
      BlazeDirectories directories,
      ActionKeyContext actionKeyContext,
      Factory workspaceStatusActionFactory,
      ImmutableList<BuildInfoFactory> buildInfoFactories,
      Iterable<? extends DiffAwareness.Factory> diffAwarenessFactories,
      ImmutableMap<SkyFunctionName, SkyFunction> extraSkyFunctions,
      Iterable<SkyValueDirtinessChecker> customDirtinessCheckers) {
    return SequencedSkyframeExecutor.create(
        InMemoryMemoizingEvaluator.SUPPLIER,
        pkgFactory,
        fileSystem,
        directories,
        actionKeyContext,
        workspaceStatusActionFactory,
        buildInfoFactories,
        diffAwarenessFactories,
        extraSkyFunctions,
        customDirtinessCheckers,
        BazelSkyframeExecutorConstants.HARDCODED_BLACKLISTED_PACKAGE_PREFIXES,
        BazelSkyframeExecutorConstants.ADDITIONAL_BLACKLISTED_PACKAGE_PREFIXES_FILE,
        BazelSkyframeExecutorConstants.CROSS_REPOSITORY_LABEL_VIOLATION_STRATEGY,
        BazelSkyframeExecutorConstants.BUILD_FILES_BY_PRIORITY,
        BazelSkyframeExecutorConstants.ACTION_ON_IO_EXCEPTION_READING_BUILD_FILE,
        defaultBuildOptions,
        new MutableArtifactFactorySupplier());
  }
}
