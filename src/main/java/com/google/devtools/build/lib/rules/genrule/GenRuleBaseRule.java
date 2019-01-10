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
package com.google.devtools.build.lib.rules.genrule;

import static com.google.devtools.build.lib.packages.Attribute.attr;
import static com.google.devtools.build.lib.packages.BuildType.LABEL_LIST;
import static com.google.devtools.build.lib.packages.BuildType.LICENSE;
import static com.google.devtools.build.lib.packages.BuildType.OUTPUT_LIST;
import static com.google.devtools.build.lib.syntax.Type.BOOLEAN;
import static com.google.devtools.build.lib.syntax.Type.STRING;

import com.google.devtools.build.lib.analysis.BaseRuleClasses;
import com.google.devtools.build.lib.analysis.RuleDefinition;
import com.google.devtools.build.lib.analysis.RuleDefinitionEnvironment;
import com.google.devtools.build.lib.analysis.config.HostTransition;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.packages.Attribute;
import com.google.devtools.build.lib.packages.Attribute.ComputedDefault;
import com.google.devtools.build.lib.packages.Attribute.LabelLateBoundDefault;
import com.google.devtools.build.lib.packages.AttributeMap;
import com.google.devtools.build.lib.packages.BuildType;
import com.google.devtools.build.lib.packages.RuleClass;
import com.google.devtools.build.lib.packages.RuleClass.Builder.RuleClassType;
import com.google.devtools.build.lib.packages.RuleClass.ExecutionPlatformConstraintsAllowed;
import com.google.devtools.build.lib.rules.cpp.CppConfiguration;
import com.google.devtools.build.lib.rules.cpp.CppRuleClasses;
import com.google.devtools.build.lib.rules.java.JavaConfiguration;
import com.google.devtools.build.lib.rules.java.JavaImplicitAttributes;
import com.google.devtools.build.lib.syntax.Type;
import com.google.devtools.build.lib.util.FileTypeSet;
import java.io.Serializable;

/**
 * Rule definition for the genrule rule, intended to be inherited by specific GenRule
 * implementations. Implementations will need to include any needed additional dependencies, such
 * as a setup script target.
 */
public class GenRuleBaseRule implements RuleDefinition {
  /**
   * Late-bound dependency on the C++ toolchain <i>iff</i> the genrule has make variables that need
   * that toolchain.
   */
  public static LabelLateBoundDefault<?> ccToolchainAttribute(RuleDefinitionEnvironment env) {
    return LabelLateBoundDefault.fromTargetConfiguration(
        CppConfiguration.class,
        env.getToolsLabel(CppRuleClasses.CROSSTOOL_LABEL),
        // null guards are needed for LateBoundAttributeTest
        (rule, attributes, cppConfig) ->
            attributes != null
                    && attributes.get("cmd", Type.STRING) != null
                    && GenRuleBase.requiresCrosstool(attributes.get("cmd", Type.STRING))
                ? CppRuleClasses.ccToolchainAttribute(env).resolve(rule, attributes, cppConfig)
                : null);
  }

  /** Computed dependency on the C++ toolchain type. */
  public static ComputedDefault ccToolchainTypeAttribute(RuleDefinitionEnvironment env) {
    return new ComputedDefault("cmd") {
      @Override
      public Object getDefault(AttributeMap rule) {
        return GenRuleBase.requiresCrosstool(rule.get("cmd", Type.STRING))
            ? CppRuleClasses.ccToolchainTypeAttribute(env)
            : null;
      }
    };
  }

  /**
   * Late-bound dependency on the host JDK <i>iff</i> the genrule has make variables that need the
   * host JDK.
   */
  public static LabelLateBoundDefault<?> maybeHostJdk(RuleDefinitionEnvironment env) {
    return LabelLateBoundDefault.fromHostConfiguration(
        JavaConfiguration.class,
        env.getToolsLabel(JavaImplicitAttributes.HOST_JDK_LABEL),
        (Attribute.LateBoundDefault.Resolver<JavaConfiguration, Label> & Serializable)
            // null guards are needed for LateBoundAttributeTest
            (rule, attributes, configuration) -> {
              if (attributes != null) {
                String cmd = attributes.get("cmd", Type.STRING);
                if (cmd != null && GenRuleBase.requiresJdk(cmd)) {
                  return configuration.getRuntimeLabel();
                }
              }
              return null;
            });
  }

  @Override
  public RuleClass build(
      RuleClass.Builder builder, RuleDefinitionEnvironment env) {
    return builder

        /* <!-- #BLAZE_RULE(genrule).ATTRIBUTE(srcs) -->
        A list of inputs for this rule, such as source files to process.
        <p>
          <em>This attributes is not suitable to list tools executed by the <code>cmd</code>; use
          the <a href="${link genrule.tools}"><code>tools</code></a> attribute for them instead.
          </em>
        </p>
        <p>
          The build system ensures these prerequisites are built before running the genrule
          command; they are built using the same configuration as the original build request. The
          names of the files of these prerequisites are available to the command as a
          space-separated list in <code>$(SRCS)</code>; alternatively the path of an individual
          <code>srcs</code> target <code>//x:y</code> can be obtained using <code>$(location
          //x:y)</code>, or using <code>$&lt;</code> provided it's the only entry in
          //<code>srcs</code>.
        </p>
        <!-- #END_BLAZE_RULE.ATTRIBUTE --> */
        .add(
            attr("srcs", LABEL_LIST)
                .direct_compile_time_input()
                .allowedFileTypes(FileTypeSet.ANY_FILE))

        /* <!-- #BLAZE_RULE(genrule).ATTRIBUTE(tools) -->
        A list of <i>tool</i> dependencies for this rule. See the definition of
        <a href="../build-ref.html#deps">dependencies</a> for more information. <br/>
        <p>
          The build system ensures these prerequisites are built before running the genrule command;
          they are built using the <a href='../user-manual.html#configurations'><i>host</i>
          configuration</a>, since these tools are executed as part of the build. The path of an
          individual <code>tools</code> target <code>//x:y</code> can be obtained using
          <code>$(location //x:y)</code>.
        </p>
        <p>
          Any <code>*_binary</code> or tool to be executed by <code>cmd</code> must appear in this
          list, not in <code>srcs</code>, to ensure they are built in the correct configuration.
        </p>
        <!-- #END_BLAZE_RULE.ATTRIBUTE --> */
        .add(attr("tools", LABEL_LIST)
            .cfg(HostTransition.INSTANCE)
            .allowedFileTypes(FileTypeSet.ANY_FILE))
        /* <!-- #BLAZE_RULE(genrule).ATTRIBUTE(outs) -->
        A list of files generated by this rule.
        <p>
          Output files must not cross package boundaries.
          Output filenames are interpreted as relative to the package.
        </p>
        <p>
          If the <code>executable</code> flag is set, <code>outs</code> must contain exactly one
          label.
        </p>
        <!-- #END_BLAZE_RULE.ATTRIBUTE --> */
        .add(attr("outs", OUTPUT_LIST).mandatory())

        /* <!-- #BLAZE_RULE(genrule).ATTRIBUTE(cmd) -->
        The command to run.
        Subject to <a href="${link make-variables#location}">$(location)</a> and
        <a href="${link make-variables}">"Make" variable</a> substitution.
        <ol>
          <li>
            First <a href="${link make-variables#location}">$(location)</a> substitution is
            applied, replacing all occurrences of <code>$(location <i>label</i>)</code> and of
            <code>$(locations <i>label</i>)</code>.
          </li>
          <li>
            <p>
              Note that <code>outs</code> are <i>not</i> included in this substitution. Output files
              are always generated into a predictable location (available via <code>$(@D)</code>,
              <code>$@</code>, <code>$(OUTS)</code> or <code>$(location <i>output_name</i>)</code>;
              see below).
            </p>
          </li>
          <li>
            Next, <a href="${link make-variables}">"Make" variables</a> are expanded. Note that
            predefined variables <code>$(JAVA)</code>, <code>$(JAVAC)</code> and
            <code>$(JAVABASE)</code> expand under the <i>host</i> configuration, so Java invocations
            that run as part of a build step can correctly load shared libraries and other
            dependencies.
          </li>
          <li>
            Finally, the resulting command is executed using the Bash shell. If its exit code is
            non-zero the command is considered to have failed.
          </li>
        </ol>
        <p>
          The command may refer to <code>*_binary</code> targets; it should use a <a
          href="../build-ref.html#labels">label</a> for this. The following
          variables are available within the <code>cmd</code> sublanguage:</p>
        <ul>
          <li>
            <a href="${link make-variables#predefined_variables.genrule.cmd}">"Make" variables</a>
          </li>
          <li>
            "Make" variables that are predefined by the build tools.
            Please use these variables instead of hardcoded values.
            See <a href="${link make-variables#predefined_variables}">Predefined "Make" Variables
            </a> in this document for a list of supported values.
          </li>
        </ul>
        <!-- #END_BLAZE_RULE.ATTRIBUTE --> */
        .add(attr("cmd", STRING).mandatory())

        /* <!-- #BLAZE_RULE(genrule).ATTRIBUTE(output_to_bindir) -->
        <p>
          If set to 1, this option causes output files to be written into the <code>bin</code>
          directory instead of the <code>genfiles</code> directory.
        </p>
        <!-- #END_BLAZE_RULE.ATTRIBUTE --> */
        // TODO(bazel-team): find a location to document genfiles/binfiles, link to them from here.
        .add(
            attr("output_to_bindir", BOOLEAN)
                .value(false)
                .nonconfigurable(
                    "policy decision: no reason for this to depend on the configuration"))

        /* <!-- #BLAZE_RULE(genrule).ATTRIBUTE(local) -->
        <p>
          If set to 1, this option force this <code>genrule</code> to run with the
          <code>standalone</code> strategy, without sandboxing.
        </p>
        <p>
          This is equivalent to providing 'local' as a tag (<code>tags=["local"]</code>). The
          local strategy is applied if either one is specified.
        </p>
        <p>
          The <code>--genrule_strategy</code> option value <code>local</code>
          overrides this attribute.
        <!-- #END_BLAZE_RULE.ATTRIBUTE --> */
        .add(attr("local", BOOLEAN).value(false))

        /* <!-- #BLAZE_RULE(genrule).ATTRIBUTE(message) -->
        A progress message.
        <p>
          A progress message that will be printed as this build step is executed. By default, the
          message is "Generating <i>output</i>" (or something equally bland) but you may provide a
          more specific one. Use this attribute instead of <code>echo</code> or other print
          statements in your <code>cmd</code> command, as this allows the build tool to control
          whether such progress messages are printed or not.
        </p>
        <!-- #END_BLAZE_RULE.ATTRIBUTE --> */
        .add(attr("message", STRING))
        /*<!-- #BLAZE_RULE(genrule).ATTRIBUTE(output_licenses) -->
        See <a href="${link common-definitions#binary.output_licenses}"><code>common attributes
        </code></a>
        <!-- #END_BLAZE_RULE.ATTRIBUTE -->*/
        .add(attr("output_licenses", LICENSE))

        /* <!-- #BLAZE_RULE(genrule).ATTRIBUTE(executable) -->
        Declare output to be executable.
        <p>
          Setting this flag to 1 means the output is an executable file and can be run using the
          <code>run</code> command. The genrule must produce exactly one output in this case.
          If this attribute is set, <code>run</code> will try executing the file regardless of
          its content.
        </p>
        <p>Declaring data dependencies for the generated executable is not supported.</p>
        <!-- #END_BLAZE_RULE.ATTRIBUTE --> */
        .add(
            attr("executable", BOOLEAN)
                .value(false)
                .nonconfigurable(
                    "Used in computed default for $is_executable, which is itself non-configurable"
                        + " (and thus expects its dependencies to be non-configurable), because"
                        + " $is_executable  is called from RunCommand.isExecutable, which has no"
                        + " configuration context"))
        .add(
            attr("$is_executable", BOOLEAN)
                .nonconfigurable("Called from RunCommand.isExecutable, which takes a Target")
                .value(
                    new Attribute.ComputedDefault() {
                      @Override
                      public Object getDefault(AttributeMap rule) {
                        return (rule.get("outs", BuildType.OUTPUT_LIST).size() == 1)
                            && rule.get("executable", BOOLEAN);
                      }
                    }))

        // This is a misfeature, so don't document it. We would like to get rid of it, but that
        // would require a cleanup of existing rules.
        .add(attr("heuristic_label_expansion", BOOLEAN).value(false))
        .removeAttribute("data")
        .removeAttribute("deps")
        .executionPlatformConstraintsAllowed(ExecutionPlatformConstraintsAllowed.PER_TARGET)
        .build();
  }

  @Override
  public RuleDefinition.Metadata getMetadata() {
    return RuleDefinition.Metadata.builder()
        .name("$genrule_base")
        .type(RuleClassType.ABSTRACT)
        .ancestors(BaseRuleClasses.RuleBase.class, BaseRuleClasses.MakeVariableExpandingRule.class)
        .build();
  }
}
