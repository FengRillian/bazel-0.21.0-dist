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
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.ResolvedTargets;
import com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable;
import com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadSafe;
import com.google.devtools.build.lib.packages.Target;
import com.google.devtools.build.lib.packages.TargetUtils;
import com.google.devtools.build.lib.skyframe.serialization.NotSerializableRuntimeException;
import com.google.devtools.build.skyframe.SkyFunctionName;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.Objects;

/**
 * A value referring to a computed set of resolved targets. This is used for the results of target
 * pattern parsing.
 */
@Immutable
@ThreadSafe
final class TestsInSuiteValue implements SkyValue {
  private ResolvedTargets<Label> labels;

  TestsInSuiteValue(ResolvedTargets<Label> labels) {
    this.labels = Preconditions.checkNotNull(labels);
  }

  public ResolvedTargets<Label> getLabels() {
    return labels;
  }

  @SuppressWarnings("unused")
  private void writeObject(ObjectOutputStream out) {
    throw new NotSerializableRuntimeException();
  }

  @SuppressWarnings("unused")
  private void readObject(ObjectInputStream in) {
    throw new NotSerializableRuntimeException();
  }

  @SuppressWarnings("unused")
  private void readObjectNoData() {
    throw new IllegalStateException();
  }

  /**
   * Create a target pattern value key.
   *
   * @param testSuiteTarget the test suite target to be expanded
   */
  @ThreadSafe
  public static SkyKey key(Target testSuiteTarget, boolean strict) {
    Preconditions.checkState(TargetUtils.isTestSuiteRule(testSuiteTarget));
    return new TestsInSuiteKey(testSuiteTarget.getLabel(), strict);
  }

  /**
   * A list of targets of which all test suites should be expanded.
   */
  @ThreadSafe
  static final class TestsInSuiteKey implements SkyKey, Serializable {
    private final Label testSuiteLabel;
    private final boolean strict;

    public TestsInSuiteKey(Label testSuiteLabel, boolean strict) {
      this.testSuiteLabel = testSuiteLabel;
      this.strict = strict;
    }

    @Override
    public SkyFunctionName functionName() {
      return SkyFunctions.TESTS_IN_SUITE;
    }

    public Label getTestSuiteLabel() {
      return testSuiteLabel;
    }

    public boolean isStrict() {
      return strict;
    }

    @Override
    public String toString() {
      return "TestsInSuite(" + testSuiteLabel.toString() + ", strict=" + strict + ")";
    }

    @Override
    public int hashCode() {
      return Objects.hash(testSuiteLabel, strict);
    }

    @Override
    public boolean equals(Object obj) {
      if (this == obj) {
        return true;
      }
      if (!(obj instanceof TestsInSuiteKey)) {
        return false;
      }
      TestsInSuiteKey other = (TestsInSuiteKey) obj;
      return other.testSuiteLabel.equals(testSuiteLabel) && other.strict == strict;
    }
  }
}
