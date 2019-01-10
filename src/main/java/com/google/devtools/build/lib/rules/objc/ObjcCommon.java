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

import static com.google.devtools.build.lib.packages.BuildType.LABEL;
import static com.google.devtools.build.lib.rules.objc.ObjcProvider.ASSET_CATALOG;
import static com.google.devtools.build.lib.rules.objc.ObjcProvider.BUNDLE_FILE;
import static com.google.devtools.build.lib.rules.objc.ObjcProvider.CC_LIBRARY;
import static com.google.devtools.build.lib.rules.objc.ObjcProvider.DEFINE;
import static com.google.devtools.build.lib.rules.objc.ObjcProvider.DYNAMIC_FRAMEWORK_DIR;
import static com.google.devtools.build.lib.rules.objc.ObjcProvider.DYNAMIC_FRAMEWORK_FILE;
import static com.google.devtools.build.lib.rules.objc.ObjcProvider.FLAG;
import static com.google.devtools.build.lib.rules.objc.ObjcProvider.FORCE_LOAD_LIBRARY;
import static com.google.devtools.build.lib.rules.objc.ObjcProvider.Flag.USES_CPP;
import static com.google.devtools.build.lib.rules.objc.ObjcProvider.Flag.USES_OBJC;
import static com.google.devtools.build.lib.rules.objc.ObjcProvider.HEADER;
import static com.google.devtools.build.lib.rules.objc.ObjcProvider.IMPORTED_LIBRARY;
import static com.google.devtools.build.lib.rules.objc.ObjcProvider.INCLUDE;
import static com.google.devtools.build.lib.rules.objc.ObjcProvider.INCLUDE_SYSTEM;
import static com.google.devtools.build.lib.rules.objc.ObjcProvider.IQUOTE;
import static com.google.devtools.build.lib.rules.objc.ObjcProvider.J2OBJC_LIBRARY;
import static com.google.devtools.build.lib.rules.objc.ObjcProvider.LIBRARY;
import static com.google.devtools.build.lib.rules.objc.ObjcProvider.LINKED_BINARY;
import static com.google.devtools.build.lib.rules.objc.ObjcProvider.LINKMAP_FILE;
import static com.google.devtools.build.lib.rules.objc.ObjcProvider.LINKOPT;
import static com.google.devtools.build.lib.rules.objc.ObjcProvider.MODULE_MAP;
import static com.google.devtools.build.lib.rules.objc.ObjcProvider.SDK_DYLIB;
import static com.google.devtools.build.lib.rules.objc.ObjcProvider.SDK_FRAMEWORK;
import static com.google.devtools.build.lib.rules.objc.ObjcProvider.SOURCE;
import static com.google.devtools.build.lib.rules.objc.ObjcProvider.STATIC_FRAMEWORK_FILE;
import static com.google.devtools.build.lib.rules.objc.ObjcProvider.STORYBOARD;
import static com.google.devtools.build.lib.rules.objc.ObjcProvider.STRINGS;
import static com.google.devtools.build.lib.rules.objc.ObjcProvider.TOP_LEVEL_MODULE_MAP;
import static com.google.devtools.build.lib.rules.objc.ObjcProvider.UMBRELLA_HEADER;
import static com.google.devtools.build.lib.rules.objc.ObjcProvider.WEAK_SDK_FRAMEWORK;
import static com.google.devtools.build.lib.rules.objc.ObjcProvider.XCASSETS_DIR;
import static com.google.devtools.build.lib.rules.objc.ObjcProvider.XCDATAMODEL;
import static com.google.devtools.build.lib.rules.objc.ObjcProvider.XIB;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.UnmodifiableIterator;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.RuleContext;
import com.google.devtools.build.lib.analysis.TransitiveInfoCollection;
import com.google.devtools.build.lib.analysis.config.BuildConfiguration;
import com.google.devtools.build.lib.analysis.configuredtargets.RuleConfiguredTarget.Mode;
import com.google.devtools.build.lib.packages.BuiltinProvider;
import com.google.devtools.build.lib.packages.Info;
import com.google.devtools.build.lib.rules.apple.AppleToolchain;
import com.google.devtools.build.lib.rules.cpp.CcCompilationContext;
import com.google.devtools.build.lib.rules.cpp.CcInfo;
import com.google.devtools.build.lib.rules.cpp.CcLinkParams;
import com.google.devtools.build.lib.rules.cpp.CcLinkingInfo;
import com.google.devtools.build.lib.rules.cpp.CppFileTypes;
import com.google.devtools.build.lib.rules.cpp.CppModuleMap;
import com.google.devtools.build.lib.skyframe.ConfiguredTargetAndData;
import com.google.devtools.build.lib.syntax.SkylarkSemantics;
import com.google.devtools.build.lib.util.FileType;
import com.google.devtools.build.lib.util.FileTypeSet;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Contains information common to multiple objc_* rules, and provides a unified API for extracting
 * and accessing it.
 */
// TODO(bazel-team): Decompose and subsume area-specific logic and data into the various *Support
// classes. Make sure to distinguish rule output (providers, runfiles, ...) from intermediate,
// rule-internal information. Any provider created by a rule should not be read, only published.
public final class ObjcCommon {

  /** Filters fileset artifacts out of a group of artifacts. */
  public static ImmutableList<Artifact> filterFileset(Iterable<Artifact> artifacts) {
    ImmutableList.Builder<Artifact> inputs = ImmutableList.<Artifact>builder();
    for (Artifact artifact : artifacts) {
      if (!artifact.isFileset()) {
        inputs.add(artifact);
      }
    }
    return inputs.build();
  }

  /**
   * Provides a way to access attributes that are common to all resources rules.
   */
  // TODO(bazel-team): Delete and move into support-specific attributes classes once ObjcCommon is
  // gone.
  static final class ResourceAttributes {
    private final RuleContext ruleContext;

    ResourceAttributes(RuleContext ruleContext) {
      this.ruleContext = ruleContext;
    }

    ImmutableList<Artifact> strings() {
      return ruleContext.getPrerequisiteArtifacts("strings", Mode.TARGET).list();
    }

    ImmutableList<Artifact> xibs() {
      return ruleContext.getPrerequisiteArtifacts("xibs", Mode.TARGET).list();
    }

    ImmutableList<Artifact> storyboards() {
      return ruleContext.getPrerequisiteArtifacts("storyboards", Mode.TARGET).list();
    }

    ImmutableList<Artifact> resources() {
      return ruleContext.getPrerequisiteArtifacts("resources", Mode.TARGET).list();
    }

    ImmutableList<Artifact> structuredResources() {
      return ruleContext.getPrerequisiteArtifacts("structured_resources", Mode.TARGET).list();
    }

    ImmutableList<Artifact> datamodels() {
      return ruleContext.getPrerequisiteArtifacts("datamodels", Mode.TARGET).list();
    }

    ImmutableList<Artifact> assetCatalogs() {
      return ruleContext.getPrerequisiteArtifacts("asset_catalogs", Mode.TARGET).list();
    }
  }

  static class Builder {
    private final RuleContext context;
    private final SkylarkSemantics semantics;
    private final BuildConfiguration buildConfiguration;
    private Optional<CompilationAttributes> compilationAttributes = Optional.absent();
    private Optional<ResourceAttributes> resourceAttributes = Optional.absent();
    private Iterable<SdkFramework> extraSdkFrameworks = ImmutableList.of();
    private Iterable<SdkFramework> extraWeakSdkFrameworks = ImmutableList.of();
    private Iterable<String> extraSdkDylibs = ImmutableList.of();
    private Iterable<Artifact> staticFrameworkImports = ImmutableList.of();
    private Iterable<Artifact> dynamicFrameworkImports = ImmutableList.of();
    private Optional<CompilationArtifacts> compilationArtifacts = Optional.absent();
    private ImmutableSet.Builder<Artifact> textualHeaders = ImmutableSet.builder();
    private Iterable<ObjcProvider> depObjcProviders = ImmutableList.of();
    private Iterable<ObjcProvider> runtimeDepObjcProviders = ImmutableList.of();
    private Iterable<ObjcProvider> repropagatedModuleMapObjcProviders = ImmutableList.of();
    private Iterable<String> defines = ImmutableList.of();
    private Iterable<PathFragment> includes = ImmutableList.of();
    private Iterable<PathFragment> directDependencyIncludes = ImmutableList.of();
    private IntermediateArtifacts intermediateArtifacts;
    private boolean alwayslink;
    private boolean hasModuleMap;
    private Iterable<Artifact> extraImportLibraries = ImmutableList.of();
    private Optional<Artifact> linkedBinary = Optional.absent();
    private Optional<Artifact> linkmapFile = Optional.absent();
    private Iterable<CcCompilationContext> depCcHeaderProviders = ImmutableList.of();
    private Iterable<CcLinkingInfo> depCcLinkProviders = ImmutableList.of();

    /**
     * Builder for {@link ObjcCommon} obtaining both attribute data and configuration data from
     * the given rule context.
     */
    Builder(RuleContext context) throws InterruptedException {
      this(context, context.getConfiguration());
    }

    /**
     * Builder for {@link ObjcCommon} obtaining attribute data from the rule context and
     * configuration data from the given configuration object for use in situations where a single
     * target's outputs are under multiple configurations.
     */
    Builder(RuleContext context, BuildConfiguration buildConfiguration)
        throws InterruptedException {
      this.context = Preconditions.checkNotNull(context);
      this.semantics = context.getAnalysisEnvironment().getSkylarkSemantics();
      this.buildConfiguration = Preconditions.checkNotNull(buildConfiguration);
    }

    public Builder setCompilationAttributes(CompilationAttributes baseCompilationAttributes) {
      Preconditions.checkState(
          !this.compilationAttributes.isPresent(),
          "compilationAttributes is already set to: %s",
          this.compilationAttributes);
      this.compilationAttributes = Optional.of(baseCompilationAttributes);
      return this;
    }

    public Builder setResourceAttributes(ResourceAttributes baseResourceAttributes) {
      Preconditions.checkState(
          !this.resourceAttributes.isPresent(),
          "resourceAttributes is already set to: %s",
          this.resourceAttributes);
      this.resourceAttributes = Optional.of(baseResourceAttributes);
      return this;
    }

    Builder addExtraSdkFrameworks(Iterable<SdkFramework> extraSdkFrameworks) {
      this.extraSdkFrameworks = Iterables.concat(this.extraSdkFrameworks, extraSdkFrameworks);
      return this;
    }

    Builder addExtraWeakSdkFrameworks(Iterable<SdkFramework> extraWeakSdkFrameworks) {
      this.extraWeakSdkFrameworks =
          Iterables.concat(this.extraWeakSdkFrameworks, extraWeakSdkFrameworks);
      return this;
    }

    Builder addExtraSdkDylibs(Iterable<String> extraSdkDylibs) {
      this.extraSdkDylibs = Iterables.concat(this.extraSdkDylibs, extraSdkDylibs);
      return this;
    }

    /**
     * Adds all given artifacts as members of static frameworks. They must be contained in
     * {@code .frameworks} directories and the binary in that framework should be statically linked.
     */
    Builder addStaticFrameworkImports(Iterable<Artifact> frameworkImports) {
      this.staticFrameworkImports = Iterables.concat(this.staticFrameworkImports, frameworkImports);
      return this;
    }

    /**
     * Adds all given artifacts as members of dynamic frameworks. They must be contained in
     * {@code .frameworks} directories and the binary in that framework should be dynamically
     * linked.
     */
    Builder addDynamicFrameworkImports(Iterable<Artifact> frameworkImports) {
      this.dynamicFrameworkImports =
          Iterables.concat(this.dynamicFrameworkImports, frameworkImports);
      return this;
    }

    Builder setCompilationArtifacts(CompilationArtifacts compilationArtifacts) {
      Preconditions.checkState(
          !this.compilationArtifacts.isPresent(),
          "compilationArtifacts is already set to: %s",
          this.compilationArtifacts);
      this.compilationArtifacts = Optional.of(compilationArtifacts);
      return this;
    }

    Builder addDeps(List<ConfiguredTargetAndData> deps) {
      ImmutableList.Builder<ObjcProvider> propagatedObjcDeps =
          ImmutableList.<ObjcProvider>builder();
      ImmutableList.Builder<CcInfo> cppDeps = ImmutableList.builder();
      ImmutableList.Builder<CcLinkingInfo> cppDepLinkParams = ImmutableList.builder();

      for (ConfiguredTargetAndData dep : deps) {
        ConfiguredTarget depCT = dep.getConfiguredTarget();
        addAnyProviders(propagatedObjcDeps, depCT, ObjcProvider.SKYLARK_CONSTRUCTOR);
        addAnyProviders(cppDeps, depCT, CcInfo.PROVIDER);
        if (isCcLibrary(dep)) {
          cppDepLinkParams.add(depCT.get(CcInfo.PROVIDER).getCcLinkingInfo());
          CcCompilationContext ccCompilationContext =
              depCT.get(CcInfo.PROVIDER).getCcCompilationContext();
          addDefines(ccCompilationContext.getDefines());
        }
      }
      ImmutableList.Builder<CcCompilationContext> ccCompilationContextBuilder =
          ImmutableList.builder();
      for (CcInfo ccInfo : cppDeps.build()) {
        ccCompilationContextBuilder.add(ccInfo.getCcCompilationContext());
      }
      addDepObjcProviders(propagatedObjcDeps.build());
      this.depCcHeaderProviders =
          Iterables.concat(this.depCcHeaderProviders, ccCompilationContextBuilder.build());
      this.depCcLinkProviders = Iterables.concat(this.depCcLinkProviders, cppDepLinkParams.build());
      return this;
    }

    /**
     * Adds providers for runtime frameworks included in the final app bundle but not linked with
     * at build time.
     */
    Builder addRuntimeDeps(List<? extends TransitiveInfoCollection> runtimeDeps) {
      ImmutableList.Builder<ObjcProvider> propagatedDeps =
          ImmutableList.<ObjcProvider>builder();

      for (TransitiveInfoCollection dep : runtimeDeps) {
        addAnyProviders(propagatedDeps, dep, ObjcProvider.SKYLARK_CONSTRUCTOR);
      }
      this.runtimeDepObjcProviders = Iterables.concat(
          this.runtimeDepObjcProviders, propagatedDeps.build());
      return this;
    }

    private <T extends Info> ImmutableList.Builder<T> addAnyProviders(
        ImmutableList.Builder<T> listBuilder,
        TransitiveInfoCollection collection,
        BuiltinProvider<T> providerClass) {
      T provider = collection.get(providerClass);
      if (provider != null) {
        listBuilder.add(provider);
      }
      return listBuilder;
    }

    /**
     * Add providers which will be exposed both to the declaring rule and to any dependers on the
     * declaring rule.
     */
    Builder addDepObjcProviders(Iterable<ObjcProvider> depObjcProviders) {
      this.depObjcProviders = Iterables.concat(this.depObjcProviders, depObjcProviders);
      return this;
    }

    /** Adds includes to be passed into compile actions with {@code -I}. */
    public Builder addIncludes(Iterable<PathFragment> includes) {
      this.includes = Iterables.concat(this.includes, includes);
      return this;
    }

    /** Adds header search paths that will only be visible by strict dependents of the provider. */
    public Builder addDirectDependencyIncludes(Iterable<PathFragment> directDependencyIncludes) {
      this.directDependencyIncludes =
          Iterables.concat(this.directDependencyIncludes, directDependencyIncludes);
      return this;
    }

    public Builder addDefines(Iterable<String> defines) {
      this.defines = Iterables.concat(this.defines, defines);
      return this;
    }

    Builder setIntermediateArtifacts(IntermediateArtifacts intermediateArtifacts) {
      this.intermediateArtifacts = intermediateArtifacts;
      return this;
    }

    Builder setAlwayslink(boolean alwayslink) {
      this.alwayslink = alwayslink;
      return this;
    }

    /**
     * Specifies that this target has a clang module map. This should be called if this target
     * compiles sources or exposes headers for other targets to use. Note that this does not add
     * the action to generate the module map. It simply indicates that it should be added to the
     * provider.
     */
    Builder setHasModuleMap() {
      this.hasModuleMap = true;
      return this;
    }

    /**
     * Adds Objc providers whose module maps should be repropagated as if they are directly
     * associated with the target propagating the provider being built.
     *
     * <p>This supports a small number of specialized use cases, like J2Objc, where the module maps
     * associated with the {@code java_library} (via an aspect) need to be repropagated by the
     * {@code j2objc_library} that depends on them so that Swift code can access those module maps
     * for the purposes of strict module map propagation (without propagating the module maps
     * _fully_ transitively).
     */
    Builder addRepropagatedModuleMapObjcProviders(Iterable<ObjcProvider> objcProviders) {
      this.repropagatedModuleMapObjcProviders =
          Iterables.concat(this.repropagatedModuleMapObjcProviders, objcProviders);
      return this;
    }

    /**
     * Adds additional static libraries to be linked into the final ObjC application bundle.
     */
    Builder addExtraImportLibraries(Iterable<Artifact> extraImportLibraries) {
      this.extraImportLibraries = Iterables.concat(this.extraImportLibraries, extraImportLibraries);
      return this;
    }

    /**
     * Sets a linked binary generated by this rule to be propagated to dependers.
     */
    Builder setLinkedBinary(Artifact linkedBinary) {
      this.linkedBinary = Optional.of(linkedBinary);
      return this;
    }

    /**
     * Sets a linkmap file generated by this rule to be propagated to dependers.
     */
    Builder setLinkmapFile(Artifact linkmapFile) {
      this.linkmapFile = Optional.of(linkmapFile);
      return this;
    }

    ObjcCommon build() {

      Iterable<BundleableFile> bundleImports = BundleableFile.bundleImportsFromRule(context);

      ObjcProvider.Builder objcProvider =
          new ObjcProvider.Builder(semantics)
              .addAll(IMPORTED_LIBRARY, extraImportLibraries)
              .addAll(BUNDLE_FILE, bundleImports)
              .addAll(SDK_FRAMEWORK, extraSdkFrameworks)
              .addAll(WEAK_SDK_FRAMEWORK, extraWeakSdkFrameworks)
              .addAll(SDK_DYLIB, extraSdkDylibs)
              .addAll(STATIC_FRAMEWORK_FILE, staticFrameworkImports)
              .addAll(DYNAMIC_FRAMEWORK_FILE, dynamicFrameworkImports)
              .addAll(
                  DYNAMIC_FRAMEWORK_DIR,
                  uniqueContainers(dynamicFrameworkImports, FRAMEWORK_CONTAINER_TYPE))
              .addAll(INCLUDE, includes)
              .add(IQUOTE, buildConfiguration.getGenfilesFragment())
              .addAllForDirectDependents(INCLUDE, directDependencyIncludes)
              .addAll(DEFINE, defines)
              .addTransitiveAndPropagate(depObjcProviders);

      for (ObjcProvider provider : runtimeDepObjcProviders) {
        objcProvider.addTransitiveAndPropagate(ObjcProvider.DYNAMIC_FRAMEWORK_FILE, provider);
        objcProvider.addTransitiveAndPropagate(ObjcProvider.STATIC_FRAMEWORK_FILE, provider);
        objcProvider.addTransitiveAndPropagate(ObjcProvider.MERGE_ZIP, provider);
      }

      for (CcCompilationContext headerProvider : depCcHeaderProviders) {
        objcProvider.addAll(HEADER, filterFileset(headerProvider.getDeclaredIncludeSrcs()));
        objcProvider.addAll(INCLUDE, headerProvider.getIncludeDirs());
        // TODO(bazel-team): This pulls in stl via
        // CppHelper.mergeToolchainDependentCcCompilationContext but
        // probably shouldn't.
        objcProvider.addAll(INCLUDE_SYSTEM, headerProvider.getSystemIncludeDirs());
        objcProvider.addAll(DEFINE, headerProvider.getDefines());
        textualHeaders.addAll(headerProvider.getTextualHdrs());
      }
      for (CcLinkingInfo linkProvider : depCcLinkProviders) {
        CcLinkParams params = linkProvider.getStaticModeParamsForExecutable();
        ImmutableList<String> linkOpts = params.flattenedLinkopts();
        ImmutableSet.Builder<SdkFramework> frameworkLinkOpts = new ImmutableSet.Builder<>();
        ImmutableList.Builder<String> nonFrameworkLinkOpts = new ImmutableList.Builder<>();
        // Add any framework flags as frameworks directly, rather than as linkopts.
        for (UnmodifiableIterator<String> iterator = linkOpts.iterator(); iterator.hasNext(); ) {
          String arg = iterator.next();
          if (arg.equals("-framework") && iterator.hasNext()) {
            String framework = iterator.next();
            frameworkLinkOpts.add(new SdkFramework(framework));
          } else {
            nonFrameworkLinkOpts.add(arg);
          }
        }

        objcProvider
            .addAll(SDK_FRAMEWORK, frameworkLinkOpts.build())
            .addAll(LINKOPT, nonFrameworkLinkOpts.build())
            .addTransitiveAndPropagate(CC_LIBRARY, params.getLibraries());
      }

      if (compilationAttributes.isPresent()) {
        CompilationAttributes attributes = compilationAttributes.get();
        Iterable<PathFragment> sdkIncludes =
            Iterables.transform(
                Interspersing.prependEach(
                    AppleToolchain.sdkDir() + "/usr/include/",
                    Iterables.transform(attributes.sdkIncludes(), PathFragment::getSafePathString)),
                PathFragment::create);
        objcProvider
            .addAll(HEADER, filterFileset(attributes.hdrs()))
            .addAll(HEADER, filterFileset(attributes.textualHdrs()))
            .addAll(INCLUDE, attributes.headerSearchPaths(buildConfiguration.getGenfilesFragment()))
            .addAll(INCLUDE, sdkIncludes)
            .addAll(SDK_FRAMEWORK, attributes.sdkFrameworks())
            .addAll(WEAK_SDK_FRAMEWORK, attributes.weakSdkFrameworks())
            .addAll(SDK_DYLIB, attributes.sdkDylibs());
      }

      if (resourceAttributes.isPresent()) {
        ResourceAttributes attributes = resourceAttributes.get();
        objcProvider
            .addAll(BUNDLE_FILE, BundleableFile.flattenedRawResourceFiles(attributes.resources()))
            .addAll(
                BUNDLE_FILE,
                BundleableFile.structuredRawResourceFiles(attributes.structuredResources()))
            .addAll(
                XCASSETS_DIR,
                uniqueContainers(attributes.assetCatalogs(), ASSET_CATALOG_CONTAINER_TYPE))
            .addAll(ASSET_CATALOG, attributes.assetCatalogs())
            .addAll(XCDATAMODEL, attributes.datamodels())
            .addAll(XIB, attributes.xibs())
            .addAll(STRINGS, attributes.strings())
            .addAll(STORYBOARD, attributes.storyboards());
      }

      if (useLaunchStoryboard(context)) {
        Artifact launchStoryboard =
            context.getPrerequisiteArtifact("launch_storyboard", Mode.TARGET);
        if (ObjcRuleClasses.STORYBOARD_TYPE.matches(launchStoryboard.getPath())) {
          objcProvider.add(STORYBOARD, launchStoryboard);
        } else {
          objcProvider.add(XIB, launchStoryboard);
        }
      }

      for (CompilationArtifacts artifacts : compilationArtifacts.asSet()) {
        Iterable<Artifact> allSources =
            Iterables.concat(artifacts.getSrcs(), artifacts.getNonArcSrcs());
        // TODO(bazel-team): Add private headers to the provider when we have module maps to enforce
        // them.
        objcProvider
            .addAll(HEADER, filterFileset(artifacts.getAdditionalHdrs()))
            .addAll(LIBRARY, artifacts.getArchive().asSet())
            .addAll(SOURCE, allSources);

        if (artifacts.getArchive().isPresent()
            && J2ObjcLibrary.J2OBJC_SUPPORTED_RULES.contains(context.getRule().getRuleClass())) {
          objcProvider.addAll(J2OBJC_LIBRARY, artifacts.getArchive().asSet());
        }

        boolean usesCpp = false;
        boolean usesObjc = false;
        for (Artifact sourceFile :
            Iterables.concat(artifacts.getSrcs(), artifacts.getNonArcSrcs())) {
          usesCpp = usesCpp || ObjcRuleClasses.CPP_SOURCES.matches(sourceFile.getExecPath());
          usesObjc =
              usesObjc
                  || FileTypeSet.of(CppFileTypes.OBJC_SOURCE, CppFileTypes.OBJCPP_SOURCE)
                      .matches(sourceFile.getExecPath().getPathString());
        }

        if (usesCpp) {
          objcProvider.add(FLAG, USES_CPP);
        }
        if (usesObjc) {
          objcProvider.add(FLAG, USES_OBJC);
        }
      }

      if (alwayslink) {
        for (CompilationArtifacts artifacts : compilationArtifacts.asSet()) {
          for (Artifact archive : artifacts.getArchive().asSet()) {
            objcProvider.add(FORCE_LOAD_LIBRARY, archive);
          }
        }
        for (Artifact archive : extraImportLibraries) {
          objcProvider.add(FORCE_LOAD_LIBRARY, archive);
        }
      }

      if (useStrictObjcModuleMaps(context)) {
        for (ObjcProvider provider : repropagatedModuleMapObjcProviders) {
          objcProvider.addAllForDirectDependents(MODULE_MAP, provider.get(ObjcProvider.MODULE_MAP));
          objcProvider.addAllForDirectDependents(
              TOP_LEVEL_MODULE_MAP, provider.get(ObjcProvider.TOP_LEVEL_MODULE_MAP));
        }
      }

      if (hasModuleMap) {
        CppModuleMap moduleMap = intermediateArtifacts.moduleMap();
        Optional<Artifact> umbrellaHeader = moduleMap.getUmbrellaHeader();
        if (umbrellaHeader.isPresent()) {
          objcProvider.add(UMBRELLA_HEADER, umbrellaHeader.get());
        }
        if (useStrictObjcModuleMaps(context)) {
          objcProvider.addForDirectDependents(MODULE_MAP, moduleMap.getArtifact());
          objcProvider.addForDirectDependents(TOP_LEVEL_MODULE_MAP, moduleMap);
        } else {
          objcProvider.add(MODULE_MAP, moduleMap.getArtifact());
          objcProvider.add(TOP_LEVEL_MODULE_MAP, moduleMap);
        }
      }

      objcProvider
          .addAll(LINKED_BINARY, linkedBinary.asSet())
          .addAll(LINKMAP_FILE, linkmapFile.asSet());

      return new ObjcCommon(objcProvider.build(), compilationArtifacts, textualHeaders.build());
    }

    private static boolean useStrictObjcModuleMaps(RuleContext context) {
      // We need to check isLegalFragment first because some non-compilation rules, like
      // objc_bundle_library, don't declare this fragment.
      return context.isLegalFragment(ObjcConfiguration.class)
          && context.getFragment(ObjcConfiguration.class).useStrictObjcModuleMaps();
    }

    private static boolean isCcLibrary(ConfiguredTargetAndData info) {
      try {
        String targetName = info.getTarget().getTargetKind();

        for (String ruleClassName : ObjcRuleClasses.CompilingRule.ALLOWED_CC_DEPS_RULE_CLASSES) {
          if (targetName.equals(ruleClassName + " rule")) {
            return true;
          }
        }
        return false;
      } catch (Exception e) {
        return false;
      }
    }

    /**
     * Returns {@code true} if the given rule context has a launch storyboard set.
     */
    private static boolean useLaunchStoryboard(RuleContext ruleContext) {
      if (!ruleContext.attributes().has("launch_storyboard", LABEL)) {
        return false;
      }
      Artifact launchStoryboard =
          ruleContext.getPrerequisiteArtifact("launch_storyboard", Mode.TARGET);
      return launchStoryboard != null;
    }
  }

  static final FileType BUNDLE_CONTAINER_TYPE = FileType.of(".bundle");

  static final FileType ASSET_CATALOG_CONTAINER_TYPE = FileType.of(".xcassets");

  public static final FileType FRAMEWORK_CONTAINER_TYPE = FileType.of(".framework");
  private final ObjcProvider objcProvider;

  private final Optional<CompilationArtifacts> compilationArtifacts;
  private final ImmutableSet<Artifact> textualHdrs;

  private ObjcCommon(
      ObjcProvider objcProvider,
      Optional<CompilationArtifacts> compilationArtifacts,
      ImmutableSet<Artifact> textualHdrs) {
    this.objcProvider = Preconditions.checkNotNull(objcProvider);
    this.compilationArtifacts = Preconditions.checkNotNull(compilationArtifacts);
    this.textualHdrs = textualHdrs;
  }

  public ObjcProvider getObjcProvider() {
    return objcProvider;
  }

  public Optional<CompilationArtifacts> getCompilationArtifacts() {
    return compilationArtifacts;
  }

  public ImmutableSet<Artifact> getTextualHdrs() {
    return textualHdrs;
  }

  /**
   * Returns an {@link Optional} containing the compiled {@code .a} file, or
   * {@link Optional#absent()} if this object contains no {@link CompilationArtifacts} or the
   * compilation information has no sources.
   */
  public Optional<Artifact> getCompiledArchive() {
    if (compilationArtifacts.isPresent()) {
      return compilationArtifacts.get().getArchive();
    }
    return Optional.absent();
  }

  /**
   * Returns effective compilation options that do not arise from the crosstool.
   */
  static Iterable<String> getNonCrosstoolCopts(RuleContext ruleContext) {
    return Iterables.concat(
        ruleContext.getFragment(ObjcConfiguration.class).getCopts(),
        ruleContext.getExpander().withDataLocations().tokenized("copts"));
  }

  static ImmutableSet<PathFragment> userHeaderSearchPaths(
      ObjcProvider provider, BuildConfiguration config) {
    return ImmutableSet.<PathFragment>builder()
        .add(PathFragment.create("."))
        .add(config.getGenfilesFragment())
        .addAll(provider.get(IQUOTE))
        .build();
  }

  /**
   * Returns the first directory in the sequence of parents of the exec path of the given artifact
   * that matches {@code type}. For instance, if {@code type} is FileType.of(".foo") and the exec
   * path of {@code artifact} is {@code a/b/c/bar.foo/d/e}, then the return value is
   * {@code a/b/c/bar.foo}.
   */
  static Optional<PathFragment> nearestContainerMatching(FileType type, Artifact artifact) {
    PathFragment container = artifact.getExecPath();
    do {
      if (type.matches(container)) {
        return Optional.of(container);
      }
      container = container.getParentDirectory();
    } while (container != null);
    return Optional.absent();
  }

  /**
   * Similar to {@link #nearestContainerMatching(FileType, Artifact)}, but tries matching several
   * file types in {@code types}, and returns a path for the first match in the sequence.
   */
  static Optional<PathFragment> nearestContainerMatching(
      Iterable<FileType> types, Artifact artifact) {
    for (FileType type : types) {
      for (PathFragment container : nearestContainerMatching(type, artifact).asSet()) {
        return Optional.of(container);
      }
    }
    return Optional.absent();
  }

  /**
   * Returns all directories matching {@code containerType} that contain the items in
   * {@code artifacts}. This function ignores artifacts that are not in any directory matching
   * {@code containerType}.
   */
  static Iterable<PathFragment> uniqueContainers(
      Iterable<Artifact> artifacts, FileType containerType) {
    ImmutableSet.Builder<PathFragment> containers = new ImmutableSet.Builder<>();
    for (Artifact artifact : artifacts) {
      containers.addAll(ObjcCommon.nearestContainerMatching(containerType, artifact).asSet());
    }
    return containers.build();
  }

  /**
   * Similar to {@link #nearestContainerMatching(FileType, Artifact)}, but returns the container
   * closest to the root that matches the given type.
   */
  static Optional<PathFragment> farthestContainerMatching(FileType type, Artifact artifact) {
    PathFragment container = artifact.getExecPath();
    Optional<PathFragment> lastMatch = Optional.absent();
    do {
      if (type.matches(container)) {
        lastMatch = Optional.of(container);
      }
      container = container.getParentDirectory();
    } while (container != null);
    return lastMatch;
  }

  static Iterable<String> notInContainerErrors(
      Iterable<Artifact> artifacts, FileType containerType) {
    return notInContainerErrors(artifacts, ImmutableList.of(containerType));
  }

  static Iterable<String> notInContainerErrors(
      Iterable<Artifact> artifacts, Iterable<FileType> containerTypes) {
    Set<String> errors = new HashSet<>();
    for (Artifact artifact : artifacts) {
      boolean inContainer = nearestContainerMatching(containerTypes, artifact).isPresent();
      if (!inContainer) {
        errors.add(
            String.format(
                NOT_IN_CONTAINER_ERROR_FORMAT,
                artifact.getExecPath(),
                Iterables.toString(containerTypes)));
      }
    }
    return errors;
  }

  @VisibleForTesting
  static final String NOT_IN_CONTAINER_ERROR_FORMAT =
      "File '%s' is not in a directory of one of these type(s): %s";
}
