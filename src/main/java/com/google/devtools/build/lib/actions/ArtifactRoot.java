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

package com.google.devtools.build.lib.actions;

import com.google.common.base.Preconditions;
import com.google.common.collect.Interner;
import com.google.common.collect.Interners;
import com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.AutoCodec;
import com.google.devtools.build.lib.skylarkbuildapi.FileRootApi;
import com.google.devtools.build.lib.skylarkinterface.SkylarkPrinter;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.Root;
import java.io.Serializable;
import java.util.Objects;

/**
 * A root for an artifact. The roots are the directories containing artifacts, and they are mapped
 * together into a single directory tree to form the execution environment. There are two kinds of
 * roots, source roots and derived roots. Source roots correspond to entries of the package path,
 * and they can be anywhere on disk. Derived roots correspond to output directories; there are
 * generally different output directories for different configurations, and different types of
 * output (bin, genfiles, includes, etc.).
 *
 * <p>When mapping the roots into a single directory tree, the source roots are merged, such that
 * each package is accessed in its entirety from a single source root. The package cache is
 * responsible for determining that mapping. The derived roots, on the other hand, have to be
 * distinct. (It is currently allowed to have a derived root that is the prefix of another one.)
 *
 * <p>The derived roots must have paths that point inside the exec root, i.e. below the directory
 * that is the root of the merged directory tree.
 */
@AutoCodec
@Immutable
public final class ArtifactRoot implements Comparable<ArtifactRoot>, Serializable, FileRootApi {
  private static final Interner<ArtifactRoot> INTERNER = Interners.newWeakInterner();

  /**
   * Do not use except in tests and in {@link
   * com.google.devtools.build.lib.skyframe.SkyframeExecutor}.
   *
   * <p>Returns the given path as a source root. The path may not be {@code null}.
   */
  public static ArtifactRoot asSourceRoot(Root root) {
    return new ArtifactRoot(root, PathFragment.EMPTY_FRAGMENT, RootType.Source);
  }

  /**
   * Returns the given path as a derived root, relative to the given exec root. The root must be a
   * proper sub-directory of the exec root (i.e. not equal). Neither may be {@code null}.
   *
   * <p>Be careful with this method - all derived roots must be registered with the artifact factory
   * before the analysis phase.
   */
  public static ArtifactRoot asDerivedRoot(Path execRoot, Path root) {
    Preconditions.checkArgument(root.startsWith(execRoot));
    Preconditions.checkArgument(!root.equals(execRoot));
    PathFragment execPath = root.relativeTo(execRoot);
    return INTERNER.intern(new ArtifactRoot(Root.fromPath(root), execPath, RootType.Output));
  }

  public static ArtifactRoot middlemanRoot(Path execRoot, Path outputDir) {
    Path root = outputDir.getRelative("internal");
    Preconditions.checkArgument(root.startsWith(execRoot));
    Preconditions.checkArgument(!root.equals(execRoot));
    PathFragment execPath = root.relativeTo(execRoot);
    return INTERNER.intern(new ArtifactRoot(Root.fromPath(root), execPath, RootType.Middleman));
  }

  @AutoCodec.VisibleForSerialization
  @AutoCodec.Instantiator
  static ArtifactRoot createForSerialization(Root root, PathFragment execPath, RootType rootType) {
    return INTERNER.intern(new ArtifactRoot(root, execPath, rootType));
  }

  @AutoCodec.VisibleForSerialization
  enum RootType {
    Source,
    Output,
    Middleman
  }

  private final Root root;
  private final PathFragment execPath;
  private final RootType rootType;

  private ArtifactRoot(Root root, PathFragment execPath, RootType rootType) {
    this.root = Preconditions.checkNotNull(root);
    this.execPath = execPath;
    this.rootType = rootType;
  }

  public Root getRoot() {
    return root;
  }

  /**
   * Returns the path fragment from the exec root to the actual root. For source roots, this returns
   * the empty fragment.
   */
  public PathFragment getExecPath() {
    return execPath;
  }

  @Override
  public String getExecPathString() {
    return getExecPath().getPathString();
  }


  public boolean isSourceRoot() {
    return rootType == RootType.Source;
  }

  boolean isMiddlemanRoot() {
    return rootType == RootType.Middleman;
  }

  @Override
  public int compareTo(ArtifactRoot o) {
    return root.compareTo(o.root);
  }

  @Override
  public int hashCode() {
    return Objects.hash(root, execPath, rootType);
  }

  @Override
  public boolean equals(Object o) {
    if (o == this) {
      return true;
    }
    if (!(o instanceof ArtifactRoot)) {
      return false;
    }
    ArtifactRoot r = (ArtifactRoot) o;
    return root.equals(r.root) && execPath.equals(r.execPath) && rootType == r.rootType;
  }

  @Override
  public String toString() {
    return root + (isSourceRoot() ? "[source]" : "[derived]");
  }

  @Override
  public void repr(SkylarkPrinter printer) {
    printer.append(isSourceRoot() ? "<source root>" : "<derived root>");
  }
}
