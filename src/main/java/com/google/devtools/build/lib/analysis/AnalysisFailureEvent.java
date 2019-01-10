// Copyright 2018 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.analysis;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.devtools.build.lib.buildeventstream.BuildEvent;
import com.google.devtools.build.lib.buildeventstream.BuildEventContext;
import com.google.devtools.build.lib.buildeventstream.BuildEventId;
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos;
import com.google.devtools.build.lib.buildeventstream.GenericBuildEvent;
import com.google.devtools.build.lib.buildeventstream.NullConfiguration;
import com.google.devtools.build.lib.causes.Cause;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.skyframe.ConfiguredTargetKey;
import java.util.Collection;
import javax.annotation.Nullable;

/**
 * This event is fired during the build, when it becomes known that the analysis of a target cannot
 * be completed because of an error in one of its dependencies.
 */
public class AnalysisFailureEvent implements BuildEvent {
  private final ConfiguredTargetKey failedTarget;
  private final BuildEventId configuration;
  private final Iterable<Cause> rootCauses;

  public AnalysisFailureEvent(
      ConfiguredTargetKey failedTarget, BuildEventId configuration, Iterable<Cause> rootCauses) {
    this.failedTarget = failedTarget;
    if (configuration != null) {
      this.configuration = configuration;
    } else {
      this.configuration = NullConfiguration.INSTANCE.getEventId();
    }
    this.rootCauses = rootCauses;
  }

  public ConfiguredTargetKey getFailedTarget() {
    return failedTarget;
  }

  /**
   * Returns the label of a single root cause. Use {@link #getRootCauses} to report all root causes.
   */
  @Nullable public Label getLegacyFailureReason() {
    if (!rootCauses.iterator().hasNext()) {
      return null;
    }
    return rootCauses.iterator().next().getLabel();
  }

  public Iterable<Cause> getRootCauses() {
    return rootCauses;
  }

  @Override
  public BuildEventId getEventId() {
    return BuildEventId.targetCompleted(failedTarget.getLabel(), configuration);
  }

  @Override
  public Collection<BuildEventId> getChildrenEvents() {
    return ImmutableList.copyOf(Iterables.transform(rootCauses, BuildEventId::fromCause));
  }

  @Override
  public BuildEventStreamProtos.BuildEvent asStreamProto(BuildEventContext converters) {
    return GenericBuildEvent.protoChaining(this)
        .setAborted(
            BuildEventStreamProtos.Aborted.newBuilder()
                .setReason(BuildEventStreamProtos.Aborted.AbortReason.ANALYSIS_FAILURE)
                .build())
        .build();
  }
}
