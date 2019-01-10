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

package com.google.devtools.build.lib.bazel.repository.downloader;

import com.google.common.base.MoreObjects;
import com.google.common.base.Optional;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.io.ByteStreams;
import com.google.devtools.build.lib.bazel.repository.cache.RepositoryCache;
import com.google.devtools.build.lib.bazel.repository.cache.RepositoryCache.KeyType;
import com.google.devtools.build.lib.buildeventstream.FetchEvent;
import com.google.devtools.build.lib.clock.Clock;
import com.google.devtools.build.lib.clock.JavaClock;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.ExtendedEventHandler;
import com.google.devtools.build.lib.packages.Rule;
import com.google.devtools.build.lib.rules.repository.RepositoryFunction.RepositoryFunctionException;
import com.google.devtools.build.lib.rules.repository.WorkspaceAttributeMapper;
import com.google.devtools.build.lib.syntax.EvalException;
import com.google.devtools.build.lib.syntax.Type;
import com.google.devtools.build.lib.util.JavaSleeper;
import com.google.devtools.build.lib.util.Sleeper;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.skyframe.SkyFunctionException.Transience;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Semaphore;

/**
 * Bazel file downloader.
 *
 * <p>This class uses {@link HttpConnectorMultiplexer} to connect to HTTP mirrors and then reads the
 * file to disk.
 */
public class HttpDownloader {

  private static final int MAX_PARALLEL_DOWNLOADS = 8;
  private static final Semaphore semaphore = new Semaphore(MAX_PARALLEL_DOWNLOADS, true);

  protected final RepositoryCache repositoryCache;
  private List<Path> distdir = ImmutableList.of();

  public HttpDownloader(RepositoryCache repositoryCache) {
    this.repositoryCache = repositoryCache;
  }

  public void setDistdir(List<Path> distdir) {
    this.distdir = ImmutableList.copyOf(distdir);
  }

  /** Validates native repository rule attributes and calls the other download method. */
  public Path download(
      Rule rule,
      Path outputDirectory,
      ExtendedEventHandler eventHandler,
      Map<String, String> clientEnv)
      throws RepositoryFunctionException, InterruptedException {
    WorkspaceAttributeMapper mapper = WorkspaceAttributeMapper.of(rule);
    List<URL> urls = new ArrayList<>();
    String sha256;
    String type;
    try {
      String urlString = Strings.nullToEmpty(mapper.get("url", Type.STRING));
      if (!urlString.isEmpty()) {
        try {
          URL url = new URL(urlString);
          if (!HttpUtils.isUrlSupportedByDownloader(url)) {
            throw new EvalException(
                rule.getAttributeLocation("url"), "Unsupported protocol: " + url.getProtocol());
          }
          urls.add(url);
        } catch (MalformedURLException e) {
          throw new EvalException(rule.getAttributeLocation("url"), e.toString());
        }
      }
      List<String> urlStrings =
          MoreObjects.firstNonNull(
              mapper.get("urls", Type.STRING_LIST),
              ImmutableList.<String>of());
      if (!urlStrings.isEmpty()) {
        if (!urls.isEmpty()) {
          throw new EvalException(rule.getAttributeLocation("url"), "Don't set url if urls is set");
        }
        try {
          for (String urlString2 : urlStrings) {
            URL url = new URL(urlString2);
            if (!HttpUtils.isUrlSupportedByDownloader(url)) {
              throw new EvalException(
                  rule.getAttributeLocation("urls"), "Unsupported protocol: " + url.getProtocol());
            }
            urls.add(url);
          }
        } catch (MalformedURLException e) {
          throw new EvalException(rule.getAttributeLocation("urls"), e.toString());
        }
      }
      if (urls.isEmpty()) {
        throw new EvalException(rule.getLocation(), "urls attribute not set");
      }
      sha256 = Strings.nullToEmpty(mapper.get("sha256", Type.STRING));
      if (!sha256.isEmpty() && !RepositoryCache.KeyType.SHA256.isValid(sha256)) {
        throw new EvalException(rule.getAttributeLocation("sha256"), "Invalid SHA256 checksum");
      }
      type = Strings.nullToEmpty(mapper.get("type", Type.STRING));
    } catch (EvalException e) {
      throw new RepositoryFunctionException(e, Transience.PERSISTENT);
    }
    try {
      return download(urls, sha256, Optional.of(type), outputDirectory, eventHandler, clientEnv);
    } catch (IOException e) {
      throw new RepositoryFunctionException(e, Transience.TRANSIENT);
    }
  }

  /**
   * Downloads file to disk and returns path.
   *
   * <p>If the SHA256 checksum and path to the repository cache is specified, attempt to load the
   * file from the {@link RepositoryCache}. If it doesn't exist, proceed to download the file and
   * load it into the cache prior to returning the value.
   *
   * @param urls list of mirror URLs with identical content
   * @param sha256 valid SHA256 hex checksum string which is checked, or empty to disable
   * @param type extension, e.g. "tar.gz" to force on downloaded filename, or empty to not do this
   * @param output destination filename if {@code type} is <i>absent</i>, otherwise output directory
   * @param eventHandler CLI progress reporter
   * @param clientEnv environment variables in shell issuing this command
   * @throws IllegalArgumentException on parameter badness, which should be checked beforehand
   * @throws IOException if download was attempted and ended up failing
   * @throws InterruptedException if this thread is being cast into oblivion
   */
  public Path download(
      List<URL> urls,
      String sha256,
      Optional<String> type,
      Path output,
      ExtendedEventHandler eventHandler,
      Map<String, String> clientEnv)
      throws IOException, InterruptedException {
    if (Thread.interrupted()) {
      throw new InterruptedException();
    }

    Path destination = getDownloadDestination(urls.get(0), type, output);

    // Is set to true if the value should be cached by the sha256 value provided
    boolean isCachingByProvidedSha256 = false;

    if (!sha256.isEmpty()) {
      try {
        String currentSha256 =
            RepositoryCache.getChecksum(KeyType.SHA256, destination);
        if (currentSha256.equals(sha256)) {
          // No need to download.
          return destination;
        }
      } catch (IOException e) {
        // Ignore error trying to hash. We'll attempt to retrieve from cache or just download again.
      }

      if (repositoryCache.isEnabled()) {
        isCachingByProvidedSha256 = true;

        try {
          Path cachedDestination = repositoryCache.get(sha256, destination, KeyType.SHA256);
          if (cachedDestination != null) {
            // Cache hit!
            return cachedDestination;
          }
        } catch (IOException e) {
          // Ignore error trying to get. We'll just download again.
        }
      }

      for (Path dir : distdir) {
        if (!dir.exists()) {
          // This is not a warning (and probably we even should drop the message); it is
          // perfectly fine to have a common rc-file pointing to a volume that is sometimes,
          // but not always mounted.
          eventHandler.handle(Event.info("non-existent distir " + dir));
        } else if (!dir.isDirectory()) {
          eventHandler.handle(Event.warn("distdir " + dir + " is not a directory"));
        } else {
          boolean match = false;
          Path candidate = dir.getRelative(destination.getBaseName());
          try {
            match = RepositoryCache.getChecksum(KeyType.SHA256, candidate).equals(sha256);
          } catch (IOException e) {
            // Not finding anything in a distdir is a normal case, so handle it absolutely
            // quietly. In fact, it is not uncommon to specify a whole list of dist dirs,
            // with the asumption that only one will contain an entry.
          }
          if (match) {
            if (isCachingByProvidedSha256) {
              try {
                repositoryCache.put(sha256, candidate, KeyType.SHA256);
              } catch (IOException e) {
                eventHandler.handle(
                    Event.warn("Failed to copy " + candidate + " to repository cache: " + e));
              }
            }
            FileSystemUtils.createDirectoryAndParents(destination.getParentDirectory());
            FileSystemUtils.copyFile(candidate, destination);
            return destination;
          }
        }
      }
    }

    Clock clock = new JavaClock();
    Sleeper sleeper = new JavaSleeper();
    Locale locale = Locale.getDefault();
    ProxyHelper proxyHelper = new ProxyHelper(clientEnv);
    HttpConnector connector = new HttpConnector(locale, eventHandler, proxyHelper, sleeper);
    ProgressInputStream.Factory progressInputStreamFactory =
        new ProgressInputStream.Factory(locale, clock, eventHandler);
    HttpStream.Factory httpStreamFactory = new HttpStream.Factory(progressInputStreamFactory);
    HttpConnectorMultiplexer multiplexer =
        new HttpConnectorMultiplexer(eventHandler, connector, httpStreamFactory, clock, sleeper);

    // Connect to the best mirror and download the file, while reporting progress to the CLI.
    semaphore.acquire();
    boolean success = false;
    try (HttpStream payload = multiplexer.connect(urls, sha256);
        OutputStream out = destination.getOutputStream()) {
      ByteStreams.copy(payload, out);
      success = true;
    } catch (InterruptedIOException e) {
      throw new InterruptedException();
    } catch (IOException e) {
      throw new IOException(
          "Error downloading " + urls + " to " + destination + ": " + e.getMessage());
    } finally {
      semaphore.release();
      eventHandler.post(new FetchEvent(urls.get(0).toString(), success));
    }

    if (isCachingByProvidedSha256) {
      repositoryCache.put(sha256, destination, KeyType.SHA256);
    } else if (repositoryCache.isEnabled()) {
      String newSha256 = repositoryCache.put(destination, KeyType.SHA256);
      eventHandler.handle(Event.info("SHA256 (" + urls.get(0) + ") = " + newSha256));
    }

    return destination;
  }

  private Path getDownloadDestination(URL url, Optional<String> type, Path output) {
    if (!type.isPresent()) {
      return output;
    }
    String basename =
        MoreObjects.firstNonNull(
            Strings.emptyToNull(PathFragment.create(url.getPath()).getBaseName()),
            "temp");
    if (!type.get().isEmpty()) {
      String suffix = "." + type.get();
      if (!basename.endsWith(suffix)) {
        basename += suffix;
      }
    }
    return output.getRelative(basename);
  }
}
