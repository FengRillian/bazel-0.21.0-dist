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
package com.google.devtools.build.lib.packages;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable;
import java.util.ArrayList;
import java.util.Objects;

/**
 * Captures the the set of providers rules and aspects can advertise.
 * It is either of:
 * <ul>
 *    <li>a set of native and skylark providers</li>
 *    <li>"can have any provider" set that alias rules have.</li>
 * </ul>
 *
 * <p>
 * Native providers should in theory only contain subclasses of
 * {@link com.google.devtools.build.lib.analysis.TransitiveInfoProvider}, but
 * our current dependency structure does not allow a reference to that class here.
 * </p>
 */
@Immutable
public final class AdvertisedProviderSet {
  private final boolean canHaveAnyProvider;
  private final ImmutableSet<Class<?>> nativeProviders;
  private final ImmutableSet<SkylarkProviderIdentifier> skylarkProviders;

  private AdvertisedProviderSet(boolean canHaveAnyProvider,
      ImmutableSet<Class<?>> nativeProviders,
      ImmutableSet<SkylarkProviderIdentifier> skylarkProviders) {
    this.canHaveAnyProvider = canHaveAnyProvider;
    this.nativeProviders = nativeProviders;
    this.skylarkProviders = skylarkProviders;
  }

  public static final AdvertisedProviderSet ANY =
      new AdvertisedProviderSet(true,
          ImmutableSet.<Class<?>>of(),
          ImmutableSet.<SkylarkProviderIdentifier>of());
  public static final AdvertisedProviderSet EMPTY =
      new AdvertisedProviderSet(false,
          ImmutableSet.<Class<?>>of(),
          ImmutableSet.<SkylarkProviderIdentifier>of());

  public static AdvertisedProviderSet create(
      ImmutableSet<Class<?>> nativeProviders,
      ImmutableSet<SkylarkProviderIdentifier> skylarkProviders) {
    if (nativeProviders.isEmpty() && skylarkProviders.isEmpty()) {
      return EMPTY;
    }
    return new AdvertisedProviderSet(false, nativeProviders, skylarkProviders);
  }

  @Override
  public int hashCode() {
    return Objects.hash(canHaveAnyProvider, nativeProviders, skylarkProviders);
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj) {
      return true;
    }

    if (!(obj instanceof AdvertisedProviderSet)) {
      return false;
    }

    AdvertisedProviderSet that = (AdvertisedProviderSet) obj;
    return Objects.equals(this.canHaveAnyProvider, that.canHaveAnyProvider)
        && Objects.equals(this.nativeProviders, that.nativeProviders)
        && Objects.equals(this.skylarkProviders, that.skylarkProviders);
  }

  @Override
  public String toString() {
    if (canHaveAnyProvider()) {
      return "Any Provider";
    }
    return String.format(
        "allowed native providers=%s, allowed Starlark providers=%s",
        getNativeProviders(), getSkylarkProviders());
  }

  /** Checks whether the rule can have any provider.
   *
   *  Used for alias rules.
   */
  public boolean canHaveAnyProvider() {
    return canHaveAnyProvider;
  }

  /**
   * Get all advertised native providers.
   */
  public ImmutableSet<Class<?>> getNativeProviders() {
    return nativeProviders;
  }

  /**
   * Get all advertised Skylark providers.
   */
  public ImmutableSet<SkylarkProviderIdentifier> getSkylarkProviders() {
    return skylarkProviders;
  }

  public static Builder builder() {
    return new Builder();
  }

  /** Builder for {@link AdvertisedProviderSet} */
  public static class Builder {
    private boolean canHaveAnyProvider;
    private final ArrayList<Class<?>> nativeProviders;
    private final ArrayList<SkylarkProviderIdentifier> skylarkProviders;
    private Builder() {
      nativeProviders = new ArrayList<>();
      skylarkProviders = new ArrayList<>();
    }

    /**
     * Advertise all providers inherited from a parent rule.
     */
    public Builder addParent(AdvertisedProviderSet parentSet) {
      Preconditions.checkState(!canHaveAnyProvider, "Alias rules inherit from no other rules");
      Preconditions.checkState(!parentSet.canHaveAnyProvider(),
          "Cannot inherit from alias rules");
      nativeProviders.addAll(parentSet.getNativeProviders());
      skylarkProviders.addAll(parentSet.getSkylarkProviders());
      return this;
    }

    public Builder addNative(Class<?> nativeProvider) {
      this.nativeProviders.add(nativeProvider);
      return this;
    }

    public void canHaveAnyProvider() {
      Preconditions.checkState(nativeProviders.isEmpty() && skylarkProviders.isEmpty());
      this.canHaveAnyProvider = true;
    }

    public AdvertisedProviderSet build() {
      if (canHaveAnyProvider) {
        Preconditions.checkState(nativeProviders.isEmpty() && skylarkProviders.isEmpty());
        return ANY;
      }
      return AdvertisedProviderSet.create(
          ImmutableSet.copyOf(nativeProviders), ImmutableSet.copyOf(skylarkProviders));
    }

    public Builder addSkylark(String providerName) {
      skylarkProviders.add(SkylarkProviderIdentifier.forLegacy(providerName));
      return this;
    }

    public Builder addSkylark(SkylarkProviderIdentifier id) {
      skylarkProviders.add(id);
      return this;
    }

    public Builder addSkylark(Provider.Key id) {
      skylarkProviders.add(SkylarkProviderIdentifier.forKey(id));
      return this;
    }
  }
}
