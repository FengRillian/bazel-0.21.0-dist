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
package com.google.devtools.build.lib.testutil;

import com.google.common.base.Function;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;
import com.google.devtools.build.lib.analysis.BlazeDirectories;
import com.google.devtools.build.lib.analysis.ConfiguredRuleClassProvider;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.PackageIdentifier;
import com.google.devtools.build.lib.packages.NoSuchPackageException;
import com.google.devtools.build.lib.packages.Package;
import com.google.devtools.build.lib.packages.Target;
import com.google.devtools.build.lib.skyframe.packages.BazelPackageLoader;
import com.google.devtools.build.lib.skyframe.packages.PackageLoader;
import com.google.devtools.build.lib.syntax.SkylarkSemantics;
import com.google.devtools.build.lib.vfs.Root;

/**
 * A Package.Builder.Helper for use in tests that a sanity check with {@link BazelPackageLoader} for
 * each loaded package, for the sake of getting pretty nice test coverage.
 */
public class BazelPackageBuilderHelperForTesting implements Package.Builder.Helper {
  private final ConfiguredRuleClassProvider ruleClassProvider;
  private final BlazeDirectories directories;

  public BazelPackageBuilderHelperForTesting(
      ConfiguredRuleClassProvider ruleClassProvider, BlazeDirectories directories) {
    this.ruleClassProvider = ruleClassProvider;
    this.directories = directories;
  }

  @Override
  public Package createFreshPackage(PackageIdentifier packageId, String runfilesPrefix) {
    return Package.Builder.DefaultHelper.INSTANCE.createFreshPackage(packageId, runfilesPrefix);
  }

  @Override
  public void onLoadingComplete(
      Package pkg, SkylarkSemantics skylarkSemantics, long loadTimeNanos) {
    sanityCheckBazelPackageLoader(pkg, ruleClassProvider, skylarkSemantics);
  }

  private static final Function<Target, Label> TARGET_TO_LABEL =
      new Function<Target, Label>() {
        @Override
        public Label apply(Target input) {
          return input.getLabel();
        }
      };

  private void sanityCheckBazelPackageLoader(
      Package pkg,
      ConfiguredRuleClassProvider ruleClassProvider,
      SkylarkSemantics skylarkSemantics) {
    PackageIdentifier pkgId = pkg.getPackageIdentifier();
    PackageLoader packageLoader =
        BazelPackageLoader.builder(
            Root.fromPath(directories.getWorkspace()),
            directories.getInstallBase(),
            directories.getOutputBase())
            .setSkylarkSemantics(skylarkSemantics)
            .setRuleClassProvider(ruleClassProvider)
            .build();
    Package newlyLoadedPkg;
    try {
      newlyLoadedPkg = packageLoader.loadPackage(pkg.getPackageIdentifier());
    } catch (InterruptedException e) {
      return;
    } catch (NoSuchPackageException e) {
      throw new IllegalStateException(e);
    }
    ImmutableSet<Label> targetsInPkg =
        ImmutableSet.copyOf(Iterables.transform(pkg.getTargets().values(), TARGET_TO_LABEL));
    ImmutableSet<Label> targetsInNewlyLoadedPkg =
        ImmutableSet.copyOf(
            Iterables.transform(newlyLoadedPkg.getTargets().values(), TARGET_TO_LABEL));
    if (!targetsInPkg.equals(targetsInNewlyLoadedPkg)) {
      Sets.SetView<Label> unsatisfied = Sets.difference(targetsInPkg, targetsInNewlyLoadedPkg);
      if (pkgId.compareTo(PackageIdentifier.createInMainRepo("tools/defaults")) == 0
          && unsatisfied.isEmpty()) {
        // The tools/defaults package is populated from command-line options
        // (=configuration fragments) which the user specifies in tests using
        // BuildViewTestCase.useConfiguration().
        // We'd like PackageLoader to work as much as possible without duplicating the entire
        // configuration of Bazel, so we prefer not to pass these flags to PackageLoader.
        // As a result, the contents of tools/defaults might differ.
        // As long as PackageLoader returns a superset of what Bazel returns, everything should load
        // fine.
        return;
      }
      Sets.SetView<Label> unexpected = Sets.difference(targetsInNewlyLoadedPkg, targetsInPkg);
      throw new IllegalStateException(
          String.format(
              "The Package for %s had a different set of targets (<targetsInPkg> - "
                  + "<targetsInNewlyLoadedPkg> = %s, <targetsInNewlyLoadedPkg> - <targetsInPkg> = "
                  + "%s) when loaded normally during execution of the current test than it did "
                  + "when loaded via BazelPackageLoader (done automatically by the "
                  + "BazelPackageBuilderHelperForTesting hook). This either means: (i) Skyframe "
                  + "package loading semantics have diverged from "
                  + "BazelPackageLoader semantics (ii) The test in question is doing something "
                  + "that confuses BazelPackageBuilderHelperForTesting.",
              pkgId, unsatisfied, unexpected));
    }
  }
}
