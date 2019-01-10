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

import static com.google.devtools.build.lib.rules.objc.ObjcProvider.NESTED_BUNDLE;
import static com.google.devtools.build.lib.rules.objc.ObjcRuleClasses.BundlingRule.FAMILIES_ATTR;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.MutableActionGraph.ActionConflictException;
import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.RuleConfiguredTargetFactory;
import com.google.devtools.build.lib.analysis.RuleContext;
import com.google.devtools.build.lib.analysis.configuredtargets.RuleConfiguredTarget.Mode;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.packages.RuleClass.ConfiguredTargetFactory.RuleErrorException;
import com.google.devtools.build.lib.rules.apple.AppleConfiguration;
import com.google.devtools.build.lib.rules.apple.ApplePlatform.PlatformType;
import com.google.devtools.build.lib.rules.apple.XcodeConfig;
import com.google.devtools.build.lib.rules.objc.BundleSupport.ExtraActoolArgs;
import com.google.devtools.build.lib.rules.objc.ObjcCommon.ResourceAttributes;
import com.google.devtools.build.lib.rules.objc.TargetDeviceFamily.InvalidFamilyNameException;
import com.google.devtools.build.lib.rules.objc.TargetDeviceFamily.RepeatedFamilyNameException;
import com.google.devtools.build.lib.syntax.Type;
import java.util.List;

/**
 * Implementation for {@code objc_bundle_library}.
 */
public class ObjcBundleLibrary implements RuleConfiguredTargetFactory {

  @VisibleForTesting
  static final String INVALID_FAMILIES_ERROR =
      "Expected one or two strings from the list 'iphone', 'ipad'";

  @VisibleForTesting
  static final String NO_ASSET_CATALOG_ERROR_FORMAT =
      "a value was specified (%s), but this app does not have any asset catalogs";

  @Override
  public ConfiguredTarget create(RuleContext ruleContext)
      throws InterruptedException, RuleErrorException, ActionConflictException {
    ObjcCommon common = common(ruleContext);
    Bundling bundling = bundling(ruleContext, common);

    NestedSetBuilder<Artifact> filesToBuild = NestedSetBuilder.stableOrder();

    new ResourceSupport(ruleContext).validateAttributes();

    if (ruleContext.hasErrors()) {
      return null;
    }

    AppleConfiguration appleConfiguration = ruleContext.getFragment(AppleConfiguration.class);

    // ApplePlatform is purposefully not validated on this BundleSupport. Multi-arch validation and
    // resource de-duplication should only take place at the level of the bundling rule.
    new BundleSupport(
            ruleContext,
            appleConfiguration,
            appleConfiguration.getSingleArchPlatform(),
            bundling,
            new ExtraActoolArgs())
        .validateResources(common.getObjcProvider())
        .registerActions(common.getObjcProvider());

    if (ruleContext.hasErrors()) {
      return null;
    }

    ObjcProvider nestedBundleProvider =
        new ObjcProvider.Builder(ruleContext.getAnalysisEnvironment().getSkylarkSemantics())
            .add(NESTED_BUNDLE, bundling)
            .build();

    return ObjcRuleClasses.ruleConfiguredTarget(ruleContext, filesToBuild.build())
        .addNativeDeclaredProvider(nestedBundleProvider)
        .build();
  }

  private Bundling bundling(RuleContext ruleContext, ObjcCommon common) {
    IntermediateArtifacts intermediateArtifacts =
        ObjcRuleClasses.intermediateArtifacts(ruleContext);
    AppleConfiguration appleConfiguration = ruleContext.getFragment(AppleConfiguration.class);

    ImmutableSet<TargetDeviceFamily> families = null;
    List<String> rawFamilies = ruleContext.attributes().get(FAMILIES_ATTR, Type.STRING_LIST);
    try {
      families = ImmutableSet.copyOf(TargetDeviceFamily.fromNamesInRule(rawFamilies));
    } catch (InvalidFamilyNameException | RepeatedFamilyNameException e) {
      families = ImmutableSet.of();
    }

    if (families.isEmpty()) {
      ruleContext.attributeError(FAMILIES_ATTR, INVALID_FAMILIES_ERROR);
    }

    return new Bundling.Builder()
        .setName(ruleContext.getLabel().getName())
        .setArchitecture(appleConfiguration.getIosCpu())
        .setBundleDirFormat("%s.bundle")
        .setObjcProvider(common.getObjcProvider())
        .addInfoplistInputFromRule(ruleContext)
        .setIntermediateArtifacts(intermediateArtifacts)
        .setMinimumOsVersion(XcodeConfig.getMinimumOsForPlatformType(ruleContext, PlatformType.IOS))
        .setTargetDeviceFamilies(families)
        .build();
  }

  private ObjcCommon common(RuleContext ruleContext) throws InterruptedException {
    return new ObjcCommon.Builder(ruleContext)
        .setResourceAttributes(new ResourceAttributes(ruleContext))
        .addDepObjcProviders(
            ruleContext.getPrerequisites("bundles", Mode.TARGET, ObjcProvider.SKYLARK_CONSTRUCTOR))
        .setIntermediateArtifacts(ObjcRuleClasses.intermediateArtifacts(ruleContext))
        .build();
  }
}
