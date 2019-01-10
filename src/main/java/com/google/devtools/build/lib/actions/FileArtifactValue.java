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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;
import com.google.common.base.Preconditions;
import com.google.common.io.BaseEncoding;
import com.google.devtools.build.lib.actions.cache.DigestUtils;
import com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable;
import com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadSafe;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.AutoCodec;
import com.google.devtools.build.lib.vfs.DigestHashFunction;
import com.google.devtools.build.lib.vfs.FileStatus;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.Symlinks;
import com.google.devtools.build.skyframe.SkyValue;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Objects;
import javax.annotation.Nullable;

/**
 * State of a file system object for the execution phase.
 *
 * <p>This is not used by Skyframe for invalidation, it is primarily used by the action cache and
 * the various {@link com.google.devtools.build.lib.exec.SpawnRunner} implementations.
 *
 * <p>We have the following cases:
 *
 * <ul>
 *   <li>an ordinary file, in which case we would expect to see a digest and size;
 *   <li>a directory, in which case we would expect to see an mtime;
 *   <li>an intentionally omitted file which the build system is aware of but doesn't actually
 *       exist, where all access methods are unsupported;
 *   <li>a "middleman marker" object, which has a null digest, 0 size, and mtime of 0.
 *   <li>The "self data" of a TreeArtifact, where we would expect to see a digest representing the
 *       artifact's contents, and a size of 0.
 * </ul>
 */
@Immutable
@ThreadSafe
public abstract class FileArtifactValue implements SkyValue {
  @AutoCodec public static final FileArtifactValue DEFAULT_MIDDLEMAN = new SingletonMarkerValue();
  /** Data that marks that a file is not present on the filesystem. */
  @AutoCodec public static final FileArtifactValue MISSING_FILE_MARKER = new SingletonMarkerValue();

  /**
   * Represents an omitted file -- we are aware of it but it doesn't exist. All access methods are
   * unsupported.
   */
  @AutoCodec public static final FileArtifactValue OMITTED_FILE_MARKER = new OmittedFileValue();

  /**
   * Marker interface for singleton implementations of this class.
   *
   * <p>Needed for a correct implementation of {@code equals}.
   */
  public interface Singleton {}

  /**
   * The type of the underlying file system object. If it is a regular file, then it is guaranteed
   * to have a digest. Otherwise it does not have a digest.
   */
  public abstract FileStateType getType();

  /**
   * Returns a digest of the content of the underlying file system object; must always return a
   * non-null value for instances of type {@link FileStateType#REGULAR_FILE}. Otherwise may return
   * null.
   *
   * <p>All instances of this interface must either have a digest or return a last-modified time.
   * Clients should prefer using the digest for content identification (e.g., for caching), and only
   * fall back to the last-modified time if no digest is available.
   *
   * <p>The return value is owned by this object and must not be modified.
   */
  @Nullable
  public abstract byte[] getDigest();

  /** Returns the file's size, or 0 if the underlying file system object is not a file. */
  // TODO(ulfjack): Throw an exception if it's not a file.
  public abstract long getSize();

  /**
   * Returns the last modified time; see the documentation of {@link #getDigest} for when this can
   * and should be called.
   */
  public abstract long getModifiedTime();

  /**
   * Index used to resolve remote files.
   *
   * <p>0 indicates that no such information is available which can mean that it's either a local
   * file or empty.
   */
  public int getLocationIndex() {
    return 0;
  }

  /** Returns {@code true} if this is a special marker as opposed to a representing a real file. */
  public final boolean isMarkerValue() {
    return this instanceof Singleton;
  }

  /**
   * Provides a best-effort determination whether the file was changed since the digest was
   * computed. This method performs file system I/O, so may be expensive. It's primarily intended to
   * avoid storing bad cache entries in an action cache. It should return true if there is a chance
   * that the file was modified since the digest was computed. Better not upload if we are not sure
   * that the cache entry is reliable.
   */
  public abstract boolean wasModifiedSinceDigest(Path path) throws IOException;

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof FileArtifactValue)) {
      return false;
    }
    if ((this instanceof Singleton) || (o instanceof Singleton)) {
      return false;
    }
    FileArtifactValue m = (FileArtifactValue) o;
    if (getType() != m.getType()) {
      return false;
    }
    if (getDigest() != null) {
      return Arrays.equals(getDigest(), m.getDigest()) && getSize() == m.getSize();
    } else {
      return getModifiedTime() == m.getModifiedTime();
    }
  }

  @Override
  public int hashCode() {
    if (this instanceof Singleton) {
      return System.identityHashCode(this);
    }
    // Hash digest by content, not reference.
    if (getDigest() != null) {
      return 37 * Long.hashCode(getSize()) + Arrays.hashCode(getDigest());
    } else {
      return Long.hashCode(getModifiedTime());
    }
  }

  public static FileArtifactValue create(Artifact artifact, FileValue fileValue)
      throws IOException {
    boolean isFile = fileValue.isFile();
    FileContentsProxy proxy = getProxyFromFileStateValue(fileValue.realFileStateValue());
    return create(
        artifact.getPath(),
        isFile,
        isFile ? fileValue.getSize() : 0,
        proxy,
        isFile ? fileValue.getDigest() : null);
  }

  public static FileArtifactValue create(Artifact artifact, ArtifactFileMetadata metadata)
      throws IOException {
    boolean isFile = metadata.isFile();
    FileContentsProxy proxy = getProxyFromFileStateValue(metadata.realFileStateValue());
    return create(
        artifact.getPath(),
        isFile,
        isFile ? metadata.getSize() : 0,
        proxy,
        isFile ? metadata.getDigest() : null);
  }

  public static FileArtifactValue create(
      Artifact artifact,
      ArtifactPathResolver resolver,
      ArtifactFileMetadata fileValue,
      @Nullable byte[] injectedDigest)
      throws IOException {
    boolean isFile = fileValue.isFile();
    FileContentsProxy proxy = getProxyFromFileStateValue(fileValue.realFileStateValue());
    return create(
        resolver.toPath(artifact), isFile, isFile ? fileValue.getSize() : 0, proxy, injectedDigest);
  }

  @VisibleForTesting
  public static FileArtifactValue create(Artifact artifact) throws IOException {
    return create(artifact.getPath());
  }

  public static FileArtifactValue create(Path path) throws IOException {
    // Caution: there's a race condition between stating the file and computing the
    // digest. We need to stat first, since we're using the stat to detect changes.
    // We follow symlinks here to be consistent with getDigest.
    return create(path, path.stat(Symlinks.FOLLOW));
  }

  public static FileArtifactValue create(Path path, FileStatus stat) throws IOException {
    return create(path, stat.isFile(), stat.getSize(), FileContentsProxy.create(stat), null);
  }

  private static FileArtifactValue create(
      Path path, boolean isFile, long size, FileContentsProxy proxy, @Nullable byte[] digest)
      throws IOException {
    if (!isFile) {
      // In this case, we need to store the mtime because the action cache uses mtime for
      // directories to determine if this artifact has changed. We want this code path to go away
      // somehow.
      return new DirectoryArtifactValue(path.getLastModifiedTime());
    }
    if (digest == null) {
      digest = DigestUtils.getDigestOrFail(path, size);
    }
    Preconditions.checkState(digest != null, path);
    return new RegularFileArtifactValue(digest, proxy, size);
  }

  public static FileArtifactValue createForVirtualActionInput(byte[] digest, long size) {
    return new RegularFileArtifactValue(digest, /*proxy=*/ null, size);
  }

  public static FileArtifactValue createNormalFile(
      byte[] digest, @Nullable FileContentsProxy proxy, long size) {
    return new RegularFileArtifactValue(digest, proxy, size);
  }

  public static FileArtifactValue createNormalFile(ArtifactFileMetadata artifactMetadata) {
    FileContentsProxy proxy = getProxyFromFileStateValue(artifactMetadata.realFileStateValue());
    return new RegularFileArtifactValue(
        artifactMetadata.getDigest(), proxy, artifactMetadata.getSize());
  }

  @VisibleForTesting
  public static FileArtifactValue createNormalFile(byte[] digest, long size) {
    return createNormalFile(digest, /*proxy=*/ null, size);
  }

  public static FileArtifactValue createDirectoryWithHash(byte[] digest) {
    return new HashedDirectoryArtifactValue(digest);
  }

  public static FileArtifactValue createDirectory(long mtime) {
    return new DirectoryArtifactValue(mtime);
  }

  /**
   * Creates a FileArtifactValue used as a 'proxy' input for other ArtifactValues. These are used in
   * {@link com.google.devtools.build.lib.actions.ActionCacheChecker}.
   */
  public static FileArtifactValue createProxy(byte[] digest) {
    Preconditions.checkNotNull(digest);
    return createNormalFile(digest, /*proxy=*/ null, /*size=*/ 0);
  }

  private static final class DirectoryArtifactValue extends FileArtifactValue {
    private final long mtime;

    private DirectoryArtifactValue(long mtime) {
      this.mtime = mtime;
    }

    @Override
    public FileStateType getType() {
      return FileStateType.DIRECTORY;
    }

    @Nullable
    @Override
    public byte[] getDigest() {
      return null;
    }

    @Override
    public long getModifiedTime() {
      return mtime;
    }

    @Override
    public long getSize() {
      return 0;
    }

    @Override
    public boolean wasModifiedSinceDigest(Path path) throws IOException {
      return false;
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this).add("mtime", mtime).toString();
    }
  }

  private static final class HashedDirectoryArtifactValue extends FileArtifactValue {
    private final byte[] digest;

    private HashedDirectoryArtifactValue(byte[] digest) {
      this.digest = digest;
    }

    @Override
    public FileStateType getType() {
      return FileStateType.DIRECTORY;
    }

    @Nullable
    @Override
    public byte[] getDigest() {
      return digest;
    }

    @Override
    public long getModifiedTime() {
      return 0;
    }

    @Override
    public long getSize() {
      return 0;
    }

    @Override
    public boolean wasModifiedSinceDigest(Path path) throws IOException {
      // TODO(ulfjack): Ideally, we'd attempt to detect intra-build modifications here. I'm
      // consciously deferring work here as this code will most likely change again, and we're
      // already doing better than before by detecting inter-build modifications.
      return false;
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this).add("digest", digest).toString();
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (!(o instanceof HashedDirectoryArtifactValue)) {
        return false;
      }
      HashedDirectoryArtifactValue r = (HashedDirectoryArtifactValue) o;
      return Arrays.equals(digest, r.digest);
    }

    @Override
    public int hashCode() {
      return Arrays.hashCode(digest);
    }
  }

  private static final class RegularFileArtifactValue extends FileArtifactValue {
    private final byte[] digest;
    @Nullable private final FileContentsProxy proxy;
    private final long size;

    private RegularFileArtifactValue(byte[] digest, @Nullable FileContentsProxy proxy, long size) {
      this.digest = Preconditions.checkNotNull(digest);
      this.proxy = proxy;
      this.size = size;
    }

    @Override
    public FileStateType getType() {
      return FileStateType.REGULAR_FILE;
    }

    @Override
    public byte[] getDigest() {
      return digest;
    }

    @Override
    public long getSize() {
      return size;
    }

    @Override
    public boolean wasModifiedSinceDigest(Path path) throws IOException {
      if (proxy == null) {
        return false;
      }
      FileStatus stat = path.statIfFound(Symlinks.FOLLOW);
      return stat == null || !stat.isFile() || !proxy.equals(FileContentsProxy.create(stat));
    }

    @Override
    public long getModifiedTime() {
      throw new UnsupportedOperationException(
          "regular file's mtime should never be called. (" + this + ")");
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("digest", BaseEncoding.base16().lowerCase().encode(digest))
          .add("size", size)
          .add("proxy", proxy).toString();
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (!(o instanceof RegularFileArtifactValue)) {
        return false;
      }
      RegularFileArtifactValue r = (RegularFileArtifactValue) o;
      return Arrays.equals(digest, r.digest) && Objects.equals(proxy, r.proxy) && size == r.size;
    }

    @Override
    public int hashCode() {
      return (proxy != null ? 127 * proxy.hashCode() : 0)
          + 37 * Long.hashCode(getSize()) + Arrays.hashCode(getDigest());
    }
  }

  /** Metadata for remotely stored files. */
  public static final class RemoteFileArtifactValue extends FileArtifactValue {
    private final byte[] digest;
    private final long size;
    private final int locationIndex;

    public RemoteFileArtifactValue(byte[] digest, long size, int locationIndex) {
      this.digest = digest;
      this.size = size;
      this.locationIndex = locationIndex;
    }

    @Override
    public FileStateType getType() {
      return FileStateType.REGULAR_FILE;
    }

    @Override
    public byte[] getDigest() {
      return digest;
    }

    @Override
    public long getSize() {
      return size;
    }

    @Override
    public long getModifiedTime() {
      throw new UnsupportedOperationException(
          "RemoteFileArifactValue doesn't support getModifiedTime");
    }

    @Override
    public int getLocationIndex() {
      return locationIndex;
    }

    @Override
    public boolean wasModifiedSinceDigest(Path path) {
      throw new UnsupportedOperationException();
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("digest", bytesToString(digest))
          .add("size", size)
          .add("locationIndex", locationIndex)
          .toString();
    }
  }

  private static String bytesToString(byte[] bytes) {
    return "0x" + BaseEncoding.base16().omitPadding().encode(bytes);
  }

  /** File stored inline in metadata. */
  public static final class InlineFileArtifactValue extends FileArtifactValue {
    private final byte[] data;
    private final byte[] digest;

    public InlineFileArtifactValue(byte[] data, byte[] digest) {
      this.data = Preconditions.checkNotNull(data);
      this.digest = Preconditions.checkNotNull(digest);
    }

    public InlineFileArtifactValue(byte[] bytes) {
      this(bytes,
          DigestHashFunction.getDefaultUnchecked().getHashFunction().hashBytes(bytes).asBytes());
    }

    public ByteArrayInputStream getInputStream() {
      return new ByteArrayInputStream(data);
    }

    @Override
    public FileStateType getType() {
      return FileStateType.REGULAR_FILE;
    }

    @Override
    public byte[] getDigest() {
      return digest;
    }

    @Override
    public long getSize() {
      return data.length;
    }

    @Override
    public long getModifiedTime() {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean wasModifiedSinceDigest(Path path) {
      throw new UnsupportedOperationException();
    }
  }

  /**
   * Used to resolve source symlinks when diskless.
   *
   * <p>When {@link com.google.devtools.build.lib.skyframe.ActionFileSystem} creates symlinks, it
   * relies on metadata ({@link FileArtifactValue}) to resolve the actual underlying data. In the
   * case of remote or inline files, this information is self-contained. However, in the case of
   * source files, the path is required to resolve the content.
   */
  public static final class SourceFileArtifactValue extends FileArtifactValue {
    private final PathFragment execPath;
    private final byte[] digest;
    private final long size;

    public SourceFileArtifactValue(
        PathFragment execPath, byte[] digest, long size) {
      this.execPath = Preconditions.checkNotNull(execPath);
      this.digest = Preconditions.checkNotNull(digest);
      this.size = size;
    }

    public PathFragment getExecPath() {
      return execPath;
    }

    @Override
    public FileStateType getType() {
      return FileStateType.REGULAR_FILE;
    }

    @Override
    public byte[] getDigest() {
      return digest;
    }

    @Override
    public long getSize() {
      return size;
    }

    @Override
    public long getModifiedTime() {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean wasModifiedSinceDigest(Path path) {
      throw new UnsupportedOperationException();
    }
  }

  private static FileContentsProxy getProxyFromFileStateValue(FileStateValue value) {
    if (value instanceof FileStateValue.RegularFileStateValue) {
      return ((FileStateValue.RegularFileStateValue) value).getContentsProxy();
    } else if (value instanceof FileStateValue.SpecialFileStateValue) {
      return ((FileStateValue.SpecialFileStateValue) value).getContentsProxy();
    }
    return null;
  }

  private static final class SingletonMarkerValue extends FileArtifactValue implements Singleton {
    @Override
    public FileStateType getType() {
      return FileStateType.NONEXISTENT;
    }

    @Nullable
    @Override
    public byte[] getDigest() {
      return null;
    }

    @Override
    public long getSize() {
      return 0;
    }

    @Override
    public long getModifiedTime() {
      return 0;
    }

    @Override
    public boolean wasModifiedSinceDigest(Path path) throws IOException {
      return false;
    }

    @Override
    public String toString() {
      return "singleton marker artifact value (" + hashCode() + ")";
    }
  }

  private static final class OmittedFileValue extends FileArtifactValue implements Singleton {
    @Override
    public FileStateType getType() {
      return FileStateType.NONEXISTENT;
    }

    @Override
    public byte[] getDigest() {
      throw new UnsupportedOperationException();
    }

    @Override
    public long getSize() {
      throw new UnsupportedOperationException();
    }

    @Override
    public long getModifiedTime() {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean wasModifiedSinceDigest(Path path) throws IOException {
      return false;
    }

    @Override
    public String toString() {
      return "OMITTED_FILE_MARKER";
    }
  }
}
