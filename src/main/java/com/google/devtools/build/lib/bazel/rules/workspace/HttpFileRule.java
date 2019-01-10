// Copyright 2015 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.bazel.rules.workspace;

import static com.google.devtools.build.lib.packages.Attribute.attr;
import static com.google.devtools.build.lib.syntax.Type.BOOLEAN;
import static com.google.devtools.build.lib.syntax.Type.STRING;
import static com.google.devtools.build.lib.syntax.Type.STRING_LIST;

import com.google.devtools.build.lib.analysis.RuleDefinition;
import com.google.devtools.build.lib.analysis.RuleDefinitionEnvironment;
import com.google.devtools.build.lib.packages.RuleClass;
import com.google.devtools.build.lib.packages.RuleClass.Builder.RuleClassType;
import com.google.devtools.build.lib.rules.repository.WorkspaceBaseRule;
import com.google.devtools.build.lib.rules.repository.WorkspaceConfiguredTargetFactory;

/**
 * Rule definition for the http_file rule.
 */
public class HttpFileRule implements RuleDefinition {

  public static final String NAME = "http_file";

  @Override
  public RuleClass build(RuleClass.Builder builder, RuleDefinitionEnvironment environment) {
    return builder
        /* <!-- #BLAZE_RULE(http_file).ATTRIBUTE(url) -->
        (Deprecated) A URL to a file that will be made available to Bazel.

        <p>This value has the same meaning as a <code>urls</code> list with a single item. This
        must not be specified if <code>urls</code> is also specified.</p>
        <!-- #END_BLAZE_RULE.ATTRIBUTE --> */
        .add(attr("url", STRING))
        /* <!-- #BLAZE_RULE(http_file).ATTRIBUTE(urls) -->
        List of mirror URLs referencing the same file that will be made available to Bazel.

        <p>This must be an http, https, or file URL. Authentication is not supported.</p>
        <!-- #END_BLAZE_RULE.ATTRIBUTE --> */
        .add(attr("urls", STRING_LIST))
        /* <!-- #BLAZE_RULE(http_file).ATTRIBUTE(sha256) -->
        The expected SHA-256 of the file downloaded.

        <p>This must match the SHA-256 of the file downloaded. <em>It is a security risk to
        omit the SHA-256 as remote files can change.</em> At best omitting this field will make
        your build non-hermetic. It is optional to make development easier but should be set
        before shipping.</p>
        <!-- #END_BLAZE_RULE.ATTRIBUTE --> */
        .add(attr("sha256", STRING))
        /* <!-- #BLAZE_RULE(http_file).ATTRIBUTE(executable) -->
        If the downloaded file should be made executable. Defaults to False.

        <!-- #END_BLAZE_RULE.ATTRIBUTE --> */
        .add(attr("executable", BOOLEAN))
        .setWorkspaceOnly()
        .build();
  }

  @Override
  public Metadata getMetadata() {
    return RuleDefinition.Metadata.builder()
        .name(HttpFileRule.NAME)
        .type(RuleClassType.WORKSPACE)
        .ancestors(WorkspaceBaseRule.class)
        .factoryClass(WorkspaceConfiguredTargetFactory.class)
        .build();
  }
}
/*<!-- #BLAZE_RULE (NAME = http_file, TYPE = OTHER, FAMILY = Workspace)[GENERIC_RULE] -->

<p><strong>Deprecated.
<code>load("@bazel_tools//tools/build_defs/repo:http.bzl", "http_file")</code>
for a drop-in replacement.</strong></p>

<p>Downloads a file from a URL and makes it available to be used as a file group.</p>

<h4 id="http_file_examples">Examples</h4>

<p>Suppose you need to have a debian package for your custom rules. This package is available from
<i>http://example.com/package.deb</i>. Then you can add to your WORKSPACE file:</p>

<pre class="code">
http_file(
   name = "my_deb",
   url = "http://example.com/package.deb",
   sha256 = "03a58ac630e59778f328af4bcc4acb4f80208ed4",
)
</pre>

<p>Targets would specify <code>@my_deb//file</code> as a dependency to depend on this file.</p>

<p>You may also reference files on the current system (localhost) by using "file:///path/to/file"
if you are on Unix-based systems. If you're on Windows, use "file:///c:/path/to/file". In both
examples, note the three slashes (<code>/</code>) -- the first two slashes belong to
<code>file://</code> and the third one belongs to the absolute path to the file.</p>

<!-- #END_BLAZE_RULE -->*/
