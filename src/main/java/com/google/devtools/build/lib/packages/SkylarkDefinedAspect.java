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

package com.google.devtools.build.lib.packages;

import com.google.common.base.Function;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.analysis.config.transitions.ConfigurationTransition;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.events.Location;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.AutoCodec;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.AutoCodec.VisibleForSerialization;
import com.google.devtools.build.lib.skylarkinterface.SkylarkPrinter;
import com.google.devtools.build.lib.syntax.BaseFunction;
import com.google.devtools.build.lib.syntax.EvalException;
import com.google.devtools.build.lib.syntax.Type;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/** A Skylark value that is a result of an 'aspect(..)' function call. */
@AutoCodec
public class SkylarkDefinedAspect implements SkylarkExportable, SkylarkAspect {
  private final BaseFunction implementation;
  private final ImmutableList<String> attributeAspects;
  private final ImmutableList<Attribute> attributes;
  private final ImmutableList<ImmutableSet<SkylarkProviderIdentifier>> requiredAspectProviders;
  private final ImmutableSet<SkylarkProviderIdentifier> provides;
  private final ImmutableSet<String> paramAttributes;
  private final ImmutableSet<String> fragments;
  private final ConfigurationTransition hostTransition;
  private final ImmutableSet<String> hostFragments;
  private final ImmutableList<Label> requiredToolchains;

  private SkylarkAspectClass aspectClass;

  public SkylarkDefinedAspect(
      BaseFunction implementation,
      ImmutableList<String> attributeAspects,
      ImmutableList<Attribute> attributes,
      ImmutableList<ImmutableSet<SkylarkProviderIdentifier>> requiredAspectProviders,
      ImmutableSet<SkylarkProviderIdentifier> provides,
      ImmutableSet<String> paramAttributes,
      ImmutableSet<String> fragments,
      // The host transition is in lib.analysis, so we can't reference it directly here.
      ConfigurationTransition hostTransition,
      ImmutableSet<String> hostFragments,
      ImmutableList<Label> requiredToolchains) {
    this.implementation = implementation;
    this.attributeAspects = attributeAspects;
    this.attributes = attributes;
    this.requiredAspectProviders = requiredAspectProviders;
    this.provides = provides;
    this.paramAttributes = paramAttributes;
    this.fragments = fragments;
    this.hostTransition = hostTransition;
    this.hostFragments = hostFragments;
    this.requiredToolchains = requiredToolchains;
  }

  /** Constructor for post export reconstruction for serialization. */
  @VisibleForSerialization
  @AutoCodec.Instantiator
  SkylarkDefinedAspect(
      BaseFunction implementation,
      ImmutableList<String> attributeAspects,
      ImmutableList<Attribute> attributes,
      ImmutableList<ImmutableSet<SkylarkProviderIdentifier>> requiredAspectProviders,
      ImmutableSet<SkylarkProviderIdentifier> provides,
      ImmutableSet<String> paramAttributes,
      ImmutableSet<String> fragments,
      // The host transition is in lib.analysis, so we can't reference it directly here.
      ConfigurationTransition hostTransition,
      ImmutableSet<String> hostFragments,
      ImmutableList<Label> requiredToolchains,
      SkylarkAspectClass aspectClass) {
    this(implementation, attributeAspects, attributes, requiredAspectProviders, provides,
        paramAttributes, fragments, hostTransition, hostFragments, requiredToolchains);
    this.aspectClass = aspectClass;
  }

  public BaseFunction getImplementation() {
    return implementation;
  }

  public ImmutableList<String> getAttributeAspects() {
    return attributeAspects;
  }

  public ImmutableList<Attribute> getAttributes() {
    return attributes;
  }

  @Override
  public boolean isImmutable() {
    return implementation.isImmutable();
  }

  @Override
  public void repr(SkylarkPrinter printer) {
    printer.append("<aspect>");
  }

  @Override
  public String getName() {
    return getAspectClass().getName();
  }

  @Override
  public SkylarkAspectClass getAspectClass() {
    Preconditions.checkState(isExported());
    return aspectClass;
  }

  @Override
  public ImmutableSet<String> getParamAttributes() {
    return paramAttributes;
  }

  @Override
  public void export(Label extensionLabel, String name) {
    Preconditions.checkArgument(!isExported());
    this.aspectClass = new SkylarkAspectClass(extensionLabel, name);
  }

  private static final List<String> allAttrAspects = Arrays.asList("*");

  public AspectDefinition getDefinition(AspectParameters aspectParams) {
    AspectDefinition.Builder builder = new AspectDefinition.Builder(aspectClass);
    if (allAttrAspects.equals(attributeAspects)) {
      builder.propagateAlongAllAttributes();
    } else {
      for (String attributeAspect : attributeAspects) {
        builder.propagateAlongAttribute(attributeAspect);
      }
    }
    
    for (Attribute attribute : attributes) {
      Attribute attr = attribute;  // Might be reassigned.
      if (!aspectParams.getAttribute(attr.getName()).isEmpty()) {
        String value = aspectParams.getOnlyValueOfAttribute(attr.getName());
        Preconditions.checkState(!Attribute.isImplicit(attr.getName()));
        Preconditions.checkState(attr.getType() == Type.STRING);
        Preconditions.checkArgument(aspectParams.getAttribute(attr.getName()).size() == 1,
            String.format("Aspect %s parameter %s has %d values (must have exactly 1).",
                          getName(),
                          attr.getName(),
                          aspectParams.getAttribute(attr.getName()).size()));
        attr = attr.cloneBuilder(Type.STRING).value(value).build(attr.getName());
      }
      builder.add(attr);
    }
    builder.requireAspectsWithProviders(requiredAspectProviders);
    ImmutableList.Builder<SkylarkProviderIdentifier> advertisedSkylarkProviders =
        ImmutableList.builder();
    for (SkylarkProviderIdentifier provider : provides) {
      advertisedSkylarkProviders.add(provider);
    }
    builder.advertiseProvider(advertisedSkylarkProviders.build());
    builder.requiresConfigurationFragmentsBySkylarkModuleName(fragments);
    builder.requiresConfigurationFragmentsBySkylarkModuleName(hostTransition, hostFragments);
    builder.addRequiredToolchains(requiredToolchains);
    return builder.build();
  }

  @Override
  public boolean isExported() {
    return aspectClass != null;
  }

  public Function<Rule, AspectParameters> getDefaultParametersExtractor() {
    return rule -> {
      AttributeMap ruleAttrs = RawAttributeMapper.of(rule);
      AspectParameters.Builder builder = new AspectParameters.Builder();
      for (Attribute aspectAttr : attributes) {
        if (!Attribute.isImplicit(aspectAttr.getName())) {
          String param = aspectAttr.getName();
          Attribute ruleAttr = ruleAttrs.getAttributeDefinition(param);
          if (paramAttributes.contains(aspectAttr.getName())) {
            // These are preconditions because if they are false, RuleFunction.call() should
            // already have generated an error.
            Preconditions.checkArgument(
                ruleAttr != null,
                String.format(
                    "Cannot apply aspect %s to %s that does not define attribute '%s'.",
                    getName(), rule.getTargetKind(), param));
            Preconditions.checkArgument(
                ruleAttr.getType() == Type.STRING,
                String.format(
                    "Cannot apply aspect %s to %s with non-string attribute '%s'.",
                    getName(), rule.getTargetKind(), param));
          }
          if (ruleAttr != null && ruleAttr.getType() == aspectAttr.getType()) {
            builder.addAttribute(param, (String) ruleAttrs.get(param, ruleAttr.getType()));
          }
        }
      }
      return builder.build();
    };
  }

  public ImmutableList<Label> getRequiredToolchains() {
    return requiredToolchains;
  }

  @Override
  public void attachToAttribute(Attribute.Builder<?> attrBuilder, Location loc)
      throws EvalException {
    if (!isExported()) {
      throw new EvalException(
          loc, "Aspects should be top-level values in extension files that define them.");
    }
    attrBuilder.aspect(this, loc);
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    SkylarkDefinedAspect that = (SkylarkDefinedAspect) o;
    return Objects.equals(implementation, that.implementation)
        && Objects.equals(attributeAspects, that.attributeAspects)
        && Objects.equals(attributes, that.attributes)
        && Objects.equals(requiredAspectProviders, that.requiredAspectProviders)
        && Objects.equals(provides, that.provides)
        && Objects.equals(paramAttributes, that.paramAttributes)
        && Objects.equals(fragments, that.fragments)
        && Objects.equals(hostTransition, that.hostTransition)
        && Objects.equals(hostFragments, that.hostFragments)
        && Objects.equals(requiredToolchains, that.requiredToolchains)
        && Objects.equals(aspectClass, that.aspectClass);
  }

  @Override
  public int hashCode() {
    return Objects.hash(
        implementation,
        attributeAspects,
        attributes,
        requiredAspectProviders,
        provides,
        paramAttributes,
        fragments,
        hostTransition,
        hostFragments,
        requiredToolchains,
        aspectClass);
  }
}
