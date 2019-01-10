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
package com.google.devtools.build.lib.collect;

import com.google.common.collect.ImmutableList;
import java.util.Comparator;
import java.util.PriorityQueue;

/**
 * A stream aggregator that, given a {@code k}, aggregates a sequence of elements into the {@code k}
 * most extreme.
 */
public class Extrema<T extends Comparable<T>> {
  private final int k;
  private final Comparator<T> extremaComparator;
  private final PriorityQueue<T> priorityQueue;

  /**
   * Creates an {@link Extrema} that aggregates a sequence of elements into the {@code k} smallest.
   */
  public static <T extends Comparable<T>> Extrema<T> min(int k) {
    return new Extrema<>(k, Comparator.<T>naturalOrder());
  }

  /**
   * Creates an {@link Extrema} that aggregates a sequence of elements into the {@code k} largest.
   */
  public static <T extends Comparable<T>> Extrema<T> max(int k) {
    return new Extrema<>(k, Comparator.<T>naturalOrder().reversed());
  }

  /**
   * @param k the number of extreme elements to compute
   * @param extremaComparator a comparator such that {@code extremaComparator(a, b) < 0} iff
   *        {@code a} is more extreme than {@code b}
   */
  private Extrema(int k, Comparator<T> extremaComparator) {
    this.k = k;
    this.extremaComparator = extremaComparator;
    this.priorityQueue = new PriorityQueue<>(
        /*initialCapacity=*/ k,
        // Our implementation strategy is to keep a priority queue of the k most extreme elements
        // encountered, ordered backwards; this way we have constant-time access to the least
        // extreme among these elements.
        extremaComparator.reversed());
  }

  /**
   * Aggregates the given element.
   *
   * <p>See {@link #getExtremeElements()}.
   */
  public void aggregate(T element) {
    if (priorityQueue.size() < k) {
      priorityQueue.add(element);
    } else {
      if (extremaComparator.compare(element, priorityQueue.peek()) < 0) {
        // Suppose the least extreme of the current k most extreme elements is e. If the new element
        // is more extreme than e, then (i) it must be among the new k most extreme among the (2) e
        // must not be.
        priorityQueue.remove();
        priorityQueue.add(element);
      }
    }
  }

  /**
   * For an {@link Extrema} created with {@code k} and with {@code n} calls to {@link #aggregate}
   * since the most recent call to {@link #clear}, returns the min(k, n) most extreme elements
   * {@link #aggregate}'ed since the most recent call to {@link #clear}.
   */
  public ImmutableList<T> getExtremeElements() {
    return ImmutableList.sortedCopyOf(extremaComparator, priorityQueue);
  }

  /**
   * Disregards all the elements {@link #aggregate}'ed already.
   *
   * <p>See {@link #getExtremeElements()}.
   */
  public void clear() {
    priorityQueue.clear();
  }
}
