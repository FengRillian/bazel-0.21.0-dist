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
package com.google.devtools.build.lib.skyframe;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.actions.MutableActionGraph.ActionConflictException;
import com.google.devtools.build.lib.analysis.AnalysisEnvironment;
import com.google.devtools.build.lib.analysis.ConfiguredAspect;
import com.google.devtools.build.lib.analysis.ConfiguredAspectFactory;
import com.google.devtools.build.lib.analysis.RuleContext;
import com.google.devtools.build.lib.analysis.SkylarkProviderValidationUtil;
import com.google.devtools.build.lib.analysis.skylark.SkylarkRuleConfiguredTargetUtil;
import com.google.devtools.build.lib.analysis.skylark.SkylarkRuleContext;
import com.google.devtools.build.lib.events.Location;
import com.google.devtools.build.lib.packages.AspectDescriptor;
import com.google.devtools.build.lib.packages.AspectParameters;
import com.google.devtools.build.lib.packages.InfoInterface;
import com.google.devtools.build.lib.packages.SkylarkDefinedAspect;
import com.google.devtools.build.lib.packages.StructImpl;
import com.google.devtools.build.lib.packages.StructProvider;
import com.google.devtools.build.lib.packages.Target;
import com.google.devtools.build.lib.skylarkinterface.SkylarkValue;
import com.google.devtools.build.lib.syntax.Environment;
import com.google.devtools.build.lib.syntax.EvalException;
import com.google.devtools.build.lib.syntax.EvalExceptionWithStackTrace;
import com.google.devtools.build.lib.syntax.EvalUtils;
import com.google.devtools.build.lib.syntax.Mutability;
import com.google.devtools.build.lib.syntax.SkylarkType;
import java.util.Map;

/** A factory for aspects that are defined in Skylark. */
public class SkylarkAspectFactory implements ConfiguredAspectFactory {

  private final SkylarkDefinedAspect skylarkAspect;

  public SkylarkAspectFactory(SkylarkDefinedAspect skylarkAspect) {
    this.skylarkAspect = skylarkAspect;
  }

  @Override
  public ConfiguredAspect create(
      ConfiguredTargetAndData ctadBase, RuleContext ruleContext, AspectParameters parameters)
      throws InterruptedException, ActionConflictException {
    SkylarkRuleContext skylarkRuleContext = null;
    try (Mutability mutability = Mutability.create("aspect")) {
      AspectDescriptor aspectDescriptor =
          new AspectDescriptor(skylarkAspect.getAspectClass(), parameters);
      AnalysisEnvironment analysisEnv = ruleContext.getAnalysisEnvironment();
      try {
        skylarkRuleContext =
            new SkylarkRuleContext(
                ruleContext, aspectDescriptor, analysisEnv.getSkylarkSemantics());
      } catch (EvalException e) {
        ruleContext.ruleError(e.getMessage());
        return null;
      }
      Environment env =
          Environment.builder(mutability)
              .setSemantics(analysisEnv.getSkylarkSemantics())
              .setEventHandler(analysisEnv.getEventHandler())
              // NB: loading phase functions are not available: this is analysis already, so we do
              // *not* setLoadingPhase().
              .build();
      Object aspectSkylarkObject;
      try {
        aspectSkylarkObject =
            skylarkAspect
                .getImplementation()
                .call(
                    /*args=*/ ImmutableList.of(ctadBase.getConfiguredTarget(), skylarkRuleContext),
                    /* kwargs= */ ImmutableMap.of(),
                    /*ast=*/ null,
                    env);

        if (ruleContext.hasErrors()) {
          return null;
        } else if (!(aspectSkylarkObject instanceof StructImpl)
            && !(aspectSkylarkObject instanceof Iterable)
            && !(aspectSkylarkObject instanceof InfoInterface)) {
          ruleContext.ruleError(
              String.format("Aspect implementation should return a struct, a list, or a provider "
                      + "instance, but got %s",
                  SkylarkType.typeOf(aspectSkylarkObject)));
          return null;
        }
        return createAspect(aspectSkylarkObject, aspectDescriptor, ruleContext);
      } catch (EvalException e) {
        addAspectToStackTrace(ctadBase.getTarget(), e);
        ruleContext.ruleError("\n" + e.print());
        return null;
      }
    } finally {
      if (skylarkRuleContext != null) {
        skylarkRuleContext.nullify();
      }
    }
  }

  private ConfiguredAspect createAspect(
      Object aspectSkylarkObject, AspectDescriptor aspectDescriptor, RuleContext ruleContext)
      throws EvalException, ActionConflictException {

    ConfiguredAspect.Builder builder = new ConfiguredAspect.Builder(aspectDescriptor, ruleContext);

    if (aspectSkylarkObject instanceof Iterable) {
      addDeclaredProviders(builder, (Iterable) aspectSkylarkObject);
    } else {
      // Either an old-style struct or a single declared provider (not in a list)
      InfoInterface info = (InfoInterface) aspectSkylarkObject;
      Location loc = info.getCreationLoc();
      if (info.getProvider().getKey().equals(StructProvider.STRUCT.getKey())) {
        // Old-style struct, that may contain declared providers.
        StructImpl struct = (StructImpl) aspectSkylarkObject;
        for (String field : struct.getFieldNames()) {
          if (field.equals("output_groups")) {
            addOutputGroups(struct.getValue(field), loc, builder);
          } else if (field.equals("providers")) {
            Object value = struct.getValue(field);
            Iterable providers =
                SkylarkType.cast(
                    value,
                    Iterable.class,
                    loc,
                    "The value for \"providers\" should be a list of declared providers, "
                        + "got %s instead",
                    EvalUtils.getDataTypeName(value, false));
            addDeclaredProviders(builder, providers);
          } else {
            builder.addSkylarkTransitiveInfo(field, struct.getValue(field), loc);
          }
        }
      } else {
        builder.addSkylarkDeclaredProvider(info);
      }
    }

    ConfiguredAspect configuredAspect = builder.build();
    SkylarkProviderValidationUtil.validateArtifacts(ruleContext);
    return configuredAspect;
  }

  private void addDeclaredProviders(ConfiguredAspect.Builder builder, Iterable aspectSkylarkObject)
      throws EvalException {
    int i = 0;
    for (Object o : aspectSkylarkObject) {
      Location loc = skylarkAspect.getImplementation().getLocation();
      InfoInterface declaredProvider =
          SkylarkType.cast(
              o,
              InfoInterface.class,
              loc,
              "A return value of an aspect implementation function should be "
                  + "a sequence of declared providers, instead got a %s at index %d",
              o.getClass(),
              i);
      builder.addSkylarkDeclaredProvider(declaredProvider);
      i++;
    }
  }

  private static void addOutputGroups(Object value, Location loc, ConfiguredAspect.Builder builder)
      throws EvalException {
    Map<String, SkylarkValue> outputGroups =
        SkylarkType.castMap(value, String.class, SkylarkValue.class, "output_groups");

    for (String outputGroup : outputGroups.keySet()) {
      SkylarkValue objects = outputGroups.get(outputGroup);

      builder.addOutputGroup(
          outputGroup,
          SkylarkRuleConfiguredTargetUtil.convertToOutputGroupValue(loc, outputGroup, objects));
    }
  }

  private void addAspectToStackTrace(Target base, EvalException e) {
    if (e instanceof EvalExceptionWithStackTrace) {
      ((EvalExceptionWithStackTrace) e)
          .registerPhantomFuncall(
              String.format("%s(...)", skylarkAspect.getName()),
              base.getAssociatedRule().getLocation(),
              skylarkAspect.getImplementation());
    }
  }
}
