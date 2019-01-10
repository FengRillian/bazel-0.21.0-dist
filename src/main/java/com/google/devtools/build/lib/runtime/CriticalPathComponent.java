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
package com.google.devtools.build.lib.runtime;

import com.google.common.base.Preconditions;
import com.google.devtools.build.lib.actions.Action;
import com.google.devtools.build.lib.actions.ActionOwner;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.SpawnMetrics;
import com.google.devtools.build.lib.clock.Clock;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadCompatible;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;

/**
 * A component of the graph over which the critical path is computed. This may be identical to the
 * action graph, but does not have to be - it may also take into account individual spawns run as
 * part of an action.
 */
@ThreadCompatible
public class CriticalPathComponent {
  /**
   * Converts from nanos to millis since the epoch. In particular, note that {@link System#nanoTime}
   * does not specify any particular time reference but only notes that returned values are only
   * meaningful when taking in relation to each other.
   */
  public interface NanosToEpochConverter {
    /** Converts from nanos to millis since the epoch. */
    long toEpoch(long timeNanos);
  }

  /**
   * Creates a {@link NanosToEpochConverter} from clock by taking the current time in millis and the
   * current time in nanos to compute the appropriate offset.
   */
  public static NanosToEpochConverter fromClock(Clock clock) {
    long nowInMillis = clock.currentTimeMillis();
    long nowInNanos = clock.nanoTime();
    return (startNanos) -> nowInMillis - TimeUnit.NANOSECONDS.toMillis((nowInNanos - startNanos));
  }

  // These two fields are values of BlazeClock.nanoTime() at the relevant points in time.
  private long startNanos;
  private long finishNanos = 0;
  protected volatile boolean isRunning = true;

  /** We keep here the critical path time for the most expensive child. */
  private long childAggregatedElapsedTime = 0;

  /** May be nulled out after finished running to allow the action to be GC'ed. */
  @Nullable protected Action action;

  private final Artifact primaryOutput;

  /** Spawn metrics for this action. */
  private SpawnMetrics spawnMetrics = SpawnMetrics.EMPTY;
  /** An unique identifier of the component for one build execution */
  private final int id;

  /**
   * Child with the maximum critical path.
   */
  @Nullable
  private CriticalPathComponent child;

  public CriticalPathComponent(int id, Action action, long startNanos) {
    this.id = id;
    this.action = Preconditions.checkNotNull(action);
    this.primaryOutput = action.getPrimaryOutput();
    this.startNanos = startNanos;
  }

  /**
   * Record the elapsed time in case the new duration is greater. This method could be called
   * multiple times if we run shared action concurrently and the one that really gets executed takes
   * more time to send the finish event and the one that was a cache hit manages to send the event
   * before. In this case we overwrite the time with the greater time.
   *
   * <p>This logic is known to be incorrect, as other actions that depend on this action will not
   * necessarily use the correct getElapsedTimeNanos(). But we do not want to block action execution
   * because of this. So in certain conditions we might see another path as the critical path.
   */
  public synchronized void finishActionExecution(long startNanos, long finishNanos) {
    if (isRunning || finishNanos - startNanos > getElapsedTimeNanos()) {
      this.startNanos = startNanos;
      this.finishNanos = finishNanos;
      isRunning = false;
    }
  }

  boolean isPrimaryOutput(Artifact possiblePrimaryOutput) {
    // We know that the keys in the CriticalPathComputer are exactly the values returned from
    // action.getPrimaryOutput(), so pointer equality is safe here.
    return possiblePrimaryOutput == primaryOutput;
  }

  /**
   * The action for which we are storing the stat. May be null if the action has finished running.
   */
  @Nullable
  public final Action maybeGetAction() {
    return action;
  }

  public boolean isRunning() {
    return isRunning;
  }

  public String prettyPrintAction() {
    return getActionNotNull().prettyPrint();
  }

  @Nullable
  public Label getOwner() {
    ActionOwner owner = getActionNotNull().getOwner();
    if (owner != null && owner.getLabel() != null) {
      return owner.getLabel();
    }
    return null;
  }

  public String getMnemonic() {
    return getActionNotNull().getMnemonic();
  }

  private Action getActionNotNull() {
    return Preconditions.checkNotNull(action, this);
  }

  /** An unique identifier of the component for one build execution */
  public int getId() {
    return id;
  }

  /**
   * An action can run multiple spawns. Those calls can be sequential or parallel. Because we do not
   * know in general how to aggregate the data (if it is a sequence of calls we should add, if they
   * are run in parallel we should keep the maximum), we keep the maximum. This is better than just
   * keeping the latest one.
   */
  public void addSpawnMetrics(SpawnMetrics spawnMetrics) {
    if (spawnMetrics.totalTime().compareTo(this.spawnMetrics.totalTime()) > 0) {
      this.spawnMetrics = spawnMetrics;
    }
  }

  /** Returns spawn metrics for the execution of the action. */
  public SpawnMetrics getSpawnMetrics() {
    return spawnMetrics;
  }

  /**
   * Add statistics for one dependency of this action. Caller should ensure {@code dep} not
   * running.
   */
  synchronized void addDepInfo(CriticalPathComponent dep) {
    long childAggregatedWallTime = dep.getAggregatedElapsedTimeNanos();
    // Replace the child if its critical path had the maximum elapsed time.
    if (child == null || childAggregatedWallTime > this.childAggregatedElapsedTime) {
      this.childAggregatedElapsedTime = childAggregatedWallTime;
      child = dep;
    }
  }

  public long getStartTimeNanos() {
    return startNanos;
  }

  public long getStartTimeMillisSinceEpoch(NanosToEpochConverter converter) {
    return converter.toEpoch(startNanos);
  }

  public Duration getElapsedTime() {
    return Duration.ofNanos(getElapsedTimeNanos());
  }

  long getElapsedTimeNanos() {
    Preconditions.checkState(!isRunning, "Still running %s", this);
    return getElapsedTimeNanosNoCheck();
  }

  /** To be used only in debugging: skips state invariance checks to avoid crash-looping. */
  protected Duration getElapsedTimeNoCheck() {
    return Duration.ofNanos(getElapsedTimeNanosNoCheck());
  }

  private long getElapsedTimeNanosNoCheck() {
    return finishNanos - startNanos;
  }

  /**
   * Returns the current critical path for the action.
   *
   * <p>Critical path is defined as : action_execution_time + max(child_critical_path).
   */
  public Duration getAggregatedElapsedTime() {
    return Duration.ofNanos(getAggregatedElapsedTimeNanos());
  }

  long getAggregatedElapsedTimeNanos() {
    Preconditions.checkState(!isRunning, "Still running %s", this);
    return getElapsedTimeNanos() + childAggregatedElapsedTime;
  }

  /**
   * Get the child critical path component.
   *
   * <p>The component dependency with the maximum total critical path time.
   */
  @Nullable
  public CriticalPathComponent getChild() {
    return child;
  }

  /** Returns a string representation of the action. Only for use in crash messages and the like. */
  protected String getActionString() {
    return (action == null ? "(null action)" : action.prettyPrint());
  }

  /**
   * Returns a user readable representation of the critical path stats with all the details.
   */
  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    String currentTime = "still running";
    if (!isRunning) {
      currentTime = String.format("%.2f", getElapsedTimeNoCheck().toMillis() / 1000.0) + "s";
    }
    sb.append(currentTime);
    sb.append(", Remote ");
    sb.append(getSpawnMetrics().toString(getElapsedTimeNoCheck(), /* summary= */ false));
    sb.append(" ");
    sb.append(getActionString());
    return sb.toString();
  }
}

