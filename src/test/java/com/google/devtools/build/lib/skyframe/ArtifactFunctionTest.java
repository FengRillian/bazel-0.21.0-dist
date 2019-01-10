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
package com.google.devtools.build.lib.skyframe;

import static com.google.common.truth.Truth.assertThat;
import static com.google.devtools.build.lib.actions.FileArtifactValue.create;
import static org.junit.Assert.fail;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.devtools.build.lib.actions.Action;
import com.google.devtools.build.lib.actions.ActionAnalysisMetadata.MiddlemanType;
import com.google.devtools.build.lib.actions.ActionInputHelper;
import com.google.devtools.build.lib.actions.ActionLookupData;
import com.google.devtools.build.lib.actions.ActionLookupValue;
import com.google.devtools.build.lib.actions.Actions;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.Artifact.SpecialArtifact;
import com.google.devtools.build.lib.actions.Artifact.SpecialArtifactType;
import com.google.devtools.build.lib.actions.Artifact.TreeFileArtifact;
import com.google.devtools.build.lib.actions.ArtifactFileMetadata;
import com.google.devtools.build.lib.actions.ArtifactOwner;
import com.google.devtools.build.lib.actions.ArtifactRoot;
import com.google.devtools.build.lib.actions.ArtifactSkyKey;
import com.google.devtools.build.lib.actions.BasicActionLookupValue;
import com.google.devtools.build.lib.actions.FileArtifactValue;
import com.google.devtools.build.lib.actions.MissingInputFileException;
import com.google.devtools.build.lib.actions.MutableActionGraph.ActionConflictException;
import com.google.devtools.build.lib.actions.util.ActionsTestUtil;
import com.google.devtools.build.lib.actions.util.TestAction.DummyAction;
import com.google.devtools.build.lib.events.NullEventHandler;
import com.google.devtools.build.lib.util.Pair;
import com.google.devtools.build.lib.vfs.FileStatus;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.Root;
import com.google.devtools.build.skyframe.EvaluationContext;
import com.google.devtools.build.skyframe.EvaluationResult;
import com.google.devtools.build.skyframe.SkyFunction;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Tests for {@link ArtifactFunction}.
 */
// Doesn't actually need any particular Skyframe, but is only relevant to Skyframe full mode.
@RunWith(JUnit4.class)
public class ArtifactFunctionTest extends ArtifactFunctionTestCase {

  @Before
  public final void setUp() throws Exception  {
    delegateActionExecutionFunction = new SimpleActionExecutionFunction();
  }

  private void assertFileArtifactValueMatches(boolean expectDigest) throws Throwable {
    Artifact output = createDerivedArtifact("output");
    Path path = output.getPath();
    file(path, "contents");
    assertValueMatches(path.stat(), expectDigest ? path.getDigest() : null, evaluateFAN(output));
  }

  @Test
  public void testBasicArtifact() throws Throwable {
    fastDigest = false;
    assertFileArtifactValueMatches(/*expectDigest=*/ true);
  }

  @Test
  public void testBasicArtifactWithXattr() throws Throwable {
    fastDigest = true;
    assertFileArtifactValueMatches(/*expectDigest=*/ true);
  }

  @Test
  public void testMissingNonMandatoryArtifact() throws Throwable {
    Artifact input = createSourceArtifact("input1");
    assertThat(evaluateArtifactValue(input, /*mandatory=*/ false)).isNotNull();
  }

  @Test
  public void testUnreadableInputWithFsWithAvailableDigest() throws Throwable {
    final byte[] expectedDigest = MessageDigest.getInstance("md5").digest(
        "someunreadablecontent".getBytes(StandardCharsets.UTF_8));
    setupRoot(
        new CustomInMemoryFs() {
          @Override
          public byte[] getDigest(Path path) throws IOException {
            return path.getBaseName().equals("unreadable") ? expectedDigest : super.getDigest(path);
          }
        });

    Artifact input = createSourceArtifact("unreadable");
    Path inputPath = input.getPath();
    file(inputPath, "dummynotused");
    inputPath.chmod(0);

    FileArtifactValue value =
        (FileArtifactValue) evaluateArtifactValue(input, /*mandatory=*/ true);

    FileStatus stat = inputPath.stat();
    assertThat(value.getSize()).isEqualTo(stat.getSize());
    assertThat(value.getDigest()).isEqualTo(expectedDigest);
  }

  @Test
  public void testMissingMandatoryArtifact() throws Throwable {
    Artifact input = createSourceArtifact("input1");
    try {
      evaluateArtifactValue(input, /*mandatory=*/ true);
      fail();
    } catch (MissingInputFileException ex) {
      // Expected.
    }
  }

  @Test
  public void testMiddlemanArtifact() throws Throwable {
    Artifact output = createMiddlemanArtifact("output");
    Artifact input1 = createSourceArtifact("input1");
    Artifact input2 = createDerivedArtifact("input2");
    SpecialArtifact tree = createDerivedTreeArtifactWithAction("treeArtifact");
    TreeFileArtifact treeFile1 = createFakeTreeFileArtifact(tree, "child1", "hello1");
    TreeFileArtifact treeFile2 = createFakeTreeFileArtifact(tree, "child2", "hello2");
    file(treeFile1.getPath(), "src1");
    file(treeFile2.getPath(), "src2");
    Action action =
        new DummyAction(
            ImmutableList.of(input1, input2, tree), output, MiddlemanType.AGGREGATING_MIDDLEMAN);
    actions.add(action);
    file(input2.getPath(), "contents");
    file(input1.getPath(), "source contents");
    evaluate(Iterables.toArray(ImmutableSet.of(input2, input1, input2, tree), SkyKey.class));
    SkyValue value = evaluateArtifactValue(output);
    ArrayList<Pair<Artifact, ?>> inputs = new ArrayList<>();
    inputs.addAll(((AggregatingArtifactValue) value).getFileArtifacts());
    inputs.addAll(((AggregatingArtifactValue) value).getTreeArtifacts());
    assertThat(inputs)
        .containsExactly(
            Pair.of(input1, create(input1)),
            Pair.of(input2, create(input2)),
            Pair.of(tree, ((TreeArtifactValue) evaluateArtifactValue(tree))));
  }

  /**
   * Tests that ArtifactFunction rethrows transitive {@link IOException}s as
   * {@link MissingInputFileException}s.
   */
  @Test
  public void testIOException_EndToEnd() throws Throwable {
    final IOException exception = new IOException("beep");
    setupRoot(
        new CustomInMemoryFs() {
          @Override
          public FileStatus statIfFound(Path path, boolean followSymlinks) throws IOException {
            if (path.getBaseName().equals("bad")) {
              throw exception;
            }
            return super.statIfFound(path, followSymlinks);
          }
        });
    try {
      evaluateArtifactValue(createSourceArtifact("bad"));
      fail();
    } catch (MissingInputFileException e) {
      assertThat(e).hasMessageThat().contains(exception.getMessage());
    }
  }

  @Test
  public void testActionTreeArtifactOutput() throws Throwable {
    SpecialArtifact artifact = createDerivedTreeArtifactWithAction("treeArtifact");
    TreeFileArtifact treeFileArtifact1 =
        createFakeTreeFileArtifact(artifact, ALL_OWNER, "child1", "hello1");
    TreeFileArtifact treeFileArtifact2 =
        createFakeTreeFileArtifact(artifact, ALL_OWNER, "child2", "hello2");

    TreeArtifactValue value = (TreeArtifactValue) evaluateArtifactValue(artifact);
    assertThat(value.getChildValues()).containsKey(treeFileArtifact1);
    assertThat(value.getChildValues()).containsKey(treeFileArtifact2);
    assertThat(value.getChildValues().get(treeFileArtifact1).getDigest()).isNotNull();
    assertThat(value.getChildValues().get(treeFileArtifact2).getDigest()).isNotNull();
  }

  @Test
  public void testSpawnActionTemplate() throws Throwable {
    // artifact1 is a tree artifact generated by normal action.
    SpecialArtifact artifact1 = createDerivedTreeArtifactWithAction("treeArtifact1");
    createFakeTreeFileArtifact(artifact1, "child1", "hello1");
    createFakeTreeFileArtifact(artifact1, "child2", "hello2");

    // artifact2 is a tree artifact generated by action template.
    SpecialArtifact artifact2 = createDerivedTreeArtifactOnly("treeArtifact2");
    TreeFileArtifact treeFileArtifact1 =
        createFakeTreeFileArtifact(
            artifact2,
            ActionTemplateExpansionValue.ActionTemplateExpansionKey.of(
                (ActionLookupValue.ActionLookupKey) artifact2.getArtifactOwner(), 1),
            "child1",
            "hello1");
    TreeFileArtifact treeFileArtifact2 =
        createFakeTreeFileArtifact(
            artifact2,
            ActionTemplateExpansionValue.ActionTemplateExpansionKey.of(
                (ActionLookupValue.ActionLookupKey) artifact2.getArtifactOwner(), 1),
            "child2",
            "hello2");

    actions.add(
        ActionsTestUtil.createDummySpawnActionTemplate(artifact1, artifact2));

    TreeArtifactValue value = (TreeArtifactValue) evaluateArtifactValue(artifact2);
    assertThat(value.getChildValues()).containsKey(treeFileArtifact1);
    assertThat(value.getChildValues()).containsKey(treeFileArtifact2);
    assertThat(value.getChildValues().get(treeFileArtifact1).getDigest()).isNotNull();
    assertThat(value.getChildValues().get(treeFileArtifact2).getDigest()).isNotNull();
  }

  @Test
  public void testConsecutiveSpawnActionTemplates() throws Throwable {
    // artifact1 is a tree artifact generated by normal action.
    SpecialArtifact artifact1 = createDerivedTreeArtifactWithAction("treeArtifact1");
    createFakeTreeFileArtifact(artifact1, "child1", "hello1");
    createFakeTreeFileArtifact(artifact1, "child2", "hello2");

    // artifact2 is a tree artifact generated by action template.
    SpecialArtifact artifact2 = createDerivedTreeArtifactOnly("treeArtifact2");
    createFakeTreeFileArtifact(artifact2, "child1", "hello1");
    createFakeTreeFileArtifact(artifact2, "child2", "hello2");
    actions.add(
        ActionsTestUtil.createDummySpawnActionTemplate(artifact1, artifact2));

    // artifact3 is a tree artifact generated by action template.
    SpecialArtifact artifact3 = createDerivedTreeArtifactOnly("treeArtifact3");
    TreeFileArtifact treeFileArtifact1 =
        createFakeTreeFileArtifact(
            artifact3,
            ActionTemplateExpansionValue.ActionTemplateExpansionKey.of(
                (ActionLookupValue.ActionLookupKey) artifact2.getArtifactOwner(), 2),
            "child1",
            "hello1");
    TreeFileArtifact treeFileArtifact2 =
        createFakeTreeFileArtifact(
            artifact3,
            ActionTemplateExpansionValue.ActionTemplateExpansionKey.of(
                (ActionLookupValue.ActionLookupKey) artifact2.getArtifactOwner(), 2),
            "child2",
            "hello2");
    actions.add(
        ActionsTestUtil.createDummySpawnActionTemplate(artifact2, artifact3));

    TreeArtifactValue value = (TreeArtifactValue) evaluateArtifactValue(artifact3);
    assertThat(value.getChildValues()).containsKey(treeFileArtifact1);
    assertThat(value.getChildValues()).containsKey(treeFileArtifact2);
    assertThat(value.getChildValues().get(treeFileArtifact1).getDigest()).isNotNull();
    assertThat(value.getChildValues().get(treeFileArtifact2).getDigest()).isNotNull();
  }

  private void file(Path path, String contents) throws Exception {
    FileSystemUtils.createDirectoryAndParents(path.getParentDirectory());
    writeFile(path, contents);
  }

  private Artifact createSourceArtifact(String path) {
    return new Artifact.SourceArtifact(
        ArtifactRoot.asSourceRoot(Root.fromPath(root)),
        PathFragment.create(path),
        ArtifactOwner.NullArtifactOwner.INSTANCE);
  }

  private Artifact createDerivedArtifact(String path) {
    PathFragment execPath = PathFragment.create("out").getRelative(path);
    Artifact output =
        new Artifact(
            ArtifactRoot.asDerivedRoot(root, root.getRelative("out")),
            execPath,
            ALL_OWNER);
    actions.add(new DummyAction(ImmutableList.<Artifact>of(), output));
    return output;
  }

  private Artifact createMiddlemanArtifact(String path) {
    ArtifactRoot middlemanRoot =
        ArtifactRoot.middlemanRoot(middlemanPath, middlemanPath.getRelative("out"));
    return new Artifact(middlemanRoot, middlemanRoot.getExecPath().getRelative(path), ALL_OWNER);
  }

  private SpecialArtifact createDerivedTreeArtifactWithAction(String path) {
    SpecialArtifact treeArtifact = createDerivedTreeArtifactOnly(path);
    actions.add(new DummyAction(ImmutableList.<Artifact>of(), treeArtifact));
    return treeArtifact;
  }

  private SpecialArtifact createDerivedTreeArtifactOnly(String path) {
    PathFragment execPath = PathFragment.create("out").getRelative(path);
    return new SpecialArtifact(
        ArtifactRoot.asDerivedRoot(root, root.getRelative("out")),
        execPath,
        ALL_OWNER,
        SpecialArtifactType.TREE);
  }

  private TreeFileArtifact createFakeTreeFileArtifact(
      SpecialArtifact treeArtifact, String parentRelativePath, String content) throws Exception {
    return createFakeTreeFileArtifact(
        treeArtifact,
        ActionTemplateExpansionValue.ActionTemplateExpansionKey.of(
            (ActionLookupValue.ActionLookupKey) treeArtifact.getArtifactOwner(), 0),
        parentRelativePath,
        content);
  }

  private TreeFileArtifact createFakeTreeFileArtifact(
      SpecialArtifact treeArtifact,
      ArtifactOwner artifactOwner,
      String parentRelativePath,
      String content)
      throws Exception {
    TreeFileArtifact treeFileArtifact =
        ActionInputHelper.treeFileArtifact(
            treeArtifact, PathFragment.create(parentRelativePath), artifactOwner);
    Path path = treeFileArtifact.getPath();
    FileSystemUtils.createDirectoryAndParents(path.getParentDirectory());
    writeFile(path, content);
    return treeFileArtifact;
  }

  private void assertValueMatches(FileStatus file, byte[] digest, FileArtifactValue value)
      throws IOException {
    assertThat(value.getSize()).isEqualTo(file.getSize());
    if (digest == null) {
      assertThat(value.getDigest()).isNull();
      assertThat(value.getModifiedTime()).isEqualTo(file.getLastModifiedTime());
    } else {
      assertThat(value.getDigest()).isEqualTo(digest);
    }
  }

  private FileArtifactValue evaluateFAN(Artifact artifact) throws Throwable {
    return ((FileArtifactValue) evaluateArtifactValue(artifact));
  }

  private SkyValue evaluateArtifactValue(Artifact artifact) throws Throwable {
    return evaluateArtifactValue(artifact, /* mandatory= */ true);
  }

  private SkyValue evaluateArtifactValue(Artifact artifact, boolean mandatory) throws Throwable {
    SkyKey key = ArtifactSkyKey.key(artifact, mandatory);
    EvaluationResult<SkyValue> result = evaluate(ImmutableList.of(key).toArray(new SkyKey[0]));
    if (result.hasError()) {
      throw result.getError().getException();
    }
    return result.get(key);
  }

  private void setGeneratingActions() throws InterruptedException, ActionConflictException {
    if (evaluator.getExistingValue(ALL_OWNER) == null) {
      differencer.inject(
          ImmutableMap.of(
              ALL_OWNER,
              new BasicActionLookupValue(
                  Actions.filterSharedActionsAndThrowActionConflict(
                      actionKeyContext, ImmutableList.copyOf(actions)))));
    }
  }

  private <E extends SkyValue> EvaluationResult<E> evaluate(SkyKey... keys)
      throws InterruptedException, ActionConflictException {
    setGeneratingActions();
    EvaluationContext evaluationContext =
        EvaluationContext.newBuilder()
            .setKeepGoing(false)
            .setNumThreads(SkyframeExecutor.DEFAULT_THREAD_COUNT)
            .setEventHander(NullEventHandler.INSTANCE)
            .build();
    return driver.evaluate(Arrays.asList(keys), evaluationContext);
  }

  /** Value Builder for actions that just stats and stores the output file (which must exist). */
  private static class SimpleActionExecutionFunction implements SkyFunction {
    @Override
    public SkyValue compute(SkyKey skyKey, Environment env) throws InterruptedException {
      Map<Artifact, ArtifactFileMetadata> artifactData = new HashMap<>();
      Map<Artifact, TreeArtifactValue> treeArtifactData = new HashMap<>();
      Map<Artifact, FileArtifactValue> additionalOutputData = new HashMap<>();
      ActionLookupData actionLookupData = (ActionLookupData) skyKey.argument();
      ActionLookupValue actionLookupValue =
          (ActionLookupValue) env.getValue(actionLookupData.getActionLookupKey());
      Action action = actionLookupValue.getAction(actionLookupData.getActionIndex());
      Artifact output = Iterables.getOnlyElement(action.getOutputs());

      try {
        if (output.isTreeArtifact()) {
          TreeFileArtifact treeFileArtifact1 = ActionInputHelper.treeFileArtifact(
              (SpecialArtifact) output, PathFragment.create("child1"));
          TreeFileArtifact treeFileArtifact2 = ActionInputHelper.treeFileArtifact(
              (SpecialArtifact) output, PathFragment.create("child2"));
          TreeArtifactValue treeArtifactValue = TreeArtifactValue.create(ImmutableMap.of(
              treeFileArtifact1, FileArtifactValue.create(treeFileArtifact1),
              treeFileArtifact2, FileArtifactValue.create(treeFileArtifact2)));
          treeArtifactData.put(output, treeArtifactValue);
        } else if (action.getActionType() == MiddlemanType.NORMAL) {
          ArtifactFileMetadata fileValue =
              ActionMetadataHandler.fileMetadataFromArtifact(output, null, null);
          artifactData.put(output, fileValue);
          additionalOutputData.put(output, FileArtifactValue.create(output, fileValue));
       } else {
          additionalOutputData.put(output, FileArtifactValue.DEFAULT_MIDDLEMAN);
        }
      } catch (IOException e) {
        throw new IllegalStateException(e);
      }
      return ActionExecutionValue.create(
          artifactData,
          treeArtifactData,
          additionalOutputData,
          /*outputSymlinks=*/ null,
          /*discoveredModules=*/ null,
          /*actionDependsOnBuildId=*/ false);
    }

    @Override
    public String extractTag(SkyKey skyKey) {
      return null;
    }
  }
}
