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

package com.google.devtools.build.lib.skylarkbuildapi;

import com.google.devtools.build.lib.skylarkinterface.SkylarkCallable;
import com.google.devtools.build.lib.skylarkinterface.SkylarkModule;
import com.google.devtools.build.lib.skylarkinterface.SkylarkModuleCategory;
import com.google.devtools.build.lib.skylarkinterface.SkylarkValue;
import com.google.devtools.build.lib.syntax.EvalException;
import com.google.devtools.build.lib.syntax.SkylarkDict;
import com.google.devtools.build.lib.syntax.SkylarkList;
import com.google.devtools.build.lib.syntax.SkylarkNestedSet;
import java.io.IOException;

/** Interface for actions in Skylark. */
@SkylarkModule(
    name = "Action",
    category = SkylarkModuleCategory.BUILTIN,
    doc =
        "An action created during rule analysis."
            + "<p>This object is visible for the purpose of testing, and may be obtained from an "
            + "<a href=\"globals.html#Actions\">Actions</a> provider. It is normally not necessary "
            + "to access <code>Action</code> objects or their fields within a rule's "
            + "implementation function. You may instead want to see the "
            + "<a href='../rules.$DOC_EXT#actions'>Rules page</a> for a general discussion of how "
            + "to use actions when defining custom rules, or the <a href='actions.html'>API "
            + "reference</a> for creating actions."
            + "<p>Some fields of this object are only applicable for certain kinds of actions. "
            + "Fields that are inapplicable are set to <code>None</code>.")
public interface ActionApi extends SkylarkValue {

  @SkylarkCallable(
    name = "mnemonic",
    structField = true,
    doc = "The mnemonic for this action."
  )
  public abstract String getMnemonic();

  @SkylarkCallable(
    name = "inputs",
    doc = "A set of the input files of this action.",
    structField = true)
  public SkylarkNestedSet getSkylarkInputs();

  @SkylarkCallable(
    name = "outputs",
    doc = "A set of the output files of this action.",
    structField = true)
  public SkylarkNestedSet getSkylarkOutputs();

  @SkylarkCallable(
    name = "argv",
    doc =
        "For actions created by <a href=\"actions.html#run\">ctx.actions.run()</a> "
            + "or <a href=\"actions.html#run_shell\">ctx.actions.run_shell()</a>  an immutable "
            + "list of the arguments for the command line to be executed. Note that "
            + "for shell actions the first two arguments will be the shell path "
            + "and <code>\"-c\"</code>.",
    structField = true,
    allowReturnNones = true
  )
  public SkylarkList<String> getSkylarkArgv() throws EvalException;

  @SkylarkCallable(
    name = "content",
    doc =
        "For actions created by <a href=\"actions.html#write\">ctx.actions.write()</a> or "
            + "<a href=\"actions.html#expand_template\">ctx.actions.expand_template()</a>,"
            + " the contents of the file to be written.",
    structField = true,
    allowReturnNones = true)
  public String getSkylarkContent() throws IOException;

  @SkylarkCallable(
    name = "substitutions",
    doc =
        "For actions created by "
            + "<a href=\"actions.html#expand_template\">ctx.actions.expand_template()</a>,"
            + " an immutable dict holding the substitution mapping.",
    structField = true,
    allowReturnNones = true)
  public SkylarkDict<String, String> getSkylarkSubstitutions();

  @SkylarkCallable(
    name = "env",
    structField = true,
    doc =
        "The 'fixed' environment variables for this action. This includes only environment "
            + "settings which are explicitly set by the action definition, and thus omits settings "
            + "which are only pre-set in the execution environment.")
  public SkylarkDict<String, String> getEnv();
}
