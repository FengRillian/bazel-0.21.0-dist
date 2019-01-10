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
package com.google.devtools.build.docgen.skylark;

import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.skylarkinterface.Param;
import com.google.devtools.build.lib.skylarkinterface.SkylarkCallable;
import com.google.devtools.build.lib.skylarkinterface.SkylarkInterfaceUtils;
import com.google.devtools.build.lib.skylarkinterface.SkylarkModule;
import com.google.devtools.build.lib.skylarkinterface.SkylarkSignature;
import com.google.devtools.build.lib.syntax.EvalUtils;
import com.google.devtools.build.lib.syntax.FuncallExpression;
import com.google.devtools.build.lib.syntax.Runtime.NoneType;
import com.google.devtools.build.lib.syntax.SkylarkList;
import com.google.devtools.build.lib.syntax.SkylarkList.MutableList;
import com.google.devtools.build.lib.syntax.SkylarkList.Tuple;
import com.google.devtools.build.lib.syntax.StringModule;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Map;

/**
 * Abstract class for containing documentation for a Skylark syntactic entity.
 */
abstract class SkylarkDoc {
  protected static final String TOP_LEVEL_ID = "globals";

  /**
   * Returns a string containing the name of the entity being documented.
   */
  public abstract String getName();

  /**
   * Returns a string containing the formatted HTML documentation of the entity being documented.
   */
  public abstract String getDocumentation();

  protected String getTypeAnchor(Class<?> returnType, Class<?> generic1) {
    return getTypeAnchor(returnType) + " of " + getTypeAnchor(generic1) + "s";
  }

  protected String getTypeAnchor(Class<?> type) {
    if (type.equals(Boolean.class) || type.equals(boolean.class)) {
      return "<a class=\"anchor\" href=\"bool.html\">bool</a>";
    } else if (type.equals(int.class) || type.equals(Integer.class)) {
      return "<a class=\"anchor\" href=\"int.html\">int</a>";
    } else if (type.equals(String.class)) {
      return "<a class=\"anchor\" href=\"string.html\">string</a>";
    } else if (Map.class.isAssignableFrom(type)) {
      return "<a class=\"anchor\" href=\"dict.html\">dict</a>";
    } else if (type.equals(Tuple.class)) {
      return "<a class=\"anchor\" href=\"list.html\">tuple</a>";
    } else if (type.equals(MutableList.class) || type.equals(ImmutableList.class)) {
      return "<a class=\"anchor\" href=\"list.html\">list</a>";
    } else if (type.equals(SkylarkList.class)) {
      return "<a class=\"anchor\" href=\"list.html\">sequence</a>";
    } else if (type.equals(Void.TYPE) || type.equals(NoneType.class)) {
      return "<a class=\"anchor\" href=\"" + TOP_LEVEL_ID + ".html#None\">None</a>";
    } else if (type.equals(NestedSet.class)) {
      return "<a class=\"anchor\" href=\"depset.html\">depset</a>";
    } else if (SkylarkInterfaceUtils.getSkylarkModule(type) != null) {
      SkylarkModule module = SkylarkInterfaceUtils.getSkylarkModule(type);
      if (module.documented()) {
        return String.format("<a class=\"anchor\" href=\"%1$s.html\">%1$s</a>",
                             module.name());
      }
    }
    return EvalUtils.getDataTypeNameFromClass(type);
  }

  // Omit self parameter from parameters in class methods.
  protected static Param[] withoutSelfParam(SkylarkSignature annotation) {
    Param[] params = annotation.parameters();
    if (params.length > 0
        && !params[0].named()
        && (params[0].defaultValue() != null && params[0].defaultValue().isEmpty())
        && params[0].positional()
        && annotation.objectType() != Object.class
        && !FuncallExpression.isNamespace(annotation.objectType())) {
      // Skip the self parameter, which is the first mandatory positional parameter.
      return Arrays.copyOfRange(params, 1, params.length);
    } else {
      return params;
    }
  }

  // Omit self parameter from parameters in class methods.
  protected static Param[] withoutSelfParam(SkylarkCallable annotation, Method method) {
    Param[] params = annotation.parameters();
    if (method.getDeclaringClass().equals(StringModule.class)) {
      // Skip the self parameter, which is the first mandatory positional parameter.
      return Arrays.copyOfRange(params, 1, params.length);
    } else {
      return params;
    }
  }
}
