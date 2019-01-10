---
layout: documentation
title: Understanding CROSSTOOL
---

# Understanding CROSSTOOL

* ToC
{:toc}


To get hands-on with `CROSSTOOL`, see the
[Configuring `CROSSTOOL`](tutorial/crosstool.html) tutorial.

## Overview

`CROSSTOOL` is a text file containing a
[protocol buffer](https://developers.google.com/protocol-buffers/docs/overview)
that provides the necessary level of granularity for configuring the behavior of
Bazel's C++ rules. By default, Bazel automatically configures `CROSSTOOL` for
your build, but you have the option to configure it manually. You reference the
`CROSSTOOL` file in your `BUILD` file(s) using a `cc_toolchain` target and check
it into source control alongside your project. You can share a single
`CROSSTOOL` file across multiple projects or create separate per-project files.

When a C++ target enters the analysis phase, Bazel selects the appropriate
`cc_toolchain` target based on the BUILD file, then reads the corresponding
toolchain definition from the `CROSSTOOL` file.  The `cc_toolchain` target
passes information from the `CROSSTOOL` proto to the C++ target through a
`CcToolchainProvider`.

For example, a compile or link action, instantiated by a rule such as
`cc_binary` or `cc_library`, needs the following information:

*   The compiler or linker to use
*   Command-line flags for the compiler/linker
*   Configuration flags passed through the `--copt/--linkopt` options
*   Environment variables
*   Artifacts needed in the sandbox in which the action executes

All of the above information except the artifacts required in the sandbox is
specified in the `CROSSTOOL` proto.

The artifacts to be shipped to the sandbox are declared in the `cc_toolchain`
target. For example, with the `cc_toolchain.linker_files` attribute you can
specify the linker binary and toolchain libraries to ship into the sandbox.

## `CROSSTOOL` proto structure

The `CROSSTOOL` proto has the following structure:

*   Map from `--cpu` to toolchain (to be used when `--compiler` is not specified
    or when `cc_toolchain_suite.toolchains` omits the `cpu` entry)

*   Toolchain for a particular `--cpu` and `--compiler` combination (1)
    *   Static toolchain:
        *   `compiler_flags`
        *   `linker_flags`
        *   `compilation_mode_flags`
        *   `linking_mode_flags`
    *   Dynamic toolchain:
        *   `features`

*   Toolchain for a particular  `--cpu` and `--compiler` combination (2)

*   Toolchain for a particular  `--cpu` and `--compiler` combination (3)

*   ...


## Toolchain selection

The toolchain selection logic operates as follows:

1.  User specifies a `cc_toolchain_suite` target in the `BUILD` file and points
    Bazel to the target using the
    [`--crosstool_top` option](https://docs.bazel.build/versions/master/user-manual.html#flag--crosstool_top).
    The `CROSSTOOL` file must reside in the same directory as the
    `BUILD` file containing the `cc_toolchain_suite` target.

2.  The `cc_toolchain_suite` target and the `CROSSTOOL`
    file reference multiple toolchains. The values of the `--cpu` and
    `--compiler `flags determine which of those toolchains is selected,
    either based only on the `--cpu` flag value, or based on a joint
    `--cpu | --compiler` value. The selection process is as follows:

  * If the `--compiler` option is specified, Bazel selects the
        corresponding entry from the `cc_toolchain_suite.toolchains`
        attribute with `--cpu | --compiler`. If Bazel does not find
        a corresponding entry, it throws an error.

  * If the `--compiler` option is not specified, Bazel selects
    the corresponding entry from the `cc_toolchain_suite.toolchains`
    attribute with just `--cpu`.

    However, if Bazel does not find a corresponding entry and the
    `--incompatible_disable_cc_toolchain_label_from_crosstool_proto`
    option is disabled, Bazel iterates through `default_toolchains`
    in the `CROSSTOOL` file until it finds an entry where the
    `default_toolchain.cpu` value matches the specified `--cpu`
    option value. Bazel then reads the `toolchain_identifier`
    value to identify the corresponding toolchain, and selects the appropriate
    entry in the `cc_toolchain_suite.toolchains` attribute using
    `toolchain.target_cpu | toolchain.compiler`.

  * If no flags are specified, Bazel inspects the host system and selects a
    `--cpu` value based on its findings. See the
    [inspection mechanism code](https://source.bazel.build/bazel/+/1b73bc37e184e71651eb631223dcce321ba16211:src/main/java/com/google/devtools/build/lib/analysis/config/AutoCpuConverter.java).

Once a toolchain has been selected, corresponding `feature` and `action_config`
messages in the `CROSSTOOL` file govern the configuration of the build (that is,
items described earlier in this document). These messages allow the
implementation of fully fledged C++ features in Bazel without modifying the
Bazel binary. C++ rules support multiple unique actions documented in detail
[in the Bazel source code](https://source.bazel.build/bazel/+/4f547a7ea86df80e4c76145ffdbb0c8b75ba3afa:tools/build_defs/cc/action_names.bzl).

## `CROSSTOOL` features

A feature is an entity that requires non-default command-line flags, actions,
constraints on the execution environment, or dependency alterations. A feature
can be something as simple as allowing BUILD files to select configurations of
flags, such as `treat_warnings_as_errors`, or interact with the C++ rules and
include new compile actions and inputs to the compilation, such as
`header_modules` or `thin_lto`.

Ideally, a toolchain definition consists of a set of features, where each
feature consists of multiple flag groups, each defining a list of flags that
apply to specific Bazel actions.

A feature is specified by name, which allows full decoupling of the `CROSSTOOL`
configuration from Bazel releases. In other words, a Bazel release does not
affect the behavior of `CROSSTOOL` configurations as long as those
configurations do not require the use of new features.

A feature is enabled only when both Bazel and the CROSSTOOL configuration
support it.

Features can have interdependencies, depend on command line flags, `BUILD` file
settings, and other variables.

### Feature relationships

Dependencies are typically managed directly with Bazel, which simply enforces
the requirements and manages conflicts intrinsic to the nature of the features
defined in the build. The toolchain specification allows for more granular
constraints for use directly within the `CROSSTOOL` file that govern feature
support and expansion. These are:

<table>
  <col width="300">
  <col width="600">
  <tr>
   <td><strong>Constraint</strong>
   </td>
   <td><strong>Description</strong>
   </td>
  </tr>
  <tr>
   <td><code>requires: ['feature1', 'feature2']</code>
   </td>
   <td>Feature-level. The feature is supported only if the specified required
       features are enabled. For example, when a feature is only supported in
       certain build modes (features <code>opt</code>, <code>dbg</code>, or
       <code>fastbuild</code>). Multiple `requires` statements are
       satisfied all at once if any one of them is satisfied.
   </td>
  </tr>
  <tr>
   <td><code>implies: 'feature'</code>
   </td>
   <td>Feature-level. This feature implies the specified feature. For example, a
       module compile implies the need for module maps, which can be implemented
       by a repeated <code>implies</code> string in the feature where each of
       the strings names a specific feature. Enabling a feature also implicitly
       enables all features implied by it (that is, it functions recursively).
     <p>
      Also provides the ability to factor common subsets of functionality out of
      a set of features, such as the common parts of sanitizers. Implied
      features cannot be disabled.
   </td>
  </tr>
  <tr>
   <td><code>provides: 'feature'</code>
   </td>
   <td>Feature-level. Indicates that this feature is one of several mutually
       exclusive alternate features. For example, all of the sanitizers could
       specify <code>provides: "sanitizer"</code>.
       <p>
       This improves error handling by listing the alternatives if the user asks
       for two or more mutually exclusive features at once.
   </td>
  </tr>
  <tr>
   <td><code>with_feature: {feature: 'feature1', not_feature: 'feature2']</code>
   </td>
   <td>Flag set-level. A feature can specify multiple flag sets with multiple
     <code>with_feature</code> statements. When <code>with_feature</code> is
     specified, the flag set will only expand to the build command if all of the
     feature in the specified <code>feature:</code> set are enabled, and all the
     features specified in <code>not_feature:</code> set are disabled.
   </td>
  </tr>
</table>

## `CROSSTOOL` actions

`CROSSTOOL` actions provide the flexibility to modify the circumstances under
which an action executes without assuming how the action will be run. An
`action_config` specifies the tool binary that an action invokes, while a
`feature` specifies the configuration (flags) that determine how that tool
behaves when the action is invoked.

[Features](#crosstool-features) reference `CROSSTOOL` actions to signal which
Bazel actions they affect since `CROSSTOOL` actions can modify the Bazel action
graph. The `CROSSTOOL` file includes actions that have flags and tools
associated with them, such as `c++-compile`. Flags are assigned to each action
by associating them with a feature.

Each `CROSSTOOL` action name represents a single type of action performed by
Bazel, such as compiling or linking. There is, however, a many-to-one
relationship between `CROSSTOOL` actions and Bazel action types, where a Bazel
action type refers to a Java class that implements an action (such as
`CppCompileAction`). In particular, the "assembler actions" and "compiler
actions" in the table below are `CppCompileAction`, while the link actions are
`CppLinkAction`.

### Assembler actions

<table>
  <col width="300">
  <col width="600">
  <tr>
   <td><strong>Action</strong>
   </td>
   <td><strong>Description</strong>
   </td>
  </tr>
  <tr>
   <td><code>preprocess-assemble</code>
   </td>
   <td>Assemble with preprocessing. Typically for <code>.S</code> files.
   </td>
  </tr>
  <tr>
   <td><code>assemble</code>
   </td>
   <td>Assemble without preprocessing. Typically for <code>.s</code> files.
   </td>
  </tr>
</table>

### Compiler actions

<table>
  <col width="300">
  <col width="600">
  <tr>
   <td><strong>Action</strong>
   </td>
   <td><strong>Description</strong>
   </td>
  </tr>
  <tr>
   <td><code>cc-flags-make-variable</code>
   </td>
   <td>Propagates <code>CC_FLAGS</code> to genrules.
   </td>
  </tr>
  <tr>
   <td><code>c-compile</code>
   </td>
   <td>Compile as C.
   </td>
  </tr>
  <tr>
   <td><code>c++-compile</code>
   </td>
   <td>Compile as C++.
   </td>
  </tr>
  <tr>
   <td><code>c++-header-parsing</code>
   </td>
   <td>Run the compiler's parser on a header file to ensure that the header is
     self-contained, as it will otherwise produce compilation errors. Applies
     only to toolchains that support modules.
   </td>
  </tr>
</table>

### Link actions

<table>
  <col width="300">
  <col width="600">
  <tr>
   <td><strong>Action</strong>
   </td>
   <td><strong>Description</strong>
   </td>
  </tr>
  <tr>
   <td><code>c++-link-dynamic-library</code>
   </td>
   <td>Link a shared library containing all of its dependencies.
   </td>
  </tr>
  <tr>
   <td><code>c++-link-nodeps-dynamic-library</code>
   </td>
   <td>Link a shared library only containing <code>cc_library</code> sources.
   </td>
  </tr>
  <tr>
   <td><code>c++-link-executable</code>
   </td>
   <td>Link a final ready-to-run library.
   </td>
  </tr>
</table>

### AR actions

AR actions assemble object files into archive libraries (`.a` files) via `ar`
and encode some semantics into the name.

<table>
  <col width="300">
  <col width="600">
  <tr>
   <td><strong>Action</strong>
   </td>
   <td><strong>Description</strong>
   </td>
  </tr>
  <tr>
   <td><code>c++-link-static-library</code>
   </td>
   <td>Create a static library (archive).
   </td>
  </tr>
</table>

### LTO actions

<table>
  <col width="300">
  <col width="600">
  <tr>
   <td><strong>Action</strong>
   </td>
   <td><strong>Description</strong>
   </td>
  </tr>
  <tr>
   <td><code>lto-backend</code>
   </td>
   <td>ThinLTO action compiling bitcodes into native objects.
   </td>
  </tr>
  <tr>
   <td><code>lto-index</code>
   </td>
   <td>ThinLTO action generating global index.
   </td>
  </tr>
</table>

## `CROSSTOOL` `action_config`

A `CROSSTOOL` `action_config` is a proto message that describes a Bazel action
by specifying the tool (binary) to invoke during the action and sets of flags,
defined by features, that apply constraints to the action's execution. A
`CROSSTOOL` action takes the following attributes:

<table>
  <col width="300">
  <col width="600">
  <tr>
   <td><strong>Attribute</strong>
   </td>
   <td><strong>Description</strong>
   </td>
  </tr>
  <tr>
   <td><code>action_name</code>
   </td>
    <td>The Bazel action to which this <code>CROSSTOOL</code> action corresponds.
        Bazel uses this attribute to discover per-action tool and execution
        requirements.
   </td>
  </tr>
  <tr>
   <td><code>tool</code>
   </td>
   <td>The executable to invoke. This can depend on a feature. A default must be
       provided.
   </td>
  </tr>
  <tr>
   <td><code>flag_set</code>
   </td>
   <td>A set of flags that applies to a group of actions. Same as for a feature.
   </td>
  </tr>
  <tr>
   <td><code>env_set</code>
   </td>
   <td>A set of environment constraints that applies to a group of actions. Same
       as for a feature.
   </td>
  </tr>
</table>

A `CROSSTOOL` `action_config` can require and imply other features and
<code>action_config</code>s as dictated by the
[feature relationships](#feature-relationships) described earlier. This behavior
is similar to that of a feature.

The last two attributes are redundant against the corresponding attributes on
features and are included because some Bazel actions require certain flags or
environment variables and we want to avoid unnecessary `action_config`+`feature`
pairs. Typically, sharing a single feature across multiple `action_config`s is
preferred.

You can not define more than one `CROSSTOOL` `action_config` with the same
`action_name` within the same toolchain. This prevents ambiguity in tool paths
and enforces the intention behind `action_config` - that an action's properties
are clearly described in a single place in the toolchain.

### `tool` messages

A `CROSSTOOL` `action_config` can specify a set of tools via `tool` messages.
A `tool` message consists of the following fields:


<table>
  <col width="300">
  <col width="600">
  <tr>
   <td><strong>Field</strong>
   </td>
   <td><strong>Description</strong>
   </td>
  </tr>
  <tr>
   <td><code>tool_path</code>
   </td>
   <td>Path to the tool in question (relative to the <code>CROSSTOOL</code>
       file).
   </td>
  </tr>
  <tr>
   <td><code>with_feature</code>
   </td>
   <td>A set of features that must be enabled for this tool to apply.
   </td>
  </tr>
</table>

For a given `CROSSTOOL` `action_config`, only a single `tool` message applies
its tool path and execution requirements to the Bazel action. A tool is selected
by sequentially parsing `tool` messages on an `action_config` until a tool with
a `with_feature` set matching the feature configuration is found
(see [Feature relationships](#feature-relationships) earlier in this document
for more information). We recommend that you end your tool lists with a default
tool that corresponds to an empty feature configuration.

### Example usage

Features and `CROSSTOOL` actions can be used together to implement Bazel actions
with diverse cross-platform semantics. For example, debug symbol generation on
macOS requires generating symbols in the compile action, then invoking a
specialized tool during the link action to create  compressed dsym archive, and
then decompressing that archive to produce the application bundle and `.plist`
files consumable by Xcode.

With Bazel, this process can instead be implemented as follows, with
`unbundle-debuginfo` being a Bazel action:

```
toolchain {
    action_config {
        config_name: "c++-link-executable"
        action_name: "c++-link-executable"
        tool {
          with_feature { feature: "generate-debug-symbols" }
          tool_path: "toolchain/mac/ld-with-dsym-packaging"
        }
        tool {
          tool_path: "toolchain/mac/ld"
        }
    }

    feature {
        name: "generate-debug-symbols"
        flag_set {
            action: "c-compile"
            action: "c++-compile"
            flag_group {
                flag: "-g"
            }
        }
        implies: { feature: "unbundle-debuginfo" }
    }
}
```

This same feature can be implemented entirely differently for Linux, which uses
`fission`, or for Windows, which produces `.pdb` files.  For example, the
implementation for `fission`-based debug symbol generation might look as
follows:

```
toolchain {
    action_config {
        name: "c++-compile"

    tool {
          tool_path: "toolchain/bin/gcc"
        }
    }

    feature {
        name: "generate-debug-symbols"
        requires { feature: "dbg" }
        flag_set {
          action: "c++-compile"
          flag_group {
              flag: "-gsplit-dwarf"
          }
        }
        flag_set {
          action: "c++-link-executable"
          flag_group {
              flag: "-Wl"
              flag: "--gdb-index"
          }
        }
      }
    }
}
```

### Flag groups

`CROSSTOOL` allows you to bundle flags into groups that serve a specific purpose.
You can specify a flag within the `CROSSTOOL` file using pre-defined variables
within the flag value, which the compiler expands when adding the flag to the
build command. For example:

```
  flag_group {
    flag: "%{output_file_path}
  }
```

In this case, the contents of the flag will be replaced by the output file path
of the action.

Flag groups are expanded to the build command in the order in which they appear
in the `CROSSTOOL` file, top-to-bottom, left-to-right.

For flags that need to repeat with different values when added to the build
command, the flag group can iterate variables of type `list`. For example, the
variable `include_path` of type `list`:

```
   flag_group {
    iterate_over: "include_paths"
    flag: "-I%{include_paths}"
  }
```

expands to `-I<path>` for each path element in the `include_paths` list. All
flags (or `flag_group`s) in the body of a flag group declaration are expanded as
a unit. For example:

```
   flag_group {
    iterate_over: "include_paths"
    flag: "-I"
    flag: "%{include_paths}"
  }
```

expands to `-I <path>` for each path element in the `include_paths` list.

A variable can repeat multiple times. For example:

```
   flag_group {
    iterate_over: "include_paths"
    flag: "-iprefix=%{include_paths}"
    flag: "-isystem=%{include_paths}"
  }
```

expands to:

```
  -iprefix=<inc0> -isystem=<inc0> -iprefix=<inc1> -isystem=<inc1>
```

Variables can correspond to structures accessible using dot-notation. For
example:

```
   flag_group {
    flag: "-l%{libraries_to_link.name}"
  }
```

Structures can be nested and may also contain sequences. To prevent name clashes
and to be explicit, you must specify the full path through the fields. For
example:

```
   flag_group {
    iterate_over: "libraries_to_link"
    flag_group {
      iterate_over: "libraries_to_link.shared_libraries"
      flag: "-l%{libraries_to_link.shared_libraries.name}"
    }
  }
```

### Conditional expansion

Flag groups support conditional expansion based on the presence of a particular
variable or its field using the `expand_if_all_available`, `expand_if_none_available`,
`expand_if_true`, `expand_if_false`, or `expand_if_equal` messages. For example:

```
   flag_group {
    iterate_over: "libraries_to_link"
    flag_group {
      iterate_over: "libraries_to_link.shared_libraries"
      flag_group {
        expand_if_all_available: "libraries_to_link.shared_libraries.is_whole_archive"
        flag: "--whole_archive"
      }
      flag_group {
        flag: "-l%{libraries_to_link.shared_libraries.name}"
      }
      flag_group {
        expand_if_all_available: "libraries_to_link.shared_libraries.is_whole_archive"
        flag: "--no_whole_archive"
      }
    }
  }
```

**Note:** The `--whole_archive` and `--no_whole_archive` options are added to
the build command only when a currently iterated library has an
`is_whole_archive` field.

## `CROSSTOOL` reference

This section provides a reference of build variables, features, and other
information required to successfully configure `CROSSTOOL`.

### `CROSSTOOL` build variables

The following is a reference of `CROSSTOOL` build variables.

**Note:** `[action]` indicates the relevant action type.

<table>
  <col width="300">
  <col width="600">
  <tr>
   <td><strong>Variable</strong>
   </td>
   <td><strong>Description</strong>
   </td>
  </tr>
  <tr>
   <td><strong><code>source_file</code></strong>
   </td>
   <td><code>[compile]</code> Source file to compile.
   </td>
  </tr>
  <tr>
   <td><strong><code>input_file</code></strong>
   </td>
   <td><code>[strip]</code> Artifact to strip.
   </td>
  </tr>
  <tr>
   <td><strong><code>output_file</code></strong>
   </td>
   <td><code>[compile]</code> Compilation output.
   </td>
  </tr>
  <tr>
   <td><strong><code>output_assembly_file</code></strong>
   </td>
   <td><code>[compile]</code> Emitted assembly file. Applies only when the
       <code>compile</code> action emits assembly text, typically when using the
       <code>--save_temps</code> flag. The contents are the same as for
       <code>output_file</code>.
   </td>
  </tr>
  <tr>
   <td><strong><code>output_preprocess_file</code></strong>
   </td>
   <td><code>[compile]</code> Preprocessed output. Applies only to compile
       actions that only preprocess the source files, typically when using the
     <code>--save_temps</code> flag. The contents are the same as for
     <code>output_file</code>.
   </td>
  </tr>
  <tr>
   <td><strong><code>includes</code></strong>
   </td>
   <td><code>[compile]</code> Sequence of files the compiler must
       unconditionally include in the compiled source.
   </td>
  </tr>
  <tr>
   <td><strong><code>include_paths</code></strong>
   </td>
   <td><code>[compile]</code> Sequence directories in which the compiler
       searches for headers included using <code>#include&lt;foo.h&gt;</code>
       and <code>#include "foo.h"</code>.
   </td>
  </tr>
  <tr>
   <td><strong><code>quote_include_paths</code></strong>
   </td>
   <td><code>[compile]</code> Sequence of <code>-iquote</code> includes -
       directories in which the compiler searches for headers included using
       <code>#include&lt;foo.h&gt;</code>.
   </td>
  </tr>
  <tr>
   <td><strong><code>system_include_paths</code></strong>
   </td>
   <td><code>[compile]</code> Sequence of <code>-isystem</code> includes -
       directories in which the compiler searches for headers included using
       <code>#include "foo.h"</code>.
   </td>
  </tr>
  <tr>
   <td><strong><code>dependency_file</code></strong>
   </td>
   <td><code>[compile]</code> The <code>.d</code> dependency file generated by
       the compiler.
   </td>
  </tr>
  <tr>
   <td><strong><code>preprocessor_defines</code></strong>
   </td>
   <td><code>[compile]</code> Sequence of <code>defines</code>, such as
       <code>--DDEBUG</code>.
   </td>
  </tr>
  <tr>
   <td><strong><code>pic</code></strong>
   </td>
   <td><code>[compile]</code> Compiles the output as position-independent code.
   </td>
  </tr>
  <tr>
   <td><strong><code>gcov_gcno_file</code></strong>
   </td>
   <td><code>[compile]</code> The <code>gcov</code> coverage file.
   </td>
  </tr>
  <tr>
   <td><strong><code>per_object_debug_info_file</code></strong>
   </td>
   <td><code>[compile]</code> The per-object debug info (<code>.dwp</code>)
       file.
   </td>
  </tr>
  <tr>
   <td><strong><code>stripotps</code></strong>
   </td>
   <td><code>[strip]</code> Sequence of <code>stripopts</code>.
   </td>
  </tr>
  <tr>
   <td><strong><code>legacy_compile_flags</code></strong>
   </td>
    <td><code>[compile]</code> Sequence of flags from legacy
        <code>CROSSTOOL</code> fields such as <code>compiler_flag</code>,
        <code>optional_compiler_flag</code>, <code>cxx_flag</code>, and
        <code>optional_cxx_flag</code>.
   </td>
  </tr>
  <tr>
   <td><strong><code>user_compile_flags</code></strong>
   </td>
   <td><code>[compile]</code> Sequence of flags from either the
       <code>copt</code> rule attribute or the <code>--copt</code>,
       <code>--cxxopt</code>, and <code>--conlyopt</code> flags.
   </td>
  </tr>
  <tr>
   <td><strong><code>unfiltered_compile_flags</code></strong>
   </td>
   <td><code>[compile]</code> Sequence of flags from the
     <code>unfiltered_cxx_flag</code> legacy <code>CROSSTOOL</code> field or the
       <code>unfiltered _compile_flags</code> feature. These are not filtered by
       the <code>nocopts</code> rule attribute.
   </td>
  </tr>
  <tr>
   <td><strong><code>sysroot</code></strong>
   </td>
   <td>The <code>sysroot</code>.
   </td>
  </tr>
  <tr>
   <td><strong><code>runtime_library_search_directories</code></strong>
   </td>
   <td><code>[link]</code> Entries in the linker runtime search path (usually
       set with the <code>-rpath</code> flag).
   </td>
  </tr>
  <tr>
   <td><strong><code>library_search_directories</code></strong>
   </td>
   <td><code>[link]</code> Entries in the linker search path (usually set with
       the <code>-L</code> flag).
   </td>
  </tr>
  <tr>
   <td><strong><code>libraries_to_link</code></strong>
   </td>
   <td><code>[link]</code> Flags providing files to link as inputs in the linker
       invocation.
   </td>
  </tr>
  <tr>
   <td><strong><code>def_file_path</code></strong>
   </td>
   <td><code>[link]</code> Location of def file used on Windows with MSVC.
   </td>
  </tr>
  <tr>
   <td><strong><code>linker_param_file</code></strong>
   </td>
   <td><code>[link]</code> Location of linker param file created by bazel to
       overcome command line length limit.
   </td>
  </tr>
  <tr>
   <td><strong><code>output_execpath</code></strong>
   </td>
   <td><code>[link]</code> execpath of the output of the linker.
   </td>
  </tr>
  <tr>
   <td><strong><code>generate_interface_library</code></strong>
   </td>
   <td><code>[link]</code> "yes"|"no" depending on whether interface library
       should be generated.
   </td>
  </tr>
  <tr>
   <td><strong><code>interface_library_builder_path</code></strong>
   </td>
   <td><code>[link]</code> Path to the interface library builder tool.
   </td>
  </tr>
  <tr>
   <td><strong><code>interface_library_input_path</code></strong>
   </td>
    <td><code>[link]</code> Input for the interface library <code>ifso</code>
        builder tool.
   </td>
  </tr>
  <tr>
   <td><strong><code>interface_library_output_path</code></strong>
   </td>
  <td><code>[link]</code> Path where to generate interface library using the
      <code>ifso</code> builder tool.
   </td>
  </tr>
  <tr>
   <td><strong><code>legacy_link_flags</code></strong>
   </td>
   <td><code>[link]</code> Linker flags coming from the legacy
       <code>CROSSTOOL</code>.
   </td>
  </tr>
  <tr>
   <td><strong><code>user_link_flags</code></strong>
   </td>
   <td><code>[link]</code> Linker flags coming from the <code>--linkopt</code>
       or <code>linkopts</code> attribute.
   </td>
  </tr>
  <tr>
   <td><strong><code>symbol_counts_output</code></strong>
   </td>
   <td><code>[link]</code> Path to which to write symbol counts.
   </td>
  </tr>
  <tr>
   <td><strong><code>linkstamp_paths</code></strong>
   </td>
   <td><code>[link]</code> A build variable giving linkstamp paths.
   </td>
  </tr>
  <tr>
   <td><strong><code>force_pic</code></strong>
   </td>
   <td><code>[link]</code> Presence of this variable indicates that PIC code
       should be generated.
   </td>
  </tr>
  <tr>
   <td><strong><code>strip_debug_symbols</code></strong>
   </td>
   <td><code>[link]</code> Presence of this variable indicates that the debug
       symbols should be stripped.
   </td>
  </tr>
  <tr>
   <td><strong><code>is_cc_test</code></strong>
   </td>
   <td><code>[link]</code> Truthy when current action is a <code>cc_test</code>
       linking action, false otherwise.
   </td>
  </tr>
  <tr>
   <td><strong><code>is_using_fission</code></strong>
   </td>
   <td><code>[link]</code> Presence of this variable indicates that files were
       compiled with fission. Debug info is in <code>.dwo</code> files instead
       of <code>.o</code> files and the linker needs to know this.
   </td>
  </tr>
</table>



### CROSSTOOL features

The following is a reference of `CROSSTOOL` features and their activation
conditions.

<table>
  <col width="300">
  <col width="600">
  <tr>
   <td><strong>Feature</strong>
   </td>
   <td><strong>Activation Condition</strong>
   </td>
  </tr>
  <tr>
   <td><strong><code>opt | dbg | fastbuild</code></strong>
   </td>
   <td>Enabled by default based on compilation mode.
   </td>
  </tr>
  <tr>
   <td><strong><code>static_linking_mode | dynamic_linking_mode</code></strong>
   </td>
   <td>Enabled by default based on linking mode.
   </td>
  </tr>
  <tr>
   <td><strong><code>random_seed</code></strong>
   </td>
   <td>Enabled by default.
   </td>
  </tr>
  <tr>
   <td><strong><code>dependency_file</code></strong>
   </td>
   <td>Enabled by default.
   </td>
  </tr>
  <tr>
   <td><strong><code>per_object_debug_info</code></strong>
   </td>
    <td>Enabled if the <code>supports_fission</code> attribute is set in the
        `CROSSTOOL` file and the current compilation mode is specified in the
        <code>--fission</code> flag.
   </td>
  </tr>
  <tr>
   <td><strong><code>pic</code></strong>
   </td>
   <td>Required if the target needs PIC objects for dynamic libraries. Enabled
       by default - the `pic` variable is present whenever PIC compilation is
       requested.
   </td>
  </tr>
</table>
