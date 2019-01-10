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
import static com.google.common.truth.Truth.assertWithMessage;
import static org.junit.Assert.fail;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.google.common.eventbus.EventBus;
import com.google.devtools.build.lib.analysis.ConfiguredRuleClassProvider;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.PackageIdentifier;
import com.google.devtools.build.lib.events.Location;
import com.google.devtools.build.lib.events.Reporter;
import com.google.devtools.build.lib.packages.RuleFactory.BuildLangTypedAttributeValuesMap;
import com.google.devtools.build.lib.packages.util.PackageLoadingTestCase;
import com.google.devtools.build.lib.syntax.Type;
import com.google.devtools.build.lib.testutil.TestRuleClassProvider;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.RootedPath;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class RuleFactoryTest extends PackageLoadingTestCase {

  private ConfiguredRuleClassProvider provider = TestRuleClassProvider.getRuleClassProvider();
  private RuleFactory ruleFactory = new RuleFactory(provider, AttributeContainer::new);

  public static final Location LOCATION_42 = Location.fromFileAndOffsets(null, 42, 42);

  @Test
  public void testCreateRule() throws Exception {
    Path myPkgPath = scratch.resolve("/workspace/mypkg/BUILD");
    Package.Builder pkgBuilder =
        packageFactory
            .newPackageBuilder(PackageIdentifier.createInMainRepo("mypkg"), "TESTING")
            .setFilename(RootedPath.toRootedPath(root, myPkgPath));

    Map<String, Object> attributeValues = new HashMap<>();
    attributeValues.put("name", "foo");
    attributeValues.put("alwayslink", true);

    RuleClass ruleClass = provider.getRuleClassMap().get("cc_library");
    Rule rule =
        RuleFactory.createAndAddRule(
            pkgBuilder,
            ruleClass,
            new BuildLangTypedAttributeValuesMap(attributeValues),
            new Reporter(new EventBus()),
            /*ast=*/ null,
            LOCATION_42,
            /*env=*/ null,
            new AttributeContainer(ruleClass));

    assertThat(rule.getAssociatedRule()).isSameAs(rule);

    // pkg.getRules() = [rule]
    Package pkg = pkgBuilder.build();
    assertThat(Sets.newHashSet(pkg.getTargets(Rule.class))).hasSize(1);
    assertThat(pkg.getTargets(Rule.class).iterator().next()).isEqualTo(rule);

    assertThat(pkg.getTarget("foo")).isSameAs(rule);

    assertThat(rule.getLabel()).isEqualTo(Label.parseAbsolute("//mypkg:foo", ImmutableMap.of()));
    assertThat(rule.getName()).isEqualTo("foo");

    assertThat(rule.getRuleClass()).isEqualTo("cc_library");
    assertThat(rule.getTargetKind()).isEqualTo("cc_library rule");
    assertThat(rule.getLocation().getStartOffset()).isEqualTo(42);
    assertThat(rule.containsErrors()).isFalse();

    // Attr with explicitly-supplied value:
    AttributeMap attributes = RawAttributeMapper.of(rule);
    assertThat(attributes.get("alwayslink", Type.BOOLEAN)).isTrue();
    try {
      attributes.get("alwayslink", Type.STRING); // type error: boolean, not string!
      fail();
    } catch (Exception e) {
      /* Class of exception and error message are not specified by API. */
    }
    try {
      attributes.get("nosuchattr", Type.STRING); // no such attribute
      fail();
    } catch (Exception e) {
      /* Class of exception and error message are not specified by API. */
    }

    // Attrs with default values:
    // cc_library linkstatic default=0 according to build encyc.
    assertThat(attributes.get("linkstatic", Type.BOOLEAN)).isFalse();
    assertThat(attributes.get("testonly", Type.BOOLEAN)).isFalse();
    assertThat(attributes.get("srcs", BuildType.LABEL_LIST)).isEmpty();
  }

  @Test
  public void testCreateWorkspaceRule() throws Exception {
    Path myPkgPath = scratch.resolve("/workspace/WORKSPACE");
    Package.Builder pkgBuilder =
        packageFactory.newExternalPackageBuilder(
            RootedPath.toRootedPath(root, myPkgPath), "TESTING");

    Map<String, Object> attributeValues = new HashMap<>();
    attributeValues.put("name", "foo");
    attributeValues.put("actual", "//foo:bar");

    RuleClass ruleClass = provider.getRuleClassMap().get("bind");
    Rule rule =
        RuleFactory.createAndAddRule(
            pkgBuilder,
            ruleClass,
            new BuildLangTypedAttributeValuesMap(attributeValues),
            new Reporter(new EventBus()),
            /*ast=*/ null,
            Location.fromFileAndOffsets(myPkgPath.asFragment(), 42, 42),
            /*env=*/ null,
            new AttributeContainer(ruleClass));
    assertThat(rule.containsErrors()).isFalse();
  }

  @Test
  public void testWorkspaceRuleFailsInBuildFile() throws Exception {
    Path myPkgPath = scratch.resolve("/workspace/mypkg/BUILD");
    Package.Builder pkgBuilder =
        packageFactory
            .newPackageBuilder(PackageIdentifier.createInMainRepo("mypkg"), "TESTING")
            .setFilename(RootedPath.toRootedPath(root, myPkgPath));

    Map<String, Object> attributeValues = new HashMap<>();
    attributeValues.put("name", "foo");
    attributeValues.put("actual", "//bar:baz");

    RuleClass ruleClass = provider.getRuleClassMap().get("bind");
    try {
      RuleFactory.createAndAddRule(
          pkgBuilder,
          ruleClass,
          new BuildLangTypedAttributeValuesMap(attributeValues),
          new Reporter(new EventBus()),
          /*ast=*/ null,
          LOCATION_42,
          /*env=*/ null,
          new AttributeContainer(ruleClass));
      fail();
    } catch (RuleFactory.InvalidRuleException e) {
      assertThat(e).hasMessageThat().contains("must be in the WORKSPACE file");
    }
  }

  @Test
  public void testBuildRuleFailsInWorkspaceFile() throws Exception {
    Path myPkgPath = scratch.resolve("/workspace/WORKSPACE");
    Package.Builder pkgBuilder =
        packageFactory
            .newPackageBuilder(Label.EXTERNAL_PACKAGE_IDENTIFIER, "TESTING")
            .setFilename(RootedPath.toRootedPath(root, myPkgPath));

    Map<String, Object> attributeValues = new HashMap<>();
    attributeValues.put("name", "foo");
    attributeValues.put("alwayslink", true);

    RuleClass ruleClass = provider.getRuleClassMap().get("cc_library");
    try {
      RuleFactory.createAndAddRule(
          pkgBuilder,
          ruleClass,
          new BuildLangTypedAttributeValuesMap(attributeValues),
          new Reporter(new EventBus()),
          /*ast=*/ null,
          Location.fromFileAndOffsets(myPkgPath.asFragment(), 42, 42),
          /*env=*/ null,
          new AttributeContainer(ruleClass));
      fail();
    } catch (RuleFactory.InvalidRuleException e) {
      assertThat(e).hasMessageThat().contains("cannot be in the WORKSPACE file");
    }
  }

  private void assertAttr(RuleClass ruleClass, String attrName, Type<?> type) throws Exception {
    assertWithMessage(
            "Rule class '"
                + ruleClass.getName()
                + "' should have attribute '"
                + attrName
                + "' of type '"
                + type
                + "'")
        .that(ruleClass.hasAttr(attrName, type))
        .isTrue();
  }

  @Test
  public void testOutputFileNotEqualDot() throws Exception {
    Path myPkgPath = scratch.resolve("/workspace/mypkg");
    Package.Builder pkgBuilder =
        packageFactory
            .newPackageBuilder(PackageIdentifier.createInMainRepo("mypkg"), "TESTING")
            .setFilename(RootedPath.toRootedPath(root, myPkgPath));

    Map<String, Object> attributeValues = new HashMap<>();
    attributeValues.put("outs", Lists.newArrayList("."));
    attributeValues.put("name", "some");
    RuleClass ruleClass = provider.getRuleClassMap().get("genrule");
    try {
      RuleFactory.createAndAddRule(
          pkgBuilder,
          ruleClass,
          new BuildLangTypedAttributeValuesMap(attributeValues),
          new Reporter(new EventBus()),
          /*ast=*/ null,
          Location.fromFileAndOffsets(myPkgPath.asFragment(), 42, 42),
          /*env=*/ null,
          new AttributeContainer(ruleClass));
      fail();
    } catch (RuleFactory.InvalidRuleException e) {
      assertWithMessage(e.getMessage())
          .that(e.getMessage().contains("output file name can't be equal '.'"))
          .isTrue();
    }
  }

  /**
   * Tests mandatory attribute definitions for test rules.
   */
  // TODO(ulfjack): Remove this check when we switch over to the builder
  // pattern, which will always guarantee that these attributes are present.
  @Test
  public void testTestRules() throws Exception {
    Path myPkgPath = scratch.resolve("/workspace/mypkg/BUILD");
    Package pkg =
        packageFactory
            .newPackageBuilder(PackageIdentifier.createInMainRepo("mypkg"), "TESTING")
            .setFilename(RootedPath.toRootedPath(root, myPkgPath))
            .build();

    for (String name : ruleFactory.getRuleClassNames()) {
      // Create rule instance directly so we'll avoid mandatory attribute check yet will be able
      // to use TargetUtils.isTestRule() method to identify test rules.
      RuleClass ruleClass = ruleFactory.getRuleClass(name);
      Rule rule =
          new Rule(
              pkg,
              Label.create(pkg.getPackageIdentifier(), "myrule"),
              ruleClass,
              Location.fromFile(myPkgPath),
              new AttributeContainer(ruleClass));
      if (TargetUtils.isTestRule(rule)) {
        assertAttr(ruleClass, "tags", Type.STRING_LIST);
        assertAttr(ruleClass, "size", Type.STRING);
        assertAttr(ruleClass, "flaky", Type.BOOLEAN);
        assertAttr(ruleClass, "shard_count", Type.INTEGER);
        assertAttr(ruleClass, "local", Type.BOOLEAN);
      }
    }
  }
}
