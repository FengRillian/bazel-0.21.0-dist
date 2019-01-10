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
package com.google.devtools.build.lib.skyframe;

import static com.google.common.truth.Truth.assertThat;
import static com.google.devtools.build.lib.actions.ActionInputHelper.asTreeFileArtifacts;
import static org.junit.Assert.fail;

import com.google.common.base.Predicate;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.devtools.build.lib.actions.Action;
import com.google.devtools.build.lib.actions.ActionInputHelper;
import com.google.devtools.build.lib.actions.ActionLookupData;
import com.google.devtools.build.lib.actions.ActionLookupValue;
import com.google.devtools.build.lib.actions.Actions;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.Artifact.SpecialArtifact;
import com.google.devtools.build.lib.actions.Artifact.SpecialArtifactType;
import com.google.devtools.build.lib.actions.Artifact.TreeFileArtifact;
import com.google.devtools.build.lib.actions.ArtifactFileMetadata;
import com.google.devtools.build.lib.actions.ArtifactRoot;
import com.google.devtools.build.lib.actions.ArtifactSkyKey;
import com.google.devtools.build.lib.actions.BasicActionLookupValue;
import com.google.devtools.build.lib.actions.FileArtifactValue;
import com.google.devtools.build.lib.actions.MissingInputFileException;
import com.google.devtools.build.lib.actions.MutableActionGraph.ActionConflictException;
import com.google.devtools.build.lib.actions.cache.DigestUtils;
import com.google.devtools.build.lib.actions.util.TestAction.DummyAction;
import com.google.devtools.build.lib.events.NullEventHandler;
import com.google.devtools.build.lib.vfs.FileStatus;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.skyframe.EvaluationContext;
import com.google.devtools.build.skyframe.EvaluationResult;
import com.google.devtools.build.skyframe.MemoizingEvaluator;
import com.google.devtools.build.skyframe.SkyFunction;
import com.google.devtools.build.skyframe.SkyFunctionException;
import com.google.devtools.build.skyframe.SkyFunctionException.Transience;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Test the behavior of ActionMetadataHandler and ArtifactFunction
 * with respect to TreeArtifacts.
 */
@RunWith(JUnit4.class)
public class TreeArtifactMetadataTest extends ArtifactFunctionTestCase {

  // A list of subpaths for the SetArtifact created by our custom ActionExecutionFunction.
  private List<PathFragment> testTreeArtifactContents;

  @Before
  public final void setUp() throws Exception  {
    delegateActionExecutionFunction = new TreeArtifactExecutionFunction();
  }

  private TreeArtifactValue evaluateTreeArtifact(Artifact treeArtifact,
      Iterable<PathFragment> children)
    throws Exception {
    testTreeArtifactContents = ImmutableList.copyOf(children);
    for (PathFragment child : children) {
      file(treeArtifact.getPath().getRelative(child), child.toString());
    }
    return (TreeArtifactValue) evaluateArtifactValue(treeArtifact, /*mandatory=*/ true);
  }

  private TreeArtifactValue doTestTreeArtifacts(Iterable<PathFragment> children) throws Exception {
    SpecialArtifact output = createTreeArtifact("output");
    return doTestTreeArtifacts(output, children);
  }

  private TreeArtifactValue doTestTreeArtifacts(
      SpecialArtifact tree, Iterable<PathFragment> children) throws Exception {
    TreeArtifactValue value = evaluateTreeArtifact(tree, children);
    assertThat(value.getChildPaths()).containsExactlyElementsIn(ImmutableSet.copyOf(children));
    assertThat(value.getChildren()).containsExactlyElementsIn(
        asTreeFileArtifacts(tree, children));

    // Assertions about digest. As of this writing this logic is essentially the same
    // as that in TreeArtifact, but it's good practice to unit test anyway to guard against
    // breaking changes.
    Map<String, FileArtifactValue> digestBuilder = new HashMap<>();
    for (PathFragment child : children) {
      FileArtifactValue subdigest = FileArtifactValue.create(tree.getPath().getRelative(child));
      digestBuilder.put(child.getPathString(), subdigest);
    }
    assertThat(DigestUtils.fromMetadata(digestBuilder).getDigestBytesUnsafe())
        .isEqualTo(value.getDigest());
    return value;
  }

  @Test
  public void testEmptyTreeArtifacts() throws Exception {
    TreeArtifactValue value = doTestTreeArtifacts(ImmutableList.<PathFragment>of());
    // Additional test, only for this test method: we expect the FileArtifactValue is equal to
    // the digest [0, 0, ...]
    assertThat(value.getMetadata().getDigest()).isEqualTo(value.getDigest());
    // Java zero-fills arrays.
    assertThat(value.getDigest()).isEqualTo(new byte[16]);
  }

  @Test
  public void testEqualTreeArtifacts() throws Exception {
    Artifact treeArtifact = createTreeArtifact("out");
    ImmutableList<PathFragment> children =
        ImmutableList.of(PathFragment.create("one"), PathFragment.create("two"));
    TreeArtifactValue valueOne = evaluateTreeArtifact(treeArtifact, children);
    MemoizingEvaluator evaluator = driver.getGraphForTesting();
    evaluator.delete(new Predicate<SkyKey>() {
      @Override
      public boolean apply(SkyKey key) {
        // Delete action execution node to force our artifacts to be re-evaluated.
        return actions.contains(key.argument());
      }
    });
    TreeArtifactValue valueTwo = evaluateTreeArtifact(treeArtifact, children);
    assertThat(valueOne.getDigest()).isNotSameAs(valueTwo.getDigest());
    assertThat(valueOne).isEqualTo(valueTwo);
  }

  @Test
  public void testTreeArtifactsWithDigests() throws Exception {
    fastDigest = true;
    doTestTreeArtifacts(ImmutableList.of(PathFragment.create("one")));
  }

  @Test
  public void testTreeArtifactsWithoutDigests() throws Exception {
    fastDigest = false;
    doTestTreeArtifacts(ImmutableList.of(PathFragment.create("one")));
  }

  @Test
  public void testTreeArtifactMultipleDigests() throws Exception {
    doTestTreeArtifacts(ImmutableList.of(PathFragment.create("one"), PathFragment.create("two")));
  }

  @Test
  public void testIdenticalTreeArtifactsProduceTheSameDigests() throws Exception {
    // Make sure different root dirs for set artifacts don't produce different digests.
    Artifact one = createTreeArtifact("outOne");
    Artifact two = createTreeArtifact("outTwo");
    ImmutableList<PathFragment> children =
        ImmutableList.of(PathFragment.create("one"), PathFragment.create("two"));
    TreeArtifactValue valueOne = evaluateTreeArtifact(one, children);
    TreeArtifactValue valueTwo = evaluateTreeArtifact(two, children);
    assertThat(valueOne.getDigest()).isEqualTo(valueTwo.getDigest());
  }

  /**
   * Tests that ArtifactFunction rethrows transitive {@link IOException}s as
   * {@link MissingInputFileException}s.
   */
  @Test
  public void testIOExceptionEndToEnd() throws Throwable {
    final IOException exception = new IOException("boop");
    setupRoot(
        new CustomInMemoryFs() {
          @Override
          public FileStatus statIfFound(Path path, boolean followSymlinks) throws IOException {
            if (path.getBaseName().equals("one")) {
              throw exception;
            }
            return super.statIfFound(path, followSymlinks);
          }
        });
    try {
      Artifact artifact = createTreeArtifact("outOne");
      TreeArtifactValue value = evaluateTreeArtifact(artifact,
          ImmutableList.of(PathFragment.create("one")));
      fail("MissingInputFileException expected, got " + value);
    } catch (Exception e) {
      assertThat(Throwables.getRootCause(e)).hasMessageThat().contains(exception.getMessage());
    }
  }

  private void file(Path path, String contents) throws Exception {
    FileSystemUtils.createDirectoryAndParents(path.getParentDirectory());
    writeFile(path, contents);
  }

  private SpecialArtifact createTreeArtifact(String path) throws IOException {
    PathFragment execPath = PathFragment.create("out").getRelative(path);
    Path fullPath = root.getRelative(execPath);
    SpecialArtifact output =
        new SpecialArtifact(
            ArtifactRoot.asDerivedRoot(root, root.getRelative("out")),
            execPath,
            ALL_OWNER,
            SpecialArtifactType.TREE);
    actions.add(new DummyAction(ImmutableList.<Artifact>of(), output));
    FileSystemUtils.createDirectoryAndParents(fullPath);
    return output;
  }

  private SkyValue evaluateArtifactValue(Artifact artifact, boolean mandatory) throws Exception {
    SkyKey key = ArtifactSkyKey.key(artifact, mandatory);
    EvaluationResult<SkyValue> result = evaluate(key);
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

  private class TreeArtifactExecutionFunction implements SkyFunction {
    @Override
    public SkyValue compute(SkyKey skyKey, Environment env)
        throws SkyFunctionException, InterruptedException {
      Map<Artifact, ArtifactFileMetadata> fileData = new HashMap<>();
      Map<TreeFileArtifact, FileArtifactValue> treeArtifactData = new HashMap<>();
      ActionLookupData actionLookupData = (ActionLookupData) skyKey.argument();
      ActionLookupValue actionLookupValue =
          (ActionLookupValue) env.getValue(actionLookupData.getActionLookupKey());
      Action action = actionLookupValue.getAction(actionLookupData.getActionIndex());
      SpecialArtifact output = (SpecialArtifact) Iterables.getOnlyElement(action.getOutputs());
      for (PathFragment subpath : testTreeArtifactContents) {
        try {
          TreeFileArtifact suboutput = ActionInputHelper.treeFileArtifact(output, subpath);
          ArtifactFileMetadata fileValue =
              ActionMetadataHandler.fileMetadataFromArtifact(suboutput, null, null);
          fileData.put(suboutput, fileValue);
          treeArtifactData.put(suboutput, FileArtifactValue.create(suboutput, fileValue));
        } catch (IOException e) {
          throw new SkyFunctionException(e, Transience.TRANSIENT) {};
        }
      }

      TreeArtifactValue treeArtifactValue = TreeArtifactValue.create(treeArtifactData);

      return ActionExecutionValue.create(
          fileData,
          ImmutableMap.of(output, treeArtifactValue),
          ImmutableMap.<Artifact, FileArtifactValue>of(),
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
