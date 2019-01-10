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

package com.google.devtools.build.lib.rules.objc;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.MutableActionGraph.ActionConflictException;
import com.google.devtools.build.lib.analysis.RuleContext;
import com.google.devtools.build.lib.analysis.skylark.SkylarkRuleContext;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.collect.nestedset.Order;
import com.google.devtools.build.lib.events.Location;
import com.google.devtools.build.lib.packages.Attribute.SplitTransitionProvider;
import com.google.devtools.build.lib.packages.NativeProvider;
import com.google.devtools.build.lib.packages.Provider;
import com.google.devtools.build.lib.packages.RuleClass.ConfiguredTargetFactory.RuleErrorException;
import com.google.devtools.build.lib.packages.SkylarkAspect;
import com.google.devtools.build.lib.packages.SkylarkInfo;
import com.google.devtools.build.lib.packages.StructImpl;
import com.google.devtools.build.lib.rules.apple.AppleConfiguration;
import com.google.devtools.build.lib.rules.apple.ApplePlatform;
import com.google.devtools.build.lib.rules.apple.ApplePlatform.PlatformType;
import com.google.devtools.build.lib.rules.apple.AppleToolchain;
import com.google.devtools.build.lib.rules.apple.DottedVersion;
import com.google.devtools.build.lib.rules.apple.XcodeConfigProvider;
import com.google.devtools.build.lib.rules.apple.XcodeVersionProperties;
import com.google.devtools.build.lib.rules.objc.AppleBinary.AppleBinaryOutput;
import com.google.devtools.build.lib.rules.objc.ObjcProvider.Key;
import com.google.devtools.build.lib.skylarkbuildapi.SkylarkRuleContextApi;
import com.google.devtools.build.lib.skylarkbuildapi.apple.AppleCommonApi;
import com.google.devtools.build.lib.skylarkinterface.SkylarkValue;
import com.google.devtools.build.lib.syntax.Environment;
import com.google.devtools.build.lib.syntax.EvalException;
import com.google.devtools.build.lib.syntax.Runtime;
import com.google.devtools.build.lib.syntax.SkylarkDict;
import com.google.devtools.build.lib.syntax.SkylarkNestedSet;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * A class that exposes apple rule implementation internals to skylark.
 */
public class AppleSkylarkCommon
    implements AppleCommonApi<Artifact, ObjcProvider, XcodeConfigProvider, ApplePlatform> {

  @VisibleForTesting
  public static final String BAD_KEY_ERROR = "Argument %s not a recognized key, 'providers',"
      + " or 'direct_dep_providers'.";

  @VisibleForTesting
  public static final String BAD_SET_TYPE_ERROR =
      "Value for key %s must be a set of %s, instead found set of %s.";

  @VisibleForTesting
  public static final String BAD_PROVIDERS_ITER_ERROR =
      "Value for argument 'providers' must be a list of ObjcProvider instances, instead found %s.";

  @VisibleForTesting
  public static final String BAD_PROVIDERS_ELEM_ERROR =
      "Value for argument 'providers' must be a list of ObjcProvider instances, instead found "
          + "iterable with %s.";

  @VisibleForTesting
  public static final String NOT_SET_ERROR = "Value for key %s must be a set, instead found %s.";

  @VisibleForTesting
  public static final String MISSING_KEY_ERROR = "No value for required key %s was present.";

  @Nullable private StructImpl platformType;
  @Nullable private StructImpl platform;

  private ObjcProtoAspect objcProtoAspect;

  public AppleSkylarkCommon(ObjcProtoAspect objcProtoAspect) {
    this.objcProtoAspect = objcProtoAspect;
  }

  @Override
  public AppleToolchain getAppleToolchain() {
    return new AppleToolchain();
  }

  @Override
  public StructImpl getPlatformTypeStruct() {
    if (platformType == null) {
      platformType = PlatformType.getSkylarkStruct();
    }
    return platformType;
  }

  @Override
  public StructImpl getPlatformStruct() {
    if (platform == null) {
      platform = ApplePlatform.getSkylarkStruct();
    }
    return platform;
  }

  @Override
  public Provider getXcodeVersionPropertiesConstructor() {
    return XcodeVersionProperties.SKYLARK_CONSTRUCTOR;
  }

  @Override
  public Provider getXcodeVersionConfigConstructor() {
    return XcodeConfigProvider.PROVIDER;
  }

  @Override
  public Provider getObjcProviderConstructor() {
    return ObjcProvider.SKYLARK_CONSTRUCTOR;
  }

  @Override
  public Provider getAppleDynamicFrameworkConstructor() {
    return AppleDynamicFrameworkInfo.SKYLARK_CONSTRUCTOR;
  }

  @Override
  public Provider getAppleDylibBinaryConstructor() {
    return AppleDylibBinaryInfo.SKYLARK_CONSTRUCTOR;
  }

  @Override
  public Provider getAppleExecutableBinaryConstructor() {
    return AppleExecutableBinaryInfo.SKYLARK_CONSTRUCTOR;
  }

  @Override
  public AppleStaticLibraryInfo.Provider getAppleStaticLibraryProvider() {
    return AppleStaticLibraryInfo.SKYLARK_CONSTRUCTOR;
  }

  @Override
  public Provider getAppleDebugOutputsConstructor() {
    return AppleDebugOutputsInfo.SKYLARK_CONSTRUCTOR;
  }

  @Override
  public Provider getAppleLoadableBundleBinaryConstructor() {
    return AppleLoadableBundleBinaryInfo.SKYLARK_CONSTRUCTOR;
  }

  @Override
  public ImmutableMap<String, String> getAppleHostSystemEnv(XcodeConfigProvider xcodeConfig) {
    return AppleConfiguration.getXcodeVersionEnv(xcodeConfig.getXcodeVersion());
  }

  @Override
  public ImmutableMap<String, String> getTargetAppleEnvironment(
      XcodeConfigProvider xcodeConfigApi, ApplePlatform platformApi) {
    XcodeConfigProvider xcodeConfig = (XcodeConfigProvider) xcodeConfigApi;
    ApplePlatform platform = (ApplePlatform) platformApi;
    return AppleConfiguration.appleTargetPlatformEnv(
        platform, xcodeConfig.getSdkVersionForPlatform(platform));
  }

  @Override
  public SplitTransitionProvider getMultiArchSplitProvider() {
    return new MultiArchSplitTransitionProvider();
  }

  @Override
  // This method is registered statically for skylark, and never called directly.
  public ObjcProvider newObjcProvider(
      Boolean usesSwift,
      SkylarkDict<?, ?> kwargs,
      Environment environment) {
    boolean disableObjcResourceKeys =
        environment.getSemantics().incompatibleDisableObjcProviderResources();
    ObjcProvider.Builder resultBuilder = new ObjcProvider.Builder(environment.getSemantics());
    if (usesSwift) {
      resultBuilder.add(ObjcProvider.FLAG, ObjcProvider.Flag.USES_SWIFT);
    }
    for (Map.Entry<?, ?> entry : kwargs.entrySet()) {
      Key<?> key = ObjcProvider.getSkylarkKeyForString((String) entry.getKey());
      if (key != null) {
        if (disableObjcResourceKeys && ObjcProvider.isDeprecatedResourceKey(key)) {
          throw new IllegalArgumentException(String.format(BAD_KEY_ERROR, entry.getKey()));
        }
        resultBuilder.addElementsFromSkylark(key, entry.getValue());
      } else if (entry.getKey().equals("providers")) {
        resultBuilder.addProvidersFromSkylark(entry.getValue());
      } else if (entry.getKey().equals("direct_dep_providers")) {
        resultBuilder.addDirectDepProvidersFromSkylark(entry.getValue());
      } else {
        throw new IllegalArgumentException(String.format(BAD_KEY_ERROR, entry.getKey()));
      }
    }
    return resultBuilder.build();
  }

  @Override
  public AppleDynamicFrameworkInfo newDynamicFrameworkProvider(
      Object dylibBinary,
      ObjcProvider depsObjcProvider,
      Object dynamicFrameworkDirs,
      Object dynamicFrameworkFiles) {
    NestedSet<PathFragment> frameworkDirs;
    if (dynamicFrameworkDirs == Runtime.NONE) {
      frameworkDirs = NestedSetBuilder.<PathFragment>emptySet(Order.STABLE_ORDER);
    } else {
      Iterable<String> pathStrings =
          ((SkylarkNestedSet) dynamicFrameworkDirs).getSet(String.class);
      frameworkDirs =
          NestedSetBuilder.<PathFragment>stableOrder()
              .addAll(Iterables.transform(pathStrings, PathFragment::create))
              .build();
    }
    NestedSet<Artifact> frameworkFiles =
        dynamicFrameworkFiles != Runtime.NONE
            ? ((SkylarkNestedSet) dynamicFrameworkFiles).getSet(Artifact.class)
            : NestedSetBuilder.<Artifact>emptySet(Order.STABLE_ORDER);
    Artifact binary = (dylibBinary != Runtime.NONE) ? (Artifact) dylibBinary : null;

    return new AppleDynamicFrameworkInfo(
        binary, depsObjcProvider, frameworkDirs, frameworkFiles);
  }

  @Override
  public StructImpl linkMultiArchBinary(
      SkylarkRuleContextApi skylarkRuleContextApi, Environment environment)
      throws EvalException, InterruptedException {
    SkylarkRuleContext skylarkRuleContext = (SkylarkRuleContext) skylarkRuleContextApi;
    try {
      RuleContext ruleContext = skylarkRuleContext.getRuleContext();
      AppleBinaryOutput appleBinaryOutput = AppleBinary.linkMultiArchBinary(ruleContext);
      return createAppleBinaryOutputSkylarkStruct(appleBinaryOutput, environment);
    } catch (RuleErrorException | ActionConflictException exception) {
      throw new EvalException(null, exception);
    }
  }

  @Override
  public DottedVersion dottedVersion(String version) {
    return DottedVersion.fromString(version);
  }

  @Override
  public SkylarkAspect getObjcProtoAspect() {
    return objcProtoAspect;
  }

  /**
   * Creates a Skylark struct that contains the results of the {@code link_multi_arch_binary}
   * function.
   */
  private StructImpl createAppleBinaryOutputSkylarkStruct(
      AppleBinaryOutput output, Environment environment) {
    Provider constructor =
        new NativeProvider<StructImpl>(StructImpl.class, "apple_binary_output") {};
    // We have to transform the output group dictionary into one that contains SkylarkValues instead
    // of plain NestedSets because the Skylark caller may want to return this directly from their
    // implementation function.
    Map<String, SkylarkValue> outputGroups =
        Maps.transformValues(output.getOutputGroups(), v -> SkylarkNestedSet.of(Artifact.class, v));

    ImmutableMap<String, Object> fields =
        ImmutableMap.of(
            "binary_provider", output.getBinaryInfoProvider(),
            "debug_outputs_provider", output.getDebugOutputsProvider(),
            "output_groups", SkylarkDict.copyOf(environment, outputGroups));
    return SkylarkInfo.createSchemaless(constructor, fields, Location.BUILTIN);
  }
}
