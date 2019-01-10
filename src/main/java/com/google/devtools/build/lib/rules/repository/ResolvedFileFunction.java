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

package com.google.devtools.build.lib.rules.repository;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.actions.FileValue;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.packages.BazelLibrary;
import com.google.devtools.build.lib.packages.BuildFileContainsErrorsException;
import com.google.devtools.build.lib.packages.NoSuchThingException;
import com.google.devtools.build.lib.rules.repository.ResolvedFileValue.ResolvedFileKey;
import com.google.devtools.build.lib.skyframe.PrecomputedValue;
import com.google.devtools.build.lib.syntax.BuildFileAST;
import com.google.devtools.build.lib.syntax.Environment;
import com.google.devtools.build.lib.syntax.Mutability;
import com.google.devtools.build.lib.syntax.ParserInputSource;
import com.google.devtools.build.lib.syntax.SkylarkSemantics;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.skyframe.SkyFunction;
import com.google.devtools.build.skyframe.SkyFunctionException;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;

/** Function computing the Starlark value of 'resolved' in a file. */
public class ResolvedFileFunction implements SkyFunction {

  @Override
  @Nullable
  public SkyValue compute(SkyKey skyKey, Environment env)
      throws InterruptedException, SkyFunctionException {

    ResolvedFileKey key = (ResolvedFileKey) skyKey;
    SkylarkSemantics skylarkSemantics = PrecomputedValue.SKYLARK_SEMANTICS.get(env);
    if (skylarkSemantics == null) {
      return null;
    }
    FileValue fileValue = (FileValue) env.getValue(FileValue.key(key.getPath()));
    if (fileValue == null) {
      return null;
    }
    try {
      if (!fileValue.exists()) {
        throw new ResolvedFileFunctionException(
            new NoSuchThingException("Specified resolved file '" + key.getPath() + "' not found."));
      } else {
        byte[] bytes =
            FileSystemUtils.readWithKnownFileSize(
                key.getPath().asPath(), key.getPath().asPath().getFileSize());
        BuildFileAST ast =
            BuildFileAST.parseSkylarkFile(
                ParserInputSource.create(bytes, key.getPath().asPath().asFragment()),
                env.getListener());
        if (ast.containsErrors()) {
          throw new ResolvedFileFunctionException(
              new BuildFileContainsErrorsException(
                  Label.EXTERNAL_PACKAGE_IDENTIFIER,
                  "Failed to parse file resolved file " + key.getPath()));
        }
        com.google.devtools.build.lib.syntax.Environment resolvedEnvironment;
        try (Mutability mutability = Mutability.create("resolved file %s", key.getPath())) {
          resolvedEnvironment =
              com.google.devtools.build.lib.syntax.Environment.builder(mutability)
                  .setSemantics(skylarkSemantics)
                  .setGlobals(BazelLibrary.GLOBALS)
                  .build();
          if (!ast.exec(resolvedEnvironment, env.getListener())) {
            throw new ResolvedFileFunctionException(
                new BuildFileContainsErrorsException(
                    Label.EXTERNAL_PACKAGE_IDENTIFIER,
                    "Failed to evaluate resolved file " + key.getPath()));
          }
        }
        Object resolved = resolvedEnvironment.moduleLookup("resolved");
        if (resolved == null) {
          throw new ResolvedFileFunctionException(
              new BuildFileContainsErrorsException(
                  Label.EXTERNAL_PACKAGE_IDENTIFIER,
                  "Symbol 'resolved' not exported in resolved file " + key.getPath()));
        }
        if (!(resolved instanceof List)) {
          throw new ResolvedFileFunctionException(
              new BuildFileContainsErrorsException(
                  Label.EXTERNAL_PACKAGE_IDENTIFIER,
                      "Symbol 'resolved' in resolved file " + key.getPath() + " not a list"));
        }
        ImmutableList.Builder<Map<String, Object>> result
            = new ImmutableList.Builder<Map<String, Object>>();
        for (Object entry : (List) resolved) {
          if (!(entry instanceof Map)) {
          throw new ResolvedFileFunctionException(
              new BuildFileContainsErrorsException(
                  Label.EXTERNAL_PACKAGE_IDENTIFIER,
                      "Symbol 'resolved' in resolved file " + key.getPath()
                      + " contains a non-map entry"));
          }
          ImmutableMap.Builder<String, Object> entryBuilder
              = new ImmutableMap.Builder<String, Object>();
          for (Map.Entry<Object, Object> keyValue : ((Map<Object, Object>) entry).entrySet()) {
            Object attribute = keyValue.getKey();
            if (!(attribute instanceof String)) {
              throw new ResolvedFileFunctionException(
                  new BuildFileContainsErrorsException(
                      Label.EXTERNAL_PACKAGE_IDENTIFIER,
                          "Symbol 'resolved' in resolved file " + key.getPath()
                          + " contains a non-string key in one of its entries"));
            }
            entryBuilder.put((String) attribute, keyValue.getValue());
          }
          result.add(entryBuilder.build());
        }
        return new ResolvedFileValue(result.build());
      }
    } catch (IOException e) {
      throw new ResolvedFileFunctionException(e);
    }
  }

  @Override
  @Nullable
  public String extractTag(SkyKey skyKey) {
    return null;
  }

  private static final class ResolvedFileFunctionException extends SkyFunctionException {
    ResolvedFileFunctionException(IOException e) {
      super(e, SkyFunctionException.Transience.PERSISTENT);
    }

    ResolvedFileFunctionException(NoSuchThingException e) {
      super(e, SkyFunctionException.Transience.PERSISTENT);
    }
  }
}
