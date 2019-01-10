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

package com.google.devtools.build.lib.rules.cpp;

import com.google.common.base.Preconditions;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Interner;
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;
import com.google.common.collect.Sets.SetView;
import com.google.common.collect.Streams;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.Artifact.ArtifactExpander;
import com.google.devtools.build.lib.analysis.RuleContext;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.concurrent.BlazeInterners;
import com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable;
import com.google.devtools.build.lib.events.Location;
import com.google.devtools.build.lib.rules.cpp.CcToolchainFeatures.ExpansionException;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.AutoCodec;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.AutoCodec.VisibleForSerialization;
import com.google.devtools.build.lib.skylarkbuildapi.cpp.CcToolchainVariablesApi;
import com.google.devtools.build.lib.syntax.EvalException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Stack;
import javax.annotation.Nullable;

/**
 * Configured build variables usable by the toolchain configuration.
 *
 * <p>TODO(b/32655571): Investigate cleanup once implicit iteration is not needed. Variables
 * instance could serve as a top level View used to expand all flag_groups.
 */
public abstract class CcToolchainVariables implements CcToolchainVariablesApi {
  /**
   * A piece of a single string value.
   *
   * <p>A single value can contain a combination of text and variables (for example "-f
   * %{var1}/%{var2}"). We split the string into chunks, where each chunk represents either a text
   * snippet, or a variable that is to be replaced.
   */
  interface StringChunk {
    /**
     * Expands this chunk.
     *
     * @param variables binding of variable names to their values for a single flag expansion.
     */
    String expand(CcToolchainVariables variables);

    String getString();
  }

  /** A plain text chunk of a string (containing no variables). */
  @Immutable
  @AutoCodec
  @VisibleForSerialization
  static class StringLiteralChunk implements StringChunk, Serializable {
    private final String text;

    @VisibleForSerialization
    StringLiteralChunk(String text) {
      this.text = text;
    }

    @Override
    public String expand(CcToolchainVariables variables) {
      return text;
    }

    @Override
    public boolean equals(@Nullable Object object) {
      if (this == object) {
        return true;
      }
      if (object instanceof StringLiteralChunk) {
        StringLiteralChunk that = (StringLiteralChunk) object;
        return text.equals(that.text);
      }
      return false;
    }

    @Override
    public int hashCode() {
      return Objects.hash(text);
    }

    @Override
    public String getString() {
      return text;
    }
  }

  /** A chunk of a string value into which a variable should be expanded. */
  @Immutable
  @AutoCodec
  static class VariableChunk implements StringChunk, Serializable {
    private final String variableName;

    @VisibleForSerialization
    VariableChunk(String variableName) {
      this.variableName = variableName;
    }

    @Override
    public String expand(CcToolchainVariables variables) {
      // We check all variables in FlagGroup.expandCommandLine.
      // If we arrive here with the variable not being available, the variable was provided, but
      // the nesting level of the NestedSequence was deeper than the nesting level of the flag
      // groups.
      return variables.getStringVariable(variableName);
    }

    @Override
    public boolean equals(@Nullable Object object) {
      if (this == object) {
        return true;
      }
      if (object instanceof VariableChunk) {
        VariableChunk that = (VariableChunk) object;
        return variableName.equals(that.variableName);
      }
      return false;
    }

    @Override
    public int hashCode() {
      return Objects.hash(variableName);
    }

    @Override
    public String getString() {
      return "%{" + variableName + "}";
    }
  }

  /**
   * Parser for toolchain string values.
   *
   * <p>A string value contains a snippet of text supporting variable expansion. For example, a
   * string value "-f %{var1}/%{var2}" will expand the values of the variables "var1" and "var2" in
   * the corresponding places in the string.
   *
   * <p>The {@code StringValueParser} takes a string and parses it into a list of {@link
   * StringChunk} objects, where each chunk represents either a snippet of text or a variable to be
   * expanded. In the above example, the resulting chunks would be ["-f ", var1, "/", var2].
   *
   * <p>In addition to the list of chunks, the {@link StringValueParser} also provides the set of
   * variables necessary for the expansion of this flag via {@link #getUsedVariables}.
   *
   * <p>To get a literal percent character, "%%" can be used in the string.
   */
  public static class StringValueParser {

    private final String value;

    /**
     * The current position in {@value} during parsing.
     */
    private int current = 0;

    private final ImmutableList.Builder<StringChunk> chunks = ImmutableList.builder();
    private final ImmutableSet.Builder<String> usedVariables = ImmutableSet.builder();

    public StringValueParser(String value) throws EvalException {
      this.value = value;
      parse();
    }

    /** @return the parsed chunks for this string. */
    public ImmutableList<StringChunk> getChunks() {
      return chunks.build();
    }

    /** @return all variable names needed to expand this string. */
    ImmutableSet<String> getUsedVariables() {
      return usedVariables.build();
    }

    /**
     * Parses the string.
     *
     * @throws EvalException if there is a parsing error.
     */
    private void parse() throws EvalException {
      while (current < value.length()) {
        if (atVariableStart()) {
          parseVariableChunk();
        } else {
          parseStringChunk();
        }
      }
    }

    /**
     * @return whether the current position is the start of a variable.
     */
    private boolean atVariableStart() {
      // We parse a variable when value starts with '%', but not '%%'.
      return value.charAt(current) == '%'
          && (current + 1 >= value.length() || value.charAt(current + 1) != '%');
    }

    /**
     * Parses a chunk of text until the next '%', which indicates either an escaped literal '%' or a
     * variable.
     */
    private void parseStringChunk() {
      int start = current;
      // We only parse string chunks starting with '%' if they also start with '%%'.
      // In that case, we want to have a single '%' in the string, so we start at the second
      // character.
      // Note that for strings like "abc%%def" this will lead to two string chunks, the first
      // referencing the subtring "abc", and a second referencing the substring "%def".
      if (value.charAt(current) == '%') {
        current = current + 1;
        start = current;
      }
      current = value.indexOf('%', current + 1);
      if (current == -1) {
        current = value.length();
      }
      final String text = value.substring(start, current);
      chunks.add(new StringLiteralChunk(text));
    }

    /**
     * Parses a variable to be expanded.
     *
     * @throws EvalException if there is a parsing error.
     */
    private void parseVariableChunk() throws EvalException {
      current = current + 1;
      if (current >= value.length() || value.charAt(current) != '{') {
        abort("expected '{'");
      }
      current = current + 1;
      if (current >= value.length() || value.charAt(current) == '}') {
        abort("expected variable name");
      }
      int end = value.indexOf('}', current);
      final String name = value.substring(current, end);
      usedVariables.add(name);
      chunks.add(new VariableChunk(name));
      current = end + 1;
    }

    /**
     * @throws EvalException with the given error text, adding information about the current
     *     position in the string.
     */
    private void abort(String error) throws EvalException {
      throw new EvalException(
          Location.BUILTIN,
          "Invalid toolchain configuration: "
              + error
              + " at position "
              + current
              + " while parsing a flag containing '"
              + value
              + "'");
    }
  }

  /** A flag or flag group that can be expanded under a set of variables. */
  public interface Expandable {
    /**
     * Expands the current expandable under the given {@code view}, adding new flags to {@code
     * commandLine}.
     *
     * <p>The {@code variables} controls which variables are visible during the expansion and allows
     * to recursively expand nested flag groups.
     */
    void expand(
        CcToolchainVariables variables,
        @Nullable ArtifactExpander expander,
        List<String> commandLine);
  }

  /** An empty variables instance. */
  public static final CcToolchainVariables EMPTY = new CcToolchainVariables.Builder().build();

  /**
   * Retrieves a {@link StringSequence} variable named {@code variableName} from {@code variables}
   * and converts it into a list of plain strings.
   *
   * <p>Throws {@link ExpansionException} when the variable is not a {@link StringSequence}.
   */
  public static final ImmutableList<String> toStringList(
      CcToolchainVariables variables, String variableName) {
      return Streams
          .stream(variables.getSequenceVariable(variableName))
          .map(variable -> variable.getStringValue(variableName))
          .collect(ImmutableList.toImmutableList());
    }

    /**
     * Get a variable value named @param name. Supports accessing fields in structures (e.g.
     * 'libraries_to_link.interface_libraries')
     *
     * @throws ExpansionException when no such variable or no such field are present, or when
     *     accessing a field of non-structured variable
     */
    VariableValue getVariable(String name) {
    return lookupVariable(name, /* throwOnMissingVariable= */ true, /* expander= */ null);
    }

    VariableValue getVariable(String name, @Nullable ArtifactExpander expander) {
    return lookupVariable(name, /* throwOnMissingVariable= */ true, expander);
    }

    /**
     * Lookup a variable named @param name or return a reason why the variable was not found.
     * Supports accessing fields in structures.
     *
     * @return Pair<VariableValue, String> returns either (variable value, null) or (null, string
     *     reason why variable was not found)
     */
    private VariableValue lookupVariable(
        String name, boolean throwOnMissingVariable, @Nullable ArtifactExpander expander) {
      VariableValue nonStructuredVariable = getNonStructuredVariable(name);
      if (nonStructuredVariable != null) {
        return nonStructuredVariable;
      }
      VariableValue structuredVariable =
          getStructureVariable(name, throwOnMissingVariable, expander);
      if (structuredVariable != null) {
        return structuredVariable;
      } else if (throwOnMissingVariable) {
        throw new ExpansionException(
            String.format(
                "Invalid toolchain configuration: Cannot find variable named '%s'.", name));
      } else {
        return null;
      }
    }

    private VariableValue getStructureVariable(
        String name, boolean throwOnMissingVariable, @Nullable ArtifactExpander expander) {
      if (!name.contains(".")) {
        return null;
      }

      Stack<String> fieldsToAccess = new Stack<>();
      String structPath = name;
      VariableValue variable;

      do {
        fieldsToAccess.push(structPath.substring(structPath.lastIndexOf('.') + 1));
        structPath = structPath.substring(0, structPath.lastIndexOf('.'));
        variable = getNonStructuredVariable(structPath);
      } while (variable == null && structPath.contains("."));

      if (variable == null) {
        return null;
      }

      while (!fieldsToAccess.empty()) {
        String field = fieldsToAccess.pop();
        variable = variable.getFieldValue(structPath, field, expander);
        if (variable == null) {
          if (throwOnMissingVariable) {
            throw new ExpansionException(
                String.format(
                    "Invalid toolchain configuration: Cannot expand variable '%s.%s': structure %s "
                        + "doesn't have a field named '%s'",
                    structPath, field, structPath, field));
          } else {
            return null;
          }
        }
      }
      return variable;
    }

    public String getStringVariable(String variableName) {
    return getVariable(variableName, /* expander= */ null).getStringValue(variableName);
    }

    public Iterable<? extends VariableValue> getSequenceVariable(String variableName) {
    return getVariable(variableName, /* expander= */ null).getSequenceValue(variableName);
    }

    public Iterable<? extends VariableValue> getSequenceVariable(
        String variableName, @Nullable ArtifactExpander expander) {
      return getVariable(variableName, expander).getSequenceValue(variableName);
    }

    /** Returns whether {@code variable} is set. */
    boolean isAvailable(String variable) {
    return isAvailable(variable, /* expander= */ null);
    }

    boolean isAvailable(String variable, @Nullable ArtifactExpander expander) {
    return lookupVariable(variable, /* throwOnMissingVariable= */ false, expander) != null;
    }

    abstract Map<String, VariableValue> getVariablesMap();

    abstract Map<String, String> getStringVariablesMap();

    @Nullable
    abstract VariableValue getNonStructuredVariable(String name);

    /**
     * Value of a build variable exposed to the CROSSTOOL used for flag expansion.
     *
     * <p>{@link VariableValue} represent either primitive values or an arbitrarily deeply nested
     * recursive structures or sequences. Since there are builds with millions of values, some
     * implementations might exist only to optimize memory usage.
     *
     * <p>Implementations must be immutable and without any side-effects. They will be expanded and
     * queried multiple times.
     */
    interface VariableValue {
      /**
       * Returns string value of the variable, if the variable type can be converted to string (e.g.
       * StringValue), or throw exception if it cannot (e.g. Sequence).
       *
       * @param variableName name of the variable value at hand, for better exception message.
       */
      String getStringValue(String variableName);

      /**
       * Returns Iterable value of the variable, if the variable type can be converted to a Iterable
       * (e.g. Sequence), or throw exception if it cannot (e.g. StringValue).
       *
       * @param variableName name of the variable value at hand, for better exception message.
       */
      Iterable<? extends VariableValue> getSequenceValue(String variableName);

      /**
       * Returns value of the field, if the variable is of struct type or throw exception if it is
       * not or no such field exists.
       *
       * @param variableName name of the variable value at hand, for better exception message.
       */
      VariableValue getFieldValue(String variableName, String field);

      VariableValue getFieldValue(
          String variableName, String field, @Nullable ArtifactExpander expander);

      /** Returns true if the variable is truthy */
      boolean isTruthy();
    }

    /**
     * Adapter for {@link VariableValue} predefining error handling methods. Override {@link
     * #getVariableTypeName()}, {@link #isTruthy()}, and one of {@link #getFieldValue(String,
     * String)}, {@link #getSequenceValue(String)}, or {@link #getStringValue(String)}, and you'll
     * get error handling for the other methods for free.
     */
    abstract static class VariableValueAdapter implements VariableValue {

      /** Returns human-readable variable type name to be used in error messages. */
      public abstract String getVariableTypeName();

      @Override
      public abstract boolean isTruthy();

      @Override
      public VariableValue getFieldValue(String variableName, String field) {
      return getFieldValue(variableName, field, /* expander= */ null);
      }

      @Override
      public VariableValue getFieldValue(
          String variableName, String field, @Nullable ArtifactExpander expander) {
        throw new ExpansionException(
            String.format(
                "Invalid toolchain configuration: Cannot expand variable '%s.%s': variable '%s' is "
                    + "%s, expected structure",
                variableName, field, variableName, getVariableTypeName()));
      }

      @Override
      public String getStringValue(String variableName) {
        throw new ExpansionException(
            String.format(
                "Invalid toolchain configuration: Cannot expand variable '%s': expected string, "
                    + "found %s",
                variableName, getVariableTypeName()));
      }

      @Override
      public Iterable<? extends VariableValue> getSequenceValue(String variableName) {
        throw new ExpansionException(
            String.format(
                "Invalid toolchain configuration: Cannot expand variable '%s': expected sequence, "
                    + "found %s",
                variableName, getVariableTypeName()));
      }
    }

    /** Interface for VariableValue builders */
    public interface VariableValueBuilder {
      VariableValue build();
    }

    /** Builder for StringSequence. */
    public static class StringSequenceBuilder implements VariableValueBuilder {

      private final ImmutableList.Builder<String> values = ImmutableList.builder();

      /** Adds a value to the sequence. */
      public StringSequenceBuilder addValue(String value) {
        values.add(value);
        return this;
      }

      /** Returns an immutable string sequence. */
      @Override
      public StringSequence build() {
        return new StringSequence(values.build());
      }
    }

    /** Builder for Sequence. */
    public static class SequenceBuilder implements VariableValueBuilder {

      private final ImmutableList.Builder<VariableValue> values = ImmutableList.builder();

      /** Adds a value to the sequence. */
      public SequenceBuilder addValue(VariableValue value) {
        values.add(value);
        return this;
      }

      /** Adds a value to the sequence. */
      public SequenceBuilder addValue(VariableValueBuilder value) {
        Preconditions.checkArgument(value != null, "Cannot use null builder for a sequence value");
        values.add(value.build());
        return this;
      }

      /** Returns an immutable sequence. */
      @Override
      public Sequence build() {
        return new Sequence(values.build());
      }
    }

    /** Builder for StructureValue. */
    public static class StructureBuilder implements VariableValueBuilder {

      private final ImmutableMap.Builder<String, VariableValue> fields = ImmutableMap.builder();

      /** Adds a field to the structure. */
      public StructureBuilder addField(String name, VariableValue value) {
        fields.put(name, value);
        return this;
      }

      /** Adds a field to the structure. */
      public StructureBuilder addField(String name, VariableValueBuilder valueBuilder) {
        Preconditions.checkArgument(
            valueBuilder != null,
            "Cannot use null builder to get a field value for field '%s'",
            name);
        fields.put(name, valueBuilder.build());
        return this;
      }

      /** Adds a field to the structure. */
      public StructureBuilder addField(String name, String value) {
        fields.put(name, new StringValue(value));
        return this;
      }

      /** Adds a field to the structure. */
      public StructureBuilder addField(String name, ImmutableList<String> values) {
        fields.put(name, new StringSequence(values));
        return this;
      }

      /** Returns an immutable structure. */
      @Override
      public StructureValue build() {
        return new StructureValue(fields.build());
      }
    }

  /**
   * Lazily computed string sequence. Exists as a memory optimization. Make sure the {@param
   * supplier} doesn't capture anything that shouldn't outlive analysis phase (e.g. {@link
   * RuleContext}).
   */
  @AutoCodec
  @VisibleForSerialization
  static final class LazyStringSequence extends VariableValueAdapter {
    private final Supplier<ImmutableList<String>> supplier;

    @VisibleForSerialization
    LazyStringSequence(Supplier<ImmutableList<String>> supplier) {
      this.supplier = Preconditions.checkNotNull(supplier);
    }

    @Override
    public Iterable<? extends VariableValue> getSequenceValue(String variableName) {
      return supplier
          .get()
          .stream()
          .map(flag -> new StringValue(flag))
          .collect(ImmutableList.toImmutableList());
    }

    @Override
    public String getVariableTypeName() {
      return Sequence.SEQUENCE_VARIABLE_TYPE_NAME;
    }

    @Override
    public boolean isTruthy() {
      return !supplier.get().isEmpty();
    }

    @Override
    public boolean equals(Object other) {
      if (!(other instanceof LazyStringSequence)) {
        return false;
      }
      if (this == other) {
        return true;
      }
      LazyStringSequence that = (LazyStringSequence) other;
      if (this.supplier == that.supplier) {
        return true;
      }
      return Objects.equals(supplier.get(), ((LazyStringSequence) other).supplier.get());
    }

    @Override
    public int hashCode() {
      return supplier.get().hashCode();
    }
  }

  /**
   * A sequence of structure values. Exists as a memory optimization - a typical build can contain
   * millions of feature values, so getting rid of the overhead of {@code StructureValue} objects
   * significantly reduces memory overhead.
   */
  @Immutable
  @AutoCodec
  public static class LibraryToLinkValue extends VariableValueAdapter {
    public static final String OBJECT_FILES_FIELD_NAME = "object_files";
    public static final String NAME_FIELD_NAME = "name";
    public static final String TYPE_FIELD_NAME = "type";
    public static final String IS_WHOLE_ARCHIVE_FIELD_NAME = "is_whole_archive";

    private static final String LIBRARY_TO_LINK_VARIABLE_TYPE_NAME = "structure (LibraryToLink)";

    @VisibleForSerialization
    enum Type {
      OBJECT_FILE("object_file"),
      OBJECT_FILE_GROUP("object_file_group"),
      INTERFACE_LIBRARY("interface_library"),
      STATIC_LIBRARY("static_library"),
      DYNAMIC_LIBRARY("dynamic_library"),
      VERSIONED_DYNAMIC_LIBRARY("versioned_dynamic_library");

      private final String name;

      Type(String name) {
        this.name = name;
      }
    }

    private final String name;
    private final ImmutableList<Artifact> objectFiles;
    private final boolean isWholeArchive;
    private final Type type;

    public static LibraryToLinkValue forDynamicLibrary(String name) {
      return new LibraryToLinkValue(
          Preconditions.checkNotNull(name),
          /* objectFiles= */ null,
          /* isWholeArchive= */ false,
          Type.DYNAMIC_LIBRARY);
    }

    public static LibraryToLinkValue forVersionedDynamicLibrary(String name) {
      return new LibraryToLinkValue(
          Preconditions.checkNotNull(name),
          /* objectFiles= */ null,
          /* isWholeArchive= */ false,
          Type.VERSIONED_DYNAMIC_LIBRARY);
    }

    public static LibraryToLinkValue forInterfaceLibrary(String name) {
      return new LibraryToLinkValue(
          Preconditions.checkNotNull(name),
          /* objectFiles= */ null,
          /* isWholeArchive= */ false,
          Type.INTERFACE_LIBRARY);
    }

    public static LibraryToLinkValue forStaticLibrary(String name, boolean isWholeArchive) {
      return new LibraryToLinkValue(
          Preconditions.checkNotNull(name),
          /* objectFiles= */ null,
          isWholeArchive,
          Type.STATIC_LIBRARY);
    }

    public static LibraryToLinkValue forObjectFile(String name, boolean isWholeArchive) {
      return new LibraryToLinkValue(
          Preconditions.checkNotNull(name),
          /* objectFiles= */ null,
          isWholeArchive,
          Type.OBJECT_FILE);
    }

    public static LibraryToLinkValue forObjectFileGroup(
        ImmutableList<Artifact> objects, boolean isWholeArchive) {
      Preconditions.checkNotNull(objects);
      Preconditions.checkArgument(!objects.isEmpty());
      return new LibraryToLinkValue(
          /* name= */ null, objects, isWholeArchive, Type.OBJECT_FILE_GROUP);
    }

    @VisibleForSerialization
    LibraryToLinkValue(
        String name, ImmutableList<Artifact> objectFiles, boolean isWholeArchive, Type type) {
      this.name = name;
      this.objectFiles = objectFiles;
      this.isWholeArchive = isWholeArchive;
      this.type = type;
    }

    @Override
    public VariableValue getFieldValue(
        String variableName, String field, @Nullable ArtifactExpander expander) {
      Preconditions.checkNotNull(field);
      if (NAME_FIELD_NAME.equals(field) && !type.equals(Type.OBJECT_FILE_GROUP)) {
        return new StringValue(name);
      } else if (OBJECT_FILES_FIELD_NAME.equals(field) && type.equals(Type.OBJECT_FILE_GROUP)) {
        ImmutableList.Builder<String> expandedObjectFiles = ImmutableList.builder();
        for (Artifact objectFile : objectFiles) {
          if (objectFile.isTreeArtifact() && (expander != null)) {
            List<Artifact> artifacts = new ArrayList<>();
            expander.expand(objectFile, artifacts);
            expandedObjectFiles.addAll(
                Iterables.transform(artifacts, artifact -> artifact.getExecPathString()));
          } else {
            expandedObjectFiles.add(objectFile.getExecPathString());
          }
        }
        return new StringSequence(expandedObjectFiles.build());
      } else if (TYPE_FIELD_NAME.equals(field)) {
        return new StringValue(type.name);
      } else if (IS_WHOLE_ARCHIVE_FIELD_NAME.equals(field)) {
        return new IntegerValue(isWholeArchive ? 1 : 0);
      } else {
        return null;
      }
    }

    @Override
    public String getVariableTypeName() {
      return LIBRARY_TO_LINK_VARIABLE_TYPE_NAME;
    }

    @Override
    public boolean isTruthy() {
      return true;
    }

    @Override
    public boolean equals(Object other) {
      if (!(other instanceof LibraryToLinkValue)) {
        return false;
      }
      if (this == other) {
        return true;
      }
      LibraryToLinkValue that = (LibraryToLinkValue) other;
      return Objects.equals(this.name, that.name)
          && Objects.equals(this.objectFiles, that.objectFiles)
          && this.isWholeArchive == that.isWholeArchive
          && Objects.equals(this.type, that.type);
    }

    @Override
    public int hashCode() {
      return 31 * Objects.hash(name, objectFiles, type) + (isWholeArchive ? 1231 : 1237);
    }
  }

  /** Sequence of arbitrary VariableValue objects. */
  @Immutable
  @AutoCodec
  @VisibleForSerialization
  static final class Sequence extends VariableValueAdapter {
    private static final String SEQUENCE_VARIABLE_TYPE_NAME = "sequence";

    private final ImmutableList<VariableValue> values;

    public Sequence(ImmutableList<VariableValue> values) {
      this.values = values;
    }

    @Override
    public Iterable<? extends VariableValue> getSequenceValue(String variableName) {
      return values;
    }

    @Override
    public String getVariableTypeName() {
      return SEQUENCE_VARIABLE_TYPE_NAME;
    }

    @Override
    public boolean isTruthy() {
      return values.isEmpty();
    }

    @Override
    public boolean equals(Object other) {
      if (!(other instanceof Sequence)) {
        return false;
      }
      if (this == other) {
        return true;
      }
      return Objects.equals(values, ((Sequence) other).values);
    }

    @Override
    public int hashCode() {
      return values.hashCode();
    }
  }

  /**
   * A sequence of structure values. Exists as a memory optimization - a typical build can contain
   * millions of feature values, so getting rid of the overhead of {@code StructureValue} objects
   * significantly reduces memory overhead.
   */
  @Immutable
  @AutoCodec
  @VisibleForSerialization
  static final class StructureSequence extends VariableValueAdapter {
    private final ImmutableList<ImmutableMap<String, VariableValue>> values;

    @VisibleForSerialization
    StructureSequence(ImmutableList<ImmutableMap<String, VariableValue>> values) {
      Preconditions.checkNotNull(values);
      this.values = values;
    }

    @Override
    public Iterable<? extends VariableValue> getSequenceValue(String variableName) {
      final ImmutableList.Builder<VariableValue> sequences = ImmutableList.builder();
      for (ImmutableMap<String, VariableValue> value : values) {
        sequences.add(new StructureValue(value));
      }
      return sequences.build();
    }

    @Override
    public String getVariableTypeName() {
      return Sequence.SEQUENCE_VARIABLE_TYPE_NAME;
    }

    @Override
    public boolean isTruthy() {
      return !values.isEmpty();
    }

    @Override
    public boolean equals(Object other) {
      if (!(other instanceof StructureSequence)) {
        return false;
      }
      if (this == other) {
        return true;
      }
      return Objects.equals(values, ((StructureSequence) other).values);
    }

    @Override
    public int hashCode() {
      return values.hashCode();
    }
  }

  /**
   * A sequence of simple string values. Exists as a memory optimization - a typical build can
   * contain millions of feature values, so getting rid of the overhead of {@code StringValue}
   * objects significantly reduces memory overhead.
   */
  @Immutable
  @AutoCodec
  static final class StringSequence extends VariableValueAdapter {
    private final Iterable<String> values;
    private int hash = 0;

    public StringSequence(Iterable<String> values) {
      Preconditions.checkNotNull(values);
      this.values = values;
    }

    @Override
    public Iterable<? extends VariableValue> getSequenceValue(String variableName) {
      final ImmutableList.Builder<VariableValue> sequences = ImmutableList.builder();
      for (String value : values) {
        sequences.add(new StringValue(value));
      }
      return sequences.build();
    }

    @Override
    public String getVariableTypeName() {
      return Sequence.SEQUENCE_VARIABLE_TYPE_NAME;
    }

    @Override
    public boolean isTruthy() {
      return !Iterables.isEmpty(values);
    }

    @Override
    public boolean equals(Object other) {
      if (!(other instanceof StringSequence)) {
        return false;
      }
      if (this == other) {
        return true;
      }
      return Iterables.elementsEqual(values, ((StringSequence) other).values);
    }

    @Override
    public int hashCode() {
      int h = hash;
      if (h == 0) {
        h = 1;
        for (String s : values) {
          h = 31 * h + (s == null ? 0 : s.hashCode());
        }
        hash = h;
      }
      return h;
    }
  }

  /**
   * Single structure value. Be careful not to create sequences of single structures, as the memory
   * overhead is prohibitively big. Use optimized {@link StructureSequence} instead.
   */
  @Immutable
  @AutoCodec
  @VisibleForSerialization
  static final class StructureValue extends VariableValueAdapter {
    private static final String STRUCTURE_VARIABLE_TYPE_NAME = "structure";

    private final ImmutableMap<String, VariableValue> value;

    public StructureValue(ImmutableMap<String, VariableValue> value) {
      this.value = value;
    }

    @Override
    public VariableValue getFieldValue(
        String variableName, String field, @Nullable ArtifactExpander expander) {
      if (value.containsKey(field)) {
        return value.get(field);
      } else {
        return null;
      }
    }

    @Override
    public String getVariableTypeName() {
      return STRUCTURE_VARIABLE_TYPE_NAME;
    }

    @Override
    public boolean isTruthy() {
      return !value.isEmpty();
    }

    @Override
    public boolean equals(Object other) {
      if (!(other instanceof StructureValue)) {
        return false;
      }
      if (this == other) {
        return true;
      }
      return Objects.equals(value, ((StructureValue) other).value);
    }

    @Override
    public int hashCode() {
      return value.hashCode();
    }
  }

  /**
   * The leaves in the variable sequence node tree are simple string values. Note that this should
   * never live outside of {@code expand}, as the object overhead is prohibitively expensive.
   */
  @Immutable
  @AutoCodec
  @VisibleForSerialization
  static final class StringValue extends VariableValueAdapter {
    private static final String STRING_VARIABLE_TYPE_NAME = "string";

    private final String value;

    public StringValue(String value) {
      Preconditions.checkNotNull(value, "Cannot create StringValue from null");
      this.value = value;
    }

    @Override
    public String getStringValue(String variableName) {
      return value;
    }

    @Override
    public String getVariableTypeName() {
      return STRING_VARIABLE_TYPE_NAME;
    }

    @Override
    public boolean isTruthy() {
      return !value.isEmpty();
    }

    @Override
    public boolean equals(Object other) {
      if (!(other instanceof StringValue)) {
        return false;
      }
      if (this == other) {
        return true;
      }
      return Objects.equals(value, ((StringValue) other).value);
    }

    @Override
    public int hashCode() {
      return value.hashCode();
    }
  }

  /**
   * The leaves in the variable sequence node tree are simple integer values. Note that this should
   * never live outside of {@code expand}, as the object overhead is prohibitively expensive.
   */
  @Immutable
  @AutoCodec
  static final class IntegerValue extends VariableValueAdapter {
    private static final String INTEGER_VALUE_TYPE_NAME = "integer";
    private final int value;

    public IntegerValue(int value) {
      this.value = value;
    }

    @Override
    public String getStringValue(String variableName) {
      return Integer.toString(value);
    }

    @Override
    public String getVariableTypeName() {
      return INTEGER_VALUE_TYPE_NAME;
    }

    @Override
    public boolean isTruthy() {
      return value != 0;
    }

    @Override
    public boolean equals(Object other) {
      if (!(other instanceof IntegerValue)) {
        return false;
      }
      return value == ((IntegerValue) other).value;
    }

    @Override
    public int hashCode() {
      return value;
    }
  }

  /** Builder for {@code Variables}. */
  // TODO(b/65472725): Forbid sequences with empty string in them.
  public static class Builder {
    private final Map<String, VariableValue> variablesMap = new LinkedHashMap<>();
    private final Map<String, String> stringVariablesMap = new LinkedHashMap<>();
    private final CcToolchainVariables parent;

    public Builder() {
      parent = null;
    }

    public Builder(@Nullable CcToolchainVariables parent) {
      this.parent = parent;
    }

    /** Add an integer variable that expands {@code name} to {@code value}. */
    public Builder addIntegerVariable(String name, int value) {
      variablesMap.put(name, new IntegerValue(value));
      return this;
    }

    /** Add a string variable that expands {@code name} to {@code value}. */
    public Builder addStringVariable(String name, String value) {
      checkVariableNotPresentAlready(name);
      Preconditions.checkNotNull(value, "Cannot set null as a value for variable '%s'", name);
      stringVariablesMap.put(name, value);
      return this;
    }

    /** Overrides a variable to expands {@code name} to {@code value} instead. */
    public Builder overrideStringVariable(String name, String value) {
      Preconditions.checkNotNull(value, "Cannot set null as a value for variable '%s'", name);
      stringVariablesMap.put(name, value);
      return this;
    }

    /** Overrides a variable to expand {@code name} to {@code value} instead. */
    public Builder overrideLazyStringSequenceVariable(
        String name, Supplier<ImmutableList<String>> supplier) {
      Preconditions.checkNotNull(supplier, "Cannot set null as a value for variable '%s'", name);
      variablesMap.put(name, new LazyStringSequence(supplier));
      return this;
    }

    /**
     * Add a sequence variable that expands {@code name} to {@code values}.
     *
     * <p>Accepts values as ImmutableSet. As ImmutableList has smaller memory footprint, we copy the
     * values into a new list.
     */
    public Builder addStringSequenceVariable(String name, ImmutableSet<String> values) {
      checkVariableNotPresentAlready(name);
      Preconditions.checkNotNull(values, "Cannot set null as a value for variable '%s'", name);
      ImmutableList.Builder<String> builder = ImmutableList.builder();
      builder.addAll(values);
      variablesMap.put(name, new StringSequence(builder.build()));
      return this;
    }

    /**
     * Add a sequence variable that expands {@code name} to {@code values}.
     *
     * <p>Accepts values as NestedSet. Nested set is stored directly, not cloned, not flattened.
     */
    public Builder addStringSequenceVariable(String name, NestedSet<String> values) {
      checkVariableNotPresentAlready(name);
      Preconditions.checkNotNull(values, "Cannot set null as a value for variable '%s'", name);
      variablesMap.put(name, new StringSequence(values));
      return this;
    }

    /**
     * Add a sequence variable that expands {@code name} to {@code values}.
     *
     * <p>Accepts values as Iterable. The iterable is stored directly, not cloned, not iterated. Be
     * mindful of memory consumption of the particular Iterable. Prefer ImmutableList, or be sure
     * that the iterable always returns the same elements in the same order, without any side
     * effects.
     */
    public Builder addStringSequenceVariable(String name, Iterable<String> values) {
      checkVariableNotPresentAlready(name);
      Preconditions.checkNotNull(values, "Cannot set null as a value for variable '%s'", name);
      variablesMap.put(name, new StringSequence(values));
      return this;
    }

    public Builder addLazyStringSequenceVariable(
        String name, Supplier<ImmutableList<String>> supplier) {
      checkVariableNotPresentAlready(name);
      Preconditions.checkNotNull(supplier, "Cannot set null as a value for variable '%s'", name);
      variablesMap.put(name, new LazyStringSequence(supplier));
      return this;
    }

    /**
     * Add a variable built using {@code VariableValueBuilder} api that expands {@code name} to the
     * value returned by the {@code builder}.
     */
    public Builder addCustomBuiltVariable(
        String name, CcToolchainVariables.VariableValueBuilder builder) {
      checkVariableNotPresentAlready(name);
      Preconditions.checkNotNull(
          builder, "Cannot use null builder to get variable value for variable '%s'", name);
      variablesMap.put(name, builder.build());
      return this;
    }

    /** Add all string variables in a map. */
    public Builder addAllStringVariables(Map<String, String> variables) {
      for (String name : variables.keySet()) {
        checkVariableNotPresentAlready(name);
      }
      stringVariablesMap.putAll(variables);
      return this;
    }

    private void checkVariableNotPresentAlready(String name) {
      Preconditions.checkNotNull(name);
      Preconditions.checkArgument(
          !variablesMap.containsKey(name), "Cannot overwrite variable '%s'", name);
      Preconditions.checkArgument(
          !stringVariablesMap.containsKey(name), "Cannot overwrite variable '%s'", name);
    }

    /**
     * Adds all variables to this builder. Cannot override already added variables. Does not add
     * variables defined in the {@code parent} variables.
     */
    public Builder addAllNonTransitive(CcToolchainVariables variables) {
      SetView<String> intersection =
          Sets.intersection(variables.getVariablesMap().keySet(), variablesMap.keySet());
      SetView<String> stringIntersection =
          Sets.intersection(
              variables.getStringVariablesMap().keySet(), stringVariablesMap.keySet());
      Preconditions.checkArgument(
          intersection.isEmpty(), "Cannot overwrite existing variables: %s", intersection);
      Preconditions.checkArgument(
          stringIntersection.isEmpty(),
          "Cannot overwrite existing variables: %s",
          stringIntersection);
      this.variablesMap.putAll(variables.getVariablesMap());
      this.stringVariablesMap.putAll(variables.getStringVariablesMap());
      return this;
    }

    /** @return a new {@link CcToolchainVariables} object. */
    public CcToolchainVariables build() {
      if (stringVariablesMap.isEmpty() && variablesMap.size() == 1) {
        return new SingleVariables(
            parent,
            variablesMap.keySet().iterator().next(),
            variablesMap.values().iterator().next());
      }
      return new MapVariables(
          parent, ImmutableMap.copyOf(variablesMap), ImmutableMap.copyOf(stringVariablesMap));
    }
  }

  /**
   * A group of extra {@code Variable} instances, packaged as logic for adding to a {@code Builder}
   */
  public interface VariablesExtension {
    void addVariables(Builder builder);
  }

  @Immutable
  @AutoCodec.VisibleForSerialization
  @AutoCodec
  static class MapVariables extends CcToolchainVariables {
    private static final Interner<MapVariables> INTERNER = BlazeInterners.newWeakInterner();

    @Nullable private final CcToolchainVariables parent;
    private final ImmutableMap<String, VariableValue> variablesMap;
    private final ImmutableMap<String, String> stringVariablesMap;

    private MapVariables(
        CcToolchainVariables parent,
        ImmutableMap<String, VariableValue> variablesMap,
        ImmutableMap<String, String> stringVariablesMap) {
      this.parent = parent;
      this.variablesMap = variablesMap;
      this.stringVariablesMap = stringVariablesMap;
    }

    @AutoCodec.Instantiator
    @VisibleForSerialization
    static MapVariables create(
        CcToolchainVariables parent,
        ImmutableMap<String, VariableValue> variablesMap,
        ImmutableMap<String, String> stringVariablesMap) {
      return INTERNER.intern(new MapVariables(parent, variablesMap, stringVariablesMap));
    }

    @Override
    Map<String, VariableValue> getVariablesMap() {
      return variablesMap;
    }

    @Override
    Map<String, String> getStringVariablesMap() {
      return stringVariablesMap;
    }

    @Override
    VariableValue getNonStructuredVariable(String name) {
      if (variablesMap.containsKey(name)) {
        return variablesMap.get(name);
      }
      if (stringVariablesMap.containsKey(name)) {
        return new StringValue(stringVariablesMap.get(name));
      }

      if (parent != null) {
        return parent.getNonStructuredVariable(name);
      }

      return null;
    }

    /**
     * NB: this compares parents using reference equality instead of logical equality.
     *
     * <p>This is a performance optimization to avoid possibly expensive recursive equality
     * expansions and suitable for comparisons needed by interning deserialized values. If full
     * logical equality is desired, it's possible to either enable full interning (at a modest CPU
     * cost) or change the parent comparison to use deep equality.
     *
     * <p>This same comment applies to {@link SingleVariables#equals}.
     */
    @Override
    public boolean equals(Object other) {
      if (!(other instanceof MapVariables)) {
        return false;
      }
      if (this == other) {
        return true;
      }
      MapVariables that = (MapVariables) other;
      if (this.parent != that.parent) {
        return false;
      }
      return Objects.equals(this.variablesMap, that.variablesMap)
          && Objects.equals(this.stringVariablesMap, that.stringVariablesMap);
    }

    @Override
    public int hashCode() {
      return 31 * Objects.hash(variablesMap, stringVariablesMap) + System.identityHashCode(parent);
    }
  }

  @VisibleForSerialization
  @AutoCodec
  @Immutable
  static class SingleVariables extends CcToolchainVariables {
    private static final Interner<SingleVariables> INTERNER = BlazeInterners.newWeakInterner();

    @Nullable private final CcToolchainVariables parent;
    private final String name;
    private final VariableValue variableValue;
    private int hash = 0;

    @AutoCodec.Instantiator
    static SingleVariables create(
        CcToolchainVariables parent, String name, VariableValue variableValue) {
      return INTERNER.intern(new SingleVariables(parent, name, variableValue));
    }

    SingleVariables(CcToolchainVariables parent, String name, VariableValue variableValue) {
      this.parent = parent;
      this.name = name;
      this.variableValue = variableValue;
    }

    @Override
    Map<String, VariableValue> getVariablesMap() {
      return ImmutableMap.of(name, variableValue);
    }

    @Override
    Map<String, String> getStringVariablesMap() {
      return ImmutableMap.of();
    }

    @Override
    VariableValue getNonStructuredVariable(String name) {
      if (this.name.equals(name)) {
        return variableValue;
      }
      return parent == null ? null : parent.getNonStructuredVariable(name);
    }

    @Override
    public boolean equals(Object other) {
      if (!(other instanceof SingleVariables)) {
        return false;
      }
      if (this == other) {
        return true;
      }
      SingleVariables that = (SingleVariables) other;
      if (this.parent != that.parent) {
        return false;
      }
      return Objects.equals(this.name, that.name)
          && Objects.equals(this.variableValue, that.variableValue);
    }

    @Override
    public int hashCode() {
      int h = hash;
      if (h == 0) {
        hash = h = Objects.hash(parent, name, variableValue);
      }
      return h;
    }
  }
}
