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
package com.google.devtools.build.lib.query2;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.Multimap;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.PackageIdentifier;
import com.google.devtools.build.lib.concurrent.MultisetSemaphore;
import com.google.devtools.build.lib.packages.Target;
import com.google.devtools.build.lib.query2.engine.Callback;
import com.google.devtools.build.lib.query2.engine.QueryException;
import com.google.devtools.build.skyframe.SkyKey;
import java.util.Collection;
import java.util.Set;

/** Helper class to traverse edges, processing targets. */
abstract class AbstractEdgeVisitor<T> extends ParallelVisitor<T, Target> {
  private static final int PROCESS_RESULTS_BATCH_SIZE = SkyQueryEnvironment.BATCH_CALLBACK_SIZE;

  protected final SkyQueryEnvironment env;
  protected final MultisetSemaphore<PackageIdentifier> packageSemaphore;

  protected AbstractEdgeVisitor(
      SkyQueryEnvironment env,
      Callback<Target> callback,
      MultisetSemaphore<PackageIdentifier> packageSemaphore) {
    super(callback, ParallelSkyQueryUtils.VISIT_BATCH_SIZE, PROCESS_RESULTS_BATCH_SIZE);
    this.env = env;
    this.packageSemaphore = packageSemaphore;
  }

  @Override
  protected void processPartialResults(
      Iterable<SkyKey> keysToUseForResult, Callback<Target> callback)
      throws QueryException, InterruptedException {
    processResultsAndReturnTargets(keysToUseForResult, callback);
  }

  protected Iterable<Target> processResultsAndReturnTargets(
      Iterable<SkyKey> keysToUseForResult, Callback<Target> callback)
      throws QueryException, InterruptedException {
    Multimap<SkyKey, SkyKey> packageKeyToTargetKeyMap =
        SkyQueryEnvironment.makePackageKeyToTargetKeyMap(keysToUseForResult);
    Set<PackageIdentifier> pkgIdsNeededForResult =
        SkyQueryEnvironment.getPkgIdsNeededForTargetification(packageKeyToTargetKeyMap);
    packageSemaphore.acquireAll(pkgIdsNeededForResult);
    Iterable<Target> targets;
    try {
      targets =
          env.getTargetKeyToTargetMapForPackageKeyToTargetKeyMap(packageKeyToTargetKeyMap).values();
      callback.process(targets);
    } finally {
      packageSemaphore.releaseAll(pkgIdsNeededForResult);
    }
    return targets;
  }

  protected abstract SkyKey getNewNodeFromEdge(T visit);

  @Override
  protected Iterable<Task> getVisitTasks(Collection<T> pendingVisits) {
    // Group pending visitation by the package of the new node, since we'll be targetfying the
    // node during the visitation.
    ListMultimap<PackageIdentifier, T> visitsByPackage = ArrayListMultimap.create();
    for (T visit : pendingVisits) {
      Label label = SkyQueryEnvironment.SKYKEY_TO_LABEL.apply(getNewNodeFromEdge(visit));
      if (label != null) {
        visitsByPackage.put(label.getPackageIdentifier(), visit);
      }
    }

    ImmutableList.Builder<Task> builder = ImmutableList.builder();

    // A couple notes here:
    // (i)  ArrayListMultimap#values returns the values grouped by key, which is exactly what we
    //      want.
    // (ii) ArrayListMultimap#values returns a Collection view, so we make a copy to avoid
    //      accidentally retaining the entire ArrayListMultimap object.
    for (Iterable<T> visitBatch :
        Iterables.partition(
            ImmutableList.copyOf(visitsByPackage.values()),
            ParallelSkyQueryUtils.VISIT_BATCH_SIZE)) {
      builder.add(new VisitTask(visitBatch));
    }

    return builder.build();
  }
}
