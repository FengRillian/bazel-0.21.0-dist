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

import com.google.devtools.build.lib.events.Location;
import com.google.devtools.build.lib.skylarkinterface.Param;
import com.google.devtools.build.lib.skylarkinterface.ParamType;
import com.google.devtools.build.lib.skylarkinterface.SkylarkCallable;
import com.google.devtools.build.lib.skylarkinterface.SkylarkModule;
import com.google.devtools.build.lib.skylarkinterface.SkylarkModuleCategory;
import com.google.devtools.build.lib.skylarkinterface.SkylarkValue;
import com.google.devtools.build.lib.syntax.Environment;
import com.google.devtools.build.lib.syntax.EvalException;
import com.google.devtools.build.lib.syntax.SkylarkDict;
import com.google.devtools.build.lib.syntax.SkylarkList;
import com.google.devtools.build.lib.syntax.SkylarkNestedSet;

/** Module providing functions to create actions. */
@SkylarkModule(
    name = "actions",
    category = SkylarkModuleCategory.BUILTIN,
    doc =
        "Module providing functions to create actions. "
            + "Access this module using <a href=\"ctx.html#actions\"><code>ctx.actions</code></a>.")
public interface SkylarkActionFactoryApi extends SkylarkValue {

  @SkylarkCallable(
      name = "declare_file",
      doc =
          "Declares that the rule or aspect creates a file with the given filename. "
              + "If <code>sibling</code> is not specified, the file name is relative to the "
              + "package "
              + "directory, otherwise the file is in the same directory as <code>sibling</code>. "
              + "Files cannot be created outside of the current package."
              + "<p>Remember that in addition to declaring a file, you must separately create an "
              + "action that emits the file. Creating that action will require passing the "
              + "returned <code>File</code> object to the action's construction function."
              + "<p>Note that <a href='../rules.$DOC_EXT#files'>predeclared output files</a> do "
              + "not "
              + "need to be (and cannot be) declared using this function. You can obtain their "
              + "<code>File</code> objects from "
              + "<a href=\"ctx.html#outputs\"><code>ctx.outputs</code></a> instead. "
              + "<a href=\"https://github.com/bazelbuild/examples/tree/master/rules/"
              + "computed_dependencies/hash.bzl\">See example of use</a>.",
      parameters = {
        @Param(
            name = "filename",
            type = String.class,
            doc =
                "If no 'sibling' provided, path of the new file, relative "
                    + "to the current package. Otherwise a base name for a file "
                    + "('sibling' determines a directory)."),
        @Param(
            name = "sibling",
            doc =
                "A file that lives in the same directory as the newly created file. "
                    + "The file must be in the current package.",
            type = FileApi.class,
            noneable = true,
            positional = false,
            named = true,
            defaultValue = "None")
      })
  public FileApi declareFile(String filename, Object sibling) throws EvalException;

  @SkylarkCallable(
      name = "declare_directory",
      doc =
          "Declares that rule or aspect create a directory with the given name, in the "
              + "current package. You must create an action that generates the directory.",
      parameters = {
        @Param(
            name = "filename",
            type = String.class,
            doc =
                "If no 'sibling' provided, path of the new directory, relative "
                    + "to the current package. Otherwise a base name for a file "
                    + "('sibling' defines a directory)."),
        @Param(
            name = "sibling",
            doc = "A file that lives in the same directory as the newly declared directory.",
            type = FileApi.class,
            noneable = true,
            positional = false,
            named = true,
            defaultValue = "None")
      })
  public FileApi declareDirectory(String filename, Object sibling) throws EvalException;

  @SkylarkCallable(
      name = "do_nothing",
      doc =
          "Creates an empty action that neither executes a command nor produces any "
              + "output, but that is useful for inserting 'extra actions'.",
      parameters = {
        @Param(
            name = "mnemonic",
            type = String.class,
            named = true,
            positional = false,
            doc = "A one-word description of the action, for example, CppCompile or GoLink."),
        @Param(
            name = "inputs",
            allowedTypes = {
              @ParamType(type = SkylarkList.class),
              @ParamType(type = SkylarkNestedSet.class),
            },
            generic1 = FileApi.class,
            named = true,
            positional = false,
            defaultValue = "[]",
            doc = "List of the input files of the action."),
      },
      useLocation = true)
  public void doNothing(String mnemonic, Object inputs, Location location) throws EvalException;

  @SkylarkCallable(
      name = "write",
      doc =
          "Creates a file write action. When the action is executed, it will write the given "
              + "content to a file. This is used to generate files using information available in "
              + "the analysis phase. If the file is large and with a lot of static content, "
              + "consider using <a href=\"#expand_template\"><code>expand_template</code></a>.",
      parameters = {
        @Param(name = "output", type = FileApi.class, doc = "The output file.", named = true),
        @Param(
            name = "content",
            type = Object.class,
            allowedTypes = {
              @ParamType(type = String.class),
              @ParamType(type = CommandLineArgsApi.class)
            },
            doc =
                "the contents of the file. "
                    + "May be a either a string or an "
                    + "<a href=\"actions.html#args\"><code>actions.args()</code></a> object.",
            named = true),
        @Param(
            name = "is_executable",
            type = Boolean.class,
            defaultValue = "False",
            doc = "Whether the output file should be executable.",
            named = true)
      },
      useLocation = true)
  public void write(FileApi output, Object content, Boolean isExecutable, Location location)
      throws EvalException;

  @SkylarkCallable(
      name = "run",
      doc =
          "Creates an action that runs an executable. "
              + "<a href=\"https://github.com/bazelbuild/examples/tree/master/rules/"
              + "actions_run/execute.bzl\">See example of use</a>.",
      parameters = {
        @Param(
            name = "outputs",
            type = SkylarkList.class,
            generic1 = FileApi.class,
            named = true,
            positional = false,
            doc = "List of the output files of the action."),
        @Param(
            name = "inputs",
            allowedTypes = {
              @ParamType(type = SkylarkList.class),
              @ParamType(type = SkylarkNestedSet.class),
            },
            generic1 = FileApi.class,
            defaultValue = "[]",
            named = true,
            positional = false,
            doc = "List or depset of the input files of the action."),
        @Param(
            name = "executable",
            type = Object.class,
            allowedTypes = {
              @ParamType(type = FileApi.class),
              @ParamType(type = String.class),
            },
            named = true,
            positional = false,
            doc = "The executable file to be called by the action."),
        @Param(
            name = "tools",
            allowedTypes = {
              @ParamType(type = SkylarkList.class),
              @ParamType(type = SkylarkNestedSet.class),
            },
            generic1 = FileApi.class,
            defaultValue = "unbound",
            named = true,
            positional = false,
            doc =
                "List or depset of any tools needed by the action. Tools are inputs with "
                    + "additional "
                    + "runfiles that are automatically made available to the action."),
        @Param(
            name = "arguments",
            type = Object.class,
            allowedTypes = {
              @ParamType(type = SkylarkList.class),
            },
            defaultValue = "[]",
            named = true,
            positional = false,
            doc =
                "Command line arguments of the action. "
                    + "Must be a list of strings or "
                    + "<a href=\"actions.html#args\"><code>actions.args()</code></a> objects."),
        @Param(
            name = "mnemonic",
            type = String.class,
            noneable = true,
            defaultValue = "None",
            named = true,
            positional = false,
            doc = "A one-word description of the action, for example, CppCompile or GoLink."),
        @Param(
            name = "progress_message",
            type = String.class,
            noneable = true,
            defaultValue = "None",
            named = true,
            positional = false,
            doc =
                "Progress message to show to the user during the build, "
                    + "for example, \"Compiling foo.cc to create foo.o\"."),
        @Param(
            name = "use_default_shell_env",
            type = Boolean.class,
            defaultValue = "False",
            named = true,
            positional = false,
            doc = "Whether the action should use the built in shell environment or not."),
        @Param(
            name = "env",
            type = SkylarkDict.class,
            noneable = true,
            defaultValue = "None",
            named = true,
            positional = false,
            doc = "Sets the dictionary of environment variables."),
        @Param(
            name = "execution_requirements",
            type = SkylarkDict.class,
            noneable = true,
            defaultValue = "None",
            named = true,
            positional = false,
            doc =
                "Information for scheduling the action. See "
                    + "<a href=\"$BE_ROOT/common-definitions.html#common.tags\">tags</a> "
                    + "for useful keys."),
        @Param(
            // TODO(bazel-team): The name here isn't accurate anymore.
            // This is technically experimental, so folks shouldn't be too attached,
            // but consider renaming to be more accurate/opaque.
            name = "input_manifests",
            type = SkylarkList.class,
            noneable = true,
            defaultValue = "None",
            named = true,
            positional = false,
            doc =
                "(Experimental) sets the input runfiles metadata; "
                    + "they are typically generated by resolve_command.")
      },
      useLocation = true)
  public void run(
      SkylarkList outputs,
      Object inputs,
      Object executableUnchecked,
      Object toolsUnchecked,
      Object arguments,
      Object mnemonicUnchecked,
      Object progressMessage,
      Boolean useDefaultShellEnv,
      Object envUnchecked,
      Object executionRequirementsUnchecked,
      Object inputManifestsUnchecked,
      Location location)
      throws EvalException;

  @SkylarkCallable(
      name = "run_shell",
      doc =
          "Creates an action that runs a shell command. "
              + "<a href=\"https://github.com/bazelbuild/examples/tree/master/rules/"
              + "shell_command/size.bzl\">See example of use</a>.",
      parameters = {
        @Param(
            name = "outputs",
            type = SkylarkList.class,
            generic1 = FileApi.class,
            named = true,
            positional = false,
            doc = "List of the output files of the action."),
        @Param(
            name = "inputs",
            allowedTypes = {
              @ParamType(type = SkylarkList.class),
              @ParamType(type = SkylarkNestedSet.class),
            },
            generic1 = FileApi.class,
            defaultValue = "[]",
            named = true,
            positional = false,
            doc = "List or depset of the input files of the action."),
        @Param(
            name = "tools",
            allowedTypes = {
              @ParamType(type = SkylarkList.class),
              @ParamType(type = SkylarkNestedSet.class),
            },
            generic1 = FileApi.class,
            defaultValue = "unbound",
            named = true,
            positional = false,
            doc =
                "List or depset of any tools needed by the action. Tools are inputs with "
                    + "additional "
                    + "runfiles that are automatically made available to the action."),
        @Param(
            name = "arguments",
            allowedTypes = {
              @ParamType(type = SkylarkList.class),
            },
            defaultValue = "[]",
            named = true,
            positional = false,
            doc =
                "Command line arguments of the action. Must be a list of strings or "
                    + "<a href=\"actions.html#args\"><code>actions.args()</code></a> objects."
                    + ""
                    + "<p>Bazel passes the elements in this attribute as arguments to the command."
                    + "The command can access these arguments using shell variable substitutions "
                    + "such as <code>$1</code>, <code>$2</code>, etc. Note that since Args "
                    + "objects are flattened before indexing, if there is an Args object of "
                    + "unknown size then all subsequent strings will be at unpredictable indices. "
                    + "It may be useful to use <code>$@</code> (to retrieve all arguments) in "
                    + "conjunction with Args objects of indeterminate size."
                    + ""
                    + "<p>In the case where <code>command</code> is a list of strings, this "
                    + "parameter may not be used."),
        @Param(
            name = "mnemonic",
            type = String.class,
            noneable = true,
            defaultValue = "None",
            named = true,
            positional = false,
            doc = "A one-word description of the action, for example, CppCompile or GoLink."),
        @Param(
            name = "command",
            type = Object.class,
            allowedTypes = {
              @ParamType(type = String.class),
              @ParamType(type = SkylarkList.class, generic1 = String.class),
            },
            named = true,
            positional = false,
            doc =
                "Shell command to execute. This may either be a string (preferred) or a sequence "
                    + "of strings <b>(deprecated)</b>."
                    + ""
                    + "<p>If <code>command</code> is a string, then it is executed as if by "
                    + "<code>sh -c &lt;command&gt; \"\" &lt;arguments&gt;</code> -- that is, the "
                    + "elements in <code>arguments</code> are made available to the command as "
                    + "<code>$1</code>, <code>$2</code>, etc. If <code>arguments</code> contains "
                    + "any <a href=\"actions.html#args\"><code>actions.args()</code></a> objects, "
                    + "their contents are appended one by one to the command line, so "
                    + "<code>$</code><i>i</i> can refer to individual strings within an Args "
                    + "object. Note that if an Args object of unknown size is passed as part of "
                    + "<code>arguments</code>, then the strings will be at unknown indices; in "
                    + "this case the <code>$@</code> shell substitution (retrieve all arguments) "
                    + "may be useful."
                    + ""
                    + "<p><b>(Deprecated)</b> If <code>command</code> is a sequence of strings, "
                    + "the first item is the executable to run and the remaining items are its "
                    + "arguments. If this form is used, the <code>arguments</code> parameter must "
                    + "not be supplied."
                    + ""
                    + "<p>Bazel uses the same shell to execute the command as it does for "
                    + "genrules."),
        @Param(
            name = "progress_message",
            type = String.class,
            noneable = true,
            defaultValue = "None",
            named = true,
            positional = false,
            doc =
                "Progress message to show to the user during the build, "
                    + "for example, \"Compiling foo.cc to create foo.o\"."),
        @Param(
            name = "use_default_shell_env",
            type = Boolean.class,
            defaultValue = "False",
            named = true,
            positional = false,
            doc = "Whether the action should use the built in shell environment or not."),
        @Param(
            name = "env",
            type = SkylarkDict.class,
            noneable = true,
            defaultValue = "None",
            named = true,
            positional = false,
            doc = "Sets the dictionary of environment variables."),
        @Param(
            name = "execution_requirements",
            type = SkylarkDict.class,
            noneable = true,
            defaultValue = "None",
            named = true,
            positional = false,
            doc =
                "Information for scheduling the action. See "
                    + "<a href=\"$BE_ROOT/common-definitions.html#common.tags\">tags</a> "
                    + "for useful keys."),
        @Param(
            // TODO(bazel-team): The name here isn't accurate anymore.
            // This is technically experimental, so folks shouldn't be too attached,
            // but consider renaming to be more accurate/opaque.
            name = "input_manifests",
            type = SkylarkList.class,
            noneable = true,
            defaultValue = "None",
            named = true,
            positional = false,
            doc =
                "(Experimental) sets the input runfiles metadata; "
                    + "they are typically generated by resolve_command.")
      },
      useLocation = true)
  public void runShell(
      SkylarkList outputs,
      Object inputs,
      Object toolsUnchecked,
      Object arguments,
      Object mnemonicUnchecked,
      Object commandUnchecked,
      Object progressMessage,
      Boolean useDefaultShellEnv,
      Object envUnchecked,
      Object executionRequirementsUnchecked,
      Object inputManifestsUnchecked,
      Location location)
      throws EvalException;

  @SkylarkCallable(
      name = "expand_template",
      doc =
          "Creates a template expansion action. When the action is executed, it will "
              + "generate a file based on a template. Parts of the template will be replaced "
              + "using the <code>substitutions</code> dictionary, in the order the substitutions "
              + "are specified. Whenever a key of the dictionary appears in the template (or a "
              + "result of a previous substitution), it is replaced with the associated value. "
              + "There is no special syntax for the keys. You may, for example, use curly braces "
              + "to avoid conflicts (for example, <code>{KEY}</code>). "
              + "<a href=\"https://github.com/bazelbuild/examples/blob/master/rules/"
              + "expand_template/hello.bzl\">"
              + "See example of use</a>.",
      parameters = {
        @Param(
            name = "template",
            type = FileApi.class,
            named = true,
            positional = false,
            doc = "The template file, which is a UTF-8 encoded text file."),
        @Param(
            name = "output",
            type = FileApi.class,
            named = true,
            positional = false,
            doc = "The output file, which is a UTF-8 encoded text file."),
        @Param(
            name = "substitutions",
            type = SkylarkDict.class,
            named = true,
            positional = false,
            doc = "Substitutions to make when expanding the template."),
        @Param(
            name = "is_executable",
            type = Boolean.class,
            defaultValue = "False",
            named = true,
            positional = false,
            doc = "Whether the output file should be executable.")
      },
      useLocation = true)
  public void expandTemplate(
      FileApi template,
      FileApi output,
      SkylarkDict<?, ?> substitutionsUnchecked,
      Boolean executable,
      Location location)
      throws EvalException;

  @SkylarkCallable(
    name = "args",
    doc = "Returns an Args object that can be used to build memory-efficient command lines.",
    useEnvironment = true
  )
  public CommandLineArgsApi args(Environment env);
}
