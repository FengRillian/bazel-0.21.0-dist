// Copyright 2014 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.skyframe;

import com.google.common.base.Preconditions;
import com.google.common.base.Verify;
import com.google.common.collect.Collections2;
import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.Artifact.TreeFileArtifact;
import com.google.devtools.build.lib.actions.ArtifactSkyKey;
import com.google.devtools.build.lib.actions.FileArtifactValue;
import com.google.devtools.build.lib.actions.FileValue;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.skyframe.RecursiveFilesystemTraversalValue.ResolvedFile;
import com.google.devtools.build.lib.skyframe.RecursiveFilesystemTraversalValue.ResolvedFileFactory;
import com.google.devtools.build.lib.skyframe.RecursiveFilesystemTraversalValue.TraversalRequest;
import com.google.devtools.build.lib.vfs.Dirent;
import com.google.devtools.build.lib.vfs.FileStatus;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.Root;
import com.google.devtools.build.lib.vfs.RootedPath;
import com.google.devtools.build.lib.vfs.Symlinks;
import com.google.devtools.build.skyframe.SkyFunction;
import com.google.devtools.build.skyframe.SkyFunctionException;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;

/** A {@link SkyFunction} to build {@link RecursiveFilesystemTraversalValue}s. */
public final class RecursiveFilesystemTraversalFunction implements SkyFunction {

  private static final class MissingDepException extends Exception {}

  /** Base class for exceptions that {@link RecursiveFilesystemTraversalFunctionException} wraps. */
  public abstract static class RecursiveFilesystemTraversalException extends Exception {
    protected RecursiveFilesystemTraversalException(String message) {
      super(message);
    }
  }

  /** Thrown when a generated directory's root-relative path conflicts with a package's path. */
  public static final class GeneratedPathConflictException extends
      RecursiveFilesystemTraversalException {
    GeneratedPathConflictException(TraversalRequest traversal) {
      super(
          String.format(
              "Generated directory %s conflicts with package under the same path. "
                  + "Additional info: %s",
              traversal.root.asRootedPath().getRootRelativePath().getPathString(),
              traversal.errorInfo != null ? traversal.errorInfo : traversal.toString()));
    }
  }

  /**
   * Thrown when the traversal encounters a subdirectory with a BUILD file but is not allowed to
   * recurse into it. See {@code PackageBoundaryMode#REPORT_ERROR}.
   */
  public static final class CannotCrossPackageBoundaryException extends
      RecursiveFilesystemTraversalException {
    CannotCrossPackageBoundaryException(String message) {
      super(message);
    }
  }

  /**
   * Thrown when a dangling symlink is attempted to be dereferenced.
   *
   * <p>Note: this class is not identical to the one in com.google.devtools.build.lib.view.fileset
   * and it's not easy to merge the two because of the dependency structure. The other one will
   * probably be removed along with the rest of the legacy Fileset code.
   */
  public static final class DanglingSymlinkException extends RecursiveFilesystemTraversalException {
    public final String path;
    public final String unresolvedLink;

    public DanglingSymlinkException(String path, String unresolvedLink) {
      super(
          String.format(
              "Found dangling symlink: %s, unresolved path: \"%s\"", path, unresolvedLink));
      Preconditions.checkArgument(path != null && !path.isEmpty());
      Preconditions.checkArgument(unresolvedLink != null && !unresolvedLink.isEmpty());
      this.path = path;
      this.unresolvedLink = unresolvedLink;
    }

    public String getPath() {
      return path;
    }
  }

  /** Thrown when we encounter errors from underlying File operations */
  public static final class FileOperationException extends RecursiveFilesystemTraversalException {
    public FileOperationException(String message) {
      super(message);
    }
  }

  /** Exception type thrown by {@link RecursiveFilesystemTraversalFunction#compute}. */
  private static final class RecursiveFilesystemTraversalFunctionException extends
      SkyFunctionException {
    RecursiveFilesystemTraversalFunctionException(RecursiveFilesystemTraversalException e) {
      super(e, Transience.PERSISTENT);
    }
  }

  @Override
  public SkyValue compute(SkyKey skyKey, Environment env)
      throws RecursiveFilesystemTraversalFunctionException, InterruptedException {
    TraversalRequest traversal = (TraversalRequest) skyKey.argument();
    try {
      // Stat the traversal root.
      FileInfo rootInfo = lookUpFileInfo(env, traversal);

      if (!rootInfo.type.exists()) {
        // May be a dangling symlink or a non-existent file. Handle gracefully.
        if (rootInfo.type.isSymlink()) {
          return resultForDanglingSymlink(traversal.root.asRootedPath(), rootInfo);
        } else {
          return RecursiveFilesystemTraversalValue.EMPTY;
        }
      }

      if (rootInfo.type.isFile()) {
        return resultForFileRoot(traversal.root.asRootedPath(), rootInfo);
      } else if (rootInfo.type.isDirectory() && rootInfo.metadata instanceof TreeArtifactValue) {
        final TreeArtifactValue value = (TreeArtifactValue) rootInfo.metadata;
        ImmutableList.Builder<RecursiveFilesystemTraversalValue> list = ImmutableList.builder();
        for (Map.Entry<TreeFileArtifact, FileArtifactValue> entry
            : value.getChildValues().entrySet()) {
          RootedPath path =
              RootedPath.toRootedPath(
                  traversal.root.asRootedPath().getRoot(), entry.getKey().getPath());
          list.add(resultForFileRoot(
              path,
              // TreeArtifact can't have symbolic inside. So the assumption for FileType.FILE
              // is always true.
              new FileInfo(FileType.FILE, entry.getValue(), path, null)));
        }
        return resultForDirectory(traversal, rootInfo, list.build());
      }

      // Otherwise the root is a directory or a symlink to one.
      PkgLookupResult pkgLookupResult = checkIfPackage(env, traversal, rootInfo);
      traversal = pkgLookupResult.traversal;

      if (pkgLookupResult.isConflicting()) {
        // The traversal was requested for an output directory whose root-relative path conflicts
        // with a source package. We can't handle that, bail out.
        throw new RecursiveFilesystemTraversalFunctionException(
            new GeneratedPathConflictException(traversal));
      } else if (pkgLookupResult.isPackage() && !traversal.skipTestingForSubpackage) {
        // The traversal was requested for a directory that defines a package.
        String msg =
            traversal.errorInfo
                + " crosses package boundary into package rooted at "
                + traversal.root.asRootedPath().getRootRelativePath().getPathString();
        switch (traversal.crossPkgBoundaries) {
          case CROSS:
            // We are free to traverse the subpackage but we need to display a warning.
            env.getListener().handle(Event.warn(null, msg));
            break;
          case DONT_CROSS:
            // We cannot traverse the subpackage and should skip it silently. Return empty results.
            return RecursiveFilesystemTraversalValue.EMPTY;
          case REPORT_ERROR:
            // We cannot traverse the subpackage and should complain loudly (display an error).
            throw new RecursiveFilesystemTraversalFunctionException(
                new CannotCrossPackageBoundaryException(msg));
          default:
            throw new IllegalStateException(traversal.toString());
        }
      }

      // We are free to traverse this directory.
      Collection<SkyKey> dependentKeys = createRecursiveTraversalKeys(env, traversal);
      return resultForDirectory(
          traversal,
          rootInfo,
          traverseChildren(env, dependentKeys, /*inline=*/ traversal.isRootGenerated));
    } catch (IOException e) {
      throw new RecursiveFilesystemTraversalFunctionException(
          new FileOperationException("Error while traversing fileset: " + e.getMessage()));
    } catch (MissingDepException e) {
      return null;
    }
  }

  @Override
  public String extractTag(SkyKey skyKey) {
    return null;
  }

  private static final class FileInfo {
    final FileType type;
    final Object metadata;
    @Nullable final RootedPath realPath;
    @Nullable final PathFragment unresolvedSymlinkTarget;

    FileInfo(
        FileType type,
        Object metadata,
        @Nullable RootedPath realPath,
        @Nullable PathFragment unresolvedSymlinkTarget) {
      this.type = Preconditions.checkNotNull(type);
      this.metadata = metadata;
      this.realPath = realPath;
      this.unresolvedSymlinkTarget = unresolvedSymlinkTarget;
    }

    @Override
    public String toString() {
      if (type.isSymlink()) {
        return String.format("(%s: link_value=%s, real_path=%s)", type,
            unresolvedSymlinkTarget.getPathString(), realPath);
      } else {
        return String.format("(%s: real_path=%s)", type, realPath);
      }
    }
  }

  private static final FileInfo NON_EXISTENT_FILE_INFO =
      new FileInfo(FileType.NONEXISTENT, new Integer(0), null, null);

  private static FileInfo lookUpFileInfo(Environment env, TraversalRequest traversal)
      throws MissingDepException, IOException, InterruptedException {
    if (traversal.isRootGenerated) {
      SkyValue fsVal = null;
      if (traversal.root.getOutputArtifact() != null) {
        Artifact artifact = traversal.root.getOutputArtifact();
        SkyKey artifactKey = ArtifactSkyKey.key(artifact, true);
        SkyValue value = env.getValue(artifactKey);
        if (env.valuesMissing()) {
          throw new MissingDepException();
        }

        if (value instanceof FileArtifactValue) {
          fsVal = value;
        } else if (value instanceof TreeArtifactValue) {
          fsVal = value;
        } else {
          return NON_EXISTENT_FILE_INFO;
        }
      }
      RootedPath realPath = traversal.root.asRootedPath();
      if (traversal.strictOutputFiles) {
        Preconditions.checkNotNull(fsVal, "Strict Fileset output tree has null FileArtifactValue");
        return new FileInfo(
            (fsVal instanceof TreeArtifactValue ? FileType.DIRECTORY : FileType.FILE),
            fsVal, realPath, null);
      } else {
        // FileArtifactValue does not currently track symlinks. If it did, we could potentially
        // remove some of the filesystem operations we're doing here.
        Path path = traversal.root.asRootedPath().asPath();
        FileStatus noFollowStat = path.stat(Symlinks.NOFOLLOW);
        FileStatus followStat = path.statIfFound(Symlinks.FOLLOW);
        FileType type;
        PathFragment unresolvedLinkTarget = null;
        if (followStat == null) {
          type = FileType.DANGLING_SYMLINK;
          if (!noFollowStat.isSymbolicLink()) {
            throw new IOException("Expected symlink for " + path + ", but got: " + noFollowStat);
          }
          unresolvedLinkTarget = path.readSymbolicLink();
        } else if (noFollowStat.isFile()) {
          type = FileType.FILE;
        } else if (noFollowStat.isDirectory()) {
          type = FileType.DIRECTORY;
        } else {
          unresolvedLinkTarget = path.readSymbolicLink();
          realPath =
              RootedPath.toRootedPath(
                  Root.absoluteRoot(path.getFileSystem()), path.resolveSymbolicLinks());
          type = followStat.isFile() ? FileType.SYMLINK_TO_FILE : FileType.SYMLINK_TO_DIRECTORY;
        }
        return new FileInfo(
            type, fsVal != null ? fsVal : noFollowStat.hashCode(), realPath, unresolvedLinkTarget);
      }
    } else {
      // Stat the file.
      FileValue fileValue =
          (FileValue) env.getValueOrThrow(
              FileValue.key(traversal.root.asRootedPath()), IOException.class);

      if (env.valuesMissing()) {
        throw new MissingDepException();
      }
      if (fileValue.exists()) {
        // If it exists, it may either be a symlink or a file/directory.
        PathFragment unresolvedLinkTarget = null;
        FileType type;
        if (fileValue.isSymlink()) {
          unresolvedLinkTarget = fileValue.getUnresolvedLinkTarget();
          type = fileValue.isDirectory() ? FileType.SYMLINK_TO_DIRECTORY : FileType.SYMLINK_TO_FILE;
        } else {
          type = fileValue.isDirectory() ? FileType.DIRECTORY : FileType.FILE;
        }
        return new FileInfo(
            type, fileValue.realFileStateValue(), fileValue.realRootedPath(), unresolvedLinkTarget);
      } else {
        // If it doesn't exist, or it's a dangling symlink, we still want to handle that gracefully.
        return new FileInfo(
            fileValue.isSymlink() ? FileType.DANGLING_SYMLINK : FileType.NONEXISTENT,
            fileValue.realFileStateValue(),
            null,
            fileValue.isSymlink() ? fileValue.getUnresolvedLinkTarget() : null);
      }
    }
  }

  private static final class PkgLookupResult {
    private enum Type {
      CONFLICT, DIRECTORY, PKG
    }

    private final Type type;
    final TraversalRequest traversal;
    final FileInfo rootInfo;

    /** Result for a generated directory that conflicts with a source package. */
    static PkgLookupResult conflict(TraversalRequest traversal, FileInfo rootInfo) {
      return new PkgLookupResult(Type.CONFLICT, traversal, rootInfo);
    }

    /** Result for a source or generated directory (not a package). */
    static PkgLookupResult directory(TraversalRequest traversal, FileInfo rootInfo) {
      return new PkgLookupResult(Type.DIRECTORY, traversal, rootInfo);
    }

    /** Result for a package, i.e. a directory  with a BUILD file. */
    static PkgLookupResult pkg(TraversalRequest traversal, FileInfo rootInfo) {
      return new PkgLookupResult(Type.PKG, traversal, rootInfo);
    }

    private PkgLookupResult(Type type, TraversalRequest traversal, FileInfo rootInfo) {
      this.type = Preconditions.checkNotNull(type);
      this.traversal = Preconditions.checkNotNull(traversal);
      this.rootInfo = Preconditions.checkNotNull(rootInfo);
    }

    boolean isPackage() {
      return type == Type.PKG;
    }

    boolean isConflicting() {
      return type == Type.CONFLICT;
    }

    @Override
    public String toString() {
      return String.format("(%s: info=%s, traversal=%s)", type, rootInfo, traversal);
    }
  }

  /**
   * Checks whether the {@code traversal}'s path refers to a package directory.
   *
   * @return the result of the lookup; it contains potentially new {@link TraversalRequest} and
   *     {@link FileInfo} so the caller should use these instead of the old ones (this happens when
   *     a package is found, but under a different root than expected)
   */
  private static PkgLookupResult checkIfPackage(
      Environment env, TraversalRequest traversal, FileInfo rootInfo)
      throws MissingDepException, IOException, InterruptedException {
    Preconditions.checkArgument(rootInfo.type.exists() && !rootInfo.type.isFile(),
        "{%s} {%s}", traversal, rootInfo);
    PackageLookupValue pkgLookup =
        (PackageLookupValue)
            getDependentSkyValue(env,
                PackageLookupValue.key(traversal.root.asRootedPath().getRootRelativePath()));

    if (pkgLookup.packageExists()) {
      if (traversal.isRootGenerated) {
        // The traversal's root was a generated directory, but its root-relative path conflicts with
        // an existing package.
        return PkgLookupResult.conflict(traversal, rootInfo);
      } else {
        // The traversal's root was a source directory and it defines a package.
        Root pkgRoot = pkgLookup.getRoot();
        if (!pkgRoot.equals(traversal.root.asRootedPath().getRoot())) {
          // However the root of this package is different from what we expected. stat() the real
          // BUILD file of that package.
          traversal = traversal.forChangedRootPath(pkgRoot);
          rootInfo = lookUpFileInfo(env, traversal);
          Verify.verify(rootInfo.type.exists(), "{%s} {%s}", traversal, rootInfo);
        }
        return PkgLookupResult.pkg(traversal, rootInfo);
      }
    } else {
      // The traversal's root was a directory (source or generated one), no package exists under the
      // same root-relative path.
      return PkgLookupResult.directory(traversal, rootInfo);
    }
  }

  /**
   * List the directory and create {@code SkyKey}s to request contents of its children recursively.
   *
   * <p>The returned keys are of type {@link SkyFunctions#RECURSIVE_FILESYSTEM_TRAVERSAL}.
   */
  private static Collection<SkyKey> createRecursiveTraversalKeys(
      Environment env, TraversalRequest traversal)
      throws MissingDepException, InterruptedException, IOException {
    // Use the traversal's path, even if it's a symlink. The contents of the directory, as listed
    // in the result, must be relative to it.
    Iterable<Dirent> dirents;
    if (traversal.isRootGenerated) {
      // If we're dealing with an output file, read the directory directly instead of creating
      // filesystem nodes under the output tree.
      List<Dirent> direntsCollection =
          new ArrayList<>(
              traversal.root.asRootedPath().asPath().readdir(Symlinks.FOLLOW));
      Collections.sort(direntsCollection);
      dirents = direntsCollection;
    } else {
      dirents = ((DirectoryListingValue) getDependentSkyValue(env,
          DirectoryListingValue.key(traversal.root.asRootedPath()))).getDirents();
    }

    List<SkyKey> result = new ArrayList<>();
    for (Dirent dirent : dirents) {
      RootedPath childPath =
          RootedPath.toRootedPath(
              traversal.root.asRootedPath().getRoot(),
              traversal.root.asRootedPath().getRootRelativePath().getRelative(dirent.getName()));
      TraversalRequest childTraversal = traversal.forChildEntry(childPath);
      result.add(childTraversal);
    }
    return result;
  }

  /**
   * Creates result for a dangling symlink.
   *
   * @param linkName path to the symbolic link
   * @param info the {@link FileInfo} associated with the link file
   */
  private static RecursiveFilesystemTraversalValue resultForDanglingSymlink(RootedPath linkName,
      FileInfo info) {
    Preconditions.checkState(info.type.isSymlink() && !info.type.exists(), "{%s} {%s}", linkName,
        info.type);
    return RecursiveFilesystemTraversalValue.of(
        ResolvedFileFactory.danglingSymlink(linkName, info.unresolvedSymlinkTarget, info.metadata));
  }

  /**
   * Creates results for a file or for a symlink that points to one.
   *
   * <p>A symlink may be direct (points to a file) or transitive (points at a direct or transitive
   * symlink).
   */
  private static RecursiveFilesystemTraversalValue resultForFileRoot(RootedPath path,
      FileInfo info) {
    Preconditions.checkState(info.type.isFile() && info.type.exists(), "{%s} {%s}", path,
        info.type);
    if (info.type.isSymlink()) {
      return RecursiveFilesystemTraversalValue.of(
          ResolvedFileFactory.symlinkToFile(
              info.realPath, path, info.unresolvedSymlinkTarget, info.metadata));
    } else {
      return RecursiveFilesystemTraversalValue.of(
          ResolvedFileFactory.regularFile(path, info.metadata));
    }
  }

  private static RecursiveFilesystemTraversalValue resultForDirectory(TraversalRequest traversal,
      FileInfo rootInfo, Collection<RecursiveFilesystemTraversalValue> subdirTraversals) {
    // Collect transitive closure of files in subdirectories.
    NestedSetBuilder<ResolvedFile> paths = NestedSetBuilder.stableOrder();
    for (RecursiveFilesystemTraversalValue child : subdirTraversals) {
      paths.addTransitive(child.getTransitiveFiles());
    }
    ResolvedFile root;
    if (rootInfo.type.isSymlink()) {
      NestedSet<ResolvedFile> children = paths.build();
      root =
          ResolvedFileFactory.symlinkToDirectory(
              rootInfo.realPath,
              traversal.root.asRootedPath(),
              rootInfo.unresolvedSymlinkTarget,
              hashDirectorySymlink(children, rootInfo.metadata));
      paths = NestedSetBuilder.<ResolvedFile>stableOrder().addTransitive(children).add(root);
    } else {
      root = ResolvedFileFactory.directory(rootInfo.realPath);
    }
    return RecursiveFilesystemTraversalValue.of(root, paths.build());
  }

  private static int hashDirectorySymlink(Iterable<ResolvedFile> children, Object metadata) {
    // If the root is a directory symlink, the associated FileStateValue does not change when the
    // linked directory's contents change, so we can't use the FileStateValue as metadata like we
    // do with other ResolvedFile kinds. Instead we compute a metadata hash from the child
    // elements and return that as the ResolvedFile's metadata hash.

    // Compute the hash using the method described in Effective Java, 2nd ed., Item 9.
    int result = 0;
    for (ResolvedFile c : children) {
      result = 31 * result + c.getMetadata().hashCode();
    }
    return 31 * result + metadata.hashCode();
  }

  private static SkyValue getDependentSkyValue(Environment env, SkyKey key)
      throws MissingDepException, InterruptedException {
    SkyValue value = env.getValue(key);
    if (env.valuesMissing()) {
      throw new MissingDepException();
    }
    return value;
  }

  /**
   * Requests Skyframe to compute the dependent values and returns them.
   *
   * <p>The keys must all be {@link SkyFunctions#RECURSIVE_FILESYSTEM_TRAVERSAL} keys.
   */
  private Collection<RecursiveFilesystemTraversalValue> traverseChildren(
      Environment env, Iterable<SkyKey> keys, boolean inline)
      throws MissingDepException, InterruptedException,
      RecursiveFilesystemTraversalFunctionException {
    Map<SkyKey, SkyValue> values;
    if (inline) {
      // Don't create Skyframe nodes for a recursive traversal over the output tree.
      // Instead, inline the recursion in the top-level request.
      values = new HashMap<>();
      for (SkyKey depKey : keys) {
        values.put(depKey, compute(depKey, env));
      }
    } else {
      values = env.getValues(keys);
    }
    if (env.valuesMissing()) {
      throw new MissingDepException();
    }

    return Collections2.transform(values.values(), RecursiveFilesystemTraversalValue.class::cast);
  }

  /** Type information about the filesystem entry residing at a path. */
  enum FileType {
    /** A regular file. */
    FILE {
      @Override boolean isFile() { return true; }
      @Override boolean exists() { return true; }
      @Override public String toString() { return "<f>"; }
    },
    /**
     * A symlink to a regular file.
     *
     * <p>The symlink may be direct (points to a non-symlink (here a file)) or it may be transitive
     * (points to a direct or transitive symlink).
     */
    SYMLINK_TO_FILE {
      @Override boolean isFile() { return true; }
      @Override boolean isSymlink() { return true; }
      @Override boolean exists() { return true; }
      @Override public String toString() { return "<lf>"; }
    },
    /** A directory. */
    DIRECTORY {
      @Override boolean isDirectory() { return true; }
      @Override boolean exists() { return true; }
      @Override public String toString() { return "<d>"; }
    },
    /**
     * A symlink to a directory.
     *
     * <p>The symlink may be direct (points to a non-symlink (here a directory)) or it may be
     * transitive (points to a direct or transitive symlink).
     */
    SYMLINK_TO_DIRECTORY {
      @Override boolean isDirectory() { return true; }
      @Override boolean isSymlink() { return true; }
      @Override boolean exists() { return true; }
      @Override public String toString() { return "<ld>"; }
    },
    /** A dangling symlink, i.e. one whose target is known not to exist. */
    DANGLING_SYMLINK {
      @Override boolean isFile() { throw new UnsupportedOperationException(); }
      @Override boolean isDirectory() { throw new UnsupportedOperationException(); }
      @Override boolean isSymlink() { return true; }
      @Override public String toString() { return "<l?>"; }
    },
    /** A path that does not exist or should be ignored. */
    NONEXISTENT {
      @Override public String toString() { return "<?>"; }
    };

    boolean isFile() { return false; }
    boolean isDirectory() { return false; }
    boolean isSymlink() { return false; }
    boolean exists() { return false; }
    @Override public abstract String toString();
  }
}
