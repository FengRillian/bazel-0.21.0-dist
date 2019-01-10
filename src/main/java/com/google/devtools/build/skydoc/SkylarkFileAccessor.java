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

package com.google.devtools.build.skydoc;

import com.google.devtools.build.lib.syntax.ParserInputSource;
import java.io.IOException;

/**
 * Helper to handle Skydoc file I/O. This abstraction is useful for tests which don't involve
 * actual file I/O.
 */
public interface SkylarkFileAccessor {

  /**
   * Returns a {@link ParserInputSource} for accessing the content of the given absolute path
   * string.
   */
  ParserInputSource inputSource(String pathString) throws IOException;
}
