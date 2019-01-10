// Copyright 2016 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.rules.cpp;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.actions.AbstractAction;
import com.google.devtools.build.lib.actions.ActionEnvironment;
import com.google.devtools.build.lib.actions.ActionExecutionContext;
import com.google.devtools.build.lib.actions.ActionExecutionException;
import com.google.devtools.build.lib.actions.ActionKeyContext;
import com.google.devtools.build.lib.actions.ActionOwner;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.CommandLineExpansionException;
import com.google.devtools.build.lib.actions.CommandLines;
import com.google.devtools.build.lib.actions.CommandLines.CommandLineLimits;
import com.google.devtools.build.lib.actions.ResourceSet;
import com.google.devtools.build.lib.actions.RunfilesSupplier;
import com.google.devtools.build.lib.analysis.actions.SpawnAction;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.util.Fingerprint;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.io.IOException;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * Action used by LtoBackendArtifacts to create an LtoBackendAction. Similar to {@link SpawnAction},
 * except that inputs are discovered from the imports file created by the ThinLTO indexing step for
 * each backend artifact.
 *
 * <p>See {@link LtoBackendArtifacts} for a high level description of the ThinLTO build process. The
 * LTO indexing step takes all bitcode .o files and decides which other .o file symbols can be
 * imported/inlined. The additional input files for each backend action are then written to an
 * imports file. Therefore these new inputs must be discovered here by subsetting the imports paths
 * from the set of all bitcode artifacts, before executing the backend action.
 *
 * <p>For more information on ThinLTO see
 * http://blog.llvm.org/2016/06/thinlto-scalable-and-incremental-lto.html.
 */
public final class LtoBackendAction extends SpawnAction {
  private Collection<Artifact> mandatoryInputs;
  private Map<PathFragment, Artifact> bitcodeFiles;
  private Artifact imports;

  private static final String GUID = "72ce1eca-4625-4e24-a0d8-bb91bb8b0e0e";

  public LtoBackendAction(
      Collection<Artifact> inputs,
      Map<PathFragment, Artifact> allBitcodeFiles,
      Artifact importsFile,
      Collection<Artifact> outputs,
      Artifact primaryOutput,
      ActionOwner owner,
      CommandLines argv,
      CommandLineLimits commandLineLimits,
      boolean isShellCommand,
      ActionEnvironment env,
      Map<String, String> executionInfo,
      CharSequence progressMessage,
      RunfilesSupplier runfilesSupplier,
      String mnemonic) {
    super(
        owner,
        ImmutableList.<Artifact>of(),
        inputs,
        outputs,
        primaryOutput,
        AbstractAction.DEFAULT_RESOURCE_SET,
        argv,
        commandLineLimits,
        isShellCommand,
        env,
        ImmutableMap.copyOf(executionInfo),
        progressMessage,
        runfilesSupplier,
        mnemonic,
        false,
        null);
    mandatoryInputs = inputs;
    Preconditions.checkState(
        (bitcodeFiles == null) == (imports == null),
        "Either both or neither bitcodeFiles and imports files should be null");
    bitcodeFiles = allBitcodeFiles;
    imports = importsFile;
  }

  @Override
  public boolean isShareable() {
    return !discoversInputs();
  }

  @Override
  public boolean discoversInputs() {
    return imports != null;
  }

  private Set<Artifact> computeBitcodeInputs(Collection<PathFragment> inputPaths) {
    HashSet<Artifact> bitcodeInputs = new HashSet<>();
    for (PathFragment inputPath : inputPaths) {
      Artifact inputArtifact = bitcodeFiles.get(inputPath);
      if (inputArtifact != null) {
        bitcodeInputs.add(inputArtifact);
      }
    }
    return bitcodeInputs;
  }

  @Nullable
  @Override
  public Iterable<Artifact> discoverInputs(ActionExecutionContext actionExecutionContext)
      throws ActionExecutionException, InterruptedException {
    // Build set of files this LTO backend artifact will import from.
    HashSet<PathFragment> importSet = new HashSet<>();
    try {
      for (String line :
          FileSystemUtils.iterateLinesAsLatin1(actionExecutionContext.getInputPath(imports))) {
        if (!line.isEmpty()) {
          PathFragment execPath = PathFragment.create(line);
          if (execPath.isAbsolute()) {
            throw new ActionExecutionException(
                "Absolute paths not allowed in imports file "
                    + actionExecutionContext.getInputPath(imports)
                    + ": "
                    + execPath,
                this,
                false);
          }
          importSet.add(PathFragment.create(line));
        }
      }
    } catch (IOException e) {
      throw new ActionExecutionException(
          "error iterating imports file " + actionExecutionContext.getInputPath(imports),
          e,
          this,
          false);
    }

    // Convert the import set of paths to the set of bitcode file artifacts.
    Set<Artifact> bitcodeInputSet = computeBitcodeInputs(importSet);
    if (bitcodeInputSet.size() != importSet.size()) {
      throw new ActionExecutionException(
          "error computing inputs from imports file "
              + actionExecutionContext.getInputPath(imports),
          this,
          false);
    }
    updateInputs(createInputs(bitcodeInputSet, getMandatoryInputs()));
    return bitcodeInputSet;
  }

  @Override
  public Collection<Artifact> getMandatoryInputs() {
    return mandatoryInputs;
  }

  private static Iterable<Artifact> createInputs(
      Set<Artifact> newInputs, Collection<Artifact> curInputs) {
    Set<Artifact> result = new LinkedHashSet<>(newInputs);
    result.addAll(curInputs);
    return result;
  }

  @Override
  public Iterable<Artifact> getAllowedDerivedInputs() {
    return bitcodeFiles.values();
  }

  @Override
  protected void computeKey(ActionKeyContext actionKeyContext, Fingerprint fp) {
    fp.addString(GUID);
    try {
      fp.addStrings(getArguments());
    } catch (CommandLineExpansionException e) {
      throw new AssertionError("LtoBackendAction command line expansion cannot fail");
    }
    fp.addString(getMnemonic());
    fp.addPaths(getRunfilesSupplier().getRunfilesDirs());
    ImmutableList<Artifact> runfilesManifests = getRunfilesSupplier().getManifests();
    for (Artifact runfilesManifest : runfilesManifests) {
      fp.addPath(runfilesManifest.getExecPath());
    }
    for (Artifact input : getMandatoryInputs()) {
      fp.addPath(input.getExecPath());
    }
    if (imports != null) {
      for (PathFragment bitcodePath : bitcodeFiles.keySet()) {
        fp.addPath(bitcodePath);
      }
      fp.addPath(imports.getExecPath());
    }
    env.addTo(fp);
    fp.addStringMap(getExecutionInfo());
  }

  /** Builder class to construct {@link LtoBackendAction} instances. */
  public static class Builder extends SpawnAction.Builder {
    private Map<PathFragment, Artifact> bitcodeFiles;
    private Artifact imports;

    public Builder addImportsInfo(
        Map<PathFragment, Artifact> allBitcodeFiles, Artifact importsFile) {
      this.bitcodeFiles = allBitcodeFiles;
      this.imports = importsFile;
      return this;
    }

    @Override
    protected SpawnAction createSpawnAction(
        ActionOwner owner,
        NestedSet<Artifact> tools,
        NestedSet<Artifact> inputsAndTools,
        ImmutableList<Artifact> outputs,
        Artifact primaryOutput,
        ResourceSet resourceSet,
        CommandLines commandLines,
        CommandLineLimits commandLineLimits,
        boolean isShellCommand,
        ActionEnvironment env,
        ImmutableMap<String, String> executionInfo,
        CharSequence progressMessage,
        RunfilesSupplier runfilesSupplier,
        String mnemonic) {
      return new LtoBackendAction(
          inputsAndTools.toCollection(),
          bitcodeFiles,
          imports,
          outputs,
          primaryOutput,
          owner,
          commandLines,
          commandLineLimits,
          isShellCommand,
          env,
          executionInfo,
          progressMessage,
          runfilesSupplier,
          mnemonic);
    }
  }
}
