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

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.devtools.build.lib.actions.InconsistentFilesystemException;
import com.google.devtools.build.lib.cmdline.PackageIdentifier;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.ExtendedEventHandler;
import com.google.devtools.build.lib.packages.BuildFileContainsErrorsException;
import com.google.devtools.build.lib.packages.NoSuchPackageException;
import com.google.devtools.build.lib.packages.Package;
import com.google.devtools.build.lib.pkgcache.AbstractRecursivePackageProvider;
import com.google.devtools.build.lib.pkgcache.PathPackageLocator;
import com.google.devtools.build.lib.pkgcache.RecursivePackageProvider;
import com.google.devtools.build.lib.rules.repository.RepositoryDirectoryValue;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.Root;
import com.google.devtools.build.lib.vfs.RootedPath;
import com.google.devtools.build.skyframe.SkyFunction.Environment;
import com.google.devtools.build.skyframe.SkyKey;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * A {@link RecursivePackageProvider} backed by an {@link Environment}. Its methods may throw {@link
 * MissingDepException} if the package values this depends on haven't been calculated and added to
 * its environment.
 *
 * <p>This implementation never emits events through the {@link ExtendedEventHandler}s passed to its
 * methods. Instead, it emits events through its environment's {@link Environment#getListener()}.
 */
public final class EnvironmentBackedRecursivePackageProvider
    extends AbstractRecursivePackageProvider {

  private final Environment env;

  EnvironmentBackedRecursivePackageProvider(Environment env) {
    this.env = env;
  }

  @Override
  public Package getPackage(ExtendedEventHandler eventHandler, PackageIdentifier packageName)
      throws NoSuchPackageException, MissingDepException, InterruptedException {
    SkyKey pkgKey = PackageValue.key(packageName);
    PackageValue pkgValue =
        (PackageValue) env.getValueOrThrow(pkgKey, NoSuchPackageException.class);
    if (pkgValue == null) {
      throw new MissingDepException();
    }
    Package pkg = pkgValue.getPackage();
    if (pkg.containsErrors()) {
      // If this is a nokeep_going build, we must shut the build down by throwing an exception. To
      // do that, we request a node that will throw an exception, and then try to catch it and
      // continue. This gives the framework notification to shut down the build if it should.
      try {
        env.getValueOrThrow(
            PackageErrorFunction.key(packageName), BuildFileContainsErrorsException.class);
        Preconditions.checkState(env.valuesMissing(), "Should have thrown for %s", packageName);
        throw new MissingDepException();
      } catch (BuildFileContainsErrorsException e) {
        // Expected.
      }
    }
    return pkgValue.getPackage();
  }

  @Override
  public Map<PackageIdentifier, Package> bulkGetPackages(Iterable<PackageIdentifier> pkgIds)
      throws NoSuchPackageException, InterruptedException {
    ImmutableMap.Builder<PackageIdentifier, Package> builder = ImmutableMap.builder();
    for (PackageIdentifier pkgId : pkgIds) {
      builder.put(pkgId, getPackage(env.getListener(), pkgId));
    }
    return builder.build();
  }

  @Override
  public boolean isPackage(ExtendedEventHandler eventHandler, PackageIdentifier packageId)
      throws MissingDepException, InterruptedException {
    SkyKey packageLookupKey = PackageLookupValue.key(packageId);
    try {
      PackageLookupValue packageLookupValue =
          (PackageLookupValue) env.getValueOrThrow(packageLookupKey, NoSuchPackageException.class,
              InconsistentFilesystemException.class);
      if (packageLookupValue == null) {
        throw new MissingDepException();
      }
      return packageLookupValue.packageExists();
    } catch (NoSuchPackageException | InconsistentFilesystemException e) {
      env.getListener().handle(Event.error(e.getMessage()));
      return false;
    }
  }

  @Override
  public Iterable<PathFragment> getPackagesUnderDirectory(
      ExtendedEventHandler eventHandler,
      RepositoryName repository,
      PathFragment directory,
      ImmutableSet<PathFragment> blacklistedSubdirectories,
      ImmutableSet<PathFragment> excludedSubdirectories)
      throws MissingDepException, InterruptedException {
    PathPackageLocator packageLocator = PrecomputedValue.PATH_PACKAGE_LOCATOR.get(env);
    if (packageLocator == null) {
      throw new MissingDepException();
    }

    List<Root> roots = new ArrayList<>();
    if (repository.isMain()) {
      roots.addAll(packageLocator.getPathEntries());
    } else {
      RepositoryDirectoryValue repositoryValue =
          (RepositoryDirectoryValue) env.getValue(RepositoryDirectoryValue.key(repository));
      if (repositoryValue == null) {
        throw new MissingDepException();
      }

      if (!repositoryValue.repositoryExists()) {
        eventHandler.handle(Event.error(String.format("No such repository '%s'", repository)));
        return ImmutableList.of();
      }
      roots.add(Root.fromPath(repositoryValue.getPath()));
    }

    if (blacklistedSubdirectories.contains(directory)) {
      return ImmutableList.of();
    }
    ImmutableSet filteredBlacklistedSubdirectories =
        ImmutableSet.copyOf(
            Iterables.filter(
                blacklistedSubdirectories,
                path -> !path.equals(directory) && path.startsWith(directory)));

    LinkedHashSet<PathFragment> packageNames = new LinkedHashSet<>();
    for (Root root : roots) {
      RecursivePkgValue lookup =
          (RecursivePkgValue)
              env.getValue(
                  RecursivePkgValue.key(
                      repository,
                      RootedPath.toRootedPath(root, directory),
                      filteredBlacklistedSubdirectories));
      if (lookup == null) {
        // Typically a null value from Environment.getValue(k) means that either the key k is
        // missing a dependency or an exception was thrown during evaluation of k. Here, if this
        // getValue call returns null in a keep_going build, it can only mean a missing dependency
        // because RecursivePkgFunction#compute never throws.
        // In a nokeep_going build, a lower-level exception that RecursivePkgFunction ignored may
        // bubble up to here, but we ignore it and depend on the top-level caller to be flexible in
        // the exception types it can accept.
        throw new MissingDepException();
      }

      for (String packageName : lookup.getPackages()) {
        // TODO(bazel-team): Make RecursivePkgValue return NestedSet<PathFragment> so this transform
        // is unnecessary.
        PathFragment packageNamePathFragment = PathFragment.create(packageName);
        if (!Iterables.any(
            excludedSubdirectories,
            excludedSubdirectory -> packageNamePathFragment.startsWith(excludedSubdirectory))) {
          packageNames.add(packageNamePathFragment);
        }
      }
    }

    return packageNames;
  }
}
