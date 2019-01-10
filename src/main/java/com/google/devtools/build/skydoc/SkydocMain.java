// Copyright 2018 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.skydoc;

import com.google.common.base.Functions;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.LabelSyntaxException;
import com.google.devtools.build.lib.events.EventHandler;
import com.google.devtools.build.lib.packages.SkylarkSemanticsOptions;
import com.google.devtools.build.lib.skylarkbuildapi.TopLevelBootstrap;
import com.google.devtools.build.lib.skylarkbuildapi.android.AndroidAssetsInfoApi;
import com.google.devtools.build.lib.skylarkbuildapi.android.AndroidBinaryDataInfoApi;
import com.google.devtools.build.lib.skylarkbuildapi.android.AndroidBootstrap;
import com.google.devtools.build.lib.skylarkbuildapi.android.AndroidCcLinkParamsProviderApi;
import com.google.devtools.build.lib.skylarkbuildapi.android.AndroidDex2OatInfoApi;
import com.google.devtools.build.lib.skylarkbuildapi.android.AndroidFeatureFlagSetProviderApi;
import com.google.devtools.build.lib.skylarkbuildapi.android.AndroidIdeInfoProviderApi;
import com.google.devtools.build.lib.skylarkbuildapi.android.AndroidIdlProviderApi;
import com.google.devtools.build.lib.skylarkbuildapi.android.AndroidLibraryAarInfoApi;
import com.google.devtools.build.lib.skylarkbuildapi.android.AndroidLibraryResourceClassJarProviderApi;
import com.google.devtools.build.lib.skylarkbuildapi.android.AndroidManifestInfoApi;
import com.google.devtools.build.lib.skylarkbuildapi.android.AndroidPreDexJarProviderApi;
import com.google.devtools.build.lib.skylarkbuildapi.android.AndroidProguardInfoApi;
import com.google.devtools.build.lib.skylarkbuildapi.android.AndroidSdkProviderApi;
import com.google.devtools.build.lib.skylarkbuildapi.android.ProguardMappingProviderApi;
import com.google.devtools.build.lib.skylarkbuildapi.android.UsesDataBindingProviderApi;
import com.google.devtools.build.lib.skylarkbuildapi.apple.AppleBootstrap;
import com.google.devtools.build.lib.skylarkbuildapi.config.ConfigBootstrap;
import com.google.devtools.build.lib.skylarkbuildapi.cpp.CcBootstrap;
import com.google.devtools.build.lib.skylarkbuildapi.java.GeneratedExtensionRegistryProviderApi;
import com.google.devtools.build.lib.skylarkbuildapi.java.JavaBootstrap;
import com.google.devtools.build.lib.skylarkbuildapi.platform.PlatformBootstrap;
import com.google.devtools.build.lib.skylarkbuildapi.repository.RepositoryBootstrap;
import com.google.devtools.build.lib.skylarkbuildapi.test.TestingBootstrap;
import com.google.devtools.build.lib.syntax.BaseFunction;
import com.google.devtools.build.lib.syntax.BuildFileAST;
import com.google.devtools.build.lib.syntax.Environment;
import com.google.devtools.build.lib.syntax.Environment.Extension;
import com.google.devtools.build.lib.syntax.Environment.GlobalFrame;
import com.google.devtools.build.lib.syntax.MethodLibrary;
import com.google.devtools.build.lib.syntax.Mutability;
import com.google.devtools.build.lib.syntax.ParserInputSource;
import com.google.devtools.build.lib.syntax.Runtime;
import com.google.devtools.build.lib.syntax.SkylarkImport;
import com.google.devtools.build.lib.syntax.SkylarkSemantics;
import com.google.devtools.build.lib.syntax.UserDefinedFunction;
import com.google.devtools.build.skydoc.fakebuildapi.FakeActionsInfoProvider;
import com.google.devtools.build.skydoc.fakebuildapi.FakeBuildApiGlobals;
import com.google.devtools.build.skydoc.fakebuildapi.FakeConfigApi;
import com.google.devtools.build.skydoc.fakebuildapi.FakeDefaultInfoProvider;
import com.google.devtools.build.skydoc.fakebuildapi.FakeOutputGroupInfo.FakeOutputGroupInfoProvider;
import com.google.devtools.build.skydoc.fakebuildapi.FakeSkylarkAttrApi;
import com.google.devtools.build.skydoc.fakebuildapi.FakeSkylarkCommandLineApi;
import com.google.devtools.build.skydoc.fakebuildapi.FakeSkylarkNativeModuleApi;
import com.google.devtools.build.skydoc.fakebuildapi.FakeSkylarkRuleFunctionsApi;
import com.google.devtools.build.skydoc.fakebuildapi.FakeStructApi.FakeStructProviderApi;
import com.google.devtools.build.skydoc.fakebuildapi.android.FakeAndroidDeviceBrokerInfo.FakeAndroidDeviceBrokerInfoProvider;
import com.google.devtools.build.skydoc.fakebuildapi.android.FakeAndroidInstrumentationInfo.FakeAndroidInstrumentationInfoProvider;
import com.google.devtools.build.skydoc.fakebuildapi.android.FakeAndroidNativeLibsInfo.FakeAndroidNativeLibsInfoProvider;
import com.google.devtools.build.skydoc.fakebuildapi.android.FakeAndroidResourcesInfo.FakeAndroidResourcesInfoProvider;
import com.google.devtools.build.skydoc.fakebuildapi.android.FakeAndroidSkylarkCommon;
import com.google.devtools.build.skydoc.fakebuildapi.android.FakeApkInfo.FakeApkInfoProvider;
import com.google.devtools.build.skydoc.fakebuildapi.apple.FakeAppleCommon;
import com.google.devtools.build.skydoc.fakebuildapi.config.FakeConfigGlobalLibrary;
import com.google.devtools.build.skydoc.fakebuildapi.config.FakeConfigSkylarkCommon;
import com.google.devtools.build.skydoc.fakebuildapi.cpp.FakeCcModule;
import com.google.devtools.build.skydoc.fakebuildapi.java.FakeJavaCcLinkParamsProvider;
import com.google.devtools.build.skydoc.fakebuildapi.java.FakeJavaCommon;
import com.google.devtools.build.skydoc.fakebuildapi.java.FakeJavaInfo.FakeJavaInfoProvider;
import com.google.devtools.build.skydoc.fakebuildapi.java.FakeJavaProtoCommon;
import com.google.devtools.build.skydoc.fakebuildapi.platform.FakePlatformCommon;
import com.google.devtools.build.skydoc.fakebuildapi.repository.FakeRepositoryModule;
import com.google.devtools.build.skydoc.fakebuildapi.test.FakeAnalysisFailureInfoProvider;
import com.google.devtools.build.skydoc.fakebuildapi.test.FakeAnalysisTestResultInfoProvider;
import com.google.devtools.build.skydoc.fakebuildapi.test.FakeTestingModule;
import com.google.devtools.build.skydoc.rendering.MarkdownRenderer;
import com.google.devtools.build.skydoc.rendering.ProviderInfo;
import com.google.devtools.build.skydoc.rendering.RuleInfo;
import com.google.devtools.build.skydoc.rendering.UserDefinedFunctionInfo;
import com.google.devtools.build.skydoc.rendering.UserDefinedFunctionInfo.DocstringParseException;
import com.google.devtools.common.options.OptionsParser;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Main entry point for the Skydoc binary.
 *
 * <p>Skydoc generates human-readable documentation for relevant details of skylark files by
 * running a skylark interpreter with a fake implementation of the build API.</p>
 *
 * <p>Currently, Skydoc generates documentation for skylark rule definitions (discovered by
 * invocations of the build API function {@code rule()}.</p>
 *
 * <p>Usage:</p>
 * <pre>
 *   skydoc {target_skylark_file_label} {output_file} [symbol_name]...
 * </pre>
 * <p>
 *   Generates documentation for all exported symbols of the target skylark file that are
 *   specified in the list of symbol names. If no symbol names are supplied, outputs documentation
 *   for all exported symbols in the target skylark file.
 * </p>
 */
public class SkydocMain {

  private final EventHandler eventHandler = new SystemOutEventHandler();
  private final LinkedHashSet<Path> pending = new LinkedHashSet<>();
  private final Map<Path, Environment> loaded = new HashMap<>();
  private final SkylarkFileAccessor fileAccessor;

  public SkydocMain(SkylarkFileAccessor fileAccessor) {
    this.fileAccessor = fileAccessor;
  }

  public static void main(String[] args)
      throws IOException, InterruptedException, LabelSyntaxException {
    OptionsParser parser =
        OptionsParser.newOptionsParser(SkylarkSemanticsOptions.class, SkydocOptions.class);
    parser.parseAndExitUponError(args);
    SkylarkSemanticsOptions semanticsOptions = parser.getOptions(SkylarkSemanticsOptions.class);
    SkydocOptions skydocOptions = parser.getOptions(SkydocOptions.class);

    String targetFileLabelString;
    String outputPath;
    ImmutableSet<String> symbolNames;

    // TODO(cparsons): Remove optional positional arg parsing.
    List<String> residualArgs = parser.getResidue();
    if (Strings.isNullOrEmpty(skydocOptions.targetFileLabel)
        || Strings.isNullOrEmpty(skydocOptions.outputFilePath)) {
      if (residualArgs.size() < 2) {
        throw new IllegalArgumentException(
            "Expected two or more arguments. Usage:\n"
                + "{skydoc_bin} {target_skylark_file_label} {output_file} [symbol_names]...");
      }

      targetFileLabelString = residualArgs.get(0);
      outputPath = residualArgs.get(1);
      symbolNames = getSymbolNames(residualArgs);
    } else {
      targetFileLabelString = skydocOptions.targetFileLabel;
      outputPath = skydocOptions.outputFilePath;
      symbolNames = ImmutableSet.copyOf(skydocOptions.symbolNames);
    }

    Label targetFileLabel =
        Label.parseAbsolute(targetFileLabelString, ImmutableMap.of());

    ImmutableMap.Builder<String, RuleInfo> ruleInfoMap = ImmutableMap.builder();
    ImmutableMap.Builder<String, ProviderInfo> providerInfoMap = ImmutableMap.builder();
    ImmutableList.Builder<RuleInfo> unknownNamedRules = ImmutableList.builder();
    ImmutableMap.Builder<String, UserDefinedFunction> userDefinedFunctions = ImmutableMap.builder();

    new SkydocMain(new FilesystemFileAccessor())
        .eval(
            semanticsOptions.toSkylarkSemantics(),
            targetFileLabel,
            ruleInfoMap,
            unknownNamedRules,
            providerInfoMap,
            userDefinedFunctions);

    MarkdownRenderer renderer = new MarkdownRenderer();

    if (symbolNames.isEmpty()) {
      try (PrintWriter printWriter = new PrintWriter(outputPath, "UTF-8")) {
        printRuleInfos(printWriter, renderer, ruleInfoMap.build(), unknownNamedRules.build());
        printProviderInfos(printWriter, renderer, providerInfoMap.build());
        printUserDefinedFunctions(printWriter, renderer, userDefinedFunctions.build());
      }
    } else {
      Map<String, RuleInfo> filteredRuleInfos =
          ruleInfoMap.build().entrySet().stream()
              .filter(entry -> symbolNames.contains(entry.getKey()))
              .collect(ImmutableMap.toImmutableMap(Entry::getKey, Entry::getValue));
      Map<String, ProviderInfo> filteredProviderInfos =
          providerInfoMap.build().entrySet().stream()
              .filter(entry -> symbolNames.contains(entry.getKey()))
              .collect(ImmutableMap.toImmutableMap(Entry::getKey, Entry::getValue));
      Map<String, UserDefinedFunction> filteredUserDefinedFunctions =
          userDefinedFunctions.build().entrySet().stream()
              .filter(entry -> symbolNames.contains(entry.getKey()))
              .collect(ImmutableMap.toImmutableMap(Entry::getKey, Entry::getValue));
      try (PrintWriter printWriter = new PrintWriter(outputPath, "UTF-8")) {
        printRuleInfos(printWriter, renderer, filteredRuleInfos, ImmutableList.of());
        printProviderInfos(printWriter, renderer, filteredProviderInfos);
        printUserDefinedFunctions(printWriter, renderer, filteredUserDefinedFunctions);
      }
    }
  }

  private static ImmutableSet<String> getSymbolNames(List<String> args) {
    ImmutableSet.Builder<String> symbolNameSet = ImmutableSet.builder();
    for (int argi = 2; argi < args.size(); argi++) {
      symbolNameSet.add(args.get(argi));
    }
    return symbolNameSet.build();
  }

  private static void printRuleInfos(
      PrintWriter printWriter,
      MarkdownRenderer renderer,
      Map<String, RuleInfo> ruleInfos,
      List<RuleInfo> unknownNamedRules) throws IOException {
    for (Entry<String, RuleInfo> ruleInfoEntry : ruleInfos.entrySet()) {
      printRuleInfo(printWriter, renderer, ruleInfoEntry.getKey(), ruleInfoEntry.getValue());
      printWriter.println();
    }
    for (RuleInfo unknownNamedRule : unknownNamedRules) {
      printRuleInfo(printWriter, renderer, "<unknown name>", unknownNamedRule);
      printWriter.println();
    }
  }

  private static void printProviderInfos(
      PrintWriter printWriter,
      MarkdownRenderer renderer,
      Map<String, ProviderInfo> providerInfos) throws IOException {
    for (Entry<String, ProviderInfo> entry : providerInfos.entrySet()) {
      printProviderInfo(printWriter, renderer, entry.getKey(), entry.getValue());
      printWriter.println();
    }
  }

  private static void printUserDefinedFunctions(
      PrintWriter printWriter,
      MarkdownRenderer renderer,
      Map<String, UserDefinedFunction> userDefinedFunctions)
      throws IOException {
    for (Entry<String, UserDefinedFunction> entry : userDefinedFunctions.entrySet()) {
      try {
        UserDefinedFunctionInfo functionInfo =
            UserDefinedFunctionInfo.fromNameAndFunction(entry.getKey(), entry.getValue());
        printUserDefinedFunctionInfo(printWriter, renderer, functionInfo);
        printWriter.println();
      } catch (DocstringParseException exception) {
        System.err.println(exception.getMessage());
        System.err.println();
      }
    }
  }

  private static void printRuleInfo(
      PrintWriter printWriter, MarkdownRenderer renderer,
      String exportedName, RuleInfo ruleInfo) throws IOException {
    printWriter.println(renderer.render(exportedName, ruleInfo));
  }

  private static void printProviderInfo(
      PrintWriter printWriter, MarkdownRenderer renderer,
      String exportedName, ProviderInfo providerInfo) throws IOException {
    printWriter.println(renderer.render(exportedName, providerInfo));
  }

  private static void printUserDefinedFunctionInfo(
      PrintWriter printWriter, MarkdownRenderer renderer, UserDefinedFunctionInfo functionInfo)
      throws IOException {
    printWriter.println(renderer.render(functionInfo));
  }

  /**
   * Evaluates/interprets the skylark file at a given path and its transitive skylark dependencies
   * using a fake build API and collects information about all rule definitions made in the root
   * skylark file.
   *
   * @param label the label of the skylark file to evaluate
   * @param ruleInfoMap a map builder to be populated with rule definition information for named
   *     rules. Keys are exported names of rules, and values are their {@link RuleInfo} rule
   *     descriptions. For example, 'my_rule = rule(...)' has key 'my_rule'
   * @param unknownNamedRules a list builder to be populated with rule definition information for
   *     rules which were not exported as top level symbols
   * @param providerInfoMap a map builder to be populated with provider definition information for
   *     named providers. Keys are exported names of providers, and values are their {@link
   *     ProviderInfo} descriptions. For example, 'my_provider = provider(...)' has key
   *     'my_provider'
   * @param userDefinedFunctionMap a map builder to be populated with user-defined functions. Keys
   *     are exported names of functions, and values are the {@link UserDefinedFunction} objects.
   *     For example, 'def my_function(foo):' is a function with key 'my_function'.
   * @throws InterruptedException if evaluation is interrupted
   */
  public Environment eval(
      SkylarkSemantics semantics,
      Label label,
      ImmutableMap.Builder<String, RuleInfo> ruleInfoMap,
      ImmutableList.Builder<RuleInfo> unknownNamedRules,
      ImmutableMap.Builder<String, ProviderInfo> providerInfoMap,
      ImmutableMap.Builder<String, UserDefinedFunction> userDefinedFunctionMap)
      throws InterruptedException, IOException, LabelSyntaxException {

    List<RuleInfo> ruleInfoList = new ArrayList<>();
    List<ProviderInfo> providerInfoList = new ArrayList<>();
    Environment env = recursiveEval(semantics, label, ruleInfoList, providerInfoList);

    Map<BaseFunction, RuleInfo> ruleFunctions = ruleInfoList.stream()
        .collect(Collectors.toMap(
            RuleInfo::getIdentifierFunction,
            Functions.identity()));
    Map<BaseFunction, ProviderInfo> providerInfos = providerInfoList.stream()
        .collect(Collectors.toMap(
            ProviderInfo::getIdentifier,
            Functions.identity()));

    ImmutableSet.Builder<RuleInfo> handledRuleDefinitions = ImmutableSet.builder();

    // Sort the bindings so their ordering is deterministic.
    TreeMap<String, Object> sortedBindings = new TreeMap<>(env.getGlobals().getExportedBindings());

    for (Entry<String, Object> envEntry : sortedBindings.entrySet()) {
      if (ruleFunctions.containsKey(envEntry.getValue())) {
        RuleInfo ruleInfo = ruleFunctions.get(envEntry.getValue());
        ruleInfoMap.put(envEntry.getKey(), ruleInfo);
        handledRuleDefinitions.add(ruleInfo);
      }
      if (providerInfos.containsKey(envEntry.getValue())) {
        ProviderInfo providerInfo = providerInfos.get(envEntry.getValue());
        providerInfoMap.put(envEntry.getKey(), providerInfo);
      }
      if (envEntry.getValue() instanceof UserDefinedFunction) {
        UserDefinedFunction userDefinedFunction = (UserDefinedFunction) envEntry.getValue();
        userDefinedFunctionMap.put(envEntry.getKey(), userDefinedFunction);
      }
    }

    unknownNamedRules.addAll(ruleFunctions.values().stream()
        .filter(ruleInfo -> !handledRuleDefinitions.build().contains(ruleInfo)).iterator());

    return env;
  }

  /**
   * Recursively evaluates/interprets the skylark file at a given path and its transitive skylark
   * dependencies using a fake build API and collects information about all rule definitions made in
   * those files.
   *
   * @param label the label of the skylark file to evaluate
   * @param ruleInfoList a collection of all rule definitions made so far (using rule()); this
   *     method will add to this list as it evaluates additional files
   * @throws InterruptedException if evaluation is interrupted
   */
  private Environment recursiveEval(
      SkylarkSemantics semantics,
      Label label,
      List<RuleInfo> ruleInfoList,
      List<ProviderInfo> providerInfoList)
      throws InterruptedException, IOException, LabelSyntaxException {
    Path path = pathOfLabel(label);

    if (pending.contains(path)) {
      throw new IllegalStateException("cycle with " + path);
    } else if (loaded.containsKey(path)) {
      return loaded.get(path);
    }
    pending.add(path);

    ParserInputSource parserInputSource = fileAccessor.inputSource(path.toString());
    BuildFileAST buildFileAST = BuildFileAST.parseSkylarkFile(parserInputSource, eventHandler);

    Map<String, Extension> imports = new HashMap<>();
    for (SkylarkImport anImport : buildFileAST.getImports()) {
      Label relativeLabel = label.getRelative(anImport.getImportString());

      try {
        Environment importEnv =
            recursiveEval(semantics, relativeLabel, ruleInfoList, providerInfoList);
        imports.put(anImport.getImportString(), new Extension(importEnv));
      } catch (NoSuchFileException noSuchFileException) {
        throw new IllegalStateException(
            String.format("File %s imported '%s', yet %s was not found.",
                path, anImport.getImportString(), pathOfLabel(relativeLabel)));
      }
    }

    Environment env =
        evalSkylarkBody(semantics, buildFileAST, imports, ruleInfoList, providerInfoList);

    pending.remove(path);
    env.mutability().freeze();
    loaded.put(path, env);
    return env;
  }

  private Path pathOfLabel(Label label) {
    String workspacePrefix = label.getWorkspaceRoot().isEmpty()
        ? ""
        : label.getWorkspaceRoot() + "/";

    return Paths.get(workspacePrefix + label.toPathFragment());
  }

  /** Evaluates the AST from a single skylark file, given the already-resolved imports. */
  private Environment evalSkylarkBody(
      SkylarkSemantics semantics,
      BuildFileAST buildFileAST,
      Map<String, Extension> imports,
      List<RuleInfo> ruleInfoList,
      List<ProviderInfo> providerInfoList)
      throws InterruptedException {

    Environment env =
        createEnvironment(
            semantics, eventHandler, globalFrame(ruleInfoList, providerInfoList), imports);

    if (!buildFileAST.exec(env, eventHandler)) {
      throw new RuntimeException("Error loading file");
    }

    env.mutability().freeze();

    return env;
  }

  /**
   * Initialize and return a global frame containing the fake build API.
   *
   * @param ruleInfoList the list of {@link RuleInfo} objects, to which rule() invocation
   *     information will be added
   * @param providerInfoList the list of {@link ProviderInfo} objects, to which provider()
   *     invocation information will be added
   */
  private static GlobalFrame globalFrame(List<RuleInfo> ruleInfoList,
      List<ProviderInfo> providerInfoList) {
    TopLevelBootstrap topLevelBootstrap =
        new TopLevelBootstrap(new FakeBuildApiGlobals(),
            new FakeSkylarkAttrApi(),
            new FakeSkylarkCommandLineApi(),
            new FakeSkylarkNativeModuleApi(),
            new FakeSkylarkRuleFunctionsApi(ruleInfoList, providerInfoList),
            new FakeStructProviderApi(),
            new FakeOutputGroupInfoProvider(),
            new FakeActionsInfoProvider(),
            new FakeDefaultInfoProvider());
    AndroidBootstrap androidBootstrap = new AndroidBootstrap(new FakeAndroidSkylarkCommon(),
        new FakeApkInfoProvider(),
        new FakeAndroidInstrumentationInfoProvider(),
        new FakeAndroidDeviceBrokerInfoProvider(),
        new FakeAndroidResourcesInfoProvider(),
        new FakeAndroidNativeLibsInfoProvider());
    AppleBootstrap appleBootstrap = new AppleBootstrap(new FakeAppleCommon());
    ConfigBootstrap configBootstrap =
        new ConfigBootstrap(new FakeConfigSkylarkCommon(), new FakeConfigApi(),
            new FakeConfigGlobalLibrary());
    CcBootstrap ccBootstrap = new CcBootstrap(new FakeCcModule());
    JavaBootstrap javaBootstrap =
        new JavaBootstrap(
            new FakeJavaCommon(),
            new FakeJavaInfoProvider(),
            new FakeJavaProtoCommon(),
            new FakeJavaCcLinkParamsProvider.Provider());
    PlatformBootstrap platformBootstrap = new PlatformBootstrap(new FakePlatformCommon());
    RepositoryBootstrap repositoryBootstrap = new RepositoryBootstrap(new FakeRepositoryModule());
    TestingBootstrap testingBootstrap = new TestingBootstrap(new FakeTestingModule(),
        new FakeAnalysisFailureInfoProvider(),
        new FakeAnalysisTestResultInfoProvider());

    ImmutableMap.Builder<String, Object> envBuilder = ImmutableMap.builder();

    Runtime.addConstantsToBuilder(envBuilder);
    MethodLibrary.addBindingsToBuilder(envBuilder);
    topLevelBootstrap.addBindingsToBuilder(envBuilder);
    androidBootstrap.addBindingsToBuilder(envBuilder);
    appleBootstrap.addBindingsToBuilder(envBuilder);
    ccBootstrap.addBindingsToBuilder(envBuilder);
    configBootstrap.addBindingsToBuilder(envBuilder);
    javaBootstrap.addBindingsToBuilder(envBuilder);
    platformBootstrap.addBindingsToBuilder(envBuilder);
    repositoryBootstrap.addBindingsToBuilder(envBuilder);
    testingBootstrap.addBindingsToBuilder(envBuilder);
    addNonBootstrapGlobals(envBuilder);

    return GlobalFrame.createForBuiltins(envBuilder.build());
  }

  // TODO(cparsons): Remove this constant by migrating the contained symbols to bootstraps.
  private static final String[] nonBootstrapGlobals = {"android_data", AndroidDex2OatInfoApi.NAME,
      AndroidManifestInfoApi.NAME, AndroidAssetsInfoApi.NAME,
      AndroidLibraryAarInfoApi.NAME, AndroidProguardInfoApi.NAME,
      AndroidIdlProviderApi.NAME, AndroidIdeInfoProviderApi.NAME,
      AndroidPreDexJarProviderApi.NAME, UsesDataBindingProviderApi.NAME,
      AndroidCcLinkParamsProviderApi.NAME, AndroidLibraryResourceClassJarProviderApi.NAME,
      AndroidSdkProviderApi.NAME, AndroidFeatureFlagSetProviderApi.NAME,
      ProguardMappingProviderApi.NAME, GeneratedExtensionRegistryProviderApi.NAME,
      AndroidBinaryDataInfoApi.NAME };

  /**
   * A hack to add a number of global symbols which are part of the build API but are otherwise
   * added by Bazel.
   */
  // TODO(cparsons): Remove this method by migrating the contained symbols to bootstraps.
  private static void addNonBootstrapGlobals(ImmutableMap.Builder<String, Object> envBuilder) {
    for (String global : nonBootstrapGlobals) {
      envBuilder.put(global, global);
    }
  }

  private static Environment createEnvironment(
      SkylarkSemantics semantics,
      EventHandler eventHandler,
      GlobalFrame globals,
      Map<String, Extension> imports) {
    return Environment.builder(Mutability.create("Skydoc"))
        .setSemantics(semantics)
        .setGlobals(globals)
        .setImportedExtensions(imports)
        .setEventHandler(eventHandler)
        .build();
  }
}
