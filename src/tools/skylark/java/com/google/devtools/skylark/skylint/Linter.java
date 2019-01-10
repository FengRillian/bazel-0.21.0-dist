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

import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.events.EventKind;
import com.google.devtools.build.lib.syntax.BuildFileAST;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Main class of the linter library.
 *
 * <p>Most users of the linter library should only need to use this class.
 */
public class Linter {
  private static final String PARSE_ERROR_CATEGORY = "parse-error";
  /** Map of all single-file checks and their names. */
  private static final ImmutableMap<String, Check> nameToCheck =
      ImmutableMap.<String, Check>builder()
          .put("bad-operation", BadOperationChecker::check)
          .put("bad-recursive-glob", NativeRecursiveGlobChecker::check)
          .put("control-flow", ControlFlowChecker::check)
          .put("deprecated-api", DeprecatedApiChecker::check)
          .put("docstring", DocstringChecker::check)
          .put("load", LoadStatementChecker::check)
          .put("naming", NamingConventionsChecker::check)
          .put("no-effect", StatementWithoutEffectChecker::check)
          .put("usage", UsageChecker::check)
          .build();
  /** Map of all multi-file checks and their names. */
  private static final ImmutableMap<String, MultiFileCheck> nameToMultiFileCheck =
      ImmutableMap.<String, MultiFileCheck>builder()
          .put("deprecation", DeprecationChecker::check)
          .build();

  /** Function to read files (can be changed for testing). */
  private FileFacade fileFacade = DEFAULT_FILE_FACADE;

  private static final FileFacade DEFAULT_FILE_FACADE =
      new FileFacade() {
        @Override
        public boolean fileExists(Path path) {
          return Files.exists(path);
        }

        @Override
        public byte[] readBytes(Path path) throws IOException {
          return Files.readAllBytes(path);
        }
      };

  private boolean singleFileMode = false;
  private final Set<String> disabledChecks = new LinkedHashSet<>();
  private final Set<String> disabledCategories = new LinkedHashSet<>();

  public Linter setFileContentsReader(FileFacade reader) {
    this.fileFacade = reader;
    return this;
  }

  public Linter disableCheck(String checkName) {
    if (!nameToCheck.containsKey(checkName)) {
      throw new IllegalArgumentException("Unknown check '" + checkName + "' cannot be disabled.");
    }
    disabledChecks.add(checkName);
    return this;
  }

  public Linter disableCategory(String categoryName) {
    disabledCategories.add(categoryName);
    return this;
  }

  /** Disables checks that require analyzing multiple files. */
  public Linter setSingleFileMode() {
    singleFileMode = true;
    return this;
  }

  /**
   * Runs all checks on the given file.
   *
   * @param path path of the file
   * @return list of issues found in that file
   */
  public List<Issue> lint(Path path) throws IOException {
    path = path.toAbsolutePath();
    String content = new String(fileFacade.readBytes(path), StandardCharsets.ISO_8859_1);
    List<Issue> issues = new ArrayList<>();
    BuildFileAST ast =
        BuildFileAST.parseString(
            event -> {
              if (event.getKind() == EventKind.ERROR || event.getKind() == EventKind.WARNING) {
                issues.add(
                    Issue.create(PARSE_ERROR_CATEGORY, event.getMessage(), event.getLocation()));
              }
            },
            content);
    for (Map.Entry<String, Check> entry : nameToCheck.entrySet()) {
      if (disabledChecks.contains(entry.getKey())) {
        continue;
      }
      issues.addAll(entry.getValue().check(ast));
    }
    if (!singleFileMode) {
      for (Map.Entry<String, MultiFileCheck> entry : nameToMultiFileCheck.entrySet()) {
        if (disabledChecks.contains(entry.getKey())) {
          continue;
        }
        issues.addAll(entry.getValue().check(path, ast, fileFacade));
      }
    }
    issues.removeIf(issue -> disabledCategories.contains(issue.category));
    issues.sort(Issue::compareLocation);
    return issues;
  }

  /**
   * Interface with a function that reads a file.
   *
   * <p>This is useful because we can use a fake for testing.
   */
  @FunctionalInterface
  public interface FileFacade {

    /**
     * Reads a file path to bytes.
     *
     * <p>This operation may be repeated for the same file.
     */
    byte[] readBytes(Path path) throws IOException;

    /**
     * Reads a file and parses it to an AST.
     *
     * <p>The default implementation silently ignores syntax errors.
     */
    default BuildFileAST readAst(Path path) throws IOException {
      String contents = new String(readBytes(path), StandardCharsets.ISO_8859_1);
      return BuildFileAST.parseString(event -> {}, contents);
    }

    /**
     * Checks whether a given file exists.
     *
     * <p>The default implementation invokes readBytes and returns false if {@link
     * NoSuchFileException} is thrown, true otherwise.
     */
    default boolean fileExists(Path path) {
      try {
        readBytes(path);
      } catch (NoSuchFileException e) {
        return false;
      } catch (IOException e) {
        // This method shouldn't throw.
      }
      return true;
    }
  }

  /** Allows to invoke a check. */
  @FunctionalInterface
  public interface Check {
    List<Issue> check(BuildFileAST ast);
  }

  /** Allows to invoke a check. */
  @FunctionalInterface
  public interface MultiFileCheck {
    List<Issue> check(Path path, BuildFileAST ast, FileFacade fileFacade);
  }
}
