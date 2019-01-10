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
package com.google.devtools.build.lib.packages;

import static com.google.common.truth.Truth.assertThat;
import static com.google.devtools.build.lib.packages.Attribute.attr;
import static com.google.devtools.build.lib.packages.BuildType.LABEL;
import static com.google.devtools.build.lib.packages.BuildType.LABEL_LIST;
import static com.google.devtools.build.lib.syntax.Type.INTEGER;
import static com.google.devtools.build.lib.syntax.Type.STRING;
import static com.google.devtools.build.lib.syntax.Type.STRING_LIST;
import static org.junit.Assert.fail;

import com.google.common.base.Predicates;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.analysis.config.BuildOptions;
import com.google.devtools.build.lib.analysis.config.HostTransition;
import com.google.devtools.build.lib.analysis.config.transitions.SplitTransition;
import com.google.devtools.build.lib.analysis.util.TestAspects;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.packages.Attribute.SplitTransitionProvider;
import com.google.devtools.build.lib.packages.RuleClass.Builder.RuleClassNamePredicate;
import com.google.devtools.build.lib.syntax.Type;
import com.google.devtools.build.lib.util.FileType;
import com.google.devtools.build.lib.util.FileTypeSet;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests of Attribute code. */
@RunWith(JUnit4.class)
public class AttributeTest {

  private void assertDefaultValue(Object expected, Attribute attr) {
    assertThat(attr.getDefaultValue(null)).isEqualTo(expected);
  }

  private void assertType(Type<?> expectedType, Attribute attr) {
    assertThat(attr.getType()).isEqualTo(expectedType);
  }

  @Test
  public void testBasics() throws Exception {
    Attribute attr = attr("foo", Type.INTEGER).mandatory().value(3).build();
    assertThat(attr.getName()).isEqualTo("foo");
    assertThat(attr.getDefaultValue(null)).isEqualTo(3);
    assertThat(attr.getType()).isEqualTo(Type.INTEGER);
    assertThat(attr.isMandatory()).isTrue();
    assertThat(attr.isDocumented()).isTrue();
    attr = attr("$foo", Type.INTEGER).build();
    assertThat(attr.isDocumented()).isFalse();
  }

  @Test
  public void testNonEmptyReqiresListType() throws Exception {
    try {
      attr("foo", Type.INTEGER).nonEmpty().value(3).build();
      fail();
    } catch (NullPointerException e) {
      assertThat(e).hasMessage("attribute 'foo' must be a list");
    }
  }

  @Test
  public void testNonEmpty() throws Exception {
    Attribute attr = attr("foo", BuildType.LABEL_LIST).nonEmpty().legacyAllowAnyFileType().build();
    assertThat(attr.getName()).isEqualTo("foo");
    assertThat(attr.getType()).isEqualTo(BuildType.LABEL_LIST);
    assertThat(attr.isNonEmpty()).isTrue();
  }

  @Test
  public void testSingleArtifactReqiresLabelType() throws Exception {
    try {
      attr("foo", Type.INTEGER).singleArtifact().value(3).build();
      fail();
    } catch (IllegalStateException e) {
      assertThat(e).hasMessage("attribute 'foo' must be a label-valued type");
    }
  }

  @Test
  public void testDoublePropertySet() {
    Attribute.Builder<String> builder = attr("x", STRING).mandatory()
        .cfg(HostTransition.INSTANCE)
        .undocumented("")
        .value("y");
    try {
      builder.mandatory();
      fail();
    } catch (IllegalStateException expected) {
      // expected
    }
    try {
      builder.cfg(HostTransition.INSTANCE);
      fail();
    } catch (IllegalStateException expected) {
      // expected
    }
    try {
      builder.undocumented("");
      fail();
    } catch (IllegalStateException expected) {
      // expected
    }
    try {
      builder.value("z");
      fail();
    } catch (IllegalStateException expected) {
      // expected
    }

    builder = attr("$x", STRING);
    try {
      builder.undocumented("");
      fail();
    } catch (IllegalStateException expected) {
      // expected
    }
  }

  /**
   *  Tests the "convenience factories" (string, label, etc) for default
   *  values.
   */
  @Test
  public void testConvenienceFactoriesDefaultValues() throws Exception {
    assertDefaultValue(0,
                       attr("x", INTEGER).build());
    assertDefaultValue(42,
                       attr("x", INTEGER).value(42).build());

    assertDefaultValue("",
                       attr("x", STRING).build());
    assertDefaultValue("foo",
                       attr("x", STRING).value("foo").build());

    Label label = Label.parseAbsolute("//foo:bar", ImmutableMap.of());
    assertDefaultValue(null,
                       attr("x", LABEL).legacyAllowAnyFileType().build());
    assertDefaultValue(label,
                       attr("x", LABEL).legacyAllowAnyFileType().value(label).build());

    List<String> slist = Arrays.asList("foo", "bar");
    assertDefaultValue(Collections.emptyList(),
                       attr("x", STRING_LIST).build());
    assertDefaultValue(slist,
                       attr("x", STRING_LIST).value(slist).build());

    List<Label> llist =
        Arrays.asList(
            Label.parseAbsolute("//foo:bar", ImmutableMap.of()),
            Label.parseAbsolute("//foo:wiz", ImmutableMap.of()));
    assertDefaultValue(Collections.emptyList(),
                       attr("x", LABEL_LIST).legacyAllowAnyFileType().build());
    assertDefaultValue(llist,
                       attr("x", LABEL_LIST).legacyAllowAnyFileType().value(llist).build());
  }

  /**
   *  Tests the "convenience factories" (string, label, etc) for types.
   */
  @Test
  public void testConvenienceFactoriesTypes() throws Exception {
    assertType(INTEGER,
               attr("x", INTEGER).build());
    assertType(INTEGER,
               attr("x", INTEGER).value(42).build());

    assertType(STRING,
               attr("x", STRING).build());
    assertType(STRING,
               attr("x", STRING).value("foo").build());

    Label label = Label.parseAbsolute("//foo:bar", ImmutableMap.of());
    assertType(LABEL,
                       attr("x", LABEL).legacyAllowAnyFileType().build());
    assertType(LABEL,
               attr("x", LABEL).legacyAllowAnyFileType().value(label).build());

    List<String> slist = Arrays.asList("foo", "bar");
    assertType(STRING_LIST,
               attr("x", STRING_LIST).build());
    assertType(STRING_LIST,
               attr("x", STRING_LIST).value(slist).build());

    List<Label> llist =
        Arrays.asList(
            Label.parseAbsolute("//foo:bar", ImmutableMap.of()),
            Label.parseAbsolute("//foo:wiz", ImmutableMap.of()));
    assertType(LABEL_LIST,
               attr("x", LABEL_LIST).legacyAllowAnyFileType().build());
    assertType(LABEL_LIST,
               attr("x", LABEL_LIST).legacyAllowAnyFileType().value(llist).build());
  }

  @Test
  public void testCloneBuilder() {
    FileTypeSet txtFiles = FileTypeSet.of(FileType.of("txt"));
    RuleClassNamePredicate ruleClasses = RuleClassNamePredicate.only("mock_rule");

    Attribute parentAttr =
        attr("x", LABEL_LIST)
            .allowedFileTypes(txtFiles)
            .mandatory()
            .aspect(TestAspects.SIMPLE_ASPECT)
            .build();

    {
      Attribute childAttr1 = parentAttr.cloneBuilder().build();
      assertThat(childAttr1.getName()).isEqualTo("x");
      assertThat(childAttr1.getAllowedFileTypesPredicate()).isEqualTo(txtFiles);
      assertThat(childAttr1.getAllowedRuleClassesPredicate()).isEqualTo(Predicates.alwaysTrue());
      assertThat(childAttr1.isMandatory()).isTrue();
      assertThat(childAttr1.isNonEmpty()).isFalse();
      assertThat(childAttr1.getAspects(/* rule= */ null)).hasSize(1);
    }

    {
      Attribute childAttr2 =
          parentAttr
              .cloneBuilder()
              .nonEmpty()
              .allowedRuleClasses(ruleClasses)
              .aspect(TestAspects.ERROR_ASPECT)
              .build();
      assertThat(childAttr2.getName()).isEqualTo("x");
      assertThat(childAttr2.getAllowedFileTypesPredicate()).isEqualTo(txtFiles);
      assertThat(childAttr2.getAllowedRuleClassesPredicate())
          .isEqualTo(ruleClasses.asPredicateOfRuleClass());
      assertThat(childAttr2.isMandatory()).isTrue();
      assertThat(childAttr2.isNonEmpty()).isTrue();
      assertThat(childAttr2.getAspects(/* rule= */ null)).hasSize(2);
    }

    // Check if the parent attribute is unchanged
    assertThat(parentAttr.isNonEmpty()).isFalse();
    assertThat(parentAttr.getAllowedRuleClassesPredicate()).isEqualTo(Predicates.alwaysTrue());
  }

  /**
   * Tests that configurability settings are properly received.
   */
  @Test
  public void testConfigurability() {
    assertThat(
            attr("foo_configurable", BuildType.LABEL_LIST)
                .legacyAllowAnyFileType()
                .build()
                .isConfigurable())
        .isTrue();
    assertThat(
            attr("foo_nonconfigurable", BuildType.LABEL_LIST)
                .legacyAllowAnyFileType()
                .nonconfigurable("test")
                .build()
                .isConfigurable())
        .isFalse();
  }

  @Test
  public void testSplitTransition() throws Exception {
    TestSplitTransition splitTransition = new TestSplitTransition();
    Attribute attr = attr("foo", LABEL).cfg(splitTransition).allowedFileTypes().build();
    assertThat(attr.hasSplitConfigurationTransition()).isTrue();
    assertThat(attr.getSplitTransition(null)).isEqualTo(splitTransition);
  }

  @Test
  public void testSplitTransitionProvider() throws Exception {
    TestSplitTransitionProvider splitTransitionProvider = new TestSplitTransitionProvider();
    Attribute attr =
        attr("foo", LABEL).cfg(splitTransitionProvider).allowedFileTypes().build();
    assertThat(attr.hasSplitConfigurationTransition()).isTrue();
    assertThat(attr.getSplitTransition(null) instanceof TestSplitTransition).isTrue();
  }

  @Test
  public void testHostTransition() throws Exception {
    Attribute attr = attr("foo", LABEL).cfg(HostTransition.INSTANCE).allowedFileTypes().build();
    assertThat(attr.getConfigurationTransition().isHostTransition()).isTrue();
    assertThat(attr.hasSplitConfigurationTransition()).isFalse();
  }

  private static class TestSplitTransition implements SplitTransition {
    @Override
    public List<BuildOptions> split(BuildOptions buildOptions) {
      return ImmutableList.of(buildOptions.clone(), buildOptions.clone());
    }
  }

  private static class TestSplitTransitionProvider implements SplitTransitionProvider {
    @Override
    public SplitTransition apply(AttributeMap attrMapper) {
      return new TestSplitTransition();
    }
  }

  @Test
  public void allowedRuleClassesAndAllowedRuleClassesWithWarningsCannotOverlap() throws Exception {
    try {
      attr("x", LABEL_LIST)
          .allowedRuleClasses("foo", "bar", "baz")
          .allowedRuleClassesWithWarning("bar")
          .allowedFileTypes()
          .build();
      fail("Expected illegal state exception because rule classes and rule classes with warning "
          + "overlap");
    } catch (IllegalStateException e) {
      assertThat(e).hasMessageThat().contains("may not contain the same rule classes");
    }
  }
}
