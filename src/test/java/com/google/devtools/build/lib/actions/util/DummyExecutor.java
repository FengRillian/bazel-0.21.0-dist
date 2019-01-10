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
package com.google.devtools.build.lib.actions.util;

import com.google.common.eventbus.EventBus;
import com.google.devtools.build.lib.actions.ActionContext;
import com.google.devtools.build.lib.actions.ActionExecutionContext.ShowSubcommands;
import com.google.devtools.build.lib.actions.Executor;
import com.google.devtools.build.lib.clock.BlazeClock;
import com.google.devtools.build.lib.clock.Clock;
import com.google.devtools.build.lib.events.ExtendedEventHandler;
import com.google.devtools.build.lib.vfs.FileSystem;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.common.options.OptionsProvider;

/**
 * A dummy implementation of Executor.
 */
public final class DummyExecutor implements Executor {

  private final FileSystem fileSystem;
  private final Path inputDir;
  private final ExtendedEventHandler eventHandler;

  public DummyExecutor(FileSystem fileSystem, Path inputDir) {
    this(fileSystem, inputDir, null);
  }

  public DummyExecutor(ExtendedEventHandler eventHandler) {
    this(null, null, eventHandler);
  }

  public DummyExecutor(FileSystem fileSystem, Path inputDir, ExtendedEventHandler eventHandler) {
    this.fileSystem = fileSystem;
    this.inputDir = inputDir;
    this.eventHandler = eventHandler;
  }

  @Override
  public FileSystem getFileSystem() {
    return fileSystem;
  }

  @Override
  public Path getExecRoot() {
    return inputDir;
  }

  @Override
  public Clock getClock() {
    return BlazeClock.instance();
  }

  @Override
  public EventBus getEventBus() {
    return new EventBus();
  }

  @Override
  public boolean getVerboseFailures() {
    throw new UnsupportedOperationException();
  }

  @Override
  public ExtendedEventHandler getEventHandler() {
    return eventHandler;
  }

  @Override
  public <T extends ActionContext> T getContext(Class<? extends T> type) {
    throw new UnsupportedOperationException();
  }

  @Override
  public OptionsProvider getOptions() {
    throw new UnsupportedOperationException();
  }

  @Override
  public ShowSubcommands reportsSubcommands() {
    throw new UnsupportedOperationException();
  }
}
