
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

package com.google.devtools.build.lib.rules.cpp;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedMap;
import com.google.devtools.build.lib.actions.AbstractAction;
import com.google.devtools.build.lib.actions.ActionExecutionContext;
import com.google.devtools.build.lib.actions.ActionExecutionException;
import com.google.devtools.build.lib.actions.ActionKeyContext;
import com.google.devtools.build.lib.actions.ActionOwner;
import com.google.devtools.build.lib.actions.ActionResult;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.AutoCodec;
import com.google.devtools.build.lib.util.Fingerprint;
import com.google.devtools.build.lib.vfs.FileSystem;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.Symlinks;
import java.io.IOException;
import java.util.Map;
import java.util.SortedMap;

/** This action creates a set of symbolic links. */
@AutoCodec
@Immutable
public final class CreateIncSymlinkAction extends AbstractAction {
  private final ImmutableSortedMap<Artifact, Artifact> symlinks;
  private final Path includePath;

  /**
   * Creates a new instance. The symlinks map maps symlinks to their targets, i.e. the symlink paths
   * must be unique, but several of them can point to the same target. All outputs must be under
   * {@code includePath}.
   */
  public CreateIncSymlinkAction(
      ActionOwner owner, Map<Artifact, Artifact> symlinks, Path includePath) {
    super(owner, ImmutableList.copyOf(symlinks.values()), ImmutableList.copyOf(symlinks.keySet()));
    this.symlinks = ImmutableSortedMap.copyOf(symlinks, Artifact.EXEC_PATH_COMPARATOR);
    this.includePath = includePath;
  }

  @Override
  public void prepare(FileSystem fileSystem, Path execRoot) throws IOException {
    if (includePath.isDirectory(Symlinks.NOFOLLOW)) {
      FileSystemUtils.deleteTree(includePath);
    }
    super.prepare(fileSystem, execRoot);
  }

  @Override
  public ActionResult execute(ActionExecutionContext actionExecutionContext)
      throws ActionExecutionException {
    try {
      for (Map.Entry<Artifact, Artifact> entry : symlinks.entrySet()) {
        Path symlink = actionExecutionContext.getInputPath(entry.getKey());
        symlink.createSymbolicLink(actionExecutionContext.getInputPath(entry.getValue()));
      }
    } catch (IOException e) {
      String message = "IO Error while creating symlink";
      throw new ActionExecutionException(message, e, this, false);
    }
    return ActionResult.EMPTY;
  }

  @VisibleForTesting
  public SortedMap<Artifact, Artifact> getSymlinks() {
    return symlinks;
  }

  @Override
  public void computeKey(ActionKeyContext actionKeyContext, Fingerprint fp) {
    for (Map.Entry<Artifact, Artifact> entry : symlinks.entrySet()) {
      fp.addPath(entry.getKey().getExecPath());
      fp.addPath(entry.getValue().getExecPath());
    }
  }

  @Override
  protected String getRawProgressMessage() {
    return null; // users don't really want to know about inc symlinks.
  }

  @Override
  public String getMnemonic() {
    return "Symlink";
  }
}

