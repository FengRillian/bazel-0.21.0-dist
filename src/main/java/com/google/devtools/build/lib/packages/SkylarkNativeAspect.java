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

import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.events.Location;
import com.google.devtools.build.lib.skylarkinterface.SkylarkPrinter;

/** A natively-defined aspect that is may be referenced by skylark attribute definitions. */
public abstract class SkylarkNativeAspect extends NativeAspectClass implements SkylarkAspect {
  @Override
  public void repr(SkylarkPrinter printer) {
    printer.append("<native aspect>");
  }

  @Override
  public void attachToAttribute(Attribute.Builder<?> attrBuilder, Location loc) {
    attrBuilder.aspect(this);
  }

  @Override
  public AspectClass getAspectClass() {
    return this;
  }

  @Override
  public ImmutableSet<String> getParamAttributes() {
    return ImmutableSet.of();
  }
}
