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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.events.Location;
import com.google.devtools.build.lib.packages.SkylarkInfo.Layout;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.AutoCodec;
import com.google.devtools.build.lib.skylarkinterface.SkylarkPrinter;
import com.google.devtools.build.lib.syntax.Environment;
import com.google.devtools.build.lib.syntax.FunctionSignature;
import com.google.devtools.build.lib.syntax.SkylarkType;
import java.util.Map;
import java.util.Objects;
import javax.annotation.Nullable;

/**
 * A provider defined in Skylark rather than in native code.
 *
 * <p>This is a result of calling the {@code provider()} function from Skylark ({@link
 * com.google.devtools.build.lib.analysis.skylark.SkylarkRuleClassFunctions#provider}).
 *
 * <p>{@code SkylarkProvider}s may be either schemaless or schemaful. Instances of schemaless
 * providers can have any set of fields on them, whereas instances of schemaful providers may have
 * only the fields that are named in the schema. Schemaful provider instances are more space
 * efficient since they do not use maps; see {@link SkylarkInfo}.
 *
 * <p>Exporting a {@code SkylarkProvider} creates a key that is used to uniquely identify it.
 * Usually a provider is exported by calling {@link #export}, but a test may wish to just create a
 * pre-exported provider directly. Exported providers use only their key for {@link #equals} and
 * {@link #hashCode}.
 */
public class SkylarkProvider extends ProviderFromFunction implements SkylarkExportable {

  private static final FunctionSignature.WithValues<Object, SkylarkType> SCHEMALESS_SIGNATURE =
      FunctionSignature.WithValues.create(FunctionSignature.KWARGS);

  /** Default value for {@link #errorMessageFormatForUnknownField}. */
  private static final String DEFAULT_ERROR_MESSAGE_FORMAT = "Object has no '%s' attribute.";

  /**
   * For schemaful providers, a layout describing the allowed fields and their order in an
   * array-based representation. For schemaless providers, null.
   */
  @Nullable
  private final Layout layout;

  /** Null iff this provider has not yet been exported. */
  @Nullable
  private SkylarkKey key;

  /** Error message format. Reassigned upon exporting. */
  private String errorMessageFormatForUnknownField;

  /**
   * Creates an unexported {@link SkylarkProvider} with no schema.
   *
   * <p>The resulting object needs to be exported later (via {@link #export}).
   *
   * @param location the location of the Skylark definition for this provider (tests may use {@link
   *     Location#BUILTIN})
   */
  public static SkylarkProvider createUnexportedSchemaless(Location location) {
    return new SkylarkProvider(/*key=*/ null, /*fields=*/ null, location);
  }

  /**
   * Creates an unexported {@link SkylarkProvider} with a schema.
   *
   * <p>The resulting object needs to be exported later (via {@link #export}).
   *
   * @param fields a list of allowed field names for instances of this provider, in some canonical
   *     order
   * @param location the location of the Skylark definition for this provider (tests may use {@link
   *     Location#BUILTIN})
   */
  public static SkylarkProvider createUnexportedSchemaful(
      Iterable<String> fields, Location location) {
    return new SkylarkProvider(
        /*key=*/ null, fields == null ? null : ImmutableList.copyOf(fields), location);
  }

  /**
   * Creates an exported {@link SkylarkProvider} with no schema.
   *
   * @param key the key that identifies this provider
   * @param location the location of the Skylark definition for this provider (tests may use {@link
   *     Location#BUILTIN})
   */
  public static SkylarkProvider createExportedSchemaless(SkylarkKey key, Location location) {
    return new SkylarkProvider(key, /*fields=*/ null, location);
  }

  /**
   * Creates an exported {@link SkylarkProvider} with no schema.
   *
   * @param key the key that identifies this provider
   * @param fields a list of allowed field names for instances of this provider, in some canonical
   *     order
   * @param location the location of the Skylark definition for this provider (tests may use {@link
   *     Location#BUILTIN})
   */
  public static SkylarkProvider createExportedSchemaful(
      SkylarkKey key, Iterable<String> fields, Location location) {
    return new SkylarkProvider(key, fields == null ? null : ImmutableList.copyOf(fields), location);
  }

  /**
   * Constructs the provider.
   *
   * <p>If {@code key} is null, the provider is unexported. If {@code fields} is null, the provider
   * is schemaless.
   */
  private SkylarkProvider(
      @Nullable SkylarkKey key, @Nullable ImmutableList<String> fields, Location location) {
    // We override getName() in order to use the name that is assigned when export() is called.
    // Hence BaseFunction's constructor gets a null name.
    super(/*name=*/ null, buildSignature(fields), location);
    this.layout = fields == null ? null : new Layout(fields);
    this.key = key;  // possibly null
    this.errorMessageFormatForUnknownField =
        key == null ? DEFAULT_ERROR_MESSAGE_FORMAT
            : makeErrorMessageFormatForUnknownField(key.getExportedName());
  }

  private static FunctionSignature.WithValues<Object, SkylarkType> buildSignature(
      @Nullable Iterable<String> fields) {
    if (fields == null) {
      return SCHEMALESS_SIGNATURE;
    }
    return FunctionSignature.WithValues.create(
        FunctionSignature.namedOnly(0, ImmutableList.copyOf(fields).toArray(new String[0])));
  }

  @Override
  protected SkylarkInfo createInstanceFromSkylark(Object[] args, Environment env, Location loc) {
    if (layout == null) {
      @SuppressWarnings("unchecked")
      Map<String, Object> kwargs = (Map<String, Object>) args[0];
      return SkylarkInfo.createSchemaless(this, kwargs, loc);
    } else {
      // Note: This depends on the layout map using the same ordering as args.
      return SkylarkInfo.createSchemaful(this, layout, args, loc);
    }
  }

  @Override
  public boolean isExported() {
    return key != null;
  }

  @Override
  public SkylarkKey getKey() {
    Preconditions.checkState(isExported());
    return key;
  }

  @Override
  public String getName() {
    return key != null ? key.getExportedName() : "<no name>";
  }

  @Override
  public String getPrintableName() {
    return getName();
  }

  /**
   * Returns the list of fields used to define this provider, or null if the provider is schemaless.
   *
   * <p>Note: In the future, this method may be replaced by one that returns more detailed schema
   * information (if/when the allowed schemas for structs become more complex).
   */
  @Nullable
  public ImmutableList<String> getFields() {
    if (layout == null) {
      return null;
    }
    return ImmutableList.copyOf(layout.getFields());
  }

  /** Returns the layout, or null if the provider is schemaless. */
  @VisibleForTesting
  @Nullable
  Layout getLayout() {
    return layout;
  }

  @Override
  public String getErrorMessageFormatForUnknownField() {
    return errorMessageFormatForUnknownField;
  }

  @Override
  public void export(Label extensionLabel, String exportedName) {
    Preconditions.checkState(!isExported());
    this.key = new SkylarkKey(extensionLabel, exportedName);
    this.errorMessageFormatForUnknownField = makeErrorMessageFormatForUnknownField(exportedName);
  }

  private static String makeErrorMessageFormatForUnknownField(String exportedName) {
    return String.format("'%s' object has no attribute '%%s'", exportedName);
  }

  @Override
  public int hashCode() {
    if (isExported()) {
      return getKey().hashCode();
    }
    return System.identityHashCode(this);
  }

  @Override
  public boolean equals(@Nullable Object otherObject) {
    if (!(otherObject instanceof SkylarkProvider)) {
      return false;
    }
    SkylarkProvider other = (SkylarkProvider) otherObject;

    if (this.isExported() && other.isExported()) {
      return this.getKey().equals(other.getKey());
    } else {
      return this == other;
    }
  }

  @Override
  public boolean isImmutable() {
    // Hash code for non exported constructors may be changed
    return isExported();
  }

  @Override
  public void repr(SkylarkPrinter printer) {
    printer.append("<provider>");
  }

  /**
   * A serializable representation of Skylark-defined {@link SkylarkProvider} that uniquely
   * identifies all {@link SkylarkProvider}s that are exposed to SkyFrame.
   */
  @AutoCodec
  public static class SkylarkKey extends Key {
    private final Label extensionLabel;
    private final String exportedName;

    public SkylarkKey(Label extensionLabel, String exportedName) {
      this.extensionLabel = Preconditions.checkNotNull(extensionLabel);
      this.exportedName = Preconditions.checkNotNull(exportedName);
    }

    public Label getExtensionLabel() {
      return extensionLabel;
    }

    public String getExportedName() {
      return exportedName;
    }

    @Override
    public String toString() {
      return exportedName;
    }

    @Override
    public int hashCode() {
      return Objects.hash(extensionLabel, exportedName);
    }

    @Override
    public boolean equals(Object obj) {
      if (this == obj) {
        return true;
      }

      if (!(obj instanceof SkylarkKey)) {
        return false;
      }
      SkylarkKey other = (SkylarkKey) obj;
      return Objects.equals(this.extensionLabel, other.extensionLabel)
          && Objects.equals(this.exportedName, other.exportedName);
    }
  }
}
