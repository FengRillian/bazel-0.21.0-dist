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

package com.google.devtools.build.lib.bazel.repository.cache;

import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.hash.HashFunction;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import java.io.IOException;
import java.io.InputStream;
import javax.annotation.Nullable;

/** The cache implementation to store download artifacts from external repositories.
 *  TODO(jingwen): Implement file locking for concurrent cache accesses.
 */
public class RepositoryCache {

  /** The types of cache keys used. */
  public enum KeyType {
    SHA1("SHA-1", "\\p{XDigit}{40}", "sha1", Hashing.sha1()),
    SHA256("SHA-256", "\\p{XDigit}{64}", "sha256", Hashing.sha256());

    private final String stringRepr;
    private final String regexp;
    private final String hashName;
    @SuppressWarnings("ImmutableEnumChecker")
    private final HashFunction hashFunction;

    KeyType(String stringRepr, String regexp, String hashName, HashFunction hashFunction) {
      this.stringRepr = stringRepr;
      this.regexp = regexp;
      this.hashName = hashName;
      this.hashFunction = hashFunction;
    }

    public boolean isValid(@Nullable String checksum) {
      return !Strings.isNullOrEmpty(checksum) && checksum.matches(regexp);
    }

    public Path getCachePath(Path parentDirectory) {
      return parentDirectory.getChild(hashName);
    }

    public Hasher newHasher() {
      return hashFunction.newHasher();
    }

    @Override
    public String toString() {
      return stringRepr;
    }
  }

  private static final int BUFFER_SIZE = 32 * 1024;

  // Repository cache subdirectories
  private static final String CAS_DIR = "content_addressable";

  // Rename cached files to this value to simplify lookup.
  public static final String DEFAULT_CACHE_FILENAME = "file";

  @Nullable private Path repositoryCachePath;
  @Nullable private Path contentAddressablePath;
  private boolean useHardlinks;

  public void setRepositoryCachePath(@Nullable Path repositoryCachePath) {
    this.repositoryCachePath = repositoryCachePath;
    this.contentAddressablePath = (repositoryCachePath != null)
        ? repositoryCachePath.getRelative(CAS_DIR) : null;
  }

  public void setHardlink(boolean useHardlinks) {
    this.useHardlinks = useHardlinks;
  }

  /**
   * @return true iff the cache path is set.
   */
  public boolean isEnabled() {
    return repositoryCachePath != null;
  }

  /**
   * Determine if a cache entry exist, given a cache key.
   *
   * @param cacheKey The string key to cache the value by.
   * @param keyType The type of key used. See: KeyType
   * @return true if the cache entry exist, false otherwise.
   */
  public boolean exists(String cacheKey, KeyType keyType) {
    Preconditions.checkState(isEnabled());
    return keyType.getCachePath(contentAddressablePath).getChild(cacheKey).exists();
  }

  /**
   * Copy or hardlink cached value to a specified directory, if it exists.
   *
   * <p>We're using hardlinking instead of symlinking because symlinking require weird checks to
   * verify that the symlink still points to an existing artifact. e.g. cleaning up the central
   * cache but not the workspace cache.
   *
   * @param cacheKey The string key to cache the value by.
   * @param targetPath The path where the cache value should be copied to.
   * @param keyType The type of key used. See: KeyType
   * @return The Path value where the cache value has been copied to. If cache value does not exist,
   *     return null.
   * @throws IOException
   */
  @Nullable
  public synchronized Path get(String cacheKey, Path targetPath, KeyType keyType)
      throws IOException {
    Preconditions.checkState(isEnabled());

    assertKeyIsValid(cacheKey, keyType);
    if (!exists(cacheKey, keyType)) {
      return null;
    }

    Path cacheEntry = keyType.getCachePath(contentAddressablePath).getRelative(cacheKey);
    Path cacheValue = cacheEntry.getRelative(DEFAULT_CACHE_FILENAME);

    try {
      assertFileChecksum(cacheKey, cacheValue, keyType);
    } catch (IOException e) {
      // New lines because this error message gets large printing multiple absolute filepaths.
      throw new IOException(e.getMessage() + "\n\n"
          + "Please delete the directory " + cacheEntry + " and try again.");
    }

    FileSystemUtils.createDirectoryAndParents(targetPath.getParentDirectory());
    if (useHardlinks) {
      FileSystemUtils.createHardLink(targetPath, cacheValue);
    } else {
      FileSystemUtils.copyFile(cacheValue, targetPath);
    }
    FileSystemUtils.touchFile(cacheValue);

    return targetPath;
  }

  /**
   * Copies a value from a specified path into the cache.
   *
   * @param cacheKey The string key to cache the value by.
   * @param sourcePath The path of the value to be cached.
   * @param keyType The type of key used. See: KeyType
   * @throws IOException
   */
  public synchronized void put(String cacheKey, Path sourcePath, KeyType keyType)
    throws IOException {
    Preconditions.checkState(isEnabled());

    assertKeyIsValid(cacheKey, keyType);
    ensureCacheDirectoryExists(keyType);

    Path cacheEntry = keyType.getCachePath(contentAddressablePath).getRelative(cacheKey);
    Path cacheValue = cacheEntry.getRelative(DEFAULT_CACHE_FILENAME);
    FileSystemUtils.createDirectoryAndParents(cacheEntry);
    FileSystemUtils.copyFile(sourcePath, cacheValue);
  }

  /**
   * Copies a value from a specified path into the cache, computing the cache key itself.
   *
   * @param sourcePath The path of the value to be cached.
   * @param keyType The type of key to be used.
   * @throws IOException
   * @return The key for the cached entry.
   */
  public synchronized String put(Path sourcePath, KeyType keyType) throws IOException {
    String cacheKey = getChecksum(keyType, sourcePath);
    put(cacheKey, sourcePath, keyType);
    return cacheKey;
  }

  private void ensureCacheDirectoryExists(KeyType keyType) throws IOException {
    Path directoryPath = keyType.getCachePath(contentAddressablePath);
    if (!directoryPath.exists()) {
      FileSystemUtils.createDirectoryAndParents(directoryPath);
    }
  }

  /**
   * Assert that a file has an expected checksum.
   *
   * @param expectedChecksum The expected checksum of the file.
   * @param filePath The path to the file.
   * @param keyType The type of hash function. e.g. SHA-1, SHA-256
   * @throws IOException If the checksum does not match or the file cannot be hashed, an
   *   exception is thrown.
   */
  public static void assertFileChecksum(String expectedChecksum, Path filePath, KeyType keyType)
      throws IOException {
    Preconditions.checkArgument(!expectedChecksum.isEmpty());

    String actualChecksum;
    try {
      actualChecksum = getChecksum(keyType, filePath);
    } catch (IOException e) {
      throw new IOException(
          "Could not hash file " + filePath + ": " + e.getMessage() + ", expected " + keyType
          + " of " + expectedChecksum + ". ");
    }
    if (!actualChecksum.equalsIgnoreCase(expectedChecksum)) {
      throw new IOException(
          "Downloaded file at " + filePath + " has " + keyType + " of " + actualChecksum
              + ", does not match expected " + keyType + " (" + expectedChecksum + ")");
    }
  }

  /**
   * Obtain the checksum of a file.
   *
   * @param keyType The type of hash function. e.g. SHA-1, SHA-256.
   * @param path The path to the file.
   * @throws IOException
   */
  public static String getChecksum(KeyType keyType, Path path) throws IOException {
    Hasher hasher = keyType.newHasher();
    byte[] byteBuffer = new byte[BUFFER_SIZE];
    try (InputStream stream = path.getInputStream()) {
      int numBytesRead = stream.read(byteBuffer);
      while (numBytesRead != -1) {
        if (numBytesRead != 0) {
          // If more than 0 bytes were read, add them to the hash.
          hasher.putBytes(byteBuffer, 0, numBytesRead);
        }
        numBytesRead = stream.read(byteBuffer);
      }
    }
    return hasher.hash().toString();
  }

  private void assertKeyIsValid(String key, KeyType keyType) throws IOException {
    if (!keyType.isValid(key)) {
      throw new IOException("Invalid key \"" + key + "\" of type " + keyType + ". ");
    }
  }

  public Path getRootPath() {
    return repositoryCachePath;
  }

  public Path getContentAddressableCachePath() {
    return contentAddressablePath;
  }
}
