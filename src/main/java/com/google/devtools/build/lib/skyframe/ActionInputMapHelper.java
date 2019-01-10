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
package com.google.devtools.build.lib.skyframe;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.devtools.build.lib.actions.ActionAnalysisMetadata;
import com.google.devtools.build.lib.actions.ActionInputMapSink;
import com.google.devtools.build.lib.actions.ActionLookupValue;
import com.google.devtools.build.lib.actions.ActionLookupValue.ActionLookupKey;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.FileArtifactValue;
import com.google.devtools.build.lib.actions.FilesetOutputSymlink;
import com.google.devtools.build.lib.analysis.actions.SymlinkAction;
import com.google.devtools.build.lib.util.Pair;
import com.google.devtools.build.skyframe.SkyFunction.Environment;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import java.util.Collection;
import java.util.Map;

class ActionInputMapHelper {

  // Adds a value obtained by an Artifact skyvalue lookup to the action input map. May do Skyframe
  // lookups.
  static void addToMap(
      ActionInputMapSink inputMap,
      Map<Artifact, Collection<Artifact>> expandedArtifacts,
      Map<Artifact, ImmutableList<FilesetOutputSymlink>> expandedFilesets,
      Artifact key,
      SkyValue value,
      Environment env)
      throws InterruptedException {
    if (value instanceof AggregatingArtifactValue) {
      AggregatingArtifactValue aggregatingValue = (AggregatingArtifactValue) value;
      for (Pair<Artifact, FileArtifactValue> entry : aggregatingValue.getFileArtifacts()) {
        Artifact artifact = entry.first;
        inputMap.put(artifact, entry.second, /*depOwner=*/ key);
        if (artifact.isFileset()) {
          ImmutableList<FilesetOutputSymlink> expandedFileset = getFilesets(env, artifact);
          if (expandedFileset != null) {
            expandedFilesets.put(artifact, expandedFileset);
          }
        }
      }
      for (Pair<Artifact, TreeArtifactValue> entry : aggregatingValue.getTreeArtifacts()) {
        expandTreeArtifactAndPopulateArtifactData(
            entry.getFirst(),
            Preconditions.checkNotNull(entry.getSecond()),
            expandedArtifacts,
            inputMap,
            /*depOwner=*/ key);
      }
      // We have to cache the "digest" of the aggregating value itself,
      // because the action cache checker may want it.
      inputMap.put(key, aggregatingValue.getSelfData(), /*depOwner=*/ key);
      // While not obvious at all this code exists to ensure that we don't expand the
      // .runfiles/MANIFEST file into the inputs. The reason for that being that the MANIFEST
      // file contains absolute paths that don't work with remote execution.
      // Instead, the way the SpawnInputExpander expands runfiles is via the Runfiles class
      // which contains all artifacts in the runfiles tree minus the MANIFEST file.
      // TODO(buchgr): Clean this up and get rid of the RunfilesArtifactValue type.
      if (!(value instanceof RunfilesArtifactValue)) {
        ImmutableList.Builder<Artifact> expansionBuilder = ImmutableList.builder();
        for (Pair<Artifact, FileArtifactValue> pair : aggregatingValue.getFileArtifacts()) {
          expansionBuilder.add(Preconditions.checkNotNull(pair.getFirst()));
        }
        expandedArtifacts.put(key, expansionBuilder.build());
      }
    } else if (value instanceof TreeArtifactValue) {
      expandTreeArtifactAndPopulateArtifactData(
          key, (TreeArtifactValue) value, expandedArtifacts, inputMap, /*depOwner=*/ key);
    } else {
      Preconditions.checkState(value instanceof FileArtifactValue);
      inputMap.put(key, (FileArtifactValue) value, /*depOwner=*/ key);
    }
  }

  static ImmutableList<FilesetOutputSymlink> getFilesets(Environment env,
      Artifact actionInput) throws InterruptedException {
    Preconditions.checkState(actionInput.isFileset(), actionInput);
    ActionLookupKey filesetActionLookupKey = (ActionLookupKey) actionInput.getArtifactOwner();

    ActionLookupValue filesetActionLookupValue =
        (ActionLookupValue) env.getValue(filesetActionLookupKey);

    ActionAnalysisMetadata generatingAction =
        filesetActionLookupValue.getGeneratingActionDangerousReadJavadoc(actionInput);
    int filesetManifestActionIndex;

    if (generatingAction instanceof SymlinkAction) {
      Artifact outputManifest = Iterables.getOnlyElement(generatingAction.getInputs());
      ActionAnalysisMetadata symlinkTreeAction =
          filesetActionLookupValue.getGeneratingActionDangerousReadJavadoc(outputManifest);
      Artifact inputManifest = Iterables.getOnlyElement(symlinkTreeAction.getInputs());
      filesetManifestActionIndex = filesetActionLookupValue.getGeneratingActionIndex(inputManifest);
    } else {
      filesetManifestActionIndex = filesetActionLookupValue.getGeneratingActionIndex(actionInput);
    }

    SkyKey filesetActionKey =
        ActionExecutionValue.key(filesetActionLookupKey, filesetManifestActionIndex);
    ActionExecutionValue filesetValue = (ActionExecutionValue) env.getValue(filesetActionKey);
    if (filesetValue == null) {
      // At this point skyframe does not guarantee that the filesetValue will be ready, since
      // the current action does not directly depend on the outputs of the
      // SkyframeFilesetManifestAction whose ActionExecutionValue (filesetValue) is needed here.
      return null;
    }
    return filesetValue.getOutputSymlinks();
  }

  private static void expandTreeArtifactAndPopulateArtifactData(
      Artifact treeArtifact,
      TreeArtifactValue value,
      Map<Artifact, Collection<Artifact>> expandedArtifacts,
      ActionInputMapSink inputMap,
      Artifact depOwner) {
    ImmutableSet.Builder<Artifact> children = ImmutableSet.builder();
    for (Map.Entry<Artifact.TreeFileArtifact, FileArtifactValue> child :
        value.getChildValues().entrySet()) {
      children.add(child.getKey());
      inputMap.put(child.getKey(), child.getValue(), depOwner);
    }
    expandedArtifacts.put(treeArtifact, children.build());
    // Again, we cache the "digest" of the value for cache checking.
    inputMap.put(treeArtifact, value.getSelfData(), depOwner);
  }
}
