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

package com.google.devtools.build.lib.testutil;

import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.packages.BuilderFactoryForTesting;
import com.google.devtools.build.lib.runtime.proto.InvocationPolicyOuterClass.InvocationPolicy;
import com.google.devtools.build.lib.skyframe.SkyframeExecutor;

/**
 * Various constants required by the tests.
 */
public class TestConstants {
  private TestConstants() {
  }

  public static final String PRODUCT_NAME = "bazel";

  /**
   * A list of all embedded binaries that go into the regular Bazel binary.
   */
  public static final ImmutableList<String> EMBEDDED_TOOLS = ImmutableList.of(
      "build-runfiles",
      "linux-sandbox",
      "process-wrapper",
      "xcode-locator");

  /**
   * Location in the bazel repo where embedded binaries come from.
   */
  public static final ImmutableList<String> EMBEDDED_SCRIPTS_PATHS = ImmutableList.of(
      "io_bazel/src/main/tools");

  /**
   * Default workspace name.
   */
  public static final String WORKSPACE_NAME = "__main__";

  /**
   * Name of a class with an INSTANCE field of type AnalysisMock to be used for analysis tests.
   */
  public static final String TEST_ANALYSIS_MOCK =
      "com.google.devtools.build.lib.analysis.mock.BazelAnalysisMock";

  /**
   * Directory where we can find bazel's Java tests, relative to a test's runfiles directory.
   */
  public static final String JAVATESTS_ROOT = "io_bazel/src/test/java/";

  /** Location of the bazel repo relative to the workspace root */
  public static final String BAZEL_REPO_PATH = "";

  /** Relative path to the {@code process-wrapper} tool. */
  public static final String PROCESS_WRAPPER_PATH =
      "io_bazel/src/main/tools/process-wrapper";

  /** Relative path to the {@code linux-sandbox} tool. */
  public static final String LINUX_SANDBOX_PATH =
      "io_bazel/src/main/tools/linux-sandbox";

  /** Relative path to the {@code spend_cpu_time} testing tool. */
  public static final String CPU_TIME_SPENDER_PATH =
      "io_bazel/src/test/shell/integration/spend_cpu_time";

  public static final String TEST_RULE_CLASS_PROVIDER =
      "com.google.devtools.build.lib.bazel.rules.BazelRuleClassProvider";
  public static final String TEST_RULE_MODULE =
        "com.google.devtools.build.lib.bazel.rules.BazelRulesModule";
  public static final String TEST_REAL_UNIX_FILE_SYSTEM =
      "com.google.devtools.build.lib.unix.UnixFileSystem";

  public static void processSkyframeExecutorForTesting(SkyframeExecutor skyframeExecutor) {}

  public static final ImmutableList<String> IGNORED_MESSAGE_PREFIXES = ImmutableList.<String>of();

  public static final String WORKSPACE_CONTENT = "";

  /** The path in which the mock cc crosstool resides. */
  public static final String MOCK_CC_CROSSTOOL_PATH = "tools/cpp";

  /** The workspace repository label under which built-in tools reside. */
  public static final String TOOLS_REPOSITORY = "@bazel_tools";
  /** The file path in which to create files so that they end up under {@link #TOOLS_REPOSITORY}. */
  public static final String TOOLS_REPOSITORY_SCRATCH = "/bazel_tools_workspace/";
  /** The output file path prefix for tool file dependencies. */
  public static final String TOOLS_REPOSITORY_PATH_PREFIX = "external/bazel_tools/";

  public static final ImmutableList<String> DOCS_RULES_PATHS = ImmutableList.of(
      "src/main/java/com/google/devtools/build/lib/rules");

  // Constants used to determine how genrule pulls in the setup script.
  public static final String GENRULE_SETUP = "@bazel_tools//tools/genrule:genrule-setup.sh";
  public static final String GENRULE_SETUP_PATH = "genrule-setup.sh";

  /**
   * A list of flags required to support use of the crosstool on OSX.
   */
  public static final ImmutableList<String> OSX_CROSSTOOL_FLAGS =
      ImmutableList.of();

  public static final InvocationPolicy TEST_INVOCATION_POLICY =
      InvocationPolicy.getDefaultInstance();

  public static final BuilderFactoryForTesting PACKAGE_FACTORY_BUILDER_FACTORY_FOR_TESTING =
      PackageFactoryBuilderFactoryForBazelUnitTests.INSTANCE;

  /** A choice of test execution mode, only varies internally. */
  public enum InternalTestExecutionMode {
    NORMAL
  }
}
