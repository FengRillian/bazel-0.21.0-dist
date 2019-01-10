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
package com.google.devtools.build.lib.packages;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.events.Location;
import com.google.devtools.build.lib.packages.BuildType.SelectorList;
import com.google.devtools.build.lib.syntax.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import javax.annotation.Nullable;

/**
 * Base {@link AttributeMap} implementation providing direct, unmanipulated access to
 * underlying attribute data as stored within the Rule.
 *
 * <p>Any instantiable subclass should define a clear policy of what it does with this
 * data before exposing it to consumers.
 */
public abstract class AbstractAttributeMapper implements AttributeMap {
  private final Package pkg;
  private final RuleClass ruleClass;
  private final Label ruleLabel;
  private final AttributeContainer attributes;

  public AbstractAttributeMapper(Package pkg, RuleClass ruleClass, Label ruleLabel,
      AttributeContainer attributes) {
    this.pkg = pkg;
    this.ruleClass = ruleClass;
    this.ruleLabel = ruleLabel;
    this.attributes = attributes;
  }

  @Override
  public String getName() {
    return ruleLabel.getName();
  }

  @Override
  public Label getLabel() {
    return ruleLabel;
  }

  @Override
  public String getRuleClassName() {
    return ruleClass.getName();
  }

  @Nullable
  @Override
  public <T> T get(String attributeName, Type<T> type) {
    int index = getIndexWithTypeCheck(attributeName, type);
    Object value = attributes.getAttributeValue(index);
    if (value instanceof Attribute.ComputedDefault) {
      value = ((Attribute.ComputedDefault) value).getDefault(this);
    } else if (value instanceof Attribute.LateBoundDefault) {
      value = ((Attribute.LateBoundDefault) value).getDefault();
    }
    try {
      return type.cast(value);
    } catch (ClassCastException e) {
      // getIndexWithTypeCheck checks the type is right, but unexpected configurable attributes
      // can still trigger cast exceptions.
      throw new IllegalArgumentException("wrong type for attribute \"" + attributeName + "\" in "
          + ruleClass + " rule " + ruleLabel, e);
    }
  }

  /**
   * Returns the given attribute if it's a computed default, null otherwise.
   *
   * @throws IllegalArgumentException if the given attribute doesn't exist with the specified
   *         type. This happens whether or not it's a computed default.
   */
  @VisibleForTesting // Should be protected
  @Nullable
  public <T> Attribute.ComputedDefault getComputedDefault(String attributeName, Type<T> type) {
    int index = getIndexWithTypeCheck(attributeName, type);
    Object value = attributes.getAttributeValue(index);
    if (value instanceof Attribute.ComputedDefault) {
      return (Attribute.ComputedDefault) value;
    } else {
      return null;
    }
  }

  /**
   * Returns the given attribute if it's a {@link Attribute.LateBoundDefault}, null otherwise.
   *
   * @throws IllegalArgumentException if the given attribute doesn't exist with the specified
   *         type. This happens whether or not it's a late bound default.
   */
  @Nullable
  @SuppressWarnings("unchecked")
  public <T> Attribute.LateBoundDefault<?, T> getLateBoundDefault(
      String attributeName, Type<T> type) {
    int index = getIndexWithTypeCheck(attributeName, type);
    Object value = attributes.getAttributeValue(index);
    if (value instanceof Attribute.LateBoundDefault) {
      return (Attribute.LateBoundDefault<?, T>) value;
    } else {
      return null;
    }
  }

  @Override
  public Iterable<String> getAttributeNames() {
    ImmutableList.Builder<String> names = ImmutableList.builder();
    for (Attribute a : ruleClass.getAttributes()) {
      names.add(a.getName());
    }
    return names.build();
  }

  @Nullable
  @Override
  public Type<?> getAttributeType(String attrName) {
    Attribute attr = getAttributeDefinition(attrName);
    return attr == null ? null : attr.getType();
  }

  @Nullable
  @Override
  public Attribute getAttributeDefinition(String attrName) {
    return ruleClass.getAttributeByNameMaybe(attrName);
  }

  @Override
  public boolean isAttributeValueExplicitlySpecified(String attributeName) {
    return attributes.isAttributeValueExplicitlySpecified(attributeName);
  }

  @Override
  public String getPackageDefaultHdrsCheck() {
    return pkg.getDefaultHdrsCheck();
  }

  @Override
  public Boolean getPackageDefaultTestOnly() {
    return pkg.getDefaultTestOnly();
  }

  @Override
  public String getPackageDefaultDeprecation() {
    return pkg.getDefaultDeprecation();
  }

  @Override
  public ImmutableList<String> getPackageDefaultCopts() {
    return pkg.getDefaultCopts();
  }

  @Override
  public Collection<DepEdge> visitLabels() {
    List<DepEdge> edges = new ArrayList<>();
    Type.LabelVisitor<Attribute> visitor =
        (label, attribute) -> {
          if (label != null) {
            Label absoluteLabel = ruleLabel.resolveRepositoryRelative(label);
            edges.add(AttributeMap.DepEdge.create(absoluteLabel, attribute));
          }
        };
    for (Attribute attribute : ruleClass.getAttributes()) {
      Type<?> type = attribute.getType();
      // TODO(bazel-team): clean up the typing / visitation interface so we don't have to
      // special-case these types.
      if (type != BuildType.OUTPUT && type != BuildType.OUTPUT_LIST
          && type != BuildType.NODEP_LABEL && type != BuildType.NODEP_LABEL_LIST) {
        visitLabels(attribute, visitor);
      }
    }
    return edges;
  }

  /** Visits all labels reachable from the given attribute. */
  protected void visitLabels(Attribute attribute, Type.LabelVisitor<Attribute> visitor) {
    Type<?> type = attribute.getType();
    Object value = get(attribute.getName(), type);
    if (value != null) { // null values are particularly possible for computed defaults.
      type.visitLabels(visitor, value, attribute);
    }
  }

  @Override
  public final boolean isConfigurable(String attributeName) {
    Attribute attrDef = getAttributeDefinition(attributeName);
    return attrDef != null && getSelectorList(attributeName, attrDef.getType()) != null;
  }

  public static <T> boolean isConfigurable(Rule rule, String attributeName, Type<T> type) {
    SelectorList<T> selectorMaybe = getSelectorList(
        rule.getRuleClassObject(),
        rule.getLabel(),
        rule.getAttributeContainer(),
        attributeName,
        type);
    return selectorMaybe != null;
  }

  /**
   * Returns a {@link SelectorList} for the given attribute if the attribute is configurable
   * for this rule, null otherwise.
   *
   * @return a {@link SelectorList} if the attribute takes the form
   *     "attrName = { 'a': value1_of_type_T, 'b': value2_of_type_T }") for this rule, null
   *     if it takes the form "attrName = value_of_type_T", null if it doesn't exist
   * @throws IllegalArgumentException if the attribute is configurable but of the wrong type
   */
  @Nullable
  public final <T> SelectorList<T> getSelectorList(String attributeName, Type<T> type) {
    return getSelectorList(ruleClass, ruleLabel, attributes, attributeName, type);
  }

  @Nullable
  @SuppressWarnings("unchecked")
  protected static <T> SelectorList<T> getSelectorList(
      RuleClass ruleClass,
      Label ruleLabel,
      AttributeContainer attributes,
      String attributeName,
      Type<T> type) {
    Integer index = ruleClass.getAttributeIndex(attributeName);
    if (index == null) {
      return null;
    }
    Object attrValue = attributes.getAttributeValue(index);
    if (!(attrValue instanceof SelectorList)) {
      return null;
    }
    if (((SelectorList<?>) attrValue).getOriginalType() != type) {
      throw new IllegalArgumentException("Attribute " + attributeName + " is not of type " + type
          + " in " + ruleClass + " rule " + ruleLabel);
    }
    return (SelectorList<T>) attrValue;
  }

  /**
   * Returns the index of the specified attribute, if its type is 'type'. Throws
   * an exception otherwise.
   */
  private int getIndexWithTypeCheck(String attrName, Type<?> type) {
    Integer index = ruleClass.getAttributeIndex(attrName);
    if (index == null) {
      throw new IllegalArgumentException(
          "No such attribute " + attrName + " in " + ruleClass + " rule " + ruleLabel);
    }
    Attribute attr = ruleClass.getAttribute(index);
    if (attr.getType() != type) {
      throw new IllegalArgumentException(
          "Attribute " + attrName + " is of type " + attr.getType() + " and not of type " + type
              + " in " + ruleClass + " rule " + ruleLabel);
    }
    return index;
  }

  /**
   * Helper routine that just checks the given attribute has the given type for this rule and
   * throws an IllegalException if not.
   */
  protected void checkType(String attrName, Type<?> type) {
    getIndexWithTypeCheck(attrName, type);
  }

  @Override
  public boolean has(String attrName) {
    Attribute attribute = ruleClass.getAttributeByNameMaybe(attrName);
    return attribute != null;
  }

  @Override
  public <T> boolean has(String attrName, Type<T> type) {
    return getAttributeType(attrName) == type;
  }

  @Override
  public Location getAttributeLocation(String attrName) {
    return attributes.getAttributeLocation(attrName);
  }
}
