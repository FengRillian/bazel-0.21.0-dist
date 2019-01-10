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

import com.google.devtools.build.lib.analysis.RuleDefinition;
import com.google.devtools.build.lib.analysis.RuleDefinitionEnvironment;
import com.google.devtools.build.lib.packages.RuleClass;
import com.google.devtools.build.lib.rules.repository.WorkspaceBaseRule;
import com.google.devtools.build.lib.rules.repository.WorkspaceConfiguredTargetFactory;

/**
 * Rule definition for the git_repository rule.
 */
public class GitRepositoryRule implements RuleDefinition {
  public static final String NAME = "git_repository";

  @Override
  public RuleClass build(RuleClass.Builder builder, RuleDefinitionEnvironment environment) {
    return builder
        /* <!-- #BLAZE_RULE(git_repository).ATTRIBUTE(remote) -->
        The URI of the remote Git repository.

        <p>This must be a HTTP URL. There is currently no support for authentication.</p>
        <!-- #END_BLAZE_RULE.ATTRIBUTE --> */
        .add(attr("remote", STRING).mandatory())
        /* <!-- #BLAZE_RULE(git_repository).ATTRIBUTE(commit) -->
        The commit hash to check out in the repository.

        <p>Note that one of either <code>commit</code> or <code>tag</code> must be defined.</p>
        <!-- #END_BLAZE_RULE.ATTRIBUTE --> */
        .add(attr("commit", STRING))
        /* <!-- #BLAZE_RULE(git_repository).ATTRIBUTE(tag) -->
        The Git tag to check out in the repository.

        <p>Note that one of either <code>commit</code> or <code>tag</code> must be defined.</p>
        <!-- #END_BLAZE_RULE.ATTRIBUTE --> */
        .add(attr("tag", STRING))
        /* <!-- #BLAZE_RULE(git_repository).ATTRIBUTE(init_submodules) -->
        Whether to clone submodules in the repository.

        <p>Currently, only cloning the top-level submodules is supported</p>
        <!-- #END_BLAZE_RULE.ATTRIBUTE --> */
        .add(attr("init_submodules", BOOLEAN).value(false))
        /* <!-- #BLAZE_RULE(git_repository).ATTRIBUTE(sha256) -->
         The expected SHA-256 hash of the file downloaded. Specifying this forces the repository to
         be downloaded as a tarball. Currently, this is only supported for public GitHub
         repositories.

         <p>This must match the SHA-256 hash of the file downloaded. <em>It is a security risk to
         omit the SHA-256 as remote files can change.</em> At best omitting this field will make
         your build non-hermetic. It is optional to make development easier but should be set
         before shipping.</p>
         <!-- #END_BLAZE_RULE.ATTRIBUTE --> */
        .add(attr("sha256", STRING))
        .setWorkspaceOnly()
        .build();
  }

  @Override
  public Metadata getMetadata() {
    return RuleDefinition.Metadata.builder()
        .name(GitRepositoryRule.NAME)
        .type(RuleClass.Builder.RuleClassType.WORKSPACE)
        .ancestors(WorkspaceBaseRule.class)
        .factoryClass(WorkspaceConfiguredTargetFactory.class)
        .build();
  }
}

/*<!-- #BLAZE_RULE (NAME = git_repository, TYPE = OTHER, FAMILY = Workspace)[GENERIC_RULE] -->

<p><strong>Deprecated.
<code>load("@bazel_tools//tools/build_defs/repo:git.bzl", "git_repository")</code>
for a drop-in replacement.</strong></p>

<p>Clones a Git repository, checks out the specified tag, or commit, and makes its targets
available for binding.</p>

<h4 id="git_repository_examples">Examples</h4>

<p>Suppose the current repository contains the source code for a chat program, rooted at the
  directory <i>~/chat-app</i>. It needs to depend on an SSL library which is available in the
  remote Git repository <i>http://example.com/openssl/openssl.git</i>. The chat app depends
  on version 1.0.2 of the SSL library, which is tagged by the v1.0.2 Git tag.<p>

<p>This Git repository contains the following directory structure:</p>

<pre class="code">
WORKSPACE
src/
  BUILD
  openssl.cc
  openssl.h
</pre>

<p><i>src/BUILD</i> contains the following target definition:</p>

<pre class="code">
cc_library(
    name = "openssl-lib",
    srcs = ["openssl.cc"],
    hdrs = ["openssl.h"],
)
</pre>

<p>Targets in the <i>~/chat-app</i> repository can depend on this target if the following lines are
  added to <i>~/chat-app/WORKSPACE</i>:</p>

<pre class="code">
git_repository(
    name = "my_ssl",
    remote = "http://example.com/openssl/openssl.git",
    tag = "v1.0.2",
)
</pre>

<p>Then targets would specify <code>@my_ssl//src:openssl-lib</code> as a dependency.</p>

<!-- #END_BLAZE_RULE -->*/
