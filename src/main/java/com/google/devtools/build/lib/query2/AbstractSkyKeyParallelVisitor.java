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

import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.query2.engine.Callback;
import com.google.devtools.build.lib.query2.engine.QueryException;
import com.google.devtools.build.lib.query2.engine.Uniquifier;
import com.google.devtools.build.skyframe.SkyKey;

/** A {@link ParallelVisitor} whose visitations occur on {@link SkyKey}s. */
public abstract class AbstractSkyKeyParallelVisitor<T> extends ParallelVisitor<SkyKey, T> {
  private final Uniquifier<SkyKey> uniquifier;

  protected AbstractSkyKeyParallelVisitor(
      Uniquifier<SkyKey> uniquifier,
      Callback<T> callback,
      int visitBatchSize,
      int processResultsBatchSize) {
    super(callback, visitBatchSize, processResultsBatchSize);
    this.uniquifier = uniquifier;
  }

  @Override
  protected ImmutableList<SkyKey> getUniqueValues(Iterable<SkyKey> values) throws QueryException {
    return uniquifier.unique(values);
  }
}
