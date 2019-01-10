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
package com.google.devtools.build.lib.query2.output;

import com.google.devtools.build.lib.query2.CommonQueryOptions;
import com.google.devtools.common.options.Option;
import com.google.devtools.common.options.OptionDocumentationCategory;
import com.google.devtools.common.options.OptionEffectTag;

/** Options class for aquery specific query options. */
public class AqueryOptions extends CommonQueryOptions {
  @Option(
      name = "output",
      defaultValue = "text",
      documentationCategory = OptionDocumentationCategory.QUERY,
      effectTags = {OptionEffectTag.TERMINAL_OUTPUT},
      help =
          "The format in which the aquery results should be printed. Allowed values for aquery "
              + "are: text, textproto, proto.")
  public String outputFormat;

  @Option(
      name = "include_commandline",
      defaultValue = "true",
      documentationCategory = OptionDocumentationCategory.QUERY,
      effectTags = {OptionEffectTag.TERMINAL_OUTPUT},
      help = "Includes the content of the action command lines in the output (potentially large).")
  public boolean includeCommandline;

  @Option(
      name = "include_aspects",
      defaultValue = "false",
      documentationCategory = OptionDocumentationCategory.QUERY,
      effectTags = {OptionEffectTag.TERMINAL_OUTPUT},
      help = "Whether to include aspect generated actions in the output.")
  public boolean useAspects;
}
