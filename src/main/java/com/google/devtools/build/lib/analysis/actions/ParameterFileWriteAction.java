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

package com.google.devtools.build.lib.analysis.actions;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.devtools.build.lib.actions.ActionExecutionContext;
import com.google.devtools.build.lib.actions.ActionKeyContext;
import com.google.devtools.build.lib.actions.ActionOwner;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.Artifact.ArtifactExpander;
import com.google.devtools.build.lib.actions.CommandLine;
import com.google.devtools.build.lib.actions.CommandLineExpansionException;
import com.google.devtools.build.lib.actions.ExecException;
import com.google.devtools.build.lib.actions.ParameterFile;
import com.google.devtools.build.lib.actions.ParameterFile.ParameterFileType;
import com.google.devtools.build.lib.actions.UserExecException;
import com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.AutoCodec;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.AutoCodec.VisibleForSerialization;
import com.google.devtools.build.lib.util.Fingerprint;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.Charset;

/** Action to write a parameter file for a {@link CommandLine}. */
@Immutable // if commandLine and charset are immutable
@AutoCodec
public final class ParameterFileWriteAction extends AbstractFileWriteAction {

  private static final String GUID = "45f678d8-e395-401e-8446-e795ccc6361f";

  private final CommandLine commandLine;
  private final ParameterFileType type;
  private final Charset charset;
  private final boolean hasInputArtifactToExpand;

  /**
   * Creates a new instance.
   *
   * @param owner the action owner
   * @param output the Artifact that will be created by executing this Action
   * @param commandLine the contents to be written to the file
   * @param type the type of the file
   * @param charset the charset of the file
   */
  public ParameterFileWriteAction(ActionOwner owner, Artifact output, CommandLine commandLine,
      ParameterFileType type, Charset charset) {
    this(owner, ImmutableList.<Artifact>of(), output, commandLine, type, charset);
  }

  /**
   * Creates a new instance.
   *
   * @param owner the action owner
   * @param inputs the list of TreeArtifacts that must be resolved and expanded before evaluating
   *     the contents of {@link CommandLine}.
   * @param output the Artifact that will be created by executing this Action
   * @param commandLine the contents to be written to the file
   * @param type the type of the file
   * @param charset the charset of the file
   */
  @AutoCodec.Instantiator
  public ParameterFileWriteAction(
      ActionOwner owner,
      Iterable<Artifact> inputs,
      Artifact output,
      CommandLine commandLine,
      ParameterFileType type,
      Charset charset) {
    super(owner, inputs, output, false);
    this.commandLine = commandLine;
    this.type = type;
    this.charset = charset;
    this.hasInputArtifactToExpand = !Iterables.isEmpty(inputs);
  }

  @VisibleForTesting
  public CommandLine getCommandLine() {
    return commandLine;
  }

  /**
   * Returns the list of options written to the parameter file. Don't use this method outside tests
   * - the list is often huge, resulting in significant garbage collection overhead.
   */
  @VisibleForTesting
  public Iterable<String> getArguments() throws CommandLineExpansionException {
    Preconditions.checkState(
        !hasInputArtifactToExpand,
        "This action contains a CommandLine with TreeArtifacts: %s, which must be expanded using "
        + "ArtifactExpander first before we can evaluate the CommandLine.",
        getInputs());
    return commandLine.arguments();
  }

  @VisibleForTesting
  public String getStringContents() throws CommandLineExpansionException, IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ParameterFile.writeParameterFile(out, getArguments(), type, charset);
    return new String(out.toByteArray(), charset);
  }

  @Override
  public DeterministicWriter newDeterministicWriter(ActionExecutionContext ctx)
      throws ExecException {
    final Iterable<String> arguments;
    try {
      ArtifactExpander artifactExpander = Preconditions.checkNotNull(ctx.getArtifactExpander());
      arguments = commandLine.arguments(artifactExpander);
    } catch (CommandLineExpansionException e) {
      throw new UserExecException(e);
    }
    return new ParamFileWriter(arguments, type, charset);
  }

  @VisibleForSerialization
  Artifact getOutput() {
    return Iterables.getOnlyElement(outputs);
  }

  private static class ParamFileWriter implements DeterministicWriter {
    private final Iterable<String> arguments;
    private final ParameterFileType type;
    private final Charset charset;

    ParamFileWriter(Iterable<String> arguments, ParameterFileType type, Charset charset) {
      this.arguments = arguments;
      this.type = type;
      this.charset = charset;
    }

    @Override
    public void writeOutputFile(OutputStream out) throws IOException {
      ParameterFile.writeParameterFile(out, arguments, type, charset);
    }
  }

  @Override
  protected void computeKey(ActionKeyContext actionKeyContext, Fingerprint fp)
      throws CommandLineExpansionException {
    fp.addString(GUID);
    fp.addString(String.valueOf(makeExecutable));
    fp.addString(type.toString());
    fp.addString(charset.toString());
    commandLine.addToFingerprint(actionKeyContext, fp);
  }
}
