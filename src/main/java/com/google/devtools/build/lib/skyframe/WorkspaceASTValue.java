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
package com.google.devtools.build.lib.skyframe;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Interner;
import com.google.devtools.build.lib.concurrent.BlazeInterners;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.AutoCodec;
import com.google.devtools.build.lib.syntax.BuildFileAST;
import com.google.devtools.build.lib.vfs.RootedPath;
import com.google.devtools.build.skyframe.AbstractSkyKey;
import com.google.devtools.build.skyframe.SkyFunctionName;
import com.google.devtools.build.skyframe.SkyValue;
import java.util.List;

/**
 * A SkyValue that stores the parsed WORKSPACE file as a list of AST. Each AST contains the part
 * of the WORKSPACE file between the first load statement of a series of load statements and the
 * last statement before the next load statement. As example, the comment indicate where the next
 * file would be split:
 *
 * <p><code>
 * # First AST
 * load('//foo:bar.bzl', 'foobar')
 * foo_bar = 1
 *
 * # Second AST
 * load('//foo:baz.bzl', 'foos')
 * load('//bar:foo.bzl', 'bars')
 * foos()
 * bars()
 *
 * # Third AST
 * load('//:bleh.bzl', 'bleh')
 * </code>
 */
public class WorkspaceASTValue implements SkyValue {

  private final ImmutableList<BuildFileAST> asts;

  public WorkspaceASTValue(List<BuildFileAST> asts) {
    Preconditions.checkNotNull(asts);
    this.asts = ImmutableList.copyOf(asts);
  }

  public ImmutableList<BuildFileAST> getASTs() {
    return asts;
  }

  public static Key key(RootedPath path) {
    return Key.create(path);
  }

  @AutoCodec.VisibleForSerialization
  @AutoCodec
  static class Key extends AbstractSkyKey<RootedPath> {
    private static final Interner<Key> interner = BlazeInterners.newWeakInterner();

    private Key(RootedPath arg) {
      super(arg);
    }

    @AutoCodec.VisibleForSerialization
    @AutoCodec.Instantiator
    static Key create(RootedPath arg) {
      return interner.intern(new Key(arg));
    }

    @Override
    public SkyFunctionName functionName() {
      return SkyFunctions.WORKSPACE_AST;
    }
  }
}
