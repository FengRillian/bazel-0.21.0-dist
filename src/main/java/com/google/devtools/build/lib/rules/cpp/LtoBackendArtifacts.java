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

package com.google.devtools.build.lib.rules.cpp;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.analysis.RuleContext;
import com.google.devtools.build.lib.analysis.config.BuildConfiguration;
import com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadSafe;
import com.google.devtools.build.lib.rules.cpp.CcToolchainFeatures.FeatureConfiguration;
import com.google.devtools.build.lib.rules.cpp.CppConfiguration.Tool;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.AutoCodec;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.AutoCodec.VisibleForSerialization;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * LtoBackendArtifacts represents a set of artifacts for a single ThinLTO backend compile.
 *
 * <p>ThinLTO expands the traditional 2 step compile (N x compile .cc, 1x link (N .o files) into a 4
 * step process:
 *
 * <ul>
 *   <li>1. Bitcode generation (N times). This is produces intermediate LLVM bitcode from a source
 *       file. For this product, it reuses the .o extension.
 *   <li>2. Indexing (once on N files). This takes all bitcode .o files, and for each .o file, it
 *       decides from which other .o files symbols can be inlined. In addition, it generates an
 *       index for looking up these symbols, and an imports file for identifying new input files for
 *       each step 3 {@link LtoBackendAction}.
 *   <li>3. Backend compile (N times). This is the traditional compilation, and uses the same
 *       command line as the Bitcode generation in 1). Since the compiler has many bit code files
 *       available, it can inline functions and propagate constants across .o files. This step is
 *       costly, as it will do traditional optimization. The result is a .lto.o file, a traditional
 *       ELF object file.
 *   <li>4. Backend link (once). This is the traditional link, and produces the final executable.
 * </ul>
 */
@AutoCodec
public final class LtoBackendArtifacts {

  // A file containing mapping of symbol => bitcode file containing the symbol.
  // It will be null when this is a shared non-lto backend.
  private final Artifact index;

  // The bitcode file which is the input of the compile.
  private final Artifact bitcodeFile;

  // A file containing a list of bitcode files necessary to run the backend step.
  // It will be null when this is a shared non-lto backend.
  private final Artifact imports;

  // The result of executing the above command line, an ELF object file.
  private final Artifact objectFile;

  // The corresponding dwoFile if fission is used.
  private Artifact dwoFile;

  @AutoCodec.Instantiator
  @VisibleForSerialization
  LtoBackendArtifacts(
      Artifact index,
      Artifact bitcodeFile,
      Artifact imports,
      Artifact objectFile,
      Artifact dwoFile) {
    this.index = index;
    this.bitcodeFile = bitcodeFile;
    this.imports = imports;
    this.objectFile = objectFile;
    this.dwoFile = dwoFile;
  }

  LtoBackendArtifacts(
      PathFragment ltoOutputRootPrefix,
      Artifact bitcodeFile,
      Map<PathFragment, Artifact> allBitCodeFiles,
      RuleContext ruleContext,
      BuildConfiguration configuration,
      CppLinkAction.LinkArtifactFactory linkArtifactFactory,
      FeatureConfiguration featureConfiguration,
      CcToolchainProvider ccToolchain,
      FdoProvider fdoProvider,
      boolean usePic,
      boolean generateDwo,
      List<String> commandLine) {
    this.bitcodeFile = bitcodeFile;
    PathFragment obj = ltoOutputRootPrefix.getRelative(bitcodeFile.getRootRelativePath());

    objectFile = linkArtifactFactory.create(ruleContext, configuration, obj);
    imports = linkArtifactFactory.create(
        ruleContext, configuration, FileSystemUtils.appendExtension(obj, ".imports"));
    index = linkArtifactFactory.create(
        ruleContext, configuration, FileSystemUtils.appendExtension(obj, ".thinlto.bc"));

    scheduleLtoBackendAction(
        ruleContext,
        featureConfiguration,
        ccToolchain,
        fdoProvider,
        usePic,
        generateDwo,
        configuration,
        linkArtifactFactory,
        commandLine,
        allBitCodeFiles);
  }

  // Interface to create an LTO backend that does not perform any cross-module optimization.
  public LtoBackendArtifacts(
      PathFragment ltoOutputRootPrefix,
      Artifact bitcodeFile,
      RuleContext ruleContext,
      BuildConfiguration configuration,
      CppLinkAction.LinkArtifactFactory linkArtifactFactory,
      FeatureConfiguration featureConfiguration,
      CcToolchainProvider ccToolchain,
      FdoProvider fdoProvider,
      boolean usePic,
      boolean generateDwo,
      List<String> commandLine) {
    this.bitcodeFile = bitcodeFile;

    PathFragment obj = ltoOutputRootPrefix.getRelative(bitcodeFile.getRootRelativePath());
    objectFile = linkArtifactFactory.create(ruleContext, configuration, obj);
    imports = null;
    index = null;

    scheduleLtoBackendAction(
        ruleContext,
        featureConfiguration,
        ccToolchain,
        fdoProvider,
        usePic,
        generateDwo,
        configuration,
        linkArtifactFactory,
        commandLine,
        null);
  }

  public Artifact getObjectFile() {
    return objectFile;
  }

  public Artifact getBitcodeFile() {
    return bitcodeFile;
  }

  public Artifact getDwoFile() {
    return dwoFile;
  }

  public void addIndexingOutputs(ImmutableSet.Builder<Artifact> builder) {
    // For objects from linkstatic libraries, we may not be including them in the LTO indexing
    // step when linked into a test, but rather will use shared non-LTO backends for better
    // scalability when running large numbers of tests.
    if (index == null) {
      return;
    }
    builder.add(imports);
    builder.add(index);
  }

  private void scheduleLtoBackendAction(
      RuleContext ruleContext,
      FeatureConfiguration featureConfiguration,
      CcToolchainProvider ccToolchain,
      FdoProvider fdoProvider,
      boolean usePic,
      boolean generateDwo,
      BuildConfiguration configuration,
      CppLinkAction.LinkArtifactFactory linkArtifactFactory,
      List<String> commandLine,
      Map<PathFragment, Artifact> bitcodeFiles) {
    LtoBackendAction.Builder builder = new LtoBackendAction.Builder();

    builder.addInput(bitcodeFile);

    Preconditions.checkState(
        (index == null) == (imports == null),
        "Either both or neither index and imports files should be null");
    if (imports != null) {
      builder.addImportsInfo(bitcodeFiles, imports);
      // Although the imports file is not used by the LTOBackendAction while the action is
      // executing, it is needed during the input discovery phase, and we must list it as an input
      // to the action in order for it to be preserved under --discard_orphaned_artifacts.
      builder.addInput(imports);
    }
    if (index != null) {
      builder.addInput(index);
    }
    builder.addTransitiveInputs(ccToolchain.getCompile());

    builder.addOutput(objectFile);

    builder.setProgressMessage("LTO Backend Compile %s", objectFile.getExecPath());
    builder.setMnemonic("CcLtoBackendCompile");

    // The command-line doesn't specify the full path to clang++, so we set it in the
    // environment.
    PathFragment compiler = ccToolchain.getToolPathFragment(Tool.GCC);

    builder.setExecutable(compiler);
    CcToolchainVariables.Builder buildVariablesBuilder =
        new CcToolchainVariables.Builder(ccToolchain.getBuildVariables());
    if (index != null) {
      buildVariablesBuilder.addStringVariable("thinlto_index", index.getExecPath().toString());
    } else {
      // An empty input indicates not to perform cross-module optimization.
      buildVariablesBuilder.addStringVariable("thinlto_index", "/dev/null");
    }
    // The output from the LTO backend step is a native object file.
    buildVariablesBuilder.addStringVariable(
        "thinlto_output_object_file", objectFile.getExecPath().toString());
    // The input to the LTO backend step is the bitcode file.
    buildVariablesBuilder.addStringVariable(
        "thinlto_input_bitcode_file", bitcodeFile.getExecPath().toString());
    addProfileForLtoBackend(builder, fdoProvider, featureConfiguration, buildVariablesBuilder);

    if (generateDwo) {
      dwoFile =
          linkArtifactFactory.create(
              ruleContext,
              configuration,
              FileSystemUtils.replaceExtension(objectFile.getRootRelativePath(), ".dwo"));
      builder.addOutput(dwoFile);
      buildVariablesBuilder.addStringVariable(
          "per_object_debug_info_file", dwoFile.getExecPathString());
    }

    List<String> execArgs = new ArrayList<>();
    execArgs.addAll(commandLine);
    CcToolchainVariables buildVariables = buildVariablesBuilder.build();
    // Feature options should go after --copt for consistency with compile actions.
    execArgs.addAll(
        featureConfiguration.getCommandLine(CppActionNames.LTO_BACKEND, buildVariables));
    // If this is a PIC compile (set based on the CppConfiguration), the PIC
    // option should be added after the rest of the command line so that it
    // cannot be overridden. This is consistent with the ordering in the
    // CppCompileAction's compiler options.
    if (usePic) {
      execArgs.add("-fPIC");
    }
    builder.addExecutableArguments(execArgs);

    ruleContext.registerAction(builder.build(ruleContext));
  }

  /**
   * Adds the AFDO profile path to the variable builder and the profile tothe inputs of the action.
   */
  @ThreadSafe
  private static void addProfileForLtoBackend(
      LtoBackendAction.Builder builder,
      FdoProvider fdoProvider,
      FeatureConfiguration featureConfiguration,
      CcToolchainVariables.Builder buildVariables) {
    Artifact prefetch = fdoProvider.getPrefetchHintsArtifact();
    if (prefetch != null) {
      buildVariables.addStringVariable("fdo_prefetch_hints_path", prefetch.getExecPathString());
      builder.addInput(fdoProvider.getPrefetchHintsArtifact());
    }
    if (!featureConfiguration.isEnabled(CppRuleClasses.AUTOFDO)
        && !featureConfiguration.isEnabled(CppRuleClasses.XBINARYFDO)) {
      return;
    }

    Artifact profile = fdoProvider.getProfileArtifact();
    buildVariables.addStringVariable("fdo_profile_path", profile.getExecPathString());
    builder.addInput(fdoProvider.getProfileArtifact());
  }
}
