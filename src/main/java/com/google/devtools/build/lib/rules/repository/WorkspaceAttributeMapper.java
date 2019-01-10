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

package com.google.devtools.build.lib.rules.repository;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.base.Preconditions;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.packages.AggregatingAttributeMapper;
import com.google.devtools.build.lib.packages.BuildType.SelectorList;
import com.google.devtools.build.lib.packages.Rule;
import com.google.devtools.build.lib.syntax.EvalException;
import com.google.devtools.build.lib.syntax.Type;
import javax.annotation.Nullable;

/**
 * An attribute mapper for workspace rules. Similar to NonconfigurableAttributeWrapper, but throws
 * a wrapped EvalException if select() is used.
 */
public class WorkspaceAttributeMapper {

  public static WorkspaceAttributeMapper of(Rule rule) {
    return new WorkspaceAttributeMapper(rule);
  }

  private final Rule rule;

  private WorkspaceAttributeMapper(Rule rule) {
    this.rule = rule;
  }

  /**
   * Returns typecasted value for attribute or {@code null} on no match.
   */
  @Nullable
  public <T> T get(String attributeName, Type<T> type) throws EvalException {
    Preconditions.checkNotNull(type);
    Object value = getObject(attributeName);
    try {
      return type.cast(value);
    } catch (ClassCastException e) {
      throw new EvalException(
          rule.getAttributeContainer().getAttributeLocation(attributeName), e.getMessage());
    }
  }

  /**
   * Returns value for attribute without casting it to any particular type, or null on no match.
   */
  @Nullable
  public Object getObject(String attributeName) throws EvalException {
    Object value = rule.getAttributeContainer().getAttr(checkNotNull(attributeName));
    if (value instanceof SelectorList) {
      String message;
      if (rule.getLocation().getPath().getBaseName().equals(
          Label.WORKSPACE_FILE_NAME.getPathString())) {
        message = "select() cannot be used in WORKSPACE files";
      } else {
        message = "select() cannot be used in macros called from WORKSPACE files";
      }
      throw new EvalException(
          rule.getAttributeContainer().getAttributeLocation(attributeName), message);
    }
    return value;
  }

  public boolean isAttributeValueExplicitlySpecified(String attr) {
    return rule.getAttributeContainer().isAttributeValueExplicitlySpecified(attr);
  }

  public Iterable<String> getAttributeNames() {
    return AggregatingAttributeMapper.of(rule).getAttributeNames();
  }
}
