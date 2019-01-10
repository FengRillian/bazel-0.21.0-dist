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

package com.google.devtools.build.lib.packages;

import com.google.common.base.Preconditions;
import com.google.common.base.Verify;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.LabelSyntaxException;
import com.google.devtools.build.lib.events.Location;
import com.google.devtools.build.lib.events.StoredEventHandler;
import com.google.devtools.build.lib.packages.RuleFactory.BuildLangTypedAttributeValuesMap;
import com.google.devtools.build.lib.syntax.FuncallExpression;
import java.util.Map;

/** A helper for the {@link WorkspaceFactory} to create repository rules */
public class WorkspaceFactoryHelper {

  public static Rule createAndAddRepositoryRule(
      Package.Builder pkg,
      RuleClass ruleClass,
      RuleClass bindRuleClass,
      Map<String, Object> kwargs,
      FuncallExpression ast)
      throws RuleFactory.InvalidRuleException, Package.NameConflictException, LabelSyntaxException,
          InterruptedException {

    StoredEventHandler eventHandler = new StoredEventHandler();
    BuildLangTypedAttributeValuesMap attributeValues = new BuildLangTypedAttributeValuesMap(kwargs);
    Rule rule =
        RuleFactory.createRule(
            pkg,
            ruleClass,
            attributeValues,
            eventHandler,
            ast,
            ast.getLocation(),
            /*env=*/ null,
            new AttributeContainer(ruleClass));
    pkg.addEvents(eventHandler.getEvents());
    pkg.addPosts(eventHandler.getPosts());
    overwriteRule(pkg, rule);
    for (Map.Entry<String, Label> entry :
        ruleClass.getExternalBindingsFunction().apply(rule).entrySet()) {
      Label nameLabel = Label.parseAbsolute("//external:" + entry.getKey(), ImmutableMap.of());
      addBindRule(
          pkg,
          bindRuleClass,
          nameLabel,
          entry.getValue(),
          rule.getLocation(),
          new AttributeContainer(bindRuleClass));
    }
    return rule;
  }

  static void addBindRule(
      Package.Builder pkg,
      RuleClass bindRuleClass,
      Label virtual,
      Label actual,
      Location location,
      AttributeContainer attributeContainer)
      throws RuleFactory.InvalidRuleException, Package.NameConflictException, InterruptedException {

    Map<String, Object> attributes = Maps.newHashMap();
    // Bound rules don't have a name field, but this works because we don't want more than one
    // with the same virtual name.
    attributes.put("name", virtual.getName());
    if (actual != null) {
      attributes.put("actual", actual);
    }
    StoredEventHandler handler = new StoredEventHandler();
    BuildLangTypedAttributeValuesMap attributeValues =
        new BuildLangTypedAttributeValuesMap(attributes);
    Rule rule =
        RuleFactory.createRule(
            pkg,
            bindRuleClass,
            attributeValues,
            handler,
            /*ast=*/ null,
            location,
            /*env=*/ null,
            attributeContainer);
    overwriteRule(pkg, rule);
    rule.setVisibility(ConstantRuleVisibility.PUBLIC);
  }

  private static void overwriteRule(Package.Builder pkg, Rule rule)
      throws Package.NameConflictException {
    Preconditions.checkArgument(rule.getOutputFiles().isEmpty());
    Target old = pkg.getTarget(rule.getName());
    if (old != null) {
      if (old instanceof Rule) {
        Verify.verify(((Rule) old).getOutputFiles().isEmpty());
      }

      pkg.removeTarget(rule);
    }

    pkg.addRule(rule);
  }
}
