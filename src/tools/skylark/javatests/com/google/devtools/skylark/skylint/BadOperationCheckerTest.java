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
import com.google.devtools.build.lib.syntax.BuildFileAST;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class BadOperationCheckerTest {
  private static List<Issue> findIssues(String... lines) {
    String content = String.join("\n", lines);
    BuildFileAST ast =
        BuildFileAST.parseString(
            event -> {
              throw new IllegalArgumentException(event.getMessage());
            },
            content);
    return BadOperationChecker.check(ast);
  }

  @Test
  public void dictionaryLiteralPlusOperator() {
    Truth.assertThat(findIssues("{} + foo").toString())
        .contains(
            "1:1-1:8: '+' operator is deprecated and should not be used on dictionaries"
                + " [deprecated-plus-dict]");
    Truth.assertThat(findIssues("foo + {}").toString())
        .contains(
            "1:1-1:8: '+' operator is deprecated and should not be used on dictionaries"
                + " [deprecated-plus-dict]");
    Truth.assertThat(findIssues("foo += {}").toString())
        .contains(
            "1:1-1:9: '+=' operator is deprecated and should not be used on dictionaries"
                + " [deprecated-plus-dict]");
  }

  @Test
  public void dictionaryComprehensionPlusOperator() {
    Truth.assertThat(findIssues("{k:v for k,v in []} + foo").toString())
        .contains(
            "1:1-1:25: '+' operator is deprecated and should not be used on dictionaries"
                + " [deprecated-plus-dict]");
    Truth.assertThat(findIssues("foo + {k:v for k,v in []}").toString())
        .contains(
            "1:1-1:25: '+' operator is deprecated and should not be used on dictionaries"
                + " [deprecated-plus-dict]");
    Truth.assertThat(findIssues("foo += {k:v for k,v in []}").toString())
        .contains(
            "1:1-1:26: '+=' operator is deprecated and should not be used on dictionaries"
                + " [deprecated-plus-dict]");
  }

  @Test
  public void dictionaryPlusOperatorNested() {
    Truth.assertThat(findIssues("foo + ({} + bar)").toString())
        .contains(
            "1:7-1:16: '+' operator is deprecated and should not be used on dictionaries"
                + " [deprecated-plus-dict]");
    Truth.assertThat(findIssues("foo + (bar + {})").toString())
        .contains(
            "1:7-1:16: '+' operator is deprecated and should not be used on dictionaries"
                + " [deprecated-plus-dict]");
  }

  @Test
  public void depsetPlusOperator() {
    Truth.assertThat(findIssues("foo + depset()").toString())
        .contains(
            "1:1-1:14: '+' operator is deprecated and should not be used on depsets "
            + "[deprecated-plus-depset]");

    Truth.assertThat(findIssues("foo = depset()", "foo + bar").toString())
        .contains(
            "2:1-2:9: '+' operator is deprecated");

    Truth.assertThat(findIssues("foo = depset()", "bar = foo", "bar + baz").toString())
        .contains(
            "3:1-3:9: '+' operator is deprecated");

    Truth.assertThat(findIssues("foo = depset()", "foo += bar").toString())
        .contains(
            "2:1-2:10: '+=' operator is deprecated");

    Truth.assertThat(findIssues("foo += depset()").toString())
        .contains(
            "1:1-1:15: '+=' operator is deprecated");
  }

  @Test
  public void dictPlusOperator() {
    Truth.assertThat(findIssues("foo + dict()").toString())
        .contains(
            "1:1-1:12: '+' operator is deprecated and should not be used on dictionaries "
            + "[deprecated-plus-dict]");

    Truth.assertThat(findIssues("foo = dict()", "foo + bar").toString())
        .contains(
            "2:1-2:9: '+' operator is deprecated");

    Truth.assertThat(findIssues("foo = dict()", "bar = foo", "bar + baz").toString())
        .contains(
            "3:1-3:9: '+' operator is deprecated");

    Truth.assertThat(findIssues("foo = dict()", "foo += bar").toString())
        .contains(
            "2:1-2:10: '+=' operator is deprecated");

    Truth.assertThat(findIssues("foo += dict()").toString())
        .contains(
            "1:1-1:13: '+=' operator is deprecated");

    Truth.assertThat(findIssues("foo += { 5:3 }").toString())
        .contains(
            "1:1-1:14: '+=' operator is deprecated");

    Truth.assertThat(findIssues("foo = { 5:3 }", "bar = foo", "bar + baz").toString())
        .contains(
            "3:1-3:9: '+' operator is deprecated");
  }

  @Test
  public void pipeOperator() {
    Truth.assertThat(findIssues("foo | bar").toString())
        .contains("1:1-1:9: '|' operator is deprecated");
  }

  @Test
  public void plusOperatorNoIssue() {
    Truth.assertThat(findIssues("foo + bar")).isEmpty();
    Truth.assertThat(findIssues("foo += bar")).isEmpty();
  }

  @Test
  public void divisionOperator() {
    Truth.assertThat(findIssues("5 / 2").toString())
        .contains("1:1-1:5: '/' operator is deprecated");
    Truth.assertThat(findIssues("5 // 2")).isEmpty();
  }

  @Test
  public void augmentedAssignmentOperator() {
    Truth.assertThat(findIssues("kwargs['name'] += 'foo'")).isEmpty();
  }
}
