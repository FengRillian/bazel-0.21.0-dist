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

import static com.google.common.collect.Iterables.getOnlyElement;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.MapMaker;
import com.google.devtools.build.lib.collect.compacthashset.CompactHashSet;
import com.google.errorprone.annotations.DoNotCall;
import java.util.concurrent.ConcurrentMap;

/**
 * A builder for nested sets.
 *
 * <p>The builder supports the standard builder interface (that is, {@code #add}, {@code #addAll}
 * and {@code #addTransitive} followed by {@code build}), in addition to shortcut method
 * {@code #wrap}. Any duplicate elements will be inserted as-is, and pruned later on during the
 * traversal of the actual NestedSet.
 */
public final class NestedSetBuilder<E> {

  private final Order order;
  private final CompactHashSet<E> items = CompactHashSet.create();
  private final CompactHashSet<NestedSet<? extends E>> transitiveSets = CompactHashSet.create();

  public NestedSetBuilder(Order order) {
    this.order = order;
  }

  /** Returns whether the set to be built is empty. */
  public boolean isEmpty() {
    return items.isEmpty() && transitiveSets.isEmpty();
  }

  /**
   * Adds a direct member to the set to be built.
   *
   * <p>The relative left-to-right order of direct members is preserved from the sequence of calls
   * to {@link #add} and {@link #addAll}. Since the traversal {@link Order} controls whether direct
   * members appear before or after transitive ones, the interleaving of
   * {@link #add}/{@link #addAll} with {@link #addTransitive} does not matter.
   *
   * @param element item to add; must not be null
   * @return the builder
   */
  public NestedSetBuilder<E> add(E element) {
    Preconditions.checkNotNull(element);
    items.add(element);
    return this;
  }

  /**
   * Adds a sequence of direct members to the set to be built. Equivalent to invoking {@link #add}
   * for each item in {@code elements}, in order.
   *
   * <p>The relative left-to-right order of direct members is preserved from the sequence of calls
   * to {@link #add} and {@link #addAll}. Since the traversal {@link Order} controls whether direct
   * members appear before or after transitive ones, the interleaving of
   * {@link #add}/{@link #addAll} with {@link #addTransitive} does not matter.
   *
   * @param elements the sequence of items to add; must not be null
   * @return the builder
   */
  public NestedSetBuilder<E> addAll(Iterable<? extends E> elements) {
    Preconditions.checkNotNull(elements);
    Iterables.addAll(items, elements);
    return this;
  }

  /** @deprecated Use {@link #addTransitive} to avoid excessive memory use. */
  @Deprecated
  @DoNotCall
  public NestedSetBuilder<E> addAll(NestedSet<? extends E> elements) {
    throw new UnsupportedOperationException();
  }

  /**
   * Adds a nested set as a transitive member to the set to be built.
   *
   * <p>The relative left-to-right order of transitive members is preserved from the sequence of
   * calls to {@link #addTransitive}. Since the traversal {@link Order} controls whether direct
   * members appear before or after transitive ones, the interleaving of
   * {@link #add}/{@link #addAll} with {@link #addTransitive} does not matter.
   *
   * <p>The {@link Order} of the added set must be compatible with the order of this builder (see
   * {@link Order#isCompatible}). This is true even if the added set is empty. Strictly speaking, it
   * is not technically necessary that two nested sets have compatible orders for them to be
   * combined as part of one larger set. But checking for it helps readability and protects against
   * bugs. Since {@link Order#STABLE_ORDER} is compatible with everything, it effectively disables
   * the check. This can be used as an escape hatch to mix and match the set arbitrarily, including
   * sharing the set as part of multiple other larger sets that have disagreeing orders.
   *
   * <p>The relative order of the elements of an added set are preserved, unless it has duplicates
   * or overlaps with other added sets, or its order is different from that of the builder.
   *
   * @param subset the set to add as a transitive member; must not be null
   * @return the builder
   * @throws IllegalStateException if the order of {@code subset} is not compatible with the
   *     order of this builder
   */
  public NestedSetBuilder<E> addTransitive(NestedSet<? extends E> subset) {
    Preconditions.checkNotNull(subset);
    Preconditions.checkArgument(
        order.isCompatible(subset.getOrder()),
        "Order mismatch: %s != %s", subset.getOrder().getSkylarkName(), order.getSkylarkName());
    if (!subset.isEmpty()) {
      transitiveSets.add(subset);
    }
    return this;
  }

  /**
   * Builds the actual nested set.
   *
   * <p>This method may be called multiple times with interleaved {@link #add}, {@link #addAll} and
   * {@link #addTransitive} calls.
   */
  // Casting from CompactHashSet<NestedSet<? extends E>> to CompactHashSet<NestedSet<E>> by way of
  // CompactHashSet<?>.
  @SuppressWarnings("unchecked")
  public NestedSet<E> build() {
    if (isEmpty()) {
      return order.emptySet();
    }

    // This cast is safe because NestedSets are immutable -- we will never try to add an element to
    // these nested sets, only to retrieve elements from them. Thus, treating them as NestedSet<E>
    // is safe.
    CompactHashSet<NestedSet<E>> transitiveSetsCast =
        (CompactHashSet<NestedSet<E>>) (CompactHashSet<?>) transitiveSets;
    if (items.isEmpty() && (transitiveSetsCast.size() == 1)) {
      NestedSet<E> candidate = getOnlyElement(transitiveSetsCast);
      if (candidate.getOrder().equals(order)) {
        return candidate;
      }
    }
    return new NestedSet<>(order, items, transitiveSetsCast);
  }

  private static final ConcurrentMap<ImmutableList<?>, NestedSet<?>> immutableListCache =
      new MapMaker().weakKeys().makeMap();

  /**
   * Creates a nested set from a given list of items.
   */
  @SuppressWarnings("unchecked")
  public static <E> NestedSet<E> wrap(Order order, Iterable<E> wrappedItems) {
    ImmutableList<E> wrappedList = ImmutableList.copyOf(wrappedItems);
    if (wrappedList.isEmpty()) {
      return order.emptySet();
    } else if (order == Order.STABLE_ORDER
               && wrappedList == wrappedItems && wrappedList.size() > 1) {
      NestedSet<?> cached = immutableListCache.get(wrappedList);
      if (cached != null) {
        return (NestedSet<E>) cached;
      }
      NestedSet<E> built = new NestedSetBuilder<E>(order).addAll(wrappedList).build();
      immutableListCache.putIfAbsent(wrappedList, built);
      return built;
    } else {
      return new NestedSetBuilder<E>(order).addAll(wrappedList).build();
    }
  }

  /**
   * Creates a nested set with the given list of items as its elements.
   */
  @SuppressWarnings("unchecked")
  public static <E> NestedSet<E> create(Order order, E... elems) {
    return wrap(order, ImmutableList.copyOf(elems));
  }

  /**
   * Creates an empty nested set.
   */
  public static <E> NestedSet<E> emptySet(Order order) {
    return order.emptySet();
  }

  /**
   * Creates a builder for stable order nested sets.
   */
  public static <E> NestedSetBuilder<E> stableOrder() {
    return new NestedSetBuilder<>(Order.STABLE_ORDER);
  }

  /**
   * Creates a builder for compile order nested sets.
   */
  public static <E> NestedSetBuilder<E> compileOrder() {
    return new NestedSetBuilder<>(Order.COMPILE_ORDER);
  }

  /**
   * Creates a builder for link order nested sets.
   */
  public static <E> NestedSetBuilder<E> linkOrder() {
    return new NestedSetBuilder<>(Order.LINK_ORDER);
  }

  /**
   * Creates a builder for naive link order nested sets.
   */
  public static <E> NestedSetBuilder<E> naiveLinkOrder() {
    return new NestedSetBuilder<>(Order.NAIVE_LINK_ORDER);
  }

  public static <E> NestedSetBuilder<E> fromNestedSet(NestedSet<E> set) {
    return new NestedSetBuilder<E>(set.getOrder()).addTransitive(set);
  }

  /**
   * Creates a Builder with the contents of 'sets'.
   *
   * <p>If 'sets' is empty, a stable-order empty NestedSet is returned.
   */
  public static <E> NestedSetBuilder<E> fromNestedSets(Iterable<NestedSet<E>> sets) {
    NestedSet<?> firstSet = Iterables.getFirst(sets, null /* defaultValue */);
    if (firstSet == null) {
      return stableOrder();
    }
    NestedSetBuilder<E> result = new NestedSetBuilder<>(firstSet.getOrder());
    sets.forEach(result::addTransitive);
    return result;
  }
}
