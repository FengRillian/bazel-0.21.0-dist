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

import com.google.common.base.Equivalence;
import com.google.common.base.Equivalence.Wrapper;
import com.google.common.base.Preconditions;
import com.google.devtools.build.lib.syntax.BuildFileAST;
import com.google.devtools.build.lib.syntax.Expression;
import com.google.devtools.build.lib.syntax.ExpressionStatement;
import com.google.devtools.build.lib.syntax.FlowStatement;
import com.google.devtools.build.lib.syntax.ForStatement;
import com.google.devtools.build.lib.syntax.FuncallExpression;
import com.google.devtools.build.lib.syntax.FunctionDefStatement;
import com.google.devtools.build.lib.syntax.Identifier;
import com.google.devtools.build.lib.syntax.IfStatement;
import com.google.devtools.build.lib.syntax.IfStatement.ConditionalStatements;
import com.google.devtools.build.lib.syntax.ReturnStatement;
import com.google.devtools.build.lib.syntax.Statement;
import com.google.devtools.build.lib.syntax.SyntaxTreeVisitor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.stream.Stream;
import javax.annotation.Nullable;

/**
 * Performs lints related to control flow.
 *
 * <p>For now, it only checks that if a function returns a value in some execution paths, it does so
 * in all execution paths.
 */
// TODO(skylark-team): Check for unreachable statements
public class ControlFlowChecker extends SyntaxTreeVisitor {
  private static final String MISSING_RETURN_VALUE_CATEGORY = "missing-return-value";
  private static final String UNREACHABLE_STATEMENT_CATEGORY = "unreachable-statement";
  private static final String NESTED_FUNCTION_CATEGORY = "nested-function";

  private final List<Issue> issues = new ArrayList<>();

  /**
   * Represents the analyzed info at the current program point. The {@code visit()} methods
   * implement the transfer function from the program point immediately before that AST node to the
   * program point immediately after that node. This destructively consumes (modifies) the CFI
   * object, so for branching nodes a copy must be made.
   *
   * <p>See also: https://en.wikipedia.org/wiki/Data-flow_analysis
   *
   * <p>This is always null whenever we're not in a function definition.
   */
  @Nullable
  private ControlFlowInfo cfi = null;

  public static List<Issue> check(BuildFileAST ast) {
    ControlFlowChecker checker = new ControlFlowChecker();
    checker.visit(ast);
    return checker.issues;
  }

  @Override
  public void visitBlock(List<Statement> statements) {
    if (cfi == null) {
      super.visitBlock(statements);
      return;
    }
    boolean alreadyReported = false;
    for (Statement stmt : statements) {
      if (!cfi.reachable && !alreadyReported) {
        issues.add(
            Issue.create(
                UNREACHABLE_STATEMENT_CATEGORY, "unreachable statement", stmt.getLocation()));
        alreadyReported = true;
      }
      visit(stmt);
    }
  }

  @Override
  public void visit(IfStatement node) {
    if (cfi == null) {
      return;
    }
    // Save the input cfi, copy its state to seed each branch, then gather the branches together
    // with a join operation to produces the output cfi.
    ControlFlowInfo input = cfi;
    ArrayList<ControlFlowInfo> outputs = new ArrayList<>();
    Stream<List<Statement>> branches =
        Stream.concat(
            node.getThenBlocks().stream().map(ConditionalStatements::getStatements),
            Stream.of(node.getElseBlock()));
    for (List<Statement> branch : (Iterable<List<Statement>>) branches::iterator) {
      cfi = ControlFlowInfo.copy(input);
      visitAll(branch);
      outputs.add(cfi);
    }
    cfi = ControlFlowInfo.join(outputs);
  }

  @Override
  public void visit(ForStatement node) {
    ControlFlowInfo noIteration = ControlFlowInfo.copy(cfi);
    super.visit(node);
    cfi = ControlFlowInfo.join(Arrays.asList(noIteration, cfi));
  }

  @Override
  public void visit(FlowStatement node) {
    Preconditions.checkNotNull(cfi);
    cfi.reachable = false;
  }

  @Override
  public void visit(ReturnStatement node) {
    // Should be rejected by parser, but we may have been fed a bad AST.
    Preconditions.checkState(cfi != null, "AST has illegal top-level return statement");
    cfi.reachable = false;
    cfi.returnsAlwaysExplicitly = true;
    if (node.getReturnExpression() != null) {
      cfi.hasReturnWithValue = true;
    } else {
      cfi.hasReturnWithoutValue = true;
      cfi.returnStatementsWithoutValue.add(wrapReturn(node));
    }
  }

  @Override
  public void visit(ExpressionStatement node) {
    if (cfi == null) {
      return;
    }
    if (isFail(node.getExpression())) {
      cfi.reachable = false;
      cfi.returnsAlwaysExplicitly = true;
    }
  }

  public static boolean isFail(Expression expression) {
    if (expression instanceof FuncallExpression) {
      Expression function = ((FuncallExpression) expression).getFunction();
      return function instanceof Identifier && ((Identifier) function).getName().equals("fail");
    }
    return false;
  }

  @Override
  public void visit(FunctionDefStatement node) {
    if (cfi != null) {
      issues.add(
          Issue.create(
              NESTED_FUNCTION_CATEGORY,
              node.getIdentifier()
                  + " is a nested function which is not allowed."
                  + " Consider inlining it or moving it to top-level."
                  + " For more details, have a look at the Skylark documentation.",
              node.getLocation()));
      return;
    }
    cfi = ControlFlowInfo.entry();
    super.visit(node);
    if (cfi.hasReturnWithValue && (!cfi.returnsAlwaysExplicitly || cfi.hasReturnWithoutValue)) {
      issues.add(
          Issue.create(
              MISSING_RETURN_VALUE_CATEGORY,
              "some but not all execution paths of '"
                  + node.getIdentifier()
                  + "' return a value."
                  + " If it is intentional, make it explicit using 'return None'."
                  + " If you know these cannot happen,"
                  + " add the statement `fail('unreachable')` to them."
                  + " For more details, have a look at the documentation.",
              node.getLocation()));
      for (Wrapper<ReturnStatement> returnWrapper : cfi.returnStatementsWithoutValue) {
        issues.add(
            Issue.create(
                MISSING_RETURN_VALUE_CATEGORY,
                "return value missing (you can `return None` if this is desired)",
                unwrapReturn(returnWrapper).getLocation()));
      }
    }
    cfi = null;
  }

  private Wrapper<ReturnStatement> wrapReturn(ReturnStatement node) {
    return Equivalence.identity().wrap(node);
  }

  private ReturnStatement unwrapReturn(Wrapper<ReturnStatement> wrapper) {
    return wrapper.get();
  }

  private static class ControlFlowInfo {
    private boolean reachable;
    private boolean hasReturnWithValue;
    private boolean hasReturnWithoutValue;
    private boolean returnsAlwaysExplicitly;
    private final LinkedHashSet<Wrapper<ReturnStatement>> returnStatementsWithoutValue;

    private ControlFlowInfo(
        boolean reachable,
        boolean hasReturnWithValue,
        boolean hasReturnWithoutValue,
        boolean returnsAlwaysExplicitly,
        LinkedHashSet<Wrapper<ReturnStatement>> returnStatementsWithoutValue) {
      this.reachable = reachable;
      this.hasReturnWithValue = hasReturnWithValue;
      this.hasReturnWithoutValue = hasReturnWithoutValue;
      this.returnsAlwaysExplicitly = returnsAlwaysExplicitly;
      this.returnStatementsWithoutValue = returnStatementsWithoutValue;
    }

    /** Create a CFI corresponding to an entry point in the control-flow graph. */
    static ControlFlowInfo entry() {
      return new ControlFlowInfo(true, false, false, false, new LinkedHashSet<>());
    }

    /** Creates a copy of a CFI, including the {@code returnStatementsWithoutValue} collection. */
    static ControlFlowInfo copy(ControlFlowInfo existing) {
      return new ControlFlowInfo(
          existing.reachable,
          existing.hasReturnWithValue,
          existing.hasReturnWithoutValue,
          existing.returnsAlwaysExplicitly,
          new LinkedHashSet<>(existing.returnStatementsWithoutValue));
    }

    /** Joins the CFIs for several alternative paths together. */
    static ControlFlowInfo join(List<ControlFlowInfo> infos) {
      ControlFlowInfo result =
          new ControlFlowInfo(false, false, false, true, new LinkedHashSet<>());
      for (ControlFlowInfo info : infos) {
        result.reachable |= info.reachable;
        result.hasReturnWithValue |= info.hasReturnWithValue;
        result.hasReturnWithoutValue |= info.hasReturnWithoutValue;
        result.returnsAlwaysExplicitly &= info.returnsAlwaysExplicitly;
        result.returnStatementsWithoutValue.addAll(info.returnStatementsWithoutValue);
      }
      return result;
    }
  }
}
