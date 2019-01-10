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

import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import com.google.common.collect.Iterables;
import com.google.devtools.build.lib.actions.ActionLookupData;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.ArtifactSkyKey;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.pkgcache.PackageProvider;
import com.google.devtools.build.lib.skyframe.TargetCompletionValue.TargetCompletionKey;
import com.google.devtools.build.lib.skyframe.TestCompletionValue.TestCompletionKey;
import com.google.devtools.build.skyframe.CycleInfo;
import com.google.devtools.build.skyframe.SkyFunctionName;
import com.google.devtools.build.skyframe.SkyKey;

/**
 * Reports cycles between Actions and Artifacts. These indicates cycles within a rule.
 */
public class ActionArtifactCycleReporter extends AbstractLabelCycleReporter {
  @SuppressWarnings("unchecked")
  private static final Predicate<SkyKey> IS_ARTIFACT_OR_ACTION_SKY_KEY =
      Predicates.or(
          SkyFunctions.isSkyFunction(Artifact.ARTIFACT),
          SkyFunctions.isSkyFunction(SkyFunctions.ACTION_EXECUTION),
          SkyFunctions.isSkyFunction(SkyFunctions.TARGET_COMPLETION),
          SkyFunctions.isSkyFunction(SkyFunctions.TEST_COMPLETION));

  ActionArtifactCycleReporter(PackageProvider packageProvider) {
    super(packageProvider);
  }

  @Override
  protected String prettyPrint(SkyKey key) {
    return prettyPrint(key.functionName(), key.argument());
  }

  private static String prettyPrintArtifact(Artifact artifact) {
    return "file: " + artifact.getRootRelativePathString();
  }

  private static String prettyPrint(SkyFunctionName skyFunctionName, Object arg) {
    if (arg instanceof ArtifactSkyKey) {
      return prettyPrintArtifact(((ArtifactSkyKey) arg).getArtifact());
    } else if (arg instanceof Artifact) {
      return prettyPrintArtifact(((Artifact) arg));
    } else if (arg instanceof ActionLookupData) {
      return "action from: " + arg;
    } else if (arg instanceof TargetCompletionKey
        && skyFunctionName.equals(SkyFunctions.TARGET_COMPLETION)) {
      return "configured target: " + ((TargetCompletionKey) arg).configuredTargetKey().getLabel();
    } else if (arg instanceof TestCompletionKey
        && skyFunctionName.equals(SkyFunctions.TEST_COMPLETION)) {
      return "test target: " + ((TestCompletionKey) arg).configuredTargetKey().getLabel();
    }
    throw new IllegalStateException(
        "Argument is not Action, TargetCompletion, TestCompletion or ArtifactSkyKey: " + arg);
  }

  @Override
  protected Label getLabel(SkyKey key) {
    Object arg = key.argument();
    if (arg instanceof ArtifactSkyKey) {
      return ((ArtifactSkyKey) arg).getArtifact().getOwner();
    } else if (arg instanceof Artifact) {
      return ((Artifact) arg).getOwner();
    } else if (arg instanceof ActionLookupData) {
      return ((ActionLookupData) arg).getLabelForErrors();
    } else if (arg instanceof TargetCompletionKey
        && key.functionName().equals(SkyFunctions.TARGET_COMPLETION)) {
      return ((TargetCompletionKey) arg).configuredTargetKey().getLabel();
    } else if (arg instanceof TestCompletionKey
        && key.functionName().equals(SkyFunctions.TEST_COMPLETION)) {
      return ((TestCompletionKey) arg).configuredTargetKey().getLabel();
    }
    throw new IllegalStateException(
        "Argument is not Action, TargetCompletion, TestCompletion or ArtifactSkyKey: " + arg);
  }

  @Override
  protected boolean canReportCycle(SkyKey topLevelKey, CycleInfo cycleInfo) {
    return IS_ARTIFACT_OR_ACTION_SKY_KEY.apply(topLevelKey)
        && Iterables.all(cycleInfo.getCycle(), IS_ARTIFACT_OR_ACTION_SKY_KEY);
  }
}
