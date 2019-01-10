// Copyright 2016 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.bazel.repository.skylark;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.analysis.BlazeDirectories;
import com.google.devtools.build.lib.analysis.RuleDefinition;
import com.google.devtools.build.lib.bazel.repository.RepositoryResolvedEvent;
import com.google.devtools.build.lib.bazel.repository.downloader.HttpDownloader;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.packages.Rule;
import com.google.devtools.build.lib.rules.repository.RepositoryDelegatorFunction;
import com.google.devtools.build.lib.rules.repository.RepositoryDirectoryValue;
import com.google.devtools.build.lib.rules.repository.RepositoryFunction;
import com.google.devtools.build.lib.rules.repository.ResolvedHashesValue;
import com.google.devtools.build.lib.skyframe.PrecomputedValue;
import com.google.devtools.build.lib.syntax.BaseFunction;
import com.google.devtools.build.lib.syntax.EvalException;
import com.google.devtools.build.lib.syntax.Mutability;
import com.google.devtools.build.lib.syntax.SkylarkSemantics;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.skyframe.SkyFunction.Environment;
import com.google.devtools.build.skyframe.SkyFunctionException.Transience;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * A repository function to delegate work done by skylark remote repositories.
 */
public class SkylarkRepositoryFunction extends RepositoryFunction {

  private final HttpDownloader httpDownloader;
  private double timeoutScaling = 1.0;

  public SkylarkRepositoryFunction(HttpDownloader httpDownloader) {
    this.httpDownloader = httpDownloader;
  }

  public void setTimeoutScaling(double timeoutScaling) {
    this.timeoutScaling = timeoutScaling;
  }

  @Nullable
  @Override
  public RepositoryDirectoryValue.Builder fetch(Rule rule, Path outputDirectory,
      BlazeDirectories directories, Environment env, Map<String, String> markerData)
      throws RepositoryFunctionException, InterruptedException {
    BaseFunction function = rule.getRuleClassObject().getConfiguredTargetFunction();
    if (declareEnvironmentDependencies(markerData, env, getEnviron(rule)) == null) {
      return null;
    }
    SkylarkSemantics skylarkSemantics = PrecomputedValue.SKYLARK_SEMANTICS.get(env);
    if (skylarkSemantics == null) {
      return null;
    }

    Set<String> verificationRules =
        RepositoryDelegatorFunction.OUTPUT_VERIFICATION_REPOSITORY_RULES.get(env);
    if (verificationRules == null) {
      return null;
    }
    ResolvedHashesValue resolvedHashesValue =
        (ResolvedHashesValue) env.getValue(ResolvedHashesValue.key());
    if (resolvedHashesValue == null) {
      return null;
    }
    Map<String, String> resolvedHashes = resolvedHashesValue.getHashes();

    try (Mutability mutability = Mutability.create("Starlark repository")) {
      com.google.devtools.build.lib.syntax.Environment buildEnv =
          com.google.devtools.build.lib.syntax.Environment.builder(mutability)
              .setSemantics(skylarkSemantics)
              .setEventHandler(env.getListener())
              .build();
      SkylarkRepositoryContext skylarkRepositoryContext =
          new SkylarkRepositoryContext(
              rule,
              outputDirectory,
              env,
              clientEnvironment,
              httpDownloader,
              timeoutScaling,
              markerData);

      // Since restarting a repository function can be really expensive, we first ensure that
      // all label-arguments can be resolved to paths.
      try {
        skylarkRepositoryContext.enforceLabelAttributes();
      } catch (EvalException e) {
        if (e instanceof RepositoryMissingDependencyException) {
          // Missing values are expected; just restart before we actually start the rule
          return null;
        }
        // Other EvalExceptions indicate labels not referring to existing files. This is fine,
        // as long as they are never resolved to files in the execution of the rule; we allow
        // non-strict rules. So now we have to start evaluating the actual rule, even if that
        // means the rule might get restarted for legitimate reasons.
      }

      // This rule is mainly executed for its side effect. Nevertheless, the return value is
      // of importance, as it provides information on how the call has to be modified to be a
      // reproducible rule.
      //
      // Also we do a lot of stuff in there, maybe blocking operations and we should certainly make
      // it possible to return null and not block but it doesn't seem to be easy with Skylark
      // structure as it is.
      Object retValue =
          function.call(
              /*args=*/ ImmutableList.of(skylarkRepositoryContext),
              /*kwargs=*/ ImmutableMap.of(),
              null,
              buildEnv);
      RepositoryResolvedEvent resolved =
          new RepositoryResolvedEvent(
              rule, skylarkRepositoryContext.getAttr(), outputDirectory, retValue);
      if (resolved.isNewInformationReturned()) {
        env.getListener().handle(Event.info(resolved.getMessage()));
      }

      String ruleClass =
          rule.getRuleClassObject().getRuleDefinitionEnvironmentLabel() + "%" + rule.getRuleClass();
      if (verificationRules.contains(ruleClass)) {
        String expectedHash = resolvedHashes.get(rule.getName());
        if (expectedHash != null) {
          String actualHash = resolved.getDirectoryDigest();
          if (!expectedHash.equals(actualHash)) {
            throw new RepositoryFunctionException(
                new IOException(
                    rule + " failed to create a directory with expected hash " + expectedHash),
                Transience.PERSISTENT);
          }
        }
      }
      env.getListener().post(resolved);
    } catch (EvalException e) {
      if (e.getCause() instanceof RepositoryMissingDependencyException) {
        // A dependency is missing, cleanup and returns null
        try {
          if (outputDirectory.exists()) {
            FileSystemUtils.deleteTree(outputDirectory);
          }
        } catch (IOException e1) {
          throw new RepositoryFunctionException(e1, Transience.TRANSIENT);
        }
        return null;
      }
      throw new RepositoryFunctionException(e, Transience.TRANSIENT);
    }

    if (!outputDirectory.isDirectory()) {
      throw new RepositoryFunctionException(
          new IOException(rule + " must create a directory"), Transience.TRANSIENT);
    }

    if (!outputDirectory.getRelative(Label.WORKSPACE_FILE_NAME).exists()) {
      createWorkspaceFile(outputDirectory, rule.getTargetKind(), rule.getName());
    }

    return RepositoryDirectoryValue.builder().setPath(outputDirectory);
  }

  @SuppressWarnings("unchecked")
  private static Iterable<String> getEnviron(Rule rule) {
    return (Iterable<String>) rule.getAttributeContainer().getAttr("$environ");
  }

  @Override
  protected boolean isLocal(Rule rule) {
    return (Boolean) rule.getAttributeContainer().getAttr("$local");
  }

  @Override
  public Class<? extends RuleDefinition> getRuleDefinition() {
    return null; // unused so safe to return null
  }
}
