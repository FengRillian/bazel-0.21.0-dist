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
// Copyright 2006 Google Inc. All rights reserved.

package com.google.devtools.build.lib.rules.cpp;

import static com.google.common.truth.Truth.assertThat;

import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.util.AnalysisMock;
import com.google.devtools.build.lib.analysis.util.BuildViewTestCase;
import com.google.devtools.build.lib.packages.util.MockCcSupport;
import com.google.devtools.build.lib.rules.cpp.LinkerInputs.LibraryToLink;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** "White-box" unit test of cc_import rule. */
@RunWith(JUnit4.class)
public abstract class CcImportBaseConfiguredTargetTest extends BuildViewTestCase {
  protected String skylarkImplementationLoadStatement = "";

  @Before
  public void setSkylarkImplementationLoadStatement() throws Exception {
    setSkylarkSemanticsOptions(SkylarkCcCommonTestHelper.CC_SKYLARK_WHITELIST_FLAG);
    invalidatePackages();
    setIsSkylarkImplementation();
  }

  protected abstract void setIsSkylarkImplementation();

  @Test
  public void testCcImportRule() throws Exception {
    scratch.file(
        "third_party/BUILD",
        skylarkImplementationLoadStatement,
        "cc_import(",
        "  name = 'a_import',",
        "  static_library = 'A.a',",
        "  shared_library = 'A.so',",
        "  interface_library = 'A.ifso',",
        "  hdrs = ['a.h'],",
        "  alwayslink = 1,",
        "  system_provided = 0,",
        ")");
    getConfiguredTarget("//third_party:a_import");
  }

  @Test
  public void testWrongCcImportDefinitions() throws Exception {
    checkError(
        "a",
        "foo",
        "does not produce any cc_import static_library files " + "(expected .a, .lib or .pic.a)",
        skylarkImplementationLoadStatement,
        "cc_import(",
        "  name = 'foo',",
        "  static_library = 'libfoo.so',",
        ")");
    checkError(
        "b",
        "foo",
        "does not produce any cc_import shared_library files (expected .so, .dylib or .dll)",
        skylarkImplementationLoadStatement,
        "cc_import(",
        "  name = 'foo',",
        "  shared_library = 'libfoo.a',",
        ")");
    checkError(
        "c",
        "foo",
        "does not produce any cc_import interface_library files "
            + "(expected .ifso, .tbd, .lib, .so or .dylib)",
        skylarkImplementationLoadStatement,
        "cc_import(",
        "  name = 'foo',",
        "  shared_library = 'libfoo.dll',",
        "  interface_library = 'libfoo.a',",
        ")");
    checkError(
        "d",
        "foo",
        "'shared_library' shouldn't be specified when 'system_provided' is true",
        skylarkImplementationLoadStatement,
        "cc_import(",
        "  name = 'foo',",
        "  shared_library = 'libfoo.so',",
        "  system_provided = 1,",
        ")");
    checkError(
        "e",
        "foo",
        "'shared_library' should be specified when 'system_provided' is false",
        skylarkImplementationLoadStatement,
        "cc_import(",
        "  name = 'foo',",
        "  interface_library = 'libfoo.ifso',",
        "  system_provided = 0,",
        ")");
  }

  @Test
  public void testWrongCcImportDefinitionsOnWindows() throws Exception {
    AnalysisMock.get()
        .ccSupport()
        .setupCrosstool(
            mockToolsConfig,
            MockCcSupport.COPY_DYNAMIC_LIBRARIES_TO_BINARY_CONFIGURATION,
            MockCcSupport.TARGETS_WINDOWS_CONFIGURATION);
    useConfiguration();
    checkError(
        "a",
        "foo",
        "'interface library' must be specified when using cc_import for shared library on Windows",
        skylarkImplementationLoadStatement,
        "cc_import(",
        "  name = 'foo',",
        "  shared_library = 'libfoo.dll',",
        ")");
  }

  @Test
  public void testCcImportWithStaticLibrary() throws Exception {
    ConfiguredTarget target =
        scratchConfiguredTarget(
            "a",
            "foo",
            skylarkImplementationLoadStatement,
            "cc_import(name = 'foo', static_library = 'libfoo.a')");
    Iterable<Artifact> libraries =
        LinkerInputs.toNonSolibArtifacts(
            target
                .get(CcInfo.PROVIDER)
                .getCcLinkingInfo()
                .getDynamicModeParamsForExecutable()
                .getLibraries());
    assertThat(artifactsToStrings(libraries)).containsExactly("src a/libfoo.a");

    libraries =
        LinkerInputs.toNonSolibArtifacts(
            target
                .get(CcInfo.PROVIDER)
                .getCcLinkingInfo()
                .getDynamicModeParamsForDynamicLibrary()
                .getLibraries());
    assertThat(artifactsToStrings(libraries)).containsExactly("src a/libfoo.a");

    libraries =
        LinkerInputs.toNonSolibArtifacts(
            target
                .get(CcInfo.PROVIDER)
                .getCcLinkingInfo()
                .getStaticModeParamsForExecutable()
                .getLibraries());
    assertThat(artifactsToStrings(libraries)).containsExactly("src a/libfoo.a");

    libraries =
        LinkerInputs.toNonSolibArtifacts(
            target
                .get(CcInfo.PROVIDER)
                .getCcLinkingInfo()
                .getStaticModeParamsForDynamicLibrary()
                .getLibraries());
    assertThat(artifactsToStrings(libraries)).containsExactly("src a/libfoo.a");
  }

  @Test
  public void testCcImportWithSharedLibrary() throws Exception {
    useConfiguration("--cpu=k8");
    ConfiguredTarget target =
        scratchConfiguredTarget(
            "a",
            "foo",
            skylarkImplementationLoadStatement,
            "cc_import(name = 'foo', shared_library = 'libfoo.so')");
    CcLinkParams ccLinkParams =
        target.get(CcInfo.PROVIDER).getCcLinkingInfo().getDynamicModeParamsForExecutable();
    Iterable<Artifact> libraries = LinkerInputs.toNonSolibArtifacts(ccLinkParams.getLibraries());
    Iterable<Artifact> dynamicLibrariesForRuntime = ccLinkParams.getDynamicLibrariesForRuntime();
    assertThat(artifactsToStrings(libraries)).containsExactly("src a/libfoo.so");
    assertThat(artifactsToStrings(dynamicLibrariesForRuntime))
        .containsExactly("bin _solib_k8/_U_S_Sa_Cfoo___Ua/libfoo.so");

    ccLinkParams =
        target.get(CcInfo.PROVIDER).getCcLinkingInfo().getDynamicModeParamsForDynamicLibrary();
    libraries = LinkerInputs.toNonSolibArtifacts(ccLinkParams.getLibraries());
    dynamicLibrariesForRuntime = ccLinkParams.getDynamicLibrariesForRuntime();
    assertThat(artifactsToStrings(libraries)).containsExactly("src a/libfoo.so");
    assertThat(artifactsToStrings(dynamicLibrariesForRuntime))
        .containsExactly("bin _solib_k8/_U_S_Sa_Cfoo___Ua/libfoo.so");

    ccLinkParams =
        target.get(CcInfo.PROVIDER).getCcLinkingInfo().getStaticModeParamsForExecutable();
    libraries = LinkerInputs.toNonSolibArtifacts(ccLinkParams.getLibraries());
    dynamicLibrariesForRuntime = ccLinkParams.getDynamicLibrariesForRuntime();
    assertThat(artifactsToStrings(libraries)).containsExactly("src a/libfoo.so");
    assertThat(artifactsToStrings(dynamicLibrariesForRuntime))
        .containsExactly("bin _solib_k8/_U_S_Sa_Cfoo___Ua/libfoo.so");

    ccLinkParams =
        target.get(CcInfo.PROVIDER).getCcLinkingInfo().getStaticModeParamsForDynamicLibrary();
    libraries = LinkerInputs.toNonSolibArtifacts(ccLinkParams.getLibraries());
    dynamicLibrariesForRuntime = ccLinkParams.getDynamicLibrariesForRuntime();
    assertThat(artifactsToStrings(libraries)).containsExactly("src a/libfoo.so");
    assertThat(artifactsToStrings(dynamicLibrariesForRuntime))
        .containsExactly("bin _solib_k8/_U_S_Sa_Cfoo___Ua/libfoo.so");
  }

  @Test
  public void testCcImportWithInterfaceSharedLibrary() throws Exception {
    useConfiguration("--cpu=k8");
    ConfiguredTarget target =
        scratchConfiguredTarget(
            "b",
            "foo",
            skylarkImplementationLoadStatement,
            "cc_import(name = 'foo', shared_library = 'libfoo.so',"
                + " interface_library = 'libfoo.ifso')");
    CcLinkParams ccLinkParams =
        target.get(CcInfo.PROVIDER).getCcLinkingInfo().getDynamicModeParamsForExecutable();
    Iterable<Artifact> libraries = LinkerInputs.toNonSolibArtifacts(ccLinkParams.getLibraries());
    Iterable<Artifact> dynamicLibrariesForRuntime = ccLinkParams.getDynamicLibrariesForRuntime();
    assertThat(artifactsToStrings(libraries)).containsExactly("src b/libfoo.ifso");
    assertThat(artifactsToStrings(dynamicLibrariesForRuntime))
        .containsExactly("bin _solib_k8/_U_S_Sb_Cfoo___Ub/libfoo.so");

    ccLinkParams =
        target.get(CcInfo.PROVIDER).getCcLinkingInfo().getDynamicModeParamsForDynamicLibrary();
    libraries = LinkerInputs.toNonSolibArtifacts(ccLinkParams.getLibraries());
    dynamicLibrariesForRuntime = ccLinkParams.getDynamicLibrariesForRuntime();
    assertThat(artifactsToStrings(libraries)).containsExactly("src b/libfoo.ifso");
    assertThat(artifactsToStrings(dynamicLibrariesForRuntime))
        .containsExactly("bin _solib_k8/_U_S_Sb_Cfoo___Ub/libfoo.so");

    ccLinkParams =
        target.get(CcInfo.PROVIDER).getCcLinkingInfo().getStaticModeParamsForExecutable();
    libraries = LinkerInputs.toNonSolibArtifacts(ccLinkParams.getLibraries());
    dynamicLibrariesForRuntime = ccLinkParams.getDynamicLibrariesForRuntime();
    assertThat(artifactsToStrings(libraries)).containsExactly("src b/libfoo.ifso");
    assertThat(artifactsToStrings(dynamicLibrariesForRuntime))
        .containsExactly("bin _solib_k8/_U_S_Sb_Cfoo___Ub/libfoo.so");

    ccLinkParams =
        target.get(CcInfo.PROVIDER).getCcLinkingInfo().getStaticModeParamsForDynamicLibrary();
    libraries = LinkerInputs.toNonSolibArtifacts(ccLinkParams.getLibraries());
    dynamicLibrariesForRuntime = ccLinkParams.getDynamicLibrariesForRuntime();
    assertThat(artifactsToStrings(libraries)).containsExactly("src b/libfoo.ifso");
    assertThat(artifactsToStrings(dynamicLibrariesForRuntime))
        .containsExactly("bin _solib_k8/_U_S_Sb_Cfoo___Ub/libfoo.so");
  }

  @Test
  public void testCcImportWithBothStaticAndSharedLibraries() throws Exception {
    useConfiguration("--cpu=k8");
    ConfiguredTarget target =
        scratchConfiguredTarget(
            "a",
            "foo",
            skylarkImplementationLoadStatement,
            "cc_import(name = 'foo', static_library = 'libfoo.a', shared_library = 'libfoo.so')");
    CcLinkParams ccLinkParams =
        target.get(CcInfo.PROVIDER).getCcLinkingInfo().getDynamicModeParamsForExecutable();
    Iterable<Artifact> libraries = LinkerInputs.toNonSolibArtifacts(ccLinkParams.getLibraries());
    Iterable<Artifact> dynamicLibrariesForRuntime = ccLinkParams.getDynamicLibrariesForRuntime();
    assertThat(artifactsToStrings(libraries)).containsExactly("src a/libfoo.so");
    assertThat(artifactsToStrings(dynamicLibrariesForRuntime))
        .containsExactly("bin _solib_k8/_U_S_Sa_Cfoo___Ua/libfoo.so");

    ccLinkParams =
        target.get(CcInfo.PROVIDER).getCcLinkingInfo().getDynamicModeParamsForDynamicLibrary();
    libraries = LinkerInputs.toNonSolibArtifacts(ccLinkParams.getLibraries());
    dynamicLibrariesForRuntime = ccLinkParams.getDynamicLibrariesForRuntime();
    assertThat(artifactsToStrings(libraries)).containsExactly("src a/libfoo.so");
    assertThat(artifactsToStrings(dynamicLibrariesForRuntime))
        .containsExactly("bin _solib_k8/_U_S_Sa_Cfoo___Ua/libfoo.so");

    ccLinkParams =
        target.get(CcInfo.PROVIDER).getCcLinkingInfo().getStaticModeParamsForExecutable();
    libraries = LinkerInputs.toNonSolibArtifacts(ccLinkParams.getLibraries());
    dynamicLibrariesForRuntime = ccLinkParams.getDynamicLibrariesForRuntime();
    assertThat(artifactsToStrings(libraries)).containsExactly("src a/libfoo.a");
    assertThat(artifactsToStrings(dynamicLibrariesForRuntime)).isEmpty();

    ccLinkParams =
        target.get(CcInfo.PROVIDER).getCcLinkingInfo().getStaticModeParamsForDynamicLibrary();
    libraries = LinkerInputs.toNonSolibArtifacts(ccLinkParams.getLibraries());
    dynamicLibrariesForRuntime = ccLinkParams.getDynamicLibrariesForRuntime();
    assertThat(artifactsToStrings(libraries)).containsExactly("src a/libfoo.a");
    assertThat(artifactsToStrings(dynamicLibrariesForRuntime)).isEmpty();
  }

  @Test
  public void testCcImportWithAlwaysLinkStaticLibrary() throws Exception {
    ConfiguredTarget target =
        scratchConfiguredTarget(
            "a",
            "foo",
            skylarkImplementationLoadStatement,
            "cc_import(name = 'foo', static_library = 'libfoo.a', alwayslink = 1)");
    LibraryToLink libraryToLink =
        target
            .get(CcInfo.PROVIDER)
            .getCcLinkingInfo()
            .getDynamicModeParamsForExecutable()
            .getLibraries()
            .toList()
            .get(0);
    assertThat(libraryToLink.getArtifactCategory())
        .isEqualTo(ArtifactCategory.ALWAYSLINK_STATIC_LIBRARY);
  }

  @Test
  public void testCcImportSystemProvidedIsTrue() throws Exception {
    ConfiguredTarget target =
        scratchConfiguredTarget(
            "a",
            "foo",
            skylarkImplementationLoadStatement,
            "cc_import(name = 'foo', interface_library = 'libfoo.ifso', system_provided = 1)");
    CcLinkParams ccLinkParams =
        target.get(CcInfo.PROVIDER).getCcLinkingInfo().getDynamicModeParamsForExecutable();
    Iterable<Artifact> libraries = LinkerInputs.toNonSolibArtifacts(ccLinkParams.getLibraries());
    Iterable<Artifact> dynamicLibrariesForRuntime = ccLinkParams.getDynamicLibrariesForRuntime();
    assertThat(artifactsToStrings(libraries)).containsExactly("src a/libfoo.ifso");
    assertThat(artifactsToStrings(dynamicLibrariesForRuntime)).isEmpty();
  }

  @Test
  public void testCcImportProvideHeaderFiles() throws Exception {
    Iterable<Artifact> headers =
        scratchConfiguredTarget(
                "a",
                "foo",
                skylarkImplementationLoadStatement,
                "cc_import(name = 'foo', static_library = 'libfoo.a', hdrs = ['foo.h'])")
            .get(CcInfo.PROVIDER)
            .getCcCompilationContext()
            .getDeclaredIncludeSrcs();
    assertThat(artifactsToStrings(headers)).containsExactly("src a/foo.h");
  }
}
