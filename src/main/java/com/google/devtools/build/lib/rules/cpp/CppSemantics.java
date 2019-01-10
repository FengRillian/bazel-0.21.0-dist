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

package com.google.devtools.build.lib.rules.cpp;

import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.analysis.RuleContext;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.rules.cpp.CppConfiguration.HeadersCheckingMode;

/** Pluggable C++ compilation semantics. */
public interface CppSemantics {
  /**
   * Called before a C++ compile action is built.
   *
   * <p>Gives the semantics implementation the opportunity to change compile actions at the last
   * minute.
   */
  void finalizeCompileActionBuilder(RuleContext ruleContext, CppCompileActionBuilder actionBuilder);

  /**
   * Called before {@link CcCompilationContext}s are finalized.
   *
   * <p>Gives the semantics implementation the opportunity to change what the C++ rule propagates to
   * dependent rules.
   */
  void setupCcCompilationContext(
      RuleContext ruleContext, CcCompilationContext.Builder ccCompilationContextBuilder);

  /**
   * Returns the set of includes which are not mandatory and may be pruned by include processing.
   */
  NestedSet<Artifact> getAdditionalPrunableIncludes();

  /**
   * Determines the applicable mode of headers checking for the passed in ruleContext.
   */
  HeadersCheckingMode determineHeadersCheckingMode(RuleContext ruleContext);

  /** Returns the include processing closure, which handles include processing for this build */
  IncludeProcessing getIncludeProcessing();

  /** Returns true iff this build should perform .d input pruning. */
  boolean needsDotdInputPruning();

  void validateAttributes(RuleContext ruleContext);

  /** Returns true iff this build requires include validation. */
  boolean needsIncludeValidation();
}
