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
package com.google.devtools.build.lib.syntax;

import static com.google.common.collect.ImmutableList.toImmutableList;

import com.google.auto.value.AutoValue;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Interner;
import com.google.devtools.build.lib.concurrent.BlazeInterners;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.AutoCodec;
import com.google.devtools.build.lib.syntax.Printer.BasePrinter;
import com.google.devtools.build.lib.util.StringCanonicalizer;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * Function Signatures for BUILD language (same as Python)
 *
 * <p>Skylark's function signatures are just like Python3's. A function may have 6 kinds of
 * arguments: positional mandatory, positional optional, positional rest (aka *star argument),
 * key-only mandatory, key-only optional, key rest (aka **star_star argument). A caller may specify
 * all arguments but the *star and **star_star arguments by name, and thus all mandatory and
 * optional arguments are named arguments.
 *
 * <p>To enable various optimizations in the argument processing routine, we sort arguments
 * according the following constraints, enabling corresponding optimizations:
 *
 * <ol>
 *   <li>The positional mandatories come just before the positional optionals, so they can be filled
 *       in one go.
 *   <li>Positionals come first, so it's easy to prepend extra positional arguments such as "self"
 *       to an argument list, and we optimize for the common case of no key-only mandatory
 *       parameters. key-only parameters are thus grouped together. positional mandatory and
 *       key-only mandatory parameters are separate, but there is no loop over a contiguous chunk of
 *       them, anyway.
 *   <li>The named are all grouped together, with star and star_star rest arguments coming last.
 *   <li>Mandatory arguments in each category (positional and named-only) come before the optional
 *       arguments, for the sake of slightly better clarity to human implementers. This eschews an
 *       optimization whereby grouping optionals together allows to iterate over them in one go
 *       instead of two; however, this relatively minor optimization only matters when keyword
 *       arguments are passed, at which point it is dwarfed by the slowness of keyword processing.
 * </ol>
 *
 * <p>Parameters are thus sorted in the following order: positional mandatory arguments (if any),
 * positional optional arguments (if any), key-only mandatory arguments (if any), key-only optional
 * arguments (if any), then star argument (if any), then star_star argument (if any).
 */
@AutoCodec
@AutoValue
public abstract class FunctionSignature implements Serializable {

  /** The shape of a FunctionSignature, without names */
  @AutoValue
  @AutoCodec
  public abstract static class Shape implements Serializable {
    private static final Interner<Shape> interner = BlazeInterners.newWeakInterner();

    /** Create a function signature */
    @AutoCodec.Instantiator
    public static Shape create(
        int mandatoryPositionals,
        int optionalPositionals,
        int mandatoryNamedOnly,
        int optionalNamedOnly,
        boolean hasStarArg,
        boolean hasKwArg) {
      Preconditions.checkArgument(
          0 <= mandatoryPositionals && 0 <= optionalPositionals
          && 0 <= mandatoryNamedOnly && 0 <= optionalNamedOnly);
      return interner.intern(
          new AutoValue_FunctionSignature_Shape(
              mandatoryPositionals,
              optionalPositionals,
              mandatoryNamedOnly,
              optionalNamedOnly,
              hasStarArg,
              hasKwArg));
    }

    // These abstract getters specify the actual argument count fields to be defined by AutoValue.
    /** number of mandatory positional arguments */
    public abstract int getMandatoryPositionals();

    /** number of optional positional arguments */
    public abstract int getOptionalPositionals();

    /** number of mandatory named-only arguments. */
    public abstract int getMandatoryNamedOnly();

    /** number of optional named-only arguments */
    public abstract int getOptionalNamedOnly();

    /** indicator for presence of a star argument for extra positional arguments */
    public abstract boolean hasStarArg();

    /** indicator for presence of a star-star argument for extra keyword arguments */
    public abstract boolean hasKwArg();


    // These are computed argument counts
    /** number of optional and mandatory positional arguments. */
    public int getPositionals() {
      return getMandatoryPositionals() + getOptionalPositionals();
    }

    /** number of optional and mandatory named-only arguments. */
    public int getNamedOnly() {
      return getMandatoryNamedOnly() + getOptionalNamedOnly();
    }

    /** number of optional arguments. */
    public int getOptionals() {
      return getOptionalPositionals() + getOptionalNamedOnly();
    }

    /** number of all named parameters: mandatory and optional of positionals and named-only */
    public int getAllNamed() {
      return getPositionals() + getNamedOnly();
    }

    /** total number of arguments */
    public int getArguments() {
      return getAllNamed() + (hasStarArg() ? 1 : 0) + (hasKwArg() ? 1 : 0);
    }
  }

  /** Names of a FunctionSignature */
  private static final Interner<ImmutableList<String>> namesInterner =
      BlazeInterners.newWeakInterner();

  /** Intern a list of names */
  public static ImmutableList<String> names(List<String> names) {
    return namesInterner.intern(
        names.stream().map(StringCanonicalizer::intern).collect(toImmutableList()));
  }

  // Interner
  private static final Interner<FunctionSignature> signatureInterner =
      BlazeInterners.newWeakInterner();

  /**
   * Signatures proper.
   *
   * <p>A signature is a Shape and an ImmutableList of argument variable names NB: we assume these
   * lists are short, so we may do linear scans.
   */
  @AutoCodec.Instantiator
  public static FunctionSignature create(Shape shape, ImmutableList<String> names) {
    Preconditions.checkArgument(names.size() == shape.getArguments());
    return signatureInterner.intern(new AutoValue_FunctionSignature(shape, names(names)));
  }



  // Field definition (details filled in by AutoValue)
  /** The shape */
  public abstract Shape getShape();

  /** The names */
  public abstract ImmutableList<String> getNames();

  /** append a representation of this signature to a string buffer. */
  public StringBuilder toStringBuilder(StringBuilder sb) {
    return WithValues.<Object, SkylarkType>create(this).toStringBuilder(sb);
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    toStringBuilder(sb);
    return sb.toString();
  }

  /**
   * FunctionSignature.WithValues: also specifies a List of default values and types.
   *
   * <p>The lists can be null, which is an optimized path for specifying all null values.
   *
   * <p>Note that if some values can be null (for BuiltinFunction, not for UserDefinedFunction), you
   * should use an ArrayList; otherwise, we recommend an ImmutableList.
   *
   * <p>V is the class of defaultValues and T is the class of types. When parsing a function
   * definition at compile-time, they are &lt;Expression, Expression&gt;; when processing
   * a @SkylarkSignature annotation at build-time, &lt;Object, SkylarkType&gt;.
   */
  @AutoCodec
  @AutoValue
  public abstract static class WithValues<V, T> implements Serializable {

    // The fields
    /** The underlying signature with parameter shape and names */
    public abstract FunctionSignature getSignature();

    /**
     * The default values (if any) as an unmodifiable List of one per optional parameter. May
     * contain nulls.
     */
    @Nullable public abstract List<V> getDefaultValues();

    /**
     * The parameter types (if specified) as an unmodifiable List of one per parameter, including *
     * and **. May contain nulls.
     */
    @Nullable public abstract List<T> getTypes();


    /** Create a signature with (default and type) values. */
    public static <V, T> WithValues<V, T> create(FunctionSignature signature,
        @Nullable List<V> defaultValues, @Nullable List<T> types) {
      Shape shape = signature.getShape();
      List<V> convertedDefaultValues = null;
      if (defaultValues != null) {
        Preconditions.checkArgument(defaultValues.size() == shape.getOptionals());
        List<V> copiedDefaultValues = new ArrayList<>();
        copiedDefaultValues.addAll(defaultValues);
        convertedDefaultValues = Collections.unmodifiableList(copiedDefaultValues);
      }
      List<T> convertedTypes = null;
      if (types != null) {
        Preconditions.checkArgument(types.size() == shape.getArguments());
        List<T> copiedTypes = new ArrayList<>();
        copiedTypes.addAll(types);
        convertedTypes = Collections.unmodifiableList(copiedTypes);
      }
      return createInternal(signature, convertedDefaultValues, convertedTypes);
    }

    public static <V, T> WithValues<V, T> create(FunctionSignature signature) {
      return create(signature, null, null);
    }

    @AutoCodec.VisibleForSerialization
    @AutoCodec.Instantiator
    static <V, T> WithValues<V, T> createInternal(
        FunctionSignature signature, @Nullable List<V> defaultValues, @Nullable List<T> types) {
      return new AutoValue_FunctionSignature_WithValues<>(signature, defaultValues, types);
    }

    /**
     * Parse a list of Parameter into a FunctionSignature.
     *
     * <p>To be used both by the Parser and by the SkylarkSignature annotation processor.
     */
    public static <V, T> WithValues<V, T> of(Iterable<Parameter<V, T>> parameters)
        throws SignatureException {
      int mandatoryPositionals = 0;
      int optionalPositionals = 0;
      int mandatoryNamedOnly = 0;
      int optionalNamedOnly = 0;
      boolean hasStarStar = false;
      boolean hasStar = false;
      @Nullable String star = null;
      @Nullable String starStar = null;
      @Nullable T starType = null;
      @Nullable T starStarType = null;
      ArrayList<String> params = new ArrayList<>();
      ArrayList<V> defaults = new ArrayList<>();
      ArrayList<T> types = new ArrayList<>();
      // optional named-only parameters are kept aside to be spliced after the mandatory ones.
      ArrayList<String> optionalNamedOnlyParams = new ArrayList<>();
      ArrayList<T> optionalNamedOnlyTypes = new ArrayList<>();
      ArrayList<V> optionalNamedOnlyDefaultValues = new ArrayList<>();
      boolean defaultRequired = false; // true after mandatory positionals and before star.
      Set<String> paramNameSet = new HashSet<>(); // set of names, to avoid duplicates

      for (Parameter<V, T> param : parameters) {
        if (hasStarStar) {
          throw new SignatureException("illegal parameter after star-star parameter", param);
        }
        @Nullable String name = param.getName();
        @Nullable T type = param.getType();
        if (param.hasName()) {
          if (paramNameSet.contains(name)) {
            throw new SignatureException("duplicate parameter name in function definition", param);
          }
          paramNameSet.add(name);
        }
        if (param.isStarStar()) {
          hasStarStar = true;
          starStar = name;
          starStarType = type;
        } else if (param.isStar()) {
          if (hasStar) {
            throw new SignatureException(
                "duplicate star parameter in function definition", param);
          }
          hasStar = true;
          defaultRequired = false;
          if (param.hasName()) {
            star = name;
            starType = type;
          }
        } else if (hasStar && param.isOptional()) {
          optionalNamedOnly++;
          optionalNamedOnlyParams.add(name);
          optionalNamedOnlyTypes.add(type);
          optionalNamedOnlyDefaultValues.add(param.getDefaultValue());
        } else {
          params.add(name);
          types.add(type);
          if (param.isOptional()) {
            optionalPositionals++;
            defaults.add(param.getDefaultValue());
            defaultRequired = true;
          } else if (hasStar) {
            mandatoryNamedOnly++;
          } else if (defaultRequired) {
              throw new SignatureException(
                  "a mandatory positional parameter must not follow an optional parameter",
                  param);
          } else {
            mandatoryPositionals++;
          }
        }
      }
      params.addAll(optionalNamedOnlyParams);
      types.addAll(optionalNamedOnlyTypes);
      defaults.addAll(optionalNamedOnlyDefaultValues);

      if (star != null) {
        params.add(star);
        types.add(starType);
      }
      if (starStar != null) {
        params.add(starStar);
        types.add(starStarType);
      }
      return WithValues.create(
          FunctionSignature.create(
              Shape.create(
                  mandatoryPositionals, optionalPositionals,
                  mandatoryNamedOnly, optionalNamedOnly,
                  star != null, starStar != null),
              ImmutableList.copyOf(params)),
          FunctionSignature.valueListOrNull(defaults),
          FunctionSignature.valueListOrNull(types));
    }

    public StringBuilder toStringBuilder(final StringBuilder sb) {
      return toStringBuilder(sb, true, true, false);
    }

    /**
     * Appends a representation of this signature to a string buffer.
     *
     * @param sb Output StringBuffer
     * @param showDefaults Determines whether the default values of arguments should be printed (if
     *     present)
     * @param showTypes Determines whether parameter type information should be shown
     * @param skipFirstMandatory Determines whether the first mandatory parameter should be omitted.
     */
    public StringBuilder toStringBuilder(
        final StringBuilder sb,
        final boolean showDefaults,
        final boolean showTypes,
        final boolean skipFirstMandatory) {
      FunctionSignature signature = getSignature();
      Shape shape = signature.getShape();
      final BasePrinter printer = Printer.getPrinter(sb);
      final ImmutableList<String> names = signature.getNames();
      @Nullable final List<V> defaultValues = getDefaultValues();
      @Nullable final List<T> types = getTypes();

      int mandatoryPositionals = shape.getMandatoryPositionals();
      int optionalPositionals = shape.getOptionalPositionals();
      int mandatoryNamedOnly = shape.getMandatoryNamedOnly();
      int optionalNamedOnly = shape.getOptionalNamedOnly();
      boolean starArg = shape.hasStarArg();
      boolean kwArg = shape.hasKwArg();
      int positionals = mandatoryPositionals + optionalPositionals;
      int namedOnly = mandatoryNamedOnly + optionalNamedOnly;
      int named = positionals + namedOnly;
      int args = named + (starArg ? 1 : 0) + (kwArg ? 1 : 0);
      int endMandatoryNamedOnly = positionals + mandatoryNamedOnly;
      boolean hasStar = starArg || (namedOnly > 0);
      int iStarArg = named;
      int iKwArg = args - 1;

      class Show {
        private boolean isMore = false;
        private int j = 0;

        public void comma() {
          if (isMore) {
            printer.append(", ");
          }
          isMore = true;
        }
        public void type(int i) {
          // We have to assign an artificial type string when the type is null.
          // This happens when either
          // a) there is no type defined (such as in user-defined functions) or
          // b) the type is java.lang.Object.
          boolean typeDefined = types != null && types.get(i) != null;
          if (typeDefined && showTypes) {
            printer.append(": ");
            printer.append(types.get(i).toString());
          }
        }
        public void mandatory(int i) {
          comma();
          printer.append(names.get(i));
          type(i);
        }
        public void optional(int i) {
          mandatory(i);
          if (showDefaults) {
            printer.append(" = ");
            if (defaultValues == null) {
              printer.append("?");
            } else {
              printer.repr(defaultValues.get(j++));
            }
          }
        }
      }

      Show show = new Show();

      int i = skipFirstMandatory ? 1 : 0;
      for (; i < mandatoryPositionals; i++) {
        show.mandatory(i);
      }
      for (; i < positionals; i++) {
        show.optional(i);
      }
      if (hasStar) {
        show.comma();
        printer.append("*");
        if (starArg) {
          printer.append(names.get(iStarArg));
        }
      }
      for (; i < endMandatoryNamedOnly; i++) {
        show.mandatory(i);
      }
      for (; i < named; i++) {
        show.optional(i);
      }
      if (kwArg) {
        show.comma();
        printer.append("**");
        printer.append(names.get(iKwArg));
      }

      return sb;
    }

    @Override
    public String toString() {
      StringBuilder sb = new StringBuilder();
      toStringBuilder(sb);
      return sb.toString();
    }
  }

  /** The given List, or null if all the list elements are null. */
  @Nullable public static <E> List<E> valueListOrNull(List<E> list) {
    if (list == null) {
      return null;
    }
    for (E value : list) {
      if (value != null) {
        return list;
      }
    }
    return null;
  }

  /**
   * Constructs a function signature (with names) from signature description and names.
   * This method covers the general case.
   * The number of optional named-only parameters is deduced from the other arguments.
   *
   * @param numMandatoryPositionals an int for the number of mandatory positional parameters
   * @param numOptionalPositionals an int for the number of optional positional parameters
   * @param numMandatoryNamedOnly an int for the number of mandatory named-only parameters
   * @param starArg a boolean for whether there is a starred parameter
   * @param kwArg a boolean for whether there is a star-starred parameter
   * @param names an Array of String for the parameter names
   * @return a FunctionSignature
   */
  public static FunctionSignature of(int numMandatoryPositionals, int numOptionalPositionals,
      int numMandatoryNamedOnly, boolean starArg, boolean kwArg, String... names) {
    return create(Shape.create(
        numMandatoryPositionals,
        numOptionalPositionals,
        numMandatoryNamedOnly,
        names.length - (kwArg ? 1 : 0) - (starArg ? 1 : 0)
            - numMandatoryPositionals - numOptionalPositionals - numMandatoryNamedOnly,
        starArg, kwArg),
        ImmutableList.copyOf(names));
  }

  /**
   * Constructs a function signature from mandatory positional argument names.
   *
   * @param names an Array of String for the positional parameter names
   * @return a FunctionSignature
   */
  public static FunctionSignature of(String... names) {
    return of(names.length, 0, 0, false, false, names);
  }

  /**
   * Constructs a function signature from positional argument names.
   *
   * @param numMandatory an int for the number of mandatory positional parameters
   * @param names an Array of String for the positional parameter names
   * @return a FunctionSignature
   */
  public static FunctionSignature of(int numMandatory, String... names) {
    return of(numMandatory, names.length - numMandatory, 0, false, false, names);
  }

  /**
   * Constructs a function signature from mandatory named-only argument names.
   *
   * @param names an Array of String for the mandatory named-only parameter names
   * @return a FunctionSignature
   */
  public static FunctionSignature namedOnly(String... names) {
    return of(0, 0, names.length, false, false, names);
  }

  /**
   * Constructs a function signature from named-only argument names.
   *
   * @param numMandatory an int for the number of mandatory named-only parameters
   * @param names an Array of String for the named-only parameter names
   * @return a FunctionSignature
   */
  public static FunctionSignature namedOnly(int numMandatory, String... names) {
    return of(0, 0, numMandatory, false, false, names);
  }

  /** Invalid signature from Parser or from SkylarkSignature annotations */
  protected static class SignatureException extends Exception {
    @Nullable private final Parameter<?, ?> parameter;

    /** SignatureException from a message and a Parameter */
    public SignatureException(String message, @Nullable Parameter<?, ?> parameter) {
      super(message);
      this.parameter = parameter;
    }

    /** what parameter caused the exception, if identified? */
    @Nullable public Parameter<?, ?> getParameter() {
      return parameter;
    }
  }

  /** A ready-made signature to allow only positional arguments and put them in a star parameter */
  public static final FunctionSignature POSITIONALS =
      FunctionSignature.of(0, 0, 0, true, false, "star");

  /** A ready-made signature to allow only keyword arguments and put them in a kwarg parameter */
  public static final FunctionSignature KWARGS =
      FunctionSignature.of(0, 0, 0, false, true, "kwargs");
}
