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

package com.google.devtools.build.lib.skyframe;

import static com.google.common.truth.Truth.assertThat;

import com.google.common.base.Optional;
import com.google.devtools.build.lib.actions.FileValue;
import com.google.devtools.build.lib.analysis.ConfiguredRuleClassProvider;
import com.google.devtools.build.lib.analysis.util.BuildViewTestCase;
import com.google.devtools.build.lib.skyframe.WorkspaceFileFunctionTest.FakeFileValue;
import com.google.devtools.build.lib.skyframe.WorkspaceFileFunctionTest.SkyKeyMatchers;
import com.google.devtools.build.lib.syntax.BuildFileAST;
import com.google.devtools.build.lib.testutil.TestRuleClassProvider;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.Root;
import com.google.devtools.build.lib.vfs.RootedPath;
import com.google.devtools.build.skyframe.SkyFunction;
import com.google.devtools.build.skyframe.SkyFunctionException;
import java.io.IOException;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Matchers;
import org.mockito.Mockito;

/**
 * Test for WorkspaceASTFunction.
 */
@RunWith(JUnit4.class)
public class WorkspaceASTFunctionTest extends BuildViewTestCase {

  private WorkspaceASTFunction astSkyFunc;
  private FakeFileValue fakeWorkspaceFileValue;

  @Before
  public final void setUp() throws Exception {
    ConfiguredRuleClassProvider ruleClassProvider = TestRuleClassProvider.getRuleClassProvider();
    ConfiguredRuleClassProvider ruleClassProviderSpy = Mockito.spy(ruleClassProvider);
    // Prevent returning default workspace file.
    Mockito.when(ruleClassProviderSpy.getDefaultWorkspacePrefix()).thenReturn("");
    Mockito.when(ruleClassProviderSpy.getDefaultWorkspaceSuffix()).thenReturn("");
    astSkyFunc = new WorkspaceASTFunction(ruleClassProviderSpy);
    fakeWorkspaceFileValue = new FakeFileValue();
  }

  private RootedPath createWorkspaceFile(String... contents) throws IOException {
    Path workspacePath = scratch.overwriteFile("WORKSPACE", contents);
    fakeWorkspaceFileValue.setSize(workspacePath.getFileSize());
    return RootedPath.toRootedPath(
        Root.fromPath(workspacePath.getParentDirectory()),
        PathFragment.create(workspacePath.getBaseName()));
  }

  private SkyFunction.Environment getEnv() throws InterruptedException {
    SkyFunction.Environment env = Mockito.mock(SkyFunction.Environment.class);
    Mockito.when(env.getValue(Matchers.argThat(new SkyKeyMatchers(FileValue.FILE))))
        .thenReturn(fakeWorkspaceFileValue);
    Mockito.when(env.getValue(Matchers.argThat(new SkyKeyMatchers(SkyFunctions.PRECOMPUTED))))
        .thenReturn(new PrecomputedValue(Optional.<RootedPath>absent()));
    return env;
  }

  private List<BuildFileAST> getASTs(String... lines)
      throws IOException, SkyFunctionException, InterruptedException {
    RootedPath workspacePath = createWorkspaceFile(lines);

    WorkspaceASTValue value =
        (WorkspaceASTValue) astSkyFunc.compute(WorkspaceASTValue.key(workspacePath), getEnv());
    return value.getASTs();
  }

  @Test
  public void testSplitASTNoLoad() throws IOException, SkyFunctionException, InterruptedException {
    List<BuildFileAST> asts = getASTs("foo_bar = 1");
    assertThat(asts).hasSize(1);
    assertThat(asts.get(0).getStatements()).hasSize(1);
  }

  @Test
  public void testSplitASTOneLoadAtTop()
      throws IOException, SkyFunctionException, InterruptedException {
    List<BuildFileAST> asts = getASTs("load('//:foo.bzl', 'bar')", "foo_bar = 1");
    assertThat(asts).hasSize(1);
    assertThat(asts.get(0).getStatements()).hasSize(2);
  }

  @Test
  public void testSplitASTOneLoad() throws IOException, SkyFunctionException, InterruptedException {
    List<BuildFileAST> asts = getASTs("foo_bar = 1", "load('//:foo.bzl', 'bar')");
    assertThat(asts).hasSize(2);
    assertThat(asts.get(0).getStatements()).hasSize(1);
    assertThat(asts.get(1).getStatements()).hasSize(1);
  }

  @Test
  public void testSplitASTTwoSuccessiveLoads()
      throws IOException, SkyFunctionException, InterruptedException {
    List<BuildFileAST> asts =
        getASTs("foo_bar = 1", "load('//:foo.bzl', 'bar')", "load('//:bar.bzl', 'foo')");
    assertThat(asts).hasSize(2);
    assertThat(asts.get(0).getStatements()).hasSize(1);
    assertThat(asts.get(1).getStatements()).hasSize(2);
  }

  @Test
  public void testSplitASTTwoSucessiveLoadsWithNonLoadStatement()
      throws IOException, SkyFunctionException, InterruptedException {
    List<BuildFileAST> asts =
        getASTs(
            "foo_bar = 1",
            "load('//:foo.bzl', 'bar')",
            "load('//:bar.bzl', 'foo')",
            "local_repository(name = 'foobar', path = '/bar/foo')");
    assertThat(asts).hasSize(2);
    assertThat(asts.get(0).getStatements()).hasSize(1);
    assertThat(asts.get(1).getStatements()).hasSize(3);
  }

  @Test
  public void testSplitASTThreeLoadsThreeSegments()
      throws IOException, SkyFunctionException, InterruptedException {
    List<BuildFileAST> asts =
        getASTs(
            "foo_bar = 1",
            "load('//:foo.bzl', 'bar')",
            "load('//:bar.bzl', 'foo')",
            "local_repository(name = 'foobar', path = '/bar/foo')",
            "load('@foobar//:baz.bzl', 'bleh')");
    assertThat(asts).hasSize(3);
    assertThat(asts.get(0).getStatements()).hasSize(1);
    assertThat(asts.get(1).getStatements()).hasSize(3);
    assertThat(asts.get(2).getStatements()).hasSize(1);
  }

  @Test
  public void testSplitASTThreeLoadsThreeSegmentsWithContent()
      throws IOException, SkyFunctionException, InterruptedException {
    List<BuildFileAST> asts =
        getASTs(
            "foo_bar = 1",
            "load('//:foo.bzl', 'bar')",
            "load('//:bar.bzl', 'foo')",
            "local_repository(name = 'foobar', path = '/bar/foo')",
            "load('@foobar//:baz.bzl', 'bleh')",
            "bleh()");
    assertThat(asts).hasSize(3);
    assertThat(asts.get(0).getStatements()).hasSize(1);
    assertThat(asts.get(1).getStatements()).hasSize(3);
    assertThat(asts.get(2).getStatements()).hasSize(2);
  }
}
