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

package com.google.devtools.skylark.skylint;

import com.google.common.truth.Truth;
import com.google.devtools.build.lib.events.EventHandler;
import com.google.devtools.build.lib.syntax.BuildFileAST;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class ControlFlowCheckerTest {
  private static List<Issue> findIssues(EventHandler eventHandler, String... lines) {
    String content = String.join("\n", lines);
    BuildFileAST ast = BuildFileAST.parseString(eventHandler, content);
    return ControlFlowChecker.check(ast);
  }

  private static List<Issue> findIssues(String... lines) {
    return findIssues(
        event -> {
          throw new IllegalArgumentException(event.getMessage());
        },
        lines);
  }

  @Test
  public void testAnalyzerToleratesTopLevelFail() throws Exception {
    Truth.assertThat(
            findIssues("fail(\"fail is considered a return, but not at the top level\")"))
        .isEmpty();
  }

  @Test
  public void testIfElseReturnMissing() throws Exception {
    Truth.assertThat(
            findIssues(
                    "def some_function(x):",
                    "  if x:",
                    "    print('foo')",
                    "  else:",
                    "    return x")
                .toString())
        .contains(
            "1:1-5:12: some but not all execution paths of 'some_function' return a value."
                + " If it is intentional, make it explicit using 'return None'."
                + " If you know these cannot happen,"
                + " add the statement `fail('unreachable')` to them."
                + " For more details, have a look at the documentation. [missing-return-value]");
  }

  @Test
  public void testNestedFunction() {
    Truth.assertThat(
            findIssues(event -> {}, "def foo():", "  def bar():", "    pass", "  return")
                .toString())
        .contains(
            "2:3-3:8: bar is a nested function which is not allowed."
                + " Consider inlining it or moving it to top-level."
                + " For more details, have a look at the Skylark documentation."
                + " [nested-function]");
  }

  @Test
  public void testIfElseReturnValueMissing() throws Exception {
    String messages =
        findIssues(
                "def some_function(x):",
                "  if x:",
                "    return x",
                "  else:",
                "    return # missing value")
            .toString();
    Truth.assertThat(messages)
        .contains(
            "1:1-5:10: some but not all execution paths of 'some_function' return a value."
                + " If it is intentional, make it explicit using 'return None'."
                + " If you know these cannot happen,"
                + " add the statement `fail('unreachable')` to them."
                + " For more details, have a look at the documentation. [missing-return-value]");
    Truth.assertThat(messages)
        .contains(
            "5:5-5:10: return value missing (you can `return None` if this is desired)"
                + " [missing-return-value]");
  }

  @Test
  public void testIfElifElseReturnMissing() throws Exception {
    Truth.assertThat(
            findIssues(
                    "def f(x):",
                    "  if x:",
                    "    return x",
                    "  elif not x:",
                    "    pass",
                    "  else:",
                    "    return not x")
                .toString())
        .contains(
            "1:1-7:16: some but not all execution paths of 'f' return a value."
                + " If it is intentional, make it explicit using 'return None'."
                + " If you know these cannot happen,"
                + " add the statement `fail('unreachable')` to them."
                + " For more details, have a look at the documentation. [missing-return-value]");
  }

  @Test
  public void testNestedIfElseReturnMissing() throws Exception {
    Truth.assertThat(
            findIssues(
                    "def f(x, y):",
                    "  if x:",
                    "    if y:",
                    "      return y",
                    "    else:",
                    "      print('foo')",
                    "  else:",
                    "    return x")
                .toString())
        .contains(
            "1:1-8:12: some but not all execution paths of 'f' return a value."
                + " If it is intentional, make it explicit using 'return None'."
                + " If you know these cannot happen,"
                + " add the statement `fail('unreachable')` to them."
                + " For more details, have a look at the documentation. [missing-return-value]");
  }

  @Test
  public void testElseBranchMissing() throws Exception {
    Truth.assertThat(
            findIssues(
                    "def some_function(x):",
                    "  if x:",
                    "    return x",
                    "  elif not x:",
                    "    return not x")
                .toString())
        .containsMatch(
            "1:1-5:16: some but not all execution paths of 'some_function' return a value."
                + " .+ \\[missing-return-value\\]");
  }

  @Test
  public void testIfAndFallOffEnd() throws Exception {
    Truth.assertThat(
            findIssues(
                    "def some_function(x):",
                    "  if x:",
                    "    return x",
                    "  print('foo')",
                    "  # return missing here")
                .toString())
        .containsMatch(
            "1:1-4:14: some but not all execution paths of 'some_function' return a value."
                + " .+ \\[missing-return-value\\]");
  }

  @Test
  public void testForAndFallOffEnd() throws Exception {
    Truth.assertThat(
            findIssues(
                    "def some_function():",
                    "  for x in []:",
                    "    return x",
                    "  print('foo')",
                    "  # return missing here")
                .toString())
        .containsMatch(
            "1:1-4:14: some but not all execution paths of 'some_function' return a value."
                + " .+ \\[missing-return-value\\]");
  }

  @Test
  public void testAlwaysReturnButSometimesWithoutValue() throws Exception {
    String messages =
        findIssues(
                "def some_function(x):",
                "  if x:",
                "    return # returns without value here",
                "  return x")
            .toString();
    Truth.assertThat(messages)
        .containsMatch(
            "1:1-4:10: some but not all execution paths of 'some_function' return a value."
                + " .+ \\[missing-return-value\\]");
    Truth.assertThat(messages)
        .contains(
            "3:5-3:10: return value missing (you can `return None` if this is desired)"
                + " [missing-return-value]");
  }

  @Test
  public void testUnreachableAfterIf() throws Exception {
    String messages =
        findIssues(
                "def some_function(x):",
                "  if x:",
                "    return",
                "  else:",
                "    fail('fail')",
                "  print('This line is unreachable')")
            .toString();
    Truth.assertThat(messages).contains("6:3-6:35: unreachable statement [unreachable-statement]");
  }

  @Test
  public void testNoUnreachableDuplicates() throws Exception {
    List<Issue> messages =
        findIssues(
            "def some_function():",
            "  return",
            "  print('unreachable1')",
            "  print('unreachable2')");
    Truth.assertThat(messages).hasSize(1);
  }

  @Test
  public void testUnreachableAfterBreakContinue() throws Exception {
    String messages =
        findIssues(
                "def some_function(x):",
                "  for y in x:",
                "    if y:",
                "      break",
                "    else:",
                "      continue",
                "    print('unreachable')")
            .toString();
    Truth.assertThat(messages).contains("7:5-7:24: unreachable statement [unreachable-statement]");
  }

  @Test
  public void testReachableStatements() throws Exception {
    Truth.assertThat(
            findIssues(
                "def some_function(x):",
                "  if x:",
                "    return",
                "  for y in []:",
                "    if y:",
                "      continue",
                "    else:",
                "      fail('fail')",
                "  return"))
        .isEmpty();
  }

  @Test
  public void testIfBeforeReturn() throws Exception {
    Truth.assertThat(
            findIssues(
                "def f(x, y):",
                "  if x:",
                "    return x",
                "  elif not y:",
                "    print('foo')",
                "  print('bar')",
                "  return y"))
        .isEmpty();
  }

  @Test
  public void testReturnInAllBranches() throws Exception {
    Truth.assertThat(
            findIssues(
                "def f(x, y):",
                "  if x:",
                "    return x",
                "  elif not y:",
                "    return None",
                "  else:",
                "    return y"))
        .isEmpty();
  }

  @Test
  public void testReturnInNestedIf() throws Exception {
    Truth.assertThat(
            findIssues(
                "def f(x,y):",
                "  if x:",
                "    if y:",
                "      return y",
                "    else:",
                "      return not y",
                "  else:",
                "    return not x"))
        .isEmpty();
  }

  @Test
  public void testIfStatementSequence() throws Exception {
    Truth.assertThat(
            findIssues(
                "def f(x,y):",
                "  if x:",
                "    print('foo')",
                "  else:",
                "    return x",
                "  print('bar')",
                "  if y:",
                "    return x",
                "  else:",
                "    return y"))
        .isEmpty();
    List<Issue> issues =
        findIssues(
            "def f(x,y):",
            "  if x:",
            "    return x",
            "  else:",
            "    return x",
            "  # from now on everything's unreachable",
            "  print('bar')",
            "  if y:",
            "    return x",
            "  # no else branch but doesn't matter since it's unreachable");
    Truth.assertThat(issues).hasSize(1);
    Truth.assertThat(issues.toString())
        .contains("7:3-7:14: unreachable statement [unreachable-statement]");
  }

  @Test
  public void testCallToFail() throws Exception {
    Truth.assertThat(
            findIssues(
                "def some_function_name(x):",
                "  if x:",
                "    fail('bar')",
                "  else:",
                "    return x"))
        .isEmpty();
  }
}
