// Copyright 2017 The Bazel Authors. All rights reserved.
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

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.actions.ActionAnalysisMetadata;
import com.google.devtools.build.lib.actions.ActionExecutionContext;
import com.google.devtools.build.lib.actions.ActionExecutionException;
import com.google.devtools.build.lib.actions.ActionInputHelper;
import com.google.devtools.build.lib.actions.ActionOwner;
import com.google.devtools.build.lib.actions.ActionTemplate;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.Artifact.SpecialArtifact;
import com.google.devtools.build.lib.actions.Artifact.TreeFileArtifact;
import com.google.devtools.build.lib.actions.ArtifactOwner;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.rules.cpp.CcCompilationHelper.SourceCategory;
import com.google.devtools.build.lib.syntax.EvalException;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.util.ArrayList;
import java.util.List;

/**
 * An {@link ActionTemplate} that expands into {@link CppCompileAction}s at execution time.
 */
public final class CppCompileActionTemplate implements ActionTemplate<CppCompileAction> {
  private final CppCompileActionBuilder cppCompileActionBuilder;
  private final SpecialArtifact sourceTreeArtifact;
  private final SpecialArtifact outputTreeArtifact;
  private final CcToolchainProvider toolchain;
  private final Iterable<ArtifactCategory> categories;
  private final ActionOwner actionOwner;
  private final NestedSet<Artifact> mandatoryInputs;
  private final NestedSet<Artifact> allInputs;

  /**
   * Creates an CppCompileActionTemplate.
   *
   * @param sourceTreeArtifact the TreeArtifact that contains source files to compile.
   * @param outputTreeArtifact the TreeArtifact that contains compilation outputs.
   * @param cppCompileActionBuilder An almost completely configured {@link CppCompileActionBuilder}
   *     without the input and output files set. It is used as a template to instantiate expanded
   *     {CppCompileAction}s.
   * @param toolchain the CcToolchainProvider representing the c++ toolchain for this action
   * @param categories A list of {@link ArtifactCategory} used to calculate output file name from a
   *     source file name.
   * @param actionOwner the owner of this {@link ActionTemplate}.
   */
  CppCompileActionTemplate(
      SpecialArtifact sourceTreeArtifact,
      SpecialArtifact outputTreeArtifact,
      CppCompileActionBuilder cppCompileActionBuilder,
      CcToolchainProvider toolchain,
      Iterable<ArtifactCategory> categories,
      ActionOwner actionOwner) {
    this.cppCompileActionBuilder = cppCompileActionBuilder;
    this.sourceTreeArtifact = sourceTreeArtifact;
    this.outputTreeArtifact = outputTreeArtifact;
    this.toolchain = toolchain;
    this.categories = categories;
    this.actionOwner = actionOwner;
    this.mandatoryInputs = cppCompileActionBuilder.buildMandatoryInputs();
    this.allInputs =
        NestedSetBuilder.fromNestedSet(mandatoryInputs)
            .addAll(cppCompileActionBuilder.buildInputsForInvalidation())
            .build();
  }

  @Override
  public Iterable<CppCompileAction> generateActionForInputArtifacts(
      Iterable<TreeFileArtifact> inputTreeFileArtifacts, ArtifactOwner artifactOwner)
      throws ActionTemplateExpansionException {
    ImmutableList.Builder<CppCompileAction> expandedActions = new ImmutableList.Builder<>();

    ImmutableList.Builder<TreeFileArtifact> sourcesBuilder = ImmutableList.builder();
    ImmutableList.Builder<Artifact> privateHeadersBuilder = ImmutableList.builder();
    for (TreeFileArtifact inputTreeFileArtifact : inputTreeFileArtifacts) {
      boolean isHeader = CppFileTypes.CPP_HEADER.matches(inputTreeFileArtifact.getExecPath());
      boolean isTextualInclude =
          CppFileTypes.CPP_TEXTUAL_INCLUDE.matches(inputTreeFileArtifact.getExecPath());
      boolean isSource =
          SourceCategory.CC_AND_OBJC
                  .getSourceTypes()
                  .matches(inputTreeFileArtifact.getExecPathString())
              && !isHeader;

      if (isHeader) {
        privateHeadersBuilder.add(inputTreeFileArtifact);
      }
      if (isSource || (isHeader && shouldCompileHeaders() && !isTextualInclude)) {
        sourcesBuilder.add(inputTreeFileArtifact);
      } else if (!isSource && !isHeader) {
        throw new ActionTemplateExpansionException(
            String.format(
                "Artifact '%s' expanded from the directory artifact '%s' is neither header "
                    + "nor source file.",
                inputTreeFileArtifact.getExecPathString(), sourceTreeArtifact.getExecPathString()));
      }
    }
    ImmutableList<TreeFileArtifact> sources = sourcesBuilder.build();
    ImmutableList<Artifact> privateHeaders = privateHeadersBuilder.build();

    for (TreeFileArtifact inputTreeFileArtifact : sources) {
      try {
        String outputName = outputTreeFileArtifactName(inputTreeFileArtifact);
        TreeFileArtifact outputTreeFileArtifact =
            ActionInputHelper.treeFileArtifact(
                outputTreeArtifact, PathFragment.create(outputName), artifactOwner);
        expandedActions.add(
            createAction(inputTreeFileArtifact, outputTreeFileArtifact, privateHeaders));
      } catch (EvalException e) {
        throw new ActionTemplateExpansionException(e);
      }
    }

    return expandedActions.build();
  }

  private boolean shouldCompileHeaders() {
    return cppCompileActionBuilder.shouldCompileHeaders();
  }

  private CppCompileAction createAction(
      Artifact sourceTreeFileArtifact,
      Artifact outputTreeFileArtifact,
      ImmutableList<Artifact> privateHeaders)
      throws ActionTemplateExpansionException {
    CppCompileActionBuilder builder = new CppCompileActionBuilder(cppCompileActionBuilder);
    builder.setAdditionalPrunableHeaders(privateHeaders);
    builder.setSourceFile(sourceTreeFileArtifact);
    builder.setOutputs(outputTreeFileArtifact, null);

    CcToolchainVariables.Builder buildVariables =
        new CcToolchainVariables.Builder(cppCompileActionBuilder.getVariables());
    buildVariables.overrideStringVariable(
        "source_file", sourceTreeFileArtifact.getExecPathString());
    buildVariables.overrideStringVariable(
        "output_file", outputTreeFileArtifact.getExecPathString());
    buildVariables.overrideStringVariable(
        "output_object_file", outputTreeFileArtifact.getExecPathString());

    builder.setVariables(buildVariables.build());

    List<String> errors = new ArrayList<>();
    CppCompileAction result =
        builder.buildAndVerify((String errorMessage) -> errors.add(errorMessage));
    if (!errors.isEmpty()) {
      throw new ActionTemplateExpansionException(Joiner.on(".\n").join(errors));
    }

    return result;
  }

  private String outputTreeFileArtifactName(TreeFileArtifact inputTreeFileArtifact)
      throws EvalException {
    String outputName = FileSystemUtils.removeExtension(
        inputTreeFileArtifact.getParentRelativePath().getPathString());
    for (ArtifactCategory category : categories) {
      outputName = toolchain.getFeatures().getArtifactNameForCategory(category, outputName);
    }
    return outputName;
  }

  @Override
  public Artifact getInputTreeArtifact() {
    return sourceTreeArtifact;
  }

  @Override
  public Artifact getOutputTreeArtifact() {
    return outputTreeArtifact;
  }

  @Override
  public ActionOwner getOwner() {
    return actionOwner;
  }

  @Override
  public boolean isShareable() {
    return false;
  }

  @Override
  public final String getMnemonic() {
    return "CppCompileActionTemplate";
  }

  @Override
  public Iterable<Artifact> getMandatoryInputs() {
    return NestedSetBuilder.<Artifact>compileOrder()
        .add(sourceTreeArtifact)
        .addTransitive(mandatoryInputs)
        .build();
  }

  @Override
  public Iterable<Artifact> getInputFilesForExtraAction(
      ActionExecutionContext actionExecutionContext)
      throws ActionExecutionException, InterruptedException {
    return ImmutableList.of();
  }

  @Override
  public ImmutableSet<Artifact> getMandatoryOutputs() {
    return ImmutableSet.<Artifact>of();
  }

  @Override
  public Iterable<Artifact> getTools() {
    return ImmutableList.<Artifact>of();
  }

  @Override
  public Iterable<Artifact> getInputs() {
    return NestedSetBuilder.<Artifact>stableOrder()
        .add(sourceTreeArtifact)
        .addTransitive(allInputs)
        .build();
  }

  @Override
  public ImmutableSet<Artifact> getOutputs() {
    return ImmutableSet.of(outputTreeArtifact);
  }

  @Override
  public Iterable<String> getClientEnvironmentVariables() {
    return ImmutableList.<String>of();
  }

  @Override
  public Artifact getPrimaryInput() {
    return sourceTreeArtifact;
  }

  @Override
  public Artifact getPrimaryOutput() {
    return outputTreeArtifact;
  }

  @Override
  public boolean shouldReportPathPrefixConflict(ActionAnalysisMetadata action) {
    return this != action;
  }

  @Override
  public MiddlemanType getActionType() {
    return MiddlemanType.NORMAL;
  }

  @Override
  public String prettyPrint() {
    return String.format(
        "CppCompileActionTemplate compiling " + sourceTreeArtifact.getExecPathString());
  }
}
