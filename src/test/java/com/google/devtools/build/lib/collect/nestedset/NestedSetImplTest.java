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
package com.google.devtools.build.lib.collect.nestedset;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.fail;

import com.google.common.collect.Lists;
import com.google.common.testing.EqualsTester;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Tests for {@link com.google.devtools.build.lib.collect.nestedset.NestedSet}.
 */
@RunWith(JUnit4.class)
public class NestedSetImplTest {
  @SafeVarargs
  private static NestedSetBuilder<String> nestedSetBuilder(String... directMembers) {
    NestedSetBuilder<String> builder = NestedSetBuilder.stableOrder();
    builder.addAll(Lists.newArrayList(directMembers));
    return builder;
  }

  @Test
  public void simple() {
    NestedSet<String> set = nestedSetBuilder("a").build();

    assertThat(set.toList()).containsExactly("a");
    assertThat(set.isEmpty()).isFalse();
  }

  @Test
  public void flatToString() {
    assertThat(nestedSetBuilder().build().toString()).isEqualTo("{}");
    assertThat(nestedSetBuilder("a").build().toString()).isEqualTo("{a}");
    assertThat(nestedSetBuilder("a", "b").build().toString()).isEqualTo("{a, b}");
  }

  @Test
  public void nestedToString() {
    NestedSet<String> b = nestedSetBuilder("b1", "b2").build();
    NestedSet<String> c = nestedSetBuilder("c1", "c2").build();

    assertThat(nestedSetBuilder("a").addTransitive(b).build().toString())
        .isEqualTo("{{b1, b2}, a}");
    assertThat(nestedSetBuilder("a").addTransitive(b).addTransitive(c).build().toString())
        .isEqualTo("{{b1, b2}, {c1, c2}, a}");

    assertThat(nestedSetBuilder().addTransitive(b).build().toString()).isEqualTo("{b1, b2}");
  }

  @Test
  public void isEmpty() {
    NestedSet<String> triviallyEmpty = nestedSetBuilder().build();
    assertThat(triviallyEmpty.isEmpty()).isTrue();

    NestedSet<String> emptyLevel1 = nestedSetBuilder().addTransitive(triviallyEmpty).build();
    assertThat(emptyLevel1.isEmpty()).isTrue();

    NestedSet<String> emptyLevel2 = nestedSetBuilder().addTransitive(emptyLevel1).build();
    assertThat(emptyLevel2.isEmpty()).isTrue();

    NestedSet<String> triviallyNonEmpty = nestedSetBuilder("mango").build();
    assertThat(triviallyNonEmpty.isEmpty()).isFalse();

    NestedSet<String> nonEmptyLevel1 = nestedSetBuilder().addTransitive(triviallyNonEmpty).build();
    assertThat(nonEmptyLevel1.isEmpty()).isFalse();

    NestedSet<String> nonEmptyLevel2 = nestedSetBuilder().addTransitive(nonEmptyLevel1).build();
    assertThat(nonEmptyLevel2.isEmpty()).isFalse();
  }

  @Test
  public void canIncludeAnyOrderInStableOrderAndViceVersa() {
    NestedSetBuilder.stableOrder()
        .addTransitive(NestedSetBuilder.compileOrder()
            .addTransitive(NestedSetBuilder.stableOrder().build()).build())
        .addTransitive(NestedSetBuilder.linkOrder()
            .addTransitive(NestedSetBuilder.stableOrder().build()).build())
        .addTransitive(NestedSetBuilder.naiveLinkOrder()
            .addTransitive(NestedSetBuilder.stableOrder().build()).build()).build();
    try {
      NestedSetBuilder.compileOrder().addTransitive(NestedSetBuilder.linkOrder().build()).build();
      fail("Shouldn't be able to include a non-stable order inside a different non-stable order!");
    } catch (IllegalArgumentException e) {
      // Expected.
    }
  }

  /**
   * A handy wrapper that allows us to use EqualsTester to test shallowEquals and shallowHashCode.
   */
  private static class SetWrapper<E> {
    NestedSet<E> set;

    SetWrapper(NestedSet<E> wrapped) {
      set = wrapped;
    }

    @Override
    public int hashCode() {
      return set.shallowHashCode();
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (!(o instanceof SetWrapper)) {
        return false;
      }
      try {
        @SuppressWarnings("unchecked")
        SetWrapper<E> other = (SetWrapper<E>) o;
        return set.shallowEquals(other.set);
      } catch (ClassCastException e) {
        return false;
      }
    }
  }

  @SafeVarargs
  private static <E> SetWrapper<E> flat(E... directMembers) {
    NestedSetBuilder<E> builder = NestedSetBuilder.stableOrder();
    builder.addAll(Lists.newArrayList(directMembers));
    return new SetWrapper<E>(builder.build());
  }

  @SafeVarargs
  private static <E> SetWrapper<E> nest(SetWrapper<E>... nested) {
    NestedSetBuilder<E> builder = NestedSetBuilder.stableOrder();
    for (SetWrapper<E> wrap : nested) {
      builder.addTransitive(wrap.set);
    }
    return new SetWrapper<E>(builder.build());
  }

  @SafeVarargs
  // Restricted to <Integer> to avoid ambiguity with the other nest() function.
  private static SetWrapper<Integer> nest(Integer elem, SetWrapper<Integer>... nested) {
    NestedSetBuilder<Integer> builder = NestedSetBuilder.stableOrder();
    builder.add(elem);
    for (SetWrapper<Integer> wrap : nested) {
      builder.addTransitive(wrap.set);
    }
    return new SetWrapper<>(builder.build());
  }

  @Test
  public void shallowEquality() {
    // Used below to check that inner nested sets can be compared by reference equality.
    SetWrapper<Integer> myRef = nest(nest(flat(7, 8)), flat(9));
    // Used to check equality for deserializing nested sets
    ListenableFuture<Object[]> contents = Futures.immediateFuture(new Object[] {"a", "b"});
    NestedSet<String> referenceNestedSet = NestedSet.withFuture(Order.STABLE_ORDER, contents);
    NestedSet<String> otherReferenceNestedSet = NestedSet.withFuture(Order.STABLE_ORDER, contents);

    // Each "equality group" contains elements that are equal to one another
    // (according to equals() and hashCode()), yet distinct from all elements
    // of all other equality groups.
    new EqualsTester()
        .addEqualityGroup(flat(), flat(), nest(flat())) // Empty set elision.
        .addEqualityGroup(NestedSetBuilder.<Integer>linkOrder().build())
        .addEqualityGroup(flat(3), flat(3), flat(3, 3)) // Element de-duplication.
        .addEqualityGroup(flat(4), nest(flat(4))) // Automatic elision of one-element nested sets.
        .addEqualityGroup(NestedSetBuilder.<Integer>linkOrder().add(4).build())
        .addEqualityGroup(nestedSetBuilder("4").build()) // Like flat("4").
        .addEqualityGroup(flat(3, 4), flat(3, 4))
        // Make a couple sets deep enough that shallowEquals() fails.
        // If this test case fails because you improve the representation, just delete it.
        .addEqualityGroup(nest(nest(flat(3, 4), flat(5)), nest(flat(6, 7), flat(8))))
        .addEqualityGroup(nest(nest(flat(3, 4), flat(5)), nest(flat(6, 7), flat(8))))
        .addEqualityGroup(nest(myRef), nest(myRef), nest(myRef, myRef)) // Set de-duplication.
        .addEqualityGroup(nest(3, myRef))
        .addEqualityGroup(nest(4, myRef))
        .addEqualityGroup(
            new SetWrapper<>(referenceNestedSet), new SetWrapper<>(otherReferenceNestedSet))
        .testEquals();

    // Some things that are not tested by the above:
    //  - ordering among direct members
    //  - ordering among transitive sets
  }

  @Test
  public void shallowInequality() {
    assertThat(nestedSetBuilder("a").build().shallowEquals(null)).isFalse();
    Object[] contents = {"a", "b"};
    assertThat(
            NestedSet.withFuture(Order.STABLE_ORDER, Futures.immediateFuture(contents))
                .shallowEquals(null))
        .isFalse();

    // shallowEquals() should require reference equality for underlying futures
    assertThat(
            NestedSet.withFuture(Order.STABLE_ORDER, Futures.immediateFuture(contents))
                .shallowEquals(
                    NestedSet.withFuture(Order.STABLE_ORDER, Futures.immediateFuture(contents))))
        .isFalse();
  }

  /** Checks that the builder always return a nested set with the correct order. */
  @Test
  public void correctOrder() {
    for (Order order : Order.values()) {
      for (int numDirects = 0; numDirects < 3; numDirects++) {
        for (int numTransitives = 0; numTransitives < 3; numTransitives++) {
          assertThat(createNestedSet(order, numDirects, numTransitives, order).getOrder())
              .isEqualTo(order);
          // We allow mixing orders if one of them is stable. This tests that the top level order is
          // the correct one.
          assertThat(
                  createNestedSet(order, numDirects, numTransitives, Order.STABLE_ORDER).getOrder())
              .isEqualTo(order);
        }
      }
    }
  }

  private NestedSet<Integer> createNestedSet(Order order, int numDirects, int numTransitives,
      Order transitiveOrder) {
    NestedSetBuilder<Integer> builder = new NestedSetBuilder<>(order);

    for (int direct = 0; direct < numDirects; direct++) {
      builder.add(direct);
    }
    for (int transitive = 0; transitive < numTransitives; transitive++) {
      builder.addTransitive(new NestedSetBuilder<Integer>(transitiveOrder).add(transitive).build());
    }
    return builder.build();
  }
}
