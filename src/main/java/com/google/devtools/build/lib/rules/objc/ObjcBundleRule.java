// Copyright 2014 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.rules.objc;

import static com.google.devtools.build.lib.packages.Attribute.attr;
import static com.google.devtools.build.lib.packages.BuildType.LABEL_LIST;

import com.google.devtools.build.lib.analysis.BaseRuleClasses;
import com.google.devtools.build.lib.analysis.RuleDefinition;
import com.google.devtools.build.lib.analysis.RuleDefinitionEnvironment;
import com.google.devtools.build.lib.packages.RuleClass;
import com.google.devtools.build.lib.util.FileTypeSet;

/**
 * Rule definition for objc_bundle.
 */
public class ObjcBundleRule implements RuleDefinition {
  @Override
  public RuleClass build(RuleClass.Builder builder, RuleDefinitionEnvironment environment) {
    return builder
        /* <!-- #BLAZE_RULE(objc_bundle).ATTRIBUTE(bundle_imports) -->
        The list of files under a <code>.bundle</code> directory which are
        provided to Objective-C targets that depend on this target.
        <!-- #END_BLAZE_RULE.ATTRIBUTE --> */
        .add(attr("bundle_imports", LABEL_LIST)
            .allowedFileTypes(FileTypeSet.ANY_FILE)
            .nonEmpty())
        .build();
  }

  @Override
  public Metadata getMetadata() {
    return RuleDefinition.Metadata.builder()
        .name("objc_bundle")
        .factoryClass(ObjcBundle.class)
        .ancestors(BaseRuleClasses.BaseRule.class)
        .build();
  }
}

/*<!-- #BLAZE_RULE (NAME = objc_bundle, TYPE = LIBRARY, FAMILY = Objective-C) -->

<p>This rule encapsulates an already-built bundle. It is defined by a list of
files in one or more <code>.bundle</code> directories.

<!-- #END_BLAZE_RULE -->*/
