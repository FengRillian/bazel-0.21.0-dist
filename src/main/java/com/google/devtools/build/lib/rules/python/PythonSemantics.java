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
package com.google.devtools.build.lib.rules.python;

import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.analysis.RuleContext;
import com.google.devtools.build.lib.analysis.Runfiles;
import com.google.devtools.build.lib.analysis.RunfilesSupport;
import com.google.devtools.build.lib.analysis.TransitiveInfoCollection;
import com.google.devtools.build.lib.analysis.test.InstrumentedFilesCollector.InstrumentationSpec;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.packages.RuleClass.ConfiguredTargetFactory.RuleErrorException;
import com.google.devtools.build.lib.rules.cpp.CcInfo;
import java.util.Collection;
import java.util.List;

/**
 * Pluggable semantics for Python rules.
 *
 * <p>A new instance of this class is created for each configured target, therefore, it is allowed
 * to keep state.
 */
public interface PythonSemantics {
  /**
   * Called at the beginning of the analysis of {@code py_binary} rules to validate its attributes.
   */
  void validate(RuleContext ruleContext, PyCommon common);

  /** Extends for the default and data runfiles of {@code py_binary} rules with custom elements. */
  void collectRunfilesForBinary(
      RuleContext ruleContext, Runfiles.Builder builder, PyCommon common, CcInfo ccInfo)
      throws InterruptedException;

  /** Extends the default runfiles of {@code py_binary} rules with custom elements. */
  void collectDefaultRunfilesForBinary(RuleContext ruleContext, Runfiles.Builder builder)
      throws InterruptedException;

  /** Collects a rule's default runfiles. */
  void collectDefaultRunfiles(RuleContext ruleContext, Runfiles.Builder builder);

  /**
   * Returns the coverage instrumentation specification to be used in Python rules.
   */
  InstrumentationSpec getCoverageInstrumentationSpec();

  /**
   * Utility function to compile multiple .py files to .pyc files, if required.
   */
  Collection<Artifact> precompiledPythonFiles(
      RuleContext ruleContext, Collection<Artifact> sources, PyCommon common);

  /** Returns a list of PathFragments for the import paths specified in the imports attribute. */
  List<String> getImports(RuleContext ruleContext);

  /**
   * Create the actual executable artifact.
   *
   * <p>This should create a generating action for {@code common.getExecutable()}.
   */
  Artifact createExecutable(
      RuleContext ruleContext, PyCommon common, CcInfo ccInfo, NestedSet<String> imports)
      throws InterruptedException, RuleErrorException;

  /**
   * Called at the end of the analysis of {@code py_binary} rules.
   * @throws InterruptedException
   */
  void postInitBinary(RuleContext ruleContext, RunfilesSupport runfilesSupport,
      PyCommon common) throws InterruptedException;

  CcInfo buildCcInfoProvider(Iterable<? extends TransitiveInfoCollection> deps);
}
