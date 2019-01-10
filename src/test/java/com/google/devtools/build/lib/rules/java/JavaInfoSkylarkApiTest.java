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
package com.google.devtools.build.lib.rules.java;

import static com.google.common.truth.Truth.assertThat;
import static com.google.devtools.build.lib.actions.util.ActionsTestUtil.prettyArtifactNames;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Streams;
import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.util.BuildViewTestCase;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.packages.SkylarkProvider.SkylarkKey;
import com.google.devtools.build.lib.packages.StructImpl;
import com.google.devtools.build.lib.rules.java.JavaRuleOutputJarsProvider.OutputJar;
import com.google.devtools.build.lib.testutil.TestConstants;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

/** Tests JavaInfo API for Skylark. */
@RunWith(Parameterized.class)
public class JavaInfoSkylarkApiTest extends BuildViewTestCase {

  private static final String HOST_JAVA_RUNTIME_LABEL =
      TestConstants.TOOLS_REPOSITORY + "//tools/jdk:current_host_java_runtime";

  @Parameters(name = "Use legacy JavaInfo constructor: {0}")
  public static Iterable<Object[]> legacyJavaInfoConstructor() {
    return ImmutableList.of(new Object[] {false}, new Object[] {true});
  }

  private final boolean legacyJavaInfoConstructor;

  public JavaInfoSkylarkApiTest(boolean legacyJavaInfoConstructor) {
    this.legacyJavaInfoConstructor = legacyJavaInfoConstructor;
  }

  @Before
  public void setIncompatibleFlag() throws Exception {
    if (legacyJavaInfoConstructor) {
      setSkylarkSemanticsOptions("--noincompatible_disallow_legacy_javainfo");
    }
  }

  @Test
  public void buildHelperCreateJavaInfoWithOutputJarOnly() throws Exception {
    ruleBuilder().build();
    scratch.file(
        "foo/BUILD",
        "load(':extension.bzl', 'my_rule')",
        "my_rule(",
        "  name = 'my_skylark_rule',",
        "  output_jar = 'my_skylark_rule_lib.jar',",
        "  source_jars = ['my_skylark_rule_src.jar']",
        ")");
    assertNoEvents();

    JavaCompilationArgsProvider javaCompilationArgsProvider =
        fetchJavaInfo().getProvider(JavaCompilationArgsProvider.class);

    assertThat(prettyArtifactNames(javaCompilationArgsProvider.getDirectCompileTimeJars()))
        .containsExactly("foo/my_skylark_rule_lib.jar");
    assertThat(prettyArtifactNames(javaCompilationArgsProvider.getDirectFullCompileTimeJars()))
        .containsExactly("foo/my_skylark_rule_lib.jar");
    assertThat(prettyArtifactNames(javaCompilationArgsProvider.getRuntimeJars()))
        .containsExactly("foo/my_skylark_rule_lib.jar");
    assertThat(prettyArtifactNames(javaCompilationArgsProvider.getTransitiveCompileTimeJars()))
        .containsExactly("foo/my_skylark_rule_lib.jar");
  }

  @Test
  public void buildHelperCreateJavaInfoWithOutputJarAndUseIJar() throws Exception {

    ruleBuilder().withIJar().build();

    scratch.file(
        "foo/BUILD",
        "load(':extension.bzl', 'my_rule')",
        "my_rule(",
        "  name = 'my_skylark_rule',",
        "  output_jar = 'my_skylark_rule_lib.jar',",
        "  source_jars = ['my_skylark_rule_src.jar']",
        ")");
    assertNoEvents();

    JavaCompilationArgsProvider javaCompilationArgsProvider =
        fetchJavaInfo().getProvider(JavaCompilationArgsProvider.class);

    assertThat(prettyArtifactNames(javaCompilationArgsProvider.getDirectCompileTimeJars()))
        .containsExactly("foo/my_skylark_rule_lib-ijar.jar");
    assertThat(prettyArtifactNames(javaCompilationArgsProvider.getDirectFullCompileTimeJars()))
        .containsExactly("foo/my_skylark_rule_lib.jar");

    assertThat(prettyArtifactNames(javaCompilationArgsProvider.getRuntimeJars()))
        .containsExactly("foo/my_skylark_rule_lib.jar");
    assertThat(prettyArtifactNames(javaCompilationArgsProvider.getTransitiveCompileTimeJars()))
        .containsExactly("foo/my_skylark_rule_lib-ijar.jar");
  }

  @Test
  public void buildHelperCreateJavaInfoJavaRuleOutputJarsProviderSourceJarOutputJarAndUseIJar()
      throws Exception {
    ruleBuilder().withIJar().build();

    scratch.file(
        "foo/BUILD",
        "load(':extension.bzl', 'my_rule')",
        "my_rule(",
        "  name = 'my_skylark_rule',",
        "  output_jar = 'my_skylark_rule_lib.jar',",
        "  source_jars = ['my_skylark_rule_src.jar'],",
        ")");
    assertNoEvents();

    JavaRuleOutputJarsProvider javaRuleOutputJarsProvider =
        fetchJavaInfo().getProvider(JavaRuleOutputJarsProvider.class);

    assertThat(prettyArtifactNames(javaRuleOutputJarsProvider.getAllSrcOutputJars()))
        .containsExactly("foo/my_skylark_rule_src.jar");

    assertThat(prettyArtifactNames(javaRuleOutputJarsProvider.getAllClassOutputJars()))
        .containsExactly("foo/my_skylark_rule_lib.jar");

    assertThat(javaRuleOutputJarsProvider.getOutputJars()).hasSize(1);
    OutputJar outputJar = javaRuleOutputJarsProvider.getOutputJars().get(0);

    assertThat(outputJar.getIJar().prettyPrint()).isEqualTo("foo/my_skylark_rule_lib-ijar.jar");
  }

  @Test
  public void buildHelperCreateJavaInfoWithDeps() throws Exception {
    ruleBuilder().build();
    scratch.file(
        "foo/BUILD",
        "load(':extension.bzl', 'my_rule')",
        "java_library(name = 'my_java_lib_direct', srcs = ['java/A.java'])",
        "my_rule(name = 'my_skylark_rule',",
        "        output_jar = 'my_skylark_rule_lib.jar',",
        "        source_jars = ['my_skylark_rule_src.jar'],",
        "        dep = [':my_java_lib_direct']",
        ")");
    assertNoEvents();

    JavaCompilationArgsProvider javaCompilationArgsProvider =
        fetchJavaInfo().getProvider(JavaCompilationArgsProvider.class);

    assertThat(prettyArtifactNames(javaCompilationArgsProvider.getDirectCompileTimeJars()))
        .containsExactly("foo/my_skylark_rule_lib.jar");
    assertThat(prettyArtifactNames(javaCompilationArgsProvider.getDirectFullCompileTimeJars()))
        .containsExactly("foo/my_skylark_rule_lib.jar");
    assertThat(prettyArtifactNames(javaCompilationArgsProvider.getRuntimeJars()))
        .containsExactly("foo/my_skylark_rule_lib.jar", "foo/libmy_java_lib_direct.jar");
    assertThat(prettyArtifactNames(javaCompilationArgsProvider.getTransitiveCompileTimeJars()))
        .containsExactly("foo/my_skylark_rule_lib.jar", "foo/libmy_java_lib_direct-hjar.jar");
  }

  @Test
  public void buildHelperCreateJavaInfoWithRunTimeDeps() throws Exception {
    ruleBuilder().build();
    scratch.file(
        "foo/BUILD",
        "load(':extension.bzl', 'my_rule')",
        "java_library(name = 'my_java_lib_direct', srcs = ['java/A.java'])",
        "my_rule(name = 'my_skylark_rule',",
        "        output_jar = 'my_skylark_rule_lib.jar',",
        "        source_jars = ['my_skylark_rule_src.jar'],",
        "        dep_runtime = [':my_java_lib_direct']",
        ")");
    assertNoEvents();

    JavaCompilationArgsProvider javaCompilationArgsProvider =
        fetchJavaInfo().getProvider(JavaCompilationArgsProvider.class);

    assertThat(prettyArtifactNames(javaCompilationArgsProvider.getDirectCompileTimeJars()))
        .containsExactly("foo/my_skylark_rule_lib.jar");
    assertThat(prettyArtifactNames(javaCompilationArgsProvider.getDirectFullCompileTimeJars()))
        .containsExactly("foo/my_skylark_rule_lib.jar");
    assertThat(prettyArtifactNames(javaCompilationArgsProvider.getRuntimeJars()))
        .containsExactly("foo/my_skylark_rule_lib.jar", "foo/libmy_java_lib_direct.jar");
    assertThat(prettyArtifactNames(javaCompilationArgsProvider.getTransitiveCompileTimeJars()))
        .containsExactly("foo/my_skylark_rule_lib.jar");
  }

  @Test
  public void buildHelperCreateJavaInfoWithDepsAndNeverLink() throws Exception {
    ruleBuilder().withNeverLink().build();

    scratch.file(
        "foo/BUILD",
        "load(':extension.bzl', 'my_rule')",
        "java_library(name = 'my_java_lib_direct', srcs = ['java/A.java'])",
        "my_rule(name = 'my_skylark_rule',",
        "        output_jar = 'my_skylark_rule_lib.jar',",
        "        source_jars = ['my_skylark_rule_src.jar'],",
        "        dep = [':my_java_lib_direct']",
        ")");
    assertNoEvents();

    JavaCompilationArgsProvider javaCompilationArgsProvider =
        fetchJavaInfo().getProvider(JavaCompilationArgsProvider.class);

    assertThat(prettyArtifactNames(javaCompilationArgsProvider.getDirectCompileTimeJars()))
        .containsExactly("foo/my_skylark_rule_lib.jar");
    assertThat(prettyArtifactNames(javaCompilationArgsProvider.getDirectFullCompileTimeJars()))
        .containsExactly("foo/my_skylark_rule_lib.jar");
    assertThat(prettyArtifactNames(javaCompilationArgsProvider.getRuntimeJars())).isEmpty();
    assertThat(prettyArtifactNames(javaCompilationArgsProvider.getTransitiveCompileTimeJars()))
        .containsExactly("foo/my_skylark_rule_lib.jar", "foo/libmy_java_lib_direct-hjar.jar");
  }

  @Test
  public void buildHelperCreateJavaInfoSourceJarsProviderWithSourceJars() throws Exception {
    ruleBuilder().build();
    scratch.file(
        "foo/BUILD",
        "load(':extension.bzl', 'my_rule')",
        "my_rule(name = 'my_skylark_rule',",
        "        output_jar = 'my_skylark_rule_lib.jar',",
        "        source_jars = ['my_skylark_rule_src.jar']",
        ")");
    assertNoEvents();

    JavaSourceJarsProvider sourceJarsProvider =
        fetchJavaInfo().getProvider(JavaSourceJarsProvider.class);

    assertThat(prettyArtifactNames(sourceJarsProvider.getSourceJars()))
        .containsExactly("foo/my_skylark_rule_src.jar");

    assertThat(prettyArtifactNames(sourceJarsProvider.getTransitiveSourceJars()))
        .containsExactly("foo/my_skylark_rule_src.jar");
  }

  @Test
  public void buildHelperCreateJavaInfoWithSourcesFiles() throws Exception {
    ruleBuilder().withSourceFiles().build();

    scratch.file(
        "foo/BUILD",
        "load(':extension.bzl', 'my_rule')",
        "my_rule(",
        "  name = 'my_skylark_rule',",
        "  output_jar = 'my_skylark_rule_lib.jar',",
        "  sources = ['ClassA.java', 'ClassB.java', 'ClassC.java', 'ClassD.java'],",
        ")");
    assertNoEvents();

    JavaRuleOutputJarsProvider javaRuleOutputJarsProvider =
        fetchJavaInfo().getProvider(JavaRuleOutputJarsProvider.class);

    assertThat(prettyArtifactNames(javaRuleOutputJarsProvider.getAllSrcOutputJars()))
        .containsExactly("foo/my_skylark_rule_lib-src.jar");

    JavaSourceJarsProvider sourceJarsProvider =
        fetchJavaInfo().getProvider(JavaSourceJarsProvider.class);

    assertThat(prettyArtifactNames(sourceJarsProvider.getSourceJars()))
        .containsExactly("foo/my_skylark_rule_lib-src.jar");

    assertThat(prettyArtifactNames(sourceJarsProvider.getTransitiveSourceJars()))
        .containsExactly("foo/my_skylark_rule_lib-src.jar");
  }

  @Test
  public void buildHelperCreateJavaInfoWithSourcesFilesAndSourcesJars() throws Exception {
    ruleBuilder().withSourceFiles().build();

    scratch.file(
        "foo/BUILD",
        "load(':extension.bzl', 'my_rule')",
        "my_rule(",
        "  name = 'my_skylark_rule',",
        "  output_jar = 'my_skylark_rule_lib.jar',",
        "  sources = ['ClassA.java', 'ClassB.java', 'ClassC.java', 'ClassD.java'],",
        "  source_jars = ['my_skylark_rule_src-A.jar']",
        ")");
    assertNoEvents();

    JavaRuleOutputJarsProvider javaRuleOutputJarsProvider =
        fetchJavaInfo().getProvider(JavaRuleOutputJarsProvider.class);

    assertThat(prettyArtifactNames(javaRuleOutputJarsProvider.getAllSrcOutputJars()))
        .containsExactly("foo/my_skylark_rule_lib-src.jar");

    JavaSourceJarsProvider sourceJarsProvider =
        fetchJavaInfo().getProvider(JavaSourceJarsProvider.class);

    assertThat(prettyArtifactNames(sourceJarsProvider.getSourceJars()))
        .containsExactly("foo/my_skylark_rule_lib-src.jar");

    assertThat(prettyArtifactNames(sourceJarsProvider.getTransitiveSourceJars()))
        .containsExactly("foo/my_skylark_rule_lib-src.jar");
  }

  @Test
  public void buildHelperCreateJavaInfoSourceJarsProviderWithDeps() throws Exception {
    ruleBuilder().build();
    scratch.file(
        "foo/BUILD",
        "load(':extension.bzl', 'my_rule')",
        "java_library(name = 'my_java_lib_direct', srcs = ['java/A.java'])",
        "my_rule(name = 'my_skylark_rule',",
        "        output_jar = 'my_skylark_rule_lib.jar',",
        "        source_jars = ['my_skylark_rule_src.jar'],",
        "        dep = [':my_java_lib_direct']",
        ")");
    assertNoEvents();

    JavaSourceJarsProvider sourceJarsProvider =
        fetchJavaInfo().getProvider(JavaSourceJarsProvider.class);

    assertThat(prettyArtifactNames(sourceJarsProvider.getSourceJars()))
        .containsExactly("foo/my_skylark_rule_src.jar");

    assertThat(prettyArtifactNames(sourceJarsProvider.getTransitiveSourceJars()))
        .containsExactly("foo/my_skylark_rule_src.jar", "foo/libmy_java_lib_direct-src.jar");
  }

  @Test
  public void buildHelperCreateJavaInfoJavaSourceJarsProviderAndRuntimeDeps() throws Exception {
    ruleBuilder().build();
    scratch.file(
        "foo/BUILD",
        "load(':extension.bzl', 'my_rule')",
        "java_library(name = 'my_java_lib_direct', srcs = ['java/A.java'])",
        "my_rule(name = 'my_skylark_rule',",
        "        output_jar = 'my_skylark_rule_lib.jar',",
        "        source_jars = ['my_skylark_rule_src.jar'],",
        "        dep_runtime = [':my_java_lib_direct']",
        ")");
    assertNoEvents();

    JavaSourceJarsProvider sourceJarsProvider =
        fetchJavaInfo().getProvider(JavaSourceJarsProvider.class);

    assertThat(prettyArtifactNames(sourceJarsProvider.getSourceJars()))
        .containsExactly("foo/my_skylark_rule_src.jar");

    assertThat(prettyArtifactNames(sourceJarsProvider.getTransitiveSourceJars()))
        .containsExactly("foo/my_skylark_rule_src.jar", "foo/libmy_java_lib_direct-src.jar");
  }

  @Test
  public void buildHelperCreateJavaInfoJavaSourceJarsProviderAndTransitiveDeps() throws Exception {
    ruleBuilder().build();
    scratch.file(
        "foo/BUILD",
        "load(':extension.bzl', 'my_rule')",
        "java_library(name = 'my_java_lib_transitive', srcs = ['java/B.java'])",
        "java_library(name = 'my_java_lib_direct',",
        "             srcs = ['java/A.java'],",
        "             deps = [':my_java_lib_transitive'])",
        "my_rule(name = 'my_skylark_rule',",
        "        output_jar = 'my_skylark_rule_lib.jar',",
        "        source_jars = ['my_skylark_rule_src.jar'],",
        "        dep = [':my_java_lib_direct']",
        ")");
    assertNoEvents();

    JavaSourceJarsProvider sourceJarsProvider =
        fetchJavaInfo().getProvider(JavaSourceJarsProvider.class);

    assertThat(prettyArtifactNames(sourceJarsProvider.getSourceJars()))
        .containsExactly("foo/my_skylark_rule_src.jar");

    assertThat(prettyArtifactNames(sourceJarsProvider.getTransitiveSourceJars()))
        .containsExactly(
            "foo/my_skylark_rule_src.jar",
            "foo/libmy_java_lib_direct-src.jar",
            "foo/libmy_java_lib_transitive-src.jar");
  }

  @Test
  public void buildHelperCreateJavaInfoJavaSourceJarsProviderAndTransitiveRuntimeDeps()
      throws Exception {
    ruleBuilder().build();
    scratch.file(
        "foo/BUILD",
        "load(':extension.bzl', 'my_rule')",
        "java_library(name = 'my_java_lib_transitive', srcs = ['java/B.java'])",
        "java_library(name = 'my_java_lib_direct',",
        "             srcs = ['java/A.java'],",
        "             deps = [':my_java_lib_transitive'])",
        "my_rule(name = 'my_skylark_rule',",
        "        output_jar = 'my_skylark_rule_lib.jar',",
        "        source_jars = ['my_skylark_rule_src.jar'],",
        "        dep = [':my_java_lib_direct']",
        ")");
    assertNoEvents();

    JavaSourceJarsProvider sourceJarsProvider =
        fetchJavaInfo().getProvider(JavaSourceJarsProvider.class);

    assertThat(prettyArtifactNames(sourceJarsProvider.getSourceJars()))
        .containsExactly("foo/my_skylark_rule_src.jar");

    assertThat(prettyArtifactNames(sourceJarsProvider.getTransitiveSourceJars()))
        .containsExactly(
            "foo/my_skylark_rule_src.jar",
            "foo/libmy_java_lib_direct-src.jar",
            "foo/libmy_java_lib_transitive-src.jar");
  }

  /** Tests that JavaExportsProvider is empty by default. */
  @Test
  public void buildHelperCreateJavaInfoExportIsEmpty() throws Exception {
    ruleBuilder().build();
    scratch.file(
        "foo/BUILD",
        "load(':extension.bzl', 'my_rule')",
        "my_rule(name = 'my_skylark_rule',",
        "        output_jar = 'my_skylark_rule_lib.jar',",
        "        source_jars = ['my_skylark_rule_src.jar']",
        ")");
    assertNoEvents();

    JavaExportsProvider exportsProvider = fetchJavaInfo().getProvider(JavaExportsProvider.class);

    assertThat(exportsProvider.getTransitiveExports()).isEmpty();
  }

  /** Test exports adds dependencies to JavaCompilationArgsProvider. */
  @Test
  public void buildHelperCreateJavaInfoExportProviderExportsDepsAdded() throws Exception {
    ruleBuilder().build();
    scratch.file(
        "foo/BUILD",
        "load(':extension.bzl', 'my_rule')",
        "java_library(name = 'my_java_lib_exports', srcs = ['java/A.java'])",
        "my_rule(name = 'my_skylark_rule',",
        "        output_jar = 'my_skylark_rule_lib.jar',",
        "        dep_exports = [':my_java_lib_exports']",
        ")");
    assertNoEvents();

    JavaInfo javaInfo = fetchJavaInfo();

    JavaExportsProvider exportsProvider = javaInfo.getProvider(JavaExportsProvider.class);

    assertThat(exportsProvider.getTransitiveExports()).isEmpty();

    JavaSourceJarsProvider javaSourceJarsProvider =
        javaInfo.getProvider(JavaSourceJarsProvider.class);

    assertThat(javaSourceJarsProvider.getSourceJars()).isEmpty();

    JavaCompilationArgsProvider javaCompilationArgsProvider =
        javaInfo.getProvider(JavaCompilationArgsProvider.class);

    assertThat(prettyArtifactNames(javaCompilationArgsProvider.getDirectCompileTimeJars()))
        .containsExactly("foo/my_skylark_rule_lib.jar", "foo/libmy_java_lib_exports-hjar.jar");
    assertThat(prettyArtifactNames(javaCompilationArgsProvider.getDirectFullCompileTimeJars()))
        .containsExactly("foo/my_skylark_rule_lib.jar", "foo/libmy_java_lib_exports.jar");
    assertThat(prettyArtifactNames(javaCompilationArgsProvider.getRuntimeJars()))
        .containsExactly("foo/my_skylark_rule_lib.jar", "foo/libmy_java_lib_exports.jar");
    assertThat(prettyArtifactNames(javaCompilationArgsProvider.getTransitiveCompileTimeJars()))
        .containsExactly("foo/my_skylark_rule_lib.jar", "foo/libmy_java_lib_exports-hjar.jar");
  }

  /**
   * Test exports adds itself and recursive dependencies to JavaCompilationArgsProvider and
   * JavaExportsProvider populated.
   */
  @Test
  public void buildHelperCreateJavaInfoExportProvider() throws Exception {
    ruleBuilder().build();
    scratch.file(
        "foo/BUILD",
        "load(':extension.bzl', 'my_rule')",
        "java_library(name = 'my_java_lib_c', srcs = ['java/C.java'])",
        "java_library(name = 'my_java_lib_b', srcs = ['java/B.java'])",
        "java_library(name = 'my_java_lib_a', srcs = ['java/A.java'],",
        "             deps = [':my_java_lib_b', ':my_java_lib_c'],",
        "             exports = [':my_java_lib_b']",
        "            )",
        "my_rule(name = 'my_skylark_rule',",
        "        output_jar = 'my_skylark_rule_lib.jar',",
        "        dep_exports = [':my_java_lib_a']",
        ")");
    assertNoEvents();

    JavaInfo javaInfo = fetchJavaInfo();

    JavaExportsProvider exportsProvider = javaInfo.getProvider(JavaExportsProvider.class);

    assertThat(exportsProvider.getTransitiveExports())
        .containsExactly(Label.parseAbsolute("//foo:my_java_lib_b", ImmutableMap.of()));

    JavaCompilationArgsProvider javaCompilationArgsProvider =
        javaInfo.getProvider(JavaCompilationArgsProvider.class);

    assertThat(prettyArtifactNames(javaCompilationArgsProvider.getDirectCompileTimeJars()))
        .containsExactly(
            "foo/my_skylark_rule_lib.jar",
            "foo/libmy_java_lib_a-hjar.jar",
            "foo/libmy_java_lib_b-hjar.jar");

    assertThat(prettyArtifactNames(javaCompilationArgsProvider.getDirectFullCompileTimeJars()))
        .containsExactly(
            "foo/my_skylark_rule_lib.jar", "foo/libmy_java_lib_a.jar", "foo/libmy_java_lib_b.jar");

    assertThat(prettyArtifactNames(javaCompilationArgsProvider.getRuntimeJars()))
        .containsExactly(
            "foo/my_skylark_rule_lib.jar",
            "foo/libmy_java_lib_a.jar",
            "foo/libmy_java_lib_b.jar",
            "foo/libmy_java_lib_c.jar");

    assertThat(prettyArtifactNames(javaCompilationArgsProvider.getTransitiveCompileTimeJars()))
        .containsExactly(
            "foo/my_skylark_rule_lib.jar",
            "foo/libmy_java_lib_a-hjar.jar",
            "foo/libmy_java_lib_b-hjar.jar",
            "foo/libmy_java_lib_c-hjar.jar");
  }

  /**
   * Tests case: my_lib // \ a c // \\ b d
   *
   * <p>where single line is normal dependency and double is exports dependency.
   */
  @Test
  public void buildHelperCreateJavaInfoExportProvider001() throws Exception {
    ruleBuilder().build();
    scratch.file(
        "foo/BUILD",
        "load(':extension.bzl', 'my_rule')",
        "java_library(name = 'my_java_lib_b', srcs = ['java/B.java'])",
        "java_library(name = 'my_java_lib_a', srcs = ['java/A.java'],",
        "             deps = [':my_java_lib_b'],",
        "             exports = [':my_java_lib_b']",
        "            )",
        "java_library(name = 'my_java_lib_d', srcs = ['java/D.java'])",
        "java_library(name = 'my_java_lib_c', srcs = ['java/C.java'],",
        "             deps = [':my_java_lib_d'],",
        "             exports = [':my_java_lib_d']",
        "            )",
        "my_rule(name = 'my_skylark_rule',",
        "        output_jar = 'my_skylark_rule_lib.jar',",
        "        dep = [':my_java_lib_a', ':my_java_lib_c'],",
        "        dep_exports = [':my_java_lib_a']",
        ")");
    assertNoEvents();

    JavaInfo javaInfo = fetchJavaInfo();

    JavaExportsProvider exportsProvider = javaInfo.getProvider(JavaExportsProvider.class);

    assertThat(exportsProvider.getTransitiveExports())
        .containsExactly(Label.parseAbsolute("//foo:my_java_lib_b", ImmutableMap.of()));

    JavaCompilationArgsProvider javaCompilationArgsProvider =
        javaInfo.getProvider(JavaCompilationArgsProvider.class);

    assertThat(prettyArtifactNames(javaCompilationArgsProvider.getDirectCompileTimeJars()))
        .containsExactly(
            "foo/my_skylark_rule_lib.jar",
            "foo/libmy_java_lib_a-hjar.jar",
            "foo/libmy_java_lib_b-hjar.jar");

    assertThat(prettyArtifactNames(javaCompilationArgsProvider.getDirectFullCompileTimeJars()))
        .containsExactly(
            "foo/my_skylark_rule_lib.jar", "foo/libmy_java_lib_a.jar", "foo/libmy_java_lib_b.jar");

    assertThat(prettyArtifactNames(javaCompilationArgsProvider.getRuntimeJars()))
        .containsExactly(
            "foo/my_skylark_rule_lib.jar",
            "foo/libmy_java_lib_a.jar",
            "foo/libmy_java_lib_b.jar",
            "foo/libmy_java_lib_c.jar",
            "foo/libmy_java_lib_d.jar");

    assertThat(prettyArtifactNames(javaCompilationArgsProvider.getTransitiveCompileTimeJars()))
        .containsExactly(
            "foo/my_skylark_rule_lib.jar",
            "foo/libmy_java_lib_a-hjar.jar",
            "foo/libmy_java_lib_b-hjar.jar",
            "foo/libmy_java_lib_c-hjar.jar",
            "foo/libmy_java_lib_d-hjar.jar");
  }

  @Test
  public void buildHelperCreateJavaInfoWithOutputJarAndStampJar() throws Exception {
    if (legacyJavaInfoConstructor) {
      // Unsupported mode, don't test this
      return;
    }

    ruleBuilder().withStampJar().build();

    scratch.file(
        "foo/BUILD",
        "load(':extension.bzl', 'my_rule')",
        "my_rule(",
        "  name = 'my_skylark_rule',",
        "  output_jar = 'my_skylark_rule_lib.jar',",
        "  source_jars = ['my_skylark_rule_src.jar']",
        ")");
    assertNoEvents();

    JavaCompilationArgsProvider javaCompilationArgsProvider =
        fetchJavaInfo().getProvider(JavaCompilationArgsProvider.class);
    assertThat(prettyArtifactNames(javaCompilationArgsProvider.getDirectFullCompileTimeJars()))
        .containsExactly("foo/my_skylark_rule_lib.jar");
    assertThat(prettyArtifactNames(javaCompilationArgsProvider.getDirectCompileTimeJars()))
        .containsExactly("foo/my_skylark_rule_lib-stamped.jar");
    assertThat(prettyArtifactNames(javaCompilationArgsProvider.getRuntimeJars()))
        .containsExactly("foo/my_skylark_rule_lib.jar");
    assertThat(prettyArtifactNames(javaCompilationArgsProvider.getTransitiveCompileTimeJars()))
        .containsExactly("foo/my_skylark_rule_lib-stamped.jar");
  }

  @Test
  public void buildHelperCreateJavaInfoWithJdeps_javaRuleOutputJarsProvider() throws Exception {
    ruleBuilder().build();
    scratch.file(
        "foo/BUILD",
        "load(':extension.bzl', 'my_rule')",
        "java_library(name = 'my_java_lib_direct', srcs = ['java/A.java'])",
        "my_rule(",
        "  name = 'my_skylark_rule',",
        "  output_jar = 'my_skylark_rule_lib.jar',",
        "  source_jars = ['my_skylark_rule_src.jar'],",
        "  dep = [':my_java_lib_direct'],",
        "  jdeps = 'my_jdeps.pb',",
        ")");
    assertNoEvents();

    JavaRuleOutputJarsProvider ruleOutputs =
        fetchJavaInfo().getProvider(JavaRuleOutputJarsProvider.class);

    assertThat(
            prettyArtifactNames(
                ruleOutputs.getOutputJars().stream()
                    .map(o -> o.getClassJar())
                    .collect(ImmutableList.toImmutableList())))
        .containsExactly("foo/my_skylark_rule_lib.jar");
    assertThat(
            prettyArtifactNames(
                ruleOutputs.getOutputJars().stream()
                    .flatMap(o -> Streams.stream(o.getSrcJars()))
                    .collect(ImmutableList.toImmutableList())))
        .containsExactly("foo/my_skylark_rule_src.jar");
    assertThat(ruleOutputs.getJdeps().prettyPrint()).isEqualTo("foo/my_jdeps.pb");
  }

  @Test
  public void testMixMatchNewAndLegacyArgsIsError() throws Exception {
    ImmutableList.Builder<String> lines = ImmutableList.builder();
    lines.add(
        "result = provider()",
        "def _impl(ctx):",
        "  output_jar = ctx.actions.declare_file('output_jar')",
        "  source_jar = ctx.actions.declare_file('source_jar')",
        "  javaInfo = JavaInfo(",
        "    output_jar = output_jar, ",
        "    source_jar = source_jar,",
        "    source_jars = [source_jar],",
        "  )",
        "  return [result(property = javaInfo)]",
        "my_rule = rule(",
        "  implementation = _impl,",
        ")");
    scratch.file("foo/extension.bzl", lines.build().toArray(new String[] {}));
    checkError(
        "foo",
        "my_skylark_rule",
        "Cannot use deprecated arguments at the same time",
        "load(':extension.bzl', 'my_rule')",
        "my_rule(name = 'my_skylark_rule')");
  }

  @Test
  public void testIncompatibleDisallowLegacyJavaInfo() throws Exception {
    setSkylarkSemanticsOptions("--incompatible_disallow_legacy_javainfo");
    ImmutableList.Builder<String> lines = ImmutableList.builder();
    lines.add(
        "result = provider()",
        "def _impl(ctx):",
        "  output_jar = ctx.actions.declare_file('output_jar')",
        "  source_jar = ctx.actions.declare_file('source_jar')",
        "  javaInfo = JavaInfo(",
        "    output_jar = output_jar,",
        "    source_jars = [source_jar],", // No longer allowed
        "  )",
        "  return [result(property = javaInfo)]",
        "my_rule = rule(",
        "  implementation = _impl,",
        ")");
    scratch.file("foo/extension.bzl", lines.build().toArray(new String[] {}));
    checkError(
        "foo",
        "my_skylark_rule",
        "Cannot use deprecated argument when --incompatible_disallow_legacy_javainfo is set. ",
        "load(':extension.bzl', 'my_rule')",
        "my_rule(name = 'my_skylark_rule')");
  }

  private RuleBuilder ruleBuilder() {
    return new RuleBuilder();
  }

  private class RuleBuilder {
    private boolean useIJar = false;
    private boolean stampJar;
    private boolean neverLink = false;
    private boolean sourceFiles = false;

    private RuleBuilder withIJar() {
      useIJar = true;
      return this;
    }

    private RuleBuilder withStampJar() {
      stampJar = true;
      return this;
    }

    private RuleBuilder withNeverLink() {
      neverLink = true;
      return this;
    }

    private RuleBuilder withSourceFiles() {
      sourceFiles = true;
      return this;
    }

    private String[] legacyJavaInfo() {
      assertThat(stampJar).isFalse();
      return new String[] {
        "  javaInfo = JavaInfo(",
        "    output_jar = ctx.outputs.output_jar, ",
        useIJar ? "    use_ijar = True," : "    use_ijar = False,",
        neverLink ? "    neverlink = True," : "",
        "    source_jars = ctx.files.source_jars,",
        "    sources = ctx.files.sources,",
        "    deps = dp,",
        "    runtime_deps = dp_runtime,",
        "    exports = dp_exports,",
        "    jdeps = ctx.file.jdeps,",
        useIJar || sourceFiles ? "    actions = ctx.actions," : "",
        useIJar || sourceFiles ? "    java_toolchain = ctx.attr._toolchain," : "",
        sourceFiles ? "    host_javabase = ctx.attr._host_javabase," : "",
        "  )",
        "  return [result(property = javaInfo)]"
      };
    }

    private String[] newJavaInfo() {
      assertThat(useIJar && stampJar).isFalse();
      ImmutableList.Builder<String> lines = ImmutableList.builder();
      if (useIJar) {
        lines.add(
            "  compile_jar = java_common.run_ijar(",
            "    ctx.actions,",
            "    jar = ctx.outputs.output_jar,",
            "    java_toolchain = ctx.attr._toolchain,",
            "  )");
      } else if (stampJar) {
        lines.add(
            "  compile_jar = java_common.stamp_jar(",
            "    ctx.actions,",
            "    jar = ctx.outputs.output_jar,",
            "    target_label = ctx.label,",
            "    java_toolchain = ctx.attr._toolchain,",
            "  )");
      } else {
        lines.add("  compile_jar = ctx.outputs.output_jar");
      }
      if (sourceFiles) {
        lines.add(
            "  source_jar = java_common.pack_sources(",
            "    ctx.actions,",
            "    output_jar = ctx.outputs.output_jar,",
            "    sources = ctx.files.sources,",
            "    source_jars = ctx.files.source_jars,",
            "    java_toolchain = ctx.attr._toolchain,",
            "    host_javabase = ctx.attr._host_javabase,",
            ")");
      } else {
        lines.add(
            "  if ctx.files.source_jars:",
            "    source_jar = list(ctx.files.source_jars)[0]",
            "  else:",
            "    source_jar = None");
      }
      lines.add(
          "  javaInfo = JavaInfo(",
          "    output_jar = ctx.outputs.output_jar,",
          "    compile_jar = compile_jar,",
          "    source_jar = source_jar,",
          neverLink ? "    neverlink = True," : "",
          "    deps = dp,",
          "    runtime_deps = dp_runtime,",
          "    exports = dp_exports,",
          "    jdeps = ctx.file.jdeps,",
          "  )",
          "  return [result(property = javaInfo)]");
      return lines.build().toArray(new String[] {});
    }

    private void build() throws Exception {
      if (useIJar || stampJar || sourceFiles) {
        writeBuildFileForJavaToolchain();
      }

      ImmutableList.Builder<String> lines = ImmutableList.builder();
      lines.add(
          "result = provider()",
          "def _impl(ctx):",
          "  ctx.actions.write(ctx.outputs.output_jar, 'JavaInfo API Test', is_executable=False) ",
          "  dp = [dep[java_common.provider] for dep in ctx.attr.dep]",
          "  dp_runtime = [dep[java_common.provider] for dep in ctx.attr.dep_runtime]",
          "  dp_exports = [dep[java_common.provider] for dep in ctx.attr.dep_exports]");
      lines.add(legacyJavaInfoConstructor ? legacyJavaInfo() : newJavaInfo());
      lines.add(
          "my_rule = rule(",
          "  implementation = _impl,",
          "  attrs = {",
          "    'dep' : attr.label_list(),",
          "    'dep_runtime' : attr.label_list(),",
          "    'dep_exports' : attr.label_list(),",
          "    'output_jar' : attr.output(default=None, mandatory=True),",
          "    'source_jars' : attr.label_list(allow_files=['.jar']),",
          "    'sources' : attr.label_list(allow_files=['.java']),",
          "    'jdeps' : attr.label(allow_single_file=True),",
          useIJar || stampJar || sourceFiles
              ? "    '_toolchain': attr.label(default = Label('//java/com/google/test:toolchain')),"
              : "",
          sourceFiles
              ? "    '_host_javabase': attr.label(default = Label('"
                  + HOST_JAVA_RUNTIME_LABEL
                  + "')),"
              : "",
          "  }",
          ")");

      scratch.file("foo/extension.bzl", lines.build().toArray(new String[] {}));
    }
  }

  private JavaInfo fetchJavaInfo() throws Exception {
    ConfiguredTarget myRuleTarget = getConfiguredTarget("//foo:my_skylark_rule");
    StructImpl info =
        (StructImpl)
            myRuleTarget.get(
                new SkylarkKey(
                    Label.parseAbsolute("//foo:extension.bzl", ImmutableMap.of()), "result"));

    @SuppressWarnings("unchecked")
    JavaInfo javaInfo = (JavaInfo) info.getValue("property");
    return javaInfo;
  }
}
