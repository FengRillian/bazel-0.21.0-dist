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
package com.google.devtools.build.lib.skylarkinterface;

import com.google.devtools.build.lib.syntax.SkylarkSemantics;
import com.google.devtools.build.lib.syntax.SkylarkSemantics.FlagIdentifier;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotates a Java method that can be called from Skylark.
 *
 * <p>This annotation is only allowed to appear on methods of classes that are directly annotated
 * with {@link SkylarkModule} or {@link SkylarkGlobalLibrary}. Since subtypes can't add new
 * Skylark-accessible methods unless they have their own {@code @SkylarkModule} annotation, this
 * implies that you can always determine the complete set of Skylark entry points for a given {@link
 * SkylarkValue} type by looking at the ancestor class or interface from which it inherits its
 * {@code @SkylarkModule}.
 *
 * <p>If a method is annotated with {@code @SkylarkCallable}, it is not allowed to have any
 * overloads or hide any static or default methods. Overriding is allowed, but the {@code
 * @SkylarkCallable} annotation itself must not be repeated on the override. This ensures that given
 * a method, we can always determine its corresponding {@code @SkylarkCallable} annotation, if it
 * has one, by scanning all methods of the same name in its class hierarchy, without worrying about
 * complications like overloading or generics. The lookup functionality is implemented by {@link
 * SkylarkInterfaceUtils#getSkylarkCallable}.
 *
 * <p>Methods having this annotation are required to satisfy the following (enforced by an
 * annotation processor):
 *
 * <ul>
 *   <li>The method must be public.
 *   <li>If structField=true, there must be zero user-supplied parameters.
 *   <li>The underlying java method's parameters must be supplied in the following order:
 *       <pre>method([positionals]*[named args]*(extra positionals list)(extra kwargs)
 *       (Location)(FuncallExpression)(Envrionment)(SkylarkSemantics))</pre>
 *       where (extra positionals list) is a SkylarkList if extraPositionals is defined, (extra
 *       kwargs) is a SkylarkDict if extraKeywords is defined, and Location, FuncallExpression,
 *       Environment, and SkylarkSemantics are supplied by the interpreter if and only if
 *       useLocation, useAst, useEnvironment, and useSkylarkSemantics are specified, respectively.
 *   <li>The number of method parameters much match the number of annotation-declared parameters
 *       plus the number of interpreter-supplied parameters.
 * </ul>
 */
@Target({ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface SkylarkCallable {

  /**
   * Name of the method, as exposed to Skylark.
   */
  String name();

  /**
   * The documentation text in Skylark. It can contain HTML tags for special formatting.
   *
   * <p>It is allowed to be empty only if {@link #documented()} is false.
   */
  String doc() default "";

  /**
   * If true, the function will appear in the Skylark documentation. Set this to false if the
   * function is experimental or an overloading and doesn't need to be documented.
   */
  boolean documented() default true;

  /**
   * If true, this method will be considered as a field of the enclosing Java object. E.g., if set
   * to true on a method {@code foo}, then the callsites of this method will look like {@code
   * bar.foo} instead of {@code bar.foo()}. The annotated method must be parameterless and {@link
   * #parameters()} should be empty.
   */
  boolean structField() default false;

  /**
   * List of parameters this function accepts.
   */
  Param[] parameters() default {};

  /**
   * Defines a catch-all list for additional unspecified positional parameters.
   *
   * <p>If this is left as default, it is an error for the caller to pass more positional arguments
   * than are explicitly allowed by the method signature. If this is defined, all additional
   * positional arguments are passed as elements of a {@link SkylarkList} to the method.
   *
   * <p>See python's <code>*args</code> (http://thepythonguru.com/python-args-and-kwargs/).
   *
   * <p>(If this is defined, the annotated method signature must contain a corresponding SkylarkList
   * parameter. See the interface-level javadoc for details.)
   */
  Param extraPositionals() default @Param(name = "");

  /**
   * Defines a catch-all dictionary for additional unspecified named parameters.
   *
   * <p>If this is left as default, it is an error for the caller to pass any named arguments not
   * explicitly declared by the method signature. If this is defined, all additional named arguments
   * are passed as elements of a {@link SkylarkDict} to the method.
   *
   * <p>See python's <code>**kwargs</code> (http://thepythonguru.com/python-args-and-kwargs/).
   *
   * <p>(If this is defined, the annotated method signature must contain a corresponding SkylarkDict
   * parameter. See the interface-level javadoc for details.)
   */
  Param extraKeywords() default @Param(name = "");

  /**
   * If true, indicates that the class containing the annotated method has the ability to be called
   * from skylark (as if it were a function) and that the annotated method should be invoked when
   * this occurs.
   *
   * <p>A class may only have one method with selfCall set to true.</p>
   *
   * <p>A method with selfCall=true must not be a structField, and must have name specified
   * (used for descriptive errors if, for example, there are missing arguments).</p>
   */
  boolean selfCall() default false;

  /**
   * Set it to true if the Java method may return <code>null</code> (which will then be converted to
   * <code>None</code>). If not set and the Java method returns null, an error will be raised.
   */
  boolean allowReturnNones() default false;

  /**
   * If true, the location of the call site will be passed as an argument of the annotated function.
   * (Thus, the annotated method signature must contain Location as a parameter. See the
   * interface-level javadoc for details.)
   *
   * <p>This is incompatible with structField=true. If structField is true, this must be false.
   */
  boolean useLocation() default false;

  /**
   * If true, the AST of the call site will be passed as an argument of the annotated function.
   * (Thus, the annotated method signature must contain FuncallExpression as a parameter. See the
   * interface-level javadoc for details.)
   *
   * <p>This is incompatible with structField=true. If structField is true, this must be false.
   */
  boolean useAst() default false;

  /**
   * If true, the Skylark Environment will be passed as an argument of the annotated function.
   * (Thus, the annotated method signature must contain Environment as a parameter. See the
   * interface-level javadoc for details.)
   *
   * <p>This is incompatible with structField=true. If structField is true, this must be false.
   */
  boolean useEnvironment() default false;

  /**
   * If true, the Skylark semantics will be passed as an argument of the annotated function. (Thus,
   * the annotated method signature must contain SkylarkSemantics as a parameter. See the
   * interface-level javadoc for details.)
   */
  boolean useSkylarkSemantics() default false;

  /**
   * If not NONE, the annotated method will only be callable if the given semantic flag is true.
   * Note that at most one of {@link #enableOnlyWithFlag} and {@link #disableWithFlag} can be
   * non-NONE.
   */
  SkylarkSemantics.FlagIdentifier enableOnlyWithFlag() default FlagIdentifier.NONE;

  /**
   * If not NONE, the annotated method will only be callable if the given semantic flag is false.
   * Note that at most one of {@link #enableOnlyWithFlag} and {@link #disableWithFlag} can be
   * non-NONE.
   */
  SkylarkSemantics.FlagIdentifier disableWithFlag() default FlagIdentifier.NONE;
}
