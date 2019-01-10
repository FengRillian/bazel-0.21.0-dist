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
package com.google.devtools.build.lib.analysis.select;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.fail;

import com.google.devtools.build.lib.packages.NonconfigurableAttributeMapper;
import com.google.devtools.build.lib.packages.Rule;
import com.google.devtools.build.lib.syntax.Type;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Unit tests for {@link NonconfigurableAttributeMapper}.
 */
@RunWith(JUnit4.class)
public class NonconfigurableAttributeMapperTest extends AbstractAttributeMapperTest {

  private Rule rule;

  @Before
  public final void createRule() throws Exception {
    rule = scratchRule("x", "myrule",
        "cc_binary(",
        "    name = 'myrule',",
        "    srcs = ['a', 'b', 'c'],",
        "    linkstatic = 1,",
        "  deprecation = \"this rule is deprecated!\")");
  }

  @Test
  public void testGetNonconfigurableAttribute() throws Exception {
    assertThat(NonconfigurableAttributeMapper.of(rule).get("deprecation", Type.STRING))
        .isEqualTo("this rule is deprecated!");
  }

  @Test
  public void testGetConfigurableAttribute() throws Exception {
    try {
      NonconfigurableAttributeMapper.of(rule).get("linkstatic", Type.BOOLEAN);
      fail("Expected NonconfigurableAttributeMapper to fail on a configurable attribute type");
    } catch (IllegalStateException e) {
      // Expected outcome.
      assertThat(e).hasMessage(
          "Attribute 'linkstatic' is potentially configurable - not allowed here");
    }
  }
}
