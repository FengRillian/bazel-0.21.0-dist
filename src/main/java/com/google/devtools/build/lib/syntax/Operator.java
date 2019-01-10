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

package com.google.devtools.build.lib.syntax;

/** Infix binary operators. */
public enum Operator {

  AND("and"),
  DIVIDE("/"),
  EQUALS_EQUALS("=="),
  FLOOR_DIVIDE("//"),
  GREATER(">"),
  GREATER_EQUALS(">="),
  IN("in"),
  LESS("<"),
  LESS_EQUALS("<="),
  MINUS("-"),
  MULT("*"),
  NOT("not"),
  NOT_EQUALS("!="),
  NOT_IN("not in"),
  OR("or"),
  PERCENT("%"),
  PIPE("|"),
  PLUS("+");

  private final String name;

  Operator(String name) {
    this.name = name;
  }

  @Override
  public String toString() {
    return name;
  }
}
