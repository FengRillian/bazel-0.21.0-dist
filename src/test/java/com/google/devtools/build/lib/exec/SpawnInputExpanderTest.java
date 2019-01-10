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
package com.google.devtools.build.lib.exec;

import static com.google.common.truth.Truth.assertThat;
import static com.google.devtools.build.lib.exec.FilesetManifest.RelativeSymlinkBehavior.ERROR;
import static com.google.devtools.build.lib.exec.FilesetManifest.RelativeSymlinkBehavior.IGNORE;
import static com.google.devtools.build.lib.exec.FilesetManifest.RelativeSymlinkBehavior.RESOLVE;
import static org.junit.Assert.fail;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.actions.ActionInput;
import com.google.devtools.build.lib.actions.ActionInputHelper;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.Artifact.ArtifactExpander;
import com.google.devtools.build.lib.actions.Artifact.SpecialArtifact;
import com.google.devtools.build.lib.actions.Artifact.SpecialArtifactType;
import com.google.devtools.build.lib.actions.Artifact.TreeFileArtifact;
import com.google.devtools.build.lib.actions.ArtifactOwner;
import com.google.devtools.build.lib.actions.ArtifactPathResolver;
import com.google.devtools.build.lib.actions.ArtifactRoot;
import com.google.devtools.build.lib.actions.EmptyRunfilesSupplier;
import com.google.devtools.build.lib.actions.FileArtifactValue;
import com.google.devtools.build.lib.actions.FilesetOutputSymlink;
import com.google.devtools.build.lib.actions.RunfilesSupplier;
import com.google.devtools.build.lib.actions.Spawn;
import com.google.devtools.build.lib.analysis.Runfiles;
import com.google.devtools.build.lib.analysis.RunfilesSupplierImpl;
import com.google.devtools.build.lib.exec.util.FakeActionInputFileCache;
import com.google.devtools.build.lib.exec.util.SpawnBuilder;
import com.google.devtools.build.lib.vfs.FileSystem;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.Root;
import com.google.devtools.build.lib.vfs.inmemoryfs.InMemoryFileSystem;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link SpawnInputExpander}. */
@RunWith(JUnit4.class)
public class SpawnInputExpanderTest {
  private static final byte[] FAKE_DIGEST = new byte[] {1, 2, 3, 4};

  private static final ArtifactExpander NO_ARTIFACT_EXPANDER =
      (a, b) -> fail("expected no interactions");

  private final FileSystem fs = new InMemoryFileSystem();
  private final Path execRoot = fs.getPath("/root");
  private final ArtifactRoot rootDir =
      ArtifactRoot.asDerivedRoot(execRoot, fs.getPath("/root/out"));

  private SpawnInputExpander expander = new SpawnInputExpander(execRoot, /*strict=*/ true);
  private Map<PathFragment, ActionInput> inputMappings = new HashMap<>();

  @Test
  public void testEmptyRunfiles() throws Exception {
    RunfilesSupplier supplier = EmptyRunfilesSupplier.INSTANCE;
    FakeActionInputFileCache mockCache = new FakeActionInputFileCache();
    expander.addRunfilesToInputs(inputMappings, supplier, mockCache, NO_ARTIFACT_EXPANDER,
        ArtifactPathResolver.IDENTITY, true);
    assertThat(inputMappings).isEmpty();
  }

  @Test
  public void testRunfilesSingleFile() throws Exception {
    Artifact artifact =
        new Artifact(
            fs.getPath("/root/dir/file"),
            ArtifactRoot.asSourceRoot(Root.fromPath(fs.getPath("/root"))));
    Runfiles runfiles = new Runfiles.Builder("workspace").addArtifact(artifact).build();
    RunfilesSupplier supplier = new RunfilesSupplierImpl(PathFragment.create("runfiles"), runfiles);
    FakeActionInputFileCache mockCache = new FakeActionInputFileCache();
    mockCache.put(artifact, FileArtifactValue.createNormalFile(FAKE_DIGEST, 0));

    expander.addRunfilesToInputs(inputMappings, supplier, mockCache, NO_ARTIFACT_EXPANDER,
        ArtifactPathResolver.IDENTITY, true);
    assertThat(inputMappings).hasSize(1);
    assertThat(inputMappings)
        .containsEntry(PathFragment.create("runfiles/workspace/dir/file"), artifact);
  }

  @Test
  public void testRunfilesDirectoryStrict() {
    Artifact artifact =
        new Artifact(
            fs.getPath("/root/dir/file"),
            ArtifactRoot.asSourceRoot(Root.fromPath(fs.getPath("/root"))));
    Runfiles runfiles = new Runfiles.Builder("workspace").addArtifact(artifact).build();
    RunfilesSupplier supplier = new RunfilesSupplierImpl(PathFragment.create("runfiles"), runfiles);
    FakeActionInputFileCache mockCache = new FakeActionInputFileCache();
    mockCache.put(artifact, FileArtifactValue.createDirectory(-1));

    try {
      expander.addRunfilesToInputs(inputMappings, supplier, mockCache, NO_ARTIFACT_EXPANDER,
          ArtifactPathResolver.IDENTITY, true);
      fail();
    } catch (IOException expected) {
      assertThat(expected).hasMessageThat().isEqualTo("Not a file: dir/file");
    }
  }

  @Test
  public void testRunfilesDirectoryNonStrict() throws Exception {
    Artifact artifact =
        new Artifact(
            fs.getPath("/root/dir/file"),
            ArtifactRoot.asSourceRoot(Root.fromPath(fs.getPath("/root"))));
    Runfiles runfiles = new Runfiles.Builder("workspace").addArtifact(artifact).build();
    RunfilesSupplier supplier = new RunfilesSupplierImpl(PathFragment.create("runfiles"), runfiles);
    FakeActionInputFileCache mockCache = new FakeActionInputFileCache();
    mockCache.put(artifact, FileArtifactValue.createDirectory(-1));

    expander = new SpawnInputExpander(execRoot, /*strict=*/ false);
    expander.addRunfilesToInputs(inputMappings, supplier, mockCache, NO_ARTIFACT_EXPANDER,
        ArtifactPathResolver.IDENTITY, true);
    assertThat(inputMappings).hasSize(1);
    assertThat(inputMappings)
        .containsEntry(PathFragment.create("runfiles/workspace/dir/file"), artifact);
  }

  @Test
  public void testRunfilesTwoFiles() throws Exception {
    Artifact artifact1 =
        new Artifact(
            fs.getPath("/root/dir/file"),
            ArtifactRoot.asSourceRoot(Root.fromPath(fs.getPath("/root"))));
    Artifact artifact2 =
        new Artifact(
            fs.getPath("/root/dir/baz"),
            ArtifactRoot.asSourceRoot(Root.fromPath(fs.getPath("/root"))));
    Runfiles runfiles =
        new Runfiles.Builder("workspace").addArtifact(artifact1).addArtifact(artifact2).build();
    RunfilesSupplier supplier = new RunfilesSupplierImpl(PathFragment.create("runfiles"), runfiles);
    FakeActionInputFileCache mockCache = new FakeActionInputFileCache();
    mockCache.put(artifact1, FileArtifactValue.createNormalFile(FAKE_DIGEST, 1));
    mockCache.put(artifact2, FileArtifactValue.createNormalFile(FAKE_DIGEST, 2));

    expander.addRunfilesToInputs(inputMappings, supplier, mockCache, NO_ARTIFACT_EXPANDER,
        ArtifactPathResolver.IDENTITY, true);
    assertThat(inputMappings).hasSize(2);
    assertThat(inputMappings)
        .containsEntry(PathFragment.create("runfiles/workspace/dir/file"), artifact1);
    assertThat(inputMappings)
        .containsEntry(PathFragment.create("runfiles/workspace/dir/baz"), artifact2);
  }

  @Test
  public void testRunfilesSymlink() throws Exception {
    Artifact artifact =
        new Artifact(
            fs.getPath("/root/dir/file"),
            ArtifactRoot.asSourceRoot(Root.fromPath(fs.getPath("/root"))));
    Runfiles runfiles =
        new Runfiles.Builder("workspace")
            .addSymlink(PathFragment.create("symlink"), artifact)
            .build();
    RunfilesSupplier supplier = new RunfilesSupplierImpl(PathFragment.create("runfiles"), runfiles);
    FakeActionInputFileCache mockCache = new FakeActionInputFileCache();
    mockCache.put(artifact, FileArtifactValue.createNormalFile(FAKE_DIGEST, 1));

    expander.addRunfilesToInputs(inputMappings, supplier, mockCache, NO_ARTIFACT_EXPANDER,
        ArtifactPathResolver.IDENTITY, true);
    assertThat(inputMappings).hasSize(1);
    assertThat(inputMappings)
        .containsEntry(PathFragment.create("runfiles/workspace/symlink"), artifact);
  }

  @Test
  public void testRunfilesRootSymlink() throws Exception {
    Artifact artifact =
        new Artifact(
            fs.getPath("/root/dir/file"),
            ArtifactRoot.asSourceRoot(Root.fromPath(fs.getPath("/root"))));
    Runfiles runfiles =
        new Runfiles.Builder("workspace")
            .addRootSymlink(PathFragment.create("symlink"), artifact)
            .build();
    RunfilesSupplier supplier = new RunfilesSupplierImpl(PathFragment.create("runfiles"), runfiles);
    FakeActionInputFileCache mockCache = new FakeActionInputFileCache();
    mockCache.put(artifact, FileArtifactValue.createNormalFile(FAKE_DIGEST, 1));

    expander.addRunfilesToInputs(inputMappings, supplier, mockCache, NO_ARTIFACT_EXPANDER,
        ArtifactPathResolver.IDENTITY, true);
    assertThat(inputMappings).hasSize(2);
    assertThat(inputMappings).containsEntry(PathFragment.create("runfiles/symlink"), artifact);
    // If there's no other entry, Runfiles adds an empty file in the workspace to make sure the
    // directory gets created.
    assertThat(inputMappings)
        .containsEntry(
            PathFragment.create("runfiles/workspace/.runfile"), SpawnInputExpander.EMPTY_FILE);
  }

  @Test
  public void testRunfilesWithTreeArtifacts() throws Exception {
    SpecialArtifact treeArtifact = createTreeArtifact("treeArtifact");
    assertThat(treeArtifact.isTreeArtifact()).isTrue();
    TreeFileArtifact file1 = ActionInputHelper.treeFileArtifact(treeArtifact, "file1");
    TreeFileArtifact file2 = ActionInputHelper.treeFileArtifact(treeArtifact, "file2");
    FileSystemUtils.writeContentAsLatin1(file1.getPath(), "foo");
    FileSystemUtils.writeContentAsLatin1(file2.getPath(), "bar");

    Runfiles runfiles = new Runfiles.Builder("workspace").addArtifact(treeArtifact).build();
    ArtifactExpander artifactExpander =
        (Artifact artifact, Collection<? super Artifact> output) -> {
          if (artifact.equals(treeArtifact)) {
            output.addAll(Arrays.asList(file1, file2));
          }
        };
    RunfilesSupplier supplier = new RunfilesSupplierImpl(PathFragment.create("runfiles"), runfiles);
    FakeActionInputFileCache fakeCache = new FakeActionInputFileCache();
    fakeCache.put(file1, FileArtifactValue.create(file1));
    fakeCache.put(file2, FileArtifactValue.create(file2));

    expander.addRunfilesToInputs(inputMappings, supplier, fakeCache, artifactExpander,
        ArtifactPathResolver.IDENTITY, true);
    assertThat(inputMappings).hasSize(2);
    assertThat(inputMappings)
        .containsEntry(PathFragment.create("runfiles/workspace/treeArtifact/file1"), file1);
    assertThat(inputMappings)
        .containsEntry(PathFragment.create("runfiles/workspace/treeArtifact/file2"), file2);
  }

  @Test
  public void testRunfilesWithTreeArtifactsInSymlinks() throws Exception {
    SpecialArtifact treeArtifact = createTreeArtifact("treeArtifact");
    assertThat(treeArtifact.isTreeArtifact()).isTrue();
    TreeFileArtifact file1 = ActionInputHelper.treeFileArtifact(treeArtifact, "file1");
    TreeFileArtifact file2 = ActionInputHelper.treeFileArtifact(treeArtifact, "file2");
    FileSystemUtils.writeContentAsLatin1(file1.getPath(), "foo");
    FileSystemUtils.writeContentAsLatin1(file2.getPath(), "bar");
    Runfiles runfiles =
        new Runfiles.Builder("workspace")
            .addSymlink(PathFragment.create("symlink"), treeArtifact)
            .build();

    ArtifactExpander artifactExpander =
        (Artifact artifact, Collection<? super Artifact> output) -> {
          if (artifact.equals(treeArtifact)) {
            output.addAll(Arrays.asList(file1, file2));
          }
        };
    RunfilesSupplier supplier = new RunfilesSupplierImpl(PathFragment.create("runfiles"), runfiles);
    FakeActionInputFileCache fakeCache = new FakeActionInputFileCache();
    fakeCache.put(file1, FileArtifactValue.create(file1));
    fakeCache.put(file2, FileArtifactValue.create(file2));

    expander.addRunfilesToInputs(inputMappings, supplier, fakeCache, artifactExpander,
        ArtifactPathResolver.IDENTITY, true);
    assertThat(inputMappings).hasSize(2);
    assertThat(inputMappings)
        .containsEntry(PathFragment.create("runfiles/workspace/symlink/file1"), file1);
    assertThat(inputMappings)
        .containsEntry(PathFragment.create("runfiles/workspace/symlink/file2"), file2);
  }

  @Test
  public void testTreeArtifactsInInputs() throws Exception {
    SpecialArtifact treeArtifact = createTreeArtifact("treeArtifact");
    assertThat(treeArtifact.isTreeArtifact()).isTrue();
    TreeFileArtifact file1 = ActionInputHelper.treeFileArtifact(treeArtifact, "file1");
    TreeFileArtifact file2 = ActionInputHelper.treeFileArtifact(treeArtifact, "file2");
    FileSystemUtils.writeContentAsLatin1(file1.getPath(), "foo");
    FileSystemUtils.writeContentAsLatin1(file2.getPath(), "bar");

    ArtifactExpander artifactExpander =
        (Artifact artifact, Collection<? super Artifact> output) -> {
          if (artifact.equals(treeArtifact)) {
            output.addAll(Arrays.asList(file1, file2));
          }
        };
    FakeActionInputFileCache fakeCache = new FakeActionInputFileCache();
    fakeCache.put(file1, FileArtifactValue.create(file1));
    fakeCache.put(file2, FileArtifactValue.create(file2));

    Spawn spawn = new SpawnBuilder("/bin/echo", "Hello World").withInput(treeArtifact).build();
    inputMappings = expander.getInputMapping(spawn, artifactExpander, ArtifactPathResolver.IDENTITY,
        fakeCache, true);
    assertThat(inputMappings).hasSize(2);
    assertThat(inputMappings).containsEntry(PathFragment.create("treeArtifact/file1"), file1);
    assertThat(inputMappings).containsEntry(PathFragment.create("treeArtifact/file2"), file2);
  }

  private SpecialArtifact createTreeArtifact(String relPath) throws IOException {
    Path outputDir = fs.getPath("/root");
    Path outputPath = execRoot.getRelative(relPath);
    outputPath.createDirectoryAndParents();
    ArtifactRoot derivedRoot = ArtifactRoot.asSourceRoot(Root.fromPath(outputDir));
    return new SpecialArtifact(
        derivedRoot,
        derivedRoot.getExecPath().getRelative(derivedRoot.getRoot().relativize(outputPath)),
        ArtifactOwner.NullArtifactOwner.INSTANCE,
        SpecialArtifactType.TREE);
  }

  @Test
  public void testEmptyManifest() throws Exception {
    Map<Artifact, ImmutableList<FilesetOutputSymlink>> filesetMappings =
        ImmutableMap.of(createFileset("out"), ImmutableList.of());

    expander.addFilesetManifests(filesetMappings, inputMappings);

    assertThat(inputMappings).isEmpty();
  }

  @Test
  public void testManifestWithSingleFile() throws Exception {
    Map<Artifact, ImmutableList<FilesetOutputSymlink>> filesetMappings =
        ImmutableMap.of(
            createFileset("out"), ImmutableList.of(filesetSymlink("foo/bar", "/dir/file")));

    expander.addFilesetManifests(filesetMappings, inputMappings);

    assertThat(inputMappings)
        .containsExactly(
            PathFragment.create("out/foo/bar"), ActionInputHelper.fromPath("/dir/file"));
  }

  @Test
  public void testManifestWithTwoFiles() throws Exception {
    Map<Artifact, ImmutableList<FilesetOutputSymlink>> filesetMappings =
        ImmutableMap.of(
            createFileset("out"),
            ImmutableList.of(
                filesetSymlink("foo/bar", "/dir/file"), filesetSymlink("foo/baz", "/dir/file")));

    expander.addFilesetManifests(filesetMappings, inputMappings);

    assertThat(inputMappings)
        .containsExactly(
            PathFragment.create("out/foo/bar"), ActionInputHelper.fromPath("/dir/file"),
            PathFragment.create("out/foo/baz"), ActionInputHelper.fromPath("/dir/file"));
  }

  @Test
  public void testManifestWithDirectory() throws Exception {
    Map<Artifact, ImmutableList<FilesetOutputSymlink>> filesetMappings =
        ImmutableMap.of(createFileset("out"), ImmutableList.of(filesetSymlink("foo/bar", "/some")));

    expander.addFilesetManifests(filesetMappings, inputMappings);

    assertThat(inputMappings)
        .containsExactly(PathFragment.create("out/foo/bar"), ActionInputHelper.fromPath("/some"));
  }

  private static FilesetOutputSymlink filesetSymlink(String from, String to) {
    return FilesetOutputSymlink.createForTesting(
        PathFragment.create(from), PathFragment.create(to), PathFragment.create("/root"));
  }

  private ImmutableMap<Artifact, ImmutableList<FilesetOutputSymlink>> simpleFilesetManifest() {
    return ImmutableMap.of(
        createFileset("out"),
        ImmutableList.of(
            filesetSymlink("workspace/bar", "foo"), filesetSymlink("workspace/foo", "/root/bar")));
  }

  private SpecialArtifact createFileset(String execPath) {
    return new SpecialArtifact(
        rootDir,
        PathFragment.create(execPath),
        ArtifactOwner.NullArtifactOwner.INSTANCE,
        SpecialArtifactType.FILESET);
  }

  @Test
  public void testManifestWithErrorOnRelativeSymlink() throws Exception {
    expander = new SpawnInputExpander(execRoot, /*strict=*/ true, ERROR);
    try {
      expander.addFilesetManifests(simpleFilesetManifest(), inputMappings);
      fail();
    } catch (IOException e) {
      assertThat(e).hasMessageThat().contains("runfiles target is not absolute: foo");
    }
  }

  @Test
  public void testManifestWithIgnoredRelativeSymlink() throws Exception {
    expander = new SpawnInputExpander(execRoot, /*strict=*/ true, IGNORE);
    expander.addFilesetManifests(simpleFilesetManifest(), inputMappings);
    assertThat(inputMappings)
        .containsExactly(
            PathFragment.create("out/workspace/foo"), ActionInputHelper.fromPath("/root/bar"));
  }

  @Test
  public void testManifestWithResolvedRelativeSymlink() throws Exception {
    expander = new SpawnInputExpander(execRoot, /*strict=*/ true, RESOLVE);
    expander.addFilesetManifests(simpleFilesetManifest(), inputMappings);
    assertThat(inputMappings)
        .containsExactly(
            PathFragment.create("out/workspace/bar"), ActionInputHelper.fromPath("/root/bar"),
            PathFragment.create("out/workspace/foo"), ActionInputHelper.fromPath("/root/bar"));
  }
}
