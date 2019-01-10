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
package com.google.devtools.build.lib.skylarkinterface;

import com.google.devtools.build.lib.syntax.SkylarkSemantics;
import com.google.devtools.build.lib.syntax.SkylarkSemantics.FlagIdentifier;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * An annotation for parameters of Skylark built-in functions.
 */
@Retention(RetentionPolicy.RUNTIME)
public @interface Param {

  /**
   * Name of the parameter, as viewed from Skylark. Used for matching keyword arguments and for
   * generating documentation.
   */
  String name();

  /**
   * Documentation of the parameter.
   */
  String doc() default "";

  /**
   * Default value for the parameter, written as a Skylark expression (e.g. "False", "True", "[]",
   * "None").
   *
   * <p>If this is empty (the default), the parameter is treated as mandatory. (Thus an exception
   * will be thrown if left unspecified by the caller).
   *
   * <p>If the function implementation needs to distinguish the case where the caller does not
   * supply a value for this parameter, you can set the default to the magic string "unbound", which
   * maps to the sentinal object {@link com.google.devtools.build.lib.syntax.Runtime#UNBOUND}
   * (which can't appear in normal Skylark code).
   */
  String defaultValue() default "";

  /**
   * Type of the parameter, e.g. {@link String}.class or
   * {@link com.google.devtools.build.lib.syntax.SkylarkList}.class.
   */
  Class<?> type() default Object.class;

  /**
   * List of allowed types for the parameter if multiple types are allowed.
   *
   * <p>If using this, {@link #type()} should be set to {@code Object.class}.
   */
  ParamType[] allowedTypes() default {};

  /**
   * When {@link #type()} is a generic type (e.g.,
   * {@link com.google.devtools.build.lib.syntax.SkylarkList}), specify the type parameter (e.g.
   * {@link String}.class} along with {@link com.google.devtools.build.lib.syntax.SkylarkList} for
   * {@link #type()} to specify a list of strings).
   *
   * <p>This is only used for documentation generation. The actual generic type is not checked at
   * runtime, so the Java method signature should use a generic type of Object and cast
   * appropriately.
   */
  Class<?> generic1() default Object.class;

  /**
   * Whether the name of a callback function can be given instead of a computed value. If a
   * callback function is used then the value of this parameter will be computed only when
   * actually requested. E.g., if a parameter {@code foo} of a function {@code bar} is passed a
   * callback function, then only when the method {@code bar} actually asks for the value
   * {@code foo}, replacing it by a
   * {@link com.google.devtools.build.lib.syntax.SkylarkCallbackFunction} in between.
   */
  boolean callbackEnabled() default false;

  /**
   * If true, this parameter can be passed the "None" value in addition to whatever types it allows.
   * If false, this parameter cannot be passed "None", no matter the types it allows.
   */
  boolean noneable() default false;

  /**
   * If true, the parameter may be specified as a named parameter. For example for an integer named
   * parameter {@code foo} of a method {@code bar}, then the method call will look like {@code
   * bar(foo=1)}.
   *
   * <p>If false, then {@link #positional} must be true (otherwise there is no way to reference the
   * parameter via an argument).
   *
   * <p>If this parameter represents the 'extra positionals' (args) or 'extra keywords' (kwargs)
   * element of a method, this field has no effect.
   */
  boolean named() default false;

  /**
   * If this true, {@link #named} should be treated as true.
   *
   * <p>This indicates this parameter is part of a {@link SkylarkCallable} method which
   * was migrated from {@link SkylarkSignature}. Due to a pre-migration bug, all parameters were
   * treated as if {@link #named} was true, even if it was false. To prevent breakages during
   * migration, the interpreter can continue to treat these parameters as named. This is distinct
   * from {@link #named}, however, so that a bulk fix/cleanup will be easier later.
   */
  // TODO(b/77902276): Remove this after a bulk cleanup/fix.
  boolean legacyNamed() default false;

  /**
   * If true, the parameter may be specified as a positional parameter. For example for an integer
   * positional parameter {@code foo} of a method {@code bar}, then the method call will look like
   * {@code bar(1)}. If {@link #named()} is {@code false}, then this will be the only way to call
   * {@code bar}.
   *
   * <p>If false, then {@link #named} must be true (otherwise there is no way to reference the
   * parameter via an argument)
   *
   * <p>Positional arguments should come first.
   *
   * <p>If this parameter represents the 'extra positionals' (args) or 'extra keywords' (kwargs)
   * element of a method, this field has no effect.
   */
  boolean positional() default true;

  /**
   * If not NONE, the annotated parameter will only be present if the given semantic flag is true.
   * (If the parameter is disabled, it may not be specified by a user, and the Java method will
   * always be invoked with the parameter set to its default value.)
   *
   * <p>Note that at most one of {@link #enableOnlyWithFlag} and {@link #disableWithFlag} can be
   * non-NONE.
   */
  SkylarkSemantics.FlagIdentifier enableOnlyWithFlag() default FlagIdentifier.NONE;

  /**
   * If not NONE, the annotated parameter will only be present if the given semantic flag is false.
   * (If the parameter is disabled, it may not be specified by a user, and the Java method will
   * always be invoked with the parameter set to its default value.)
   *
   * <p>Note that at most one of {@link #enableOnlyWithFlag} and {@link #disableWithFlag} can be
   * non-NONE.
   */
  SkylarkSemantics.FlagIdentifier disableWithFlag() default FlagIdentifier.NONE;

  /**
   * Value for the parameter when the parameter is "disabled" based on semantic flags. (When the
   * parameter is disabled, it may not be set from Starlark, but an argument of the given value is
   * passed to the annotated Java method when invoked.) (See {@link #enableOnlyWithFlag()} and
   * {@link #disableWithFlag()} for toggling a parameter with semantic flags.
   *
   * <p>The parameter value is written as a Skylark expression (for example: "False", "True", "[]",
   * "None").
   *
   * <p>This should be set (non-empty) if and only if the parameter may be disabled with a semantic
   * flag.
   *
   * <p>Note that this is very similar to {@link #defaultValue}; it may be considered "the default
   * value if no parameter is specified". It is important that this is distinct, however, in cases
   * where it is desired to have a normally-mandatory parameter toggled by flag. Such a parameter
   * should have no {@link #defaultValue} set, but should have a sensible {@link
   * #valueWhenDisabled()} set. ("unbound" may be used in cases where no value would be valid. See
   * {@link #defaultValue}.)
   */
  String valueWhenDisabled() default "";

  // TODO(bazel-team): parse the type from a single field in Skylark syntax,
  // and allow a Union as "ThisType or ThatType or NoneType":
  // String type() default "Object";
}
