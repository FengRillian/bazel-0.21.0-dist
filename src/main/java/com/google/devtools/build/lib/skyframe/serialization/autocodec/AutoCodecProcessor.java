// Copyright 2017 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.skyframe.serialization.autocodec;

import static com.google.common.collect.ImmutableList.toImmutableList;

import com.google.auto.service.AutoService;
import com.google.auto.value.AutoValue;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.devtools.build.lib.skyframe.serialization.CodecScanningConstants;
import com.google.devtools.build.lib.skyframe.serialization.ObjectCodec;
import com.google.devtools.build.lib.skyframe.serialization.SerializationException;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.SerializationCodeGenerator.Marshaller;
import com.google.devtools.build.lib.unsafe.UnsafeProvider;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.Processor;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;
import javax.tools.Diagnostic;

/**
 * Javac annotation processor (compiler plugin) for generating {@link ObjectCodec} implementations.
 *
 * <p>User code must never reference this class.
 */
@AutoService(Processor.class)
public class AutoCodecProcessor extends AbstractProcessor {
  /**
   * Passing {@code --javacopt=-Aautocodec_print_generated} to {@code blaze build} tells AutoCodec
   * to print the generated code.
   */
  private static final String PRINT_GENERATED_OPTION = "autocodec_print_generated";

  private ProcessingEnvironment env; // Captured from `init` method.
  private Marshallers marshallers;

  @Override
  public Set<String> getSupportedOptions() {
    return ImmutableSet.of(PRINT_GENERATED_OPTION);
  }

  @Override
  public Set<String> getSupportedAnnotationTypes() {
    return ImmutableSet.of(AutoCodecUtil.ANNOTATION.getCanonicalName());
  }

  @Override
  public SourceVersion getSupportedSourceVersion() {
    return SourceVersion.latestSupported(); // Supports all versions of Java.
  }

  @Override
  public synchronized void init(ProcessingEnvironment processingEnv) {
    super.init(processingEnv);
    this.env = processingEnv;
    this.marshallers = new Marshallers(processingEnv);
  }

  @Override
  public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
    for (Element element : roundEnv.getElementsAnnotatedWith(AutoCodecUtil.ANNOTATION)) {
      AutoCodec annotation = element.getAnnotation(AutoCodecUtil.ANNOTATION);
      TypeSpec builtClass;
      if (element instanceof TypeElement) {
        TypeElement encodedType = (TypeElement) element;
        TypeSpec.Builder codecClassBuilder;
        switch (annotation.strategy()) {
          case INSTANTIATOR:
            codecClassBuilder = buildClassWithInstantiatorStrategy(encodedType, annotation);
            break;
          case AUTO_VALUE_BUILDER:
            codecClassBuilder = buildClassWithAutoValueBuilderStrategy(encodedType, annotation);
            break;
          default:
            throw new IllegalArgumentException("Unknown strategy: " + annotation.strategy());
        }
        codecClassBuilder.addMethod(
            AutoCodecUtil.initializeGetEncodedClassMethod(encodedType, env)
                .addStatement(
                    "return $T.class",
                    TypeName.get(env.getTypeUtils().erasure(encodedType.asType())))
                .build());
        builtClass = codecClassBuilder.build();
      } else {
        builtClass = buildRegisteredSingletonClass((VariableElement) element);
      }
      String packageName =
          env.getElementUtils().getPackageOf(element).getQualifiedName().toString();
      try {
        JavaFile file = JavaFile.builder(packageName, builtClass).build();
        file.writeTo(env.getFiler());
        if (env.getOptions().containsKey(PRINT_GENERATED_OPTION)) {
          note("AutoCodec generated codec for " + element + ":\n" + file);
        }
      } catch (IOException e) {
        env.getMessager()
            .printMessage(
                Diagnostic.Kind.ERROR, "Failed to generate output file: " + e.getMessage());
      }
    }
    return true;
  }

  @SuppressWarnings("MutableConstantField")
  private static final Collection<Modifier> REQUIRED_SINGLETON_MODIFIERS =
      ImmutableList.of(Modifier.STATIC, Modifier.FINAL);

  private TypeSpec buildRegisteredSingletonClass(VariableElement symbol) {
    Preconditions.checkState(
        symbol.getModifiers().containsAll(REQUIRED_SINGLETON_MODIFIERS),
        "Field must be static and final to be annotated with @AutoCodec: " + symbol);
    return TypeSpec.classBuilder(
            AutoCodecUtil.getGeneratedName(
                symbol, CodecScanningConstants.REGISTERED_SINGLETON_SUFFIX))
        .addModifiers(Modifier.PUBLIC)
        .addSuperinterface(RegisteredSingletonDoNotUse.class)
        .addField(
            FieldSpec.builder(
                    TypeName.get(symbol.asType()),
                    CodecScanningConstants.REGISTERED_SINGLETON_INSTANCE_VAR_NAME,
                    Modifier.PUBLIC,
                    Modifier.STATIC,
                    Modifier.FINAL)
                .initializer(
                    "$T.$L",
                    sanitizeTypeParameter(symbol.getEnclosingElement().asType()),
                    symbol.getSimpleName())
                .build())
        .build();
  }

  private TypeSpec.Builder buildClassWithInstantiatorStrategy(
      TypeElement encodedType, AutoCodec annotation) {
    ExecutableElement constructor = selectInstantiator(encodedType);
    List<? extends VariableElement> fields = constructor.getParameters();

    TypeSpec.Builder codecClassBuilder =
        AutoCodecUtil.initializeCodecClassBuilder(encodedType, env);

    if (encodedType.getAnnotation(AutoValue.class) == null) {
      initializeUnsafeOffsets(codecClassBuilder, encodedType, fields);
      codecClassBuilder.addMethod(
          buildSerializeMethodWithInstantiator(encodedType, fields, annotation));
    } else {
      codecClassBuilder.addMethod(
          buildSerializeMethodWithInstantiatorForAutoValue(encodedType, fields, annotation));
    }

    MethodSpec.Builder deserializeBuilder =
        AutoCodecUtil.initializeDeserializeMethodBuilder(encodedType, env);
    buildDeserializeBody(deserializeBuilder, fields);
    addReturnNew(deserializeBuilder, encodedType, constructor, /*builderVar=*/ null, env);
    codecClassBuilder.addMethod(deserializeBuilder.build());

    return codecClassBuilder;
  }

  private TypeSpec.Builder buildClassWithAutoValueBuilderStrategy(
      TypeElement encodedType, AutoCodec annotation) {
    TypeElement builderType = findBuilderType(encodedType);
    List<ExecutableElement> getters = findGettersFromType(encodedType, builderType);
    ExecutableElement builderCreationMethod = findBuilderCreationMethod(encodedType, builderType);
    ExecutableElement buildMethod = findBuildMethod(encodedType, builderType);
    TypeSpec.Builder codecClassBuilder =
        AutoCodecUtil.initializeCodecClassBuilder(encodedType, env);
    MethodSpec.Builder serializeBuilder =
        AutoCodecUtil.initializeSerializeMethodBuilder(encodedType, annotation, env);
    for (ExecutableElement getter : getters) {
      marshallers.writeSerializationCode(
          new Marshaller.Context(
              serializeBuilder,
              getter.getReturnType(),
              turnGetterIntoExpression(getter.getSimpleName().toString())));
    }
    codecClassBuilder.addMethod(serializeBuilder.build());
    MethodSpec.Builder deserializeBuilder =
        AutoCodecUtil.initializeDeserializeMethodBuilder(encodedType, env);
    String builderVarName =
        buildDeserializeBodyWithBuilder(
            encodedType, builderType, deserializeBuilder, getters, builderCreationMethod);
    addReturnNew(deserializeBuilder, encodedType, buildMethod, builderVarName, env);
    codecClassBuilder.addMethod(deserializeBuilder.build());

    return codecClassBuilder;
  }

  private ExecutableElement selectInstantiator(TypeElement encodedType) {
    List<ExecutableElement> constructors =
        ElementFilter.constructorsIn(encodedType.getEnclosedElements());
    Stream<ExecutableElement> factoryMethods =
        ElementFilter.methodsIn(encodedType.getEnclosedElements())
            .stream()
            .filter(AutoCodecProcessor::hasInstantiatorAnnotation)
            .peek(m -> verifyFactoryMethod(encodedType, m));
    ImmutableList<ExecutableElement> markedInstantiators =
        Stream.concat(
                constructors.stream().filter(AutoCodecProcessor::hasInstantiatorAnnotation),
                factoryMethods)
            .collect(toImmutableList());
    if (markedInstantiators.isEmpty()) {
      // If nothing is marked, see if there is a unique constructor.
      if (constructors.size() > 1) {
        throw new IllegalArgumentException(
            encodedType.getQualifiedName()
                + " has multiple constructors but no Instantiator annotation.");
      }
      // In Java, every class has at least one constructor, so this never fails.
      return constructors.get(0);
    }
    if (markedInstantiators.size() == 1) {
      return markedInstantiators.get(0);
    }
    throw new IllegalArgumentException(
        encodedType.getQualifiedName() + " has multiple Instantiator annotations.");
  }

  private static boolean hasInstantiatorAnnotation(Element elt) {
    return elt.getAnnotation(AutoCodec.Instantiator.class) != null;
  }

  private TypeElement findBuilderType(TypeElement encodedType) {
    TypeElement builderType = null;
    for (Element element : encodedType.getEnclosedElements()) {
      if (element instanceof TypeElement
          && element.getModifiers().contains(Modifier.STATIC)
          && element.getAnnotation(AutoValue.Builder.class) != null) {
        if (builderType != null) {
          throw new IllegalArgumentException(
              "Type "
                  + encodedType
                  + " had multiple inner classes annotated as @AutoValue.Builder: "
                  + builderType
                  + " and "
                  + element);
        }
        builderType = (TypeElement) element;
      }
    }
    if (builderType == null) {
      throw new IllegalArgumentException(
          "Couldn't find @AutoValue.Builder-annotated static class inside " + encodedType);
    }
    return builderType;
  }

  private List<ExecutableElement> findGettersFromType(
      TypeElement encodedType, TypeElement builderTypeForFiltering) {
    List<ExecutableElement> result = new ArrayList<>();
    for (ExecutableElement method :
        ElementFilter.methodsIn(env.getElementUtils().getAllMembers(encodedType))) {
      if (!method.getModifiers().contains(Modifier.STATIC)
          && method.getModifiers().contains(Modifier.ABSTRACT)
          && method.getParameters().isEmpty()
          && method.getReturnType().getKind() != TypeKind.VOID
          && (!method.getReturnType().getKind().equals(TypeKind.DECLARED)
              || !builderTypeForFiltering.equals(
                  env.getTypeUtils().asElement(method.getReturnType())))) {
        result.add(method);
      }
    }
    if (result.isEmpty()) {
      throw new IllegalArgumentException("Couldn't find any properties for " + encodedType);
    }
    return result;
  }

  private String getNameFromGetter(ExecutableElement method) {
    String name = method.getSimpleName().toString();
    if (name.startsWith("get")) {
      return name.substring(3, 4).toLowerCase() + name.substring(4);
    } else if (name.startsWith("is")) {
      return name.substring(2, 3).toLowerCase() + name.substring(3);
    } else {
      return name;
    }
  }

  private ExecutableElement findBuilderCreationMethod(
      TypeElement encodedType, TypeElement builderType) {
    ExecutableElement builderMethod = null;
    for (ExecutableElement method :
        ElementFilter.methodsIn(env.getElementUtils().getAllMembers(encodedType))) {
      if (method.getModifiers().contains(Modifier.STATIC)
          && !method.getModifiers().contains(Modifier.ABSTRACT)
          && method.getParameters().isEmpty()
          && method.getReturnType().equals(builderType.asType())) {
        if (builderMethod != null) {
          throw new IllegalArgumentException(
              "Type "
                  + encodedType
                  + " had multiple static methods to create an element of type "
                  + builderType
                  + ": "
                  + builderMethod
                  + " and "
                  + method);
        }
        builderMethod = method;
      }
    }
    if (builderMethod == null) {
      throw new IllegalArgumentException(
          "Couldn't find builder creation method for " + encodedType + " and " + builderType);
    }
    return builderMethod;
  }

  private ExecutableElement findBuildMethod(TypeElement encodedType, TypeElement builderType) {
    ExecutableElement abstractBuildMethod = null;
    for (ExecutableElement method :
        ElementFilter.methodsIn(env.getElementUtils().getAllMembers(builderType))) {
      if (method.getModifiers().contains(Modifier.STATIC)) {
        continue;
      }
      if (method.getParameters().isEmpty()
          && method.getReturnType().equals(encodedType.asType())
          && method.getModifiers().contains(Modifier.ABSTRACT)) {
          if (abstractBuildMethod != null) {
            throw new IllegalArgumentException(
                "Type "
                    + builderType
                    + " had multiple abstract methods to create an element of type "
                    + encodedType
                    + ": "
                    + abstractBuildMethod
                    + " and "
                    + method);
          }
          abstractBuildMethod = method;
      }
    }
    if (abstractBuildMethod == null) {
      throw new IllegalArgumentException(
          "Couldn't find build method for " + encodedType + " and " + builderType);
    }
    return abstractBuildMethod;
  }

  private String buildDeserializeBodyWithBuilder(
      TypeElement encodedType,
      TypeElement builderType,
      MethodSpec.Builder builder,
      List<ExecutableElement> fields,
      ExecutableElement builderCreationMethod) {
    String builderVarName = "objectBuilder";
    builder.addStatement(
        "$T $L = $T.$L()",
        builderCreationMethod.getReturnType(),
        builderVarName,
        encodedType,
        builderCreationMethod.getSimpleName());
    for (ExecutableElement getter : fields) {
      String paramName = getNameFromGetter(getter) + "_";
      marshallers.writeDeserializationCode(
          new Marshaller.Context(builder, getter.getReturnType(), paramName));
      setValueInBuilder(builderType, getter, paramName, builderVarName, builder);
    }
    return builderVarName;
  }

  private void setValueInBuilder(
      TypeElement builderType,
      ExecutableElement getter,
      String paramName,
      String builderVarName,
      MethodSpec.Builder methodBuilder) {
    ExecutableElement setterMethod = findSetterGivenGetter(getter, builderType);
    methodBuilder.addStatement(
        "$L.$L($L)", builderVarName, setterMethod.getSimpleName(), paramName);
  }

  private ExecutableElement findSetterGivenGetter(
      ExecutableElement getter, TypeElement builderType) {
    List<ExecutableElement> methods =
        ElementFilter.methodsIn(env.getElementUtils().getAllMembers(builderType));
    String varName = getNameFromGetter(getter);
    TypeMirror type = getter.getReturnType();
    ImmutableSet<String> setterNames = ImmutableSet.of(varName, addCamelCasePrefix(varName, "set"));

    ExecutableElement setterMethod = null;
    for (ExecutableElement method : methods) {
      if (!method.getModifiers().contains(Modifier.STATIC)
          && !method.getModifiers().contains(Modifier.PRIVATE)
          && setterNames.contains(method.getSimpleName().toString())
          && method.getReturnType().equals(builderType.asType())
          && method.getParameters().size() == 1
          && env.getTypeUtils()
              .isSubtype(type, Iterables.getOnlyElement(method.getParameters()).asType())) {
        if (setterMethod != null) {
          throw new IllegalArgumentException(
              "Multiple setter methods for "
                  + getter
                  + " found in "
                  + builderType
                  + ": "
                  + setterMethod
                  + " and "
                  + method);
        }
        setterMethod = method;
      }
    }
    if (setterMethod != null) {
      return setterMethod;
    }

    throw new IllegalArgumentException(
        builderType
            + ": No setter found corresponding to getter "
            + getter.getSimpleName()
            + ", "
            + type);
  }

  private enum Relation {
    INSTANCE_OF,
    EQUAL_TO,
    SUPERTYPE_OF,
    UNRELATED_TO
  }

  private Relation findRelationWithGenerics(TypeMirror type1, TypeMirror type2) {
    if (type1.getKind() == TypeKind.TYPEVAR
        || type1.getKind() == TypeKind.WILDCARD
        || type2.getKind() == TypeKind.TYPEVAR
        || type2.getKind() == TypeKind.WILDCARD) {
      return Relation.EQUAL_TO;
    }
    if (env.getTypeUtils().isAssignable(type1, type2)) {
      if (env.getTypeUtils().isAssignable(type2, type1)) {
        return Relation.EQUAL_TO;
      }
      return Relation.INSTANCE_OF;
    }
    if (env.getTypeUtils().isAssignable(type2, type1)) {
      return Relation.SUPERTYPE_OF;
    }
    // From here on out, we can't detect subtype/supertype, we're only checking for equality.
    TypeMirror erasedType1 = env.getTypeUtils().erasure(type1);
    TypeMirror erasedType2 = env.getTypeUtils().erasure(type2);
    if (!env.getTypeUtils().isSameType(erasedType1, erasedType2)) {
      // Technically, there could be a relationship, but it's too hard to figure out for now.
      return Relation.UNRELATED_TO;
    }
    List<? extends TypeMirror> genericTypes1 = ((DeclaredType) type1).getTypeArguments();
    List<? extends TypeMirror> genericTypes2 = ((DeclaredType) type2).getTypeArguments();
    if (genericTypes1.size() != genericTypes2.size()) {
      return null;
    }
    for (int i = 0; i < genericTypes1.size(); i++) {
      Relation result = findRelationWithGenerics(genericTypes1.get(i), genericTypes2.get(i));
      if (result != Relation.EQUAL_TO) {
        return Relation.UNRELATED_TO;
      }
    }
    return Relation.EQUAL_TO;
  }

  private void verifyFactoryMethod(TypeElement encodedType, ExecutableElement elt) {
    boolean success = elt.getModifiers().contains(Modifier.STATIC);
    if (success) {
      Relation equalityTest = findRelationWithGenerics(elt.getReturnType(), encodedType.asType());
      success = equalityTest == Relation.EQUAL_TO || equalityTest == Relation.INSTANCE_OF;
    }
    if (!success) {
      throw new IllegalArgumentException(
          encodedType.getQualifiedName()
              + " tags "
              + elt.getSimpleName()
              + " as an Instantiator, but it's not a valid factory method "
              + elt.getReturnType()
              + ", "
              + encodedType.asType());
    }
  }

  private MethodSpec buildSerializeMethodWithInstantiator(
      TypeElement encodedType, List<? extends VariableElement> fields, AutoCodec annotation) {
    MethodSpec.Builder serializeBuilder =
        AutoCodecUtil.initializeSerializeMethodBuilder(encodedType, annotation, env);
    for (VariableElement parameter : fields) {
      Optional<FieldValueAndClass> hasField =
          getFieldByNameRecursive(encodedType, parameter.getSimpleName().toString());
      if (hasField.isPresent()) {
        Preconditions.checkArgument(
            findRelationWithGenerics(hasField.get().value.asType(), parameter.asType())
                != Relation.UNRELATED_TO,
            "%s: parameter %s's type %s is unrelated to corresponding field type %s",
            encodedType.getQualifiedName(),
            parameter.getSimpleName(),
            parameter.asType(),
            hasField.get().value.asType());
        TypeKind typeKind = parameter.asType().getKind();
        serializeBuilder.addStatement(
            "$T unsafe_$L = ($T) $T.getInstance().get$L(input, $L_offset)",
            sanitizeTypeParameter(parameter.asType()),
            parameter.getSimpleName(),
            sanitizeTypeParameter(parameter.asType()),
            UnsafeProvider.class,
            typeKind.isPrimitive() ? firstLetterUpper(typeKind.toString().toLowerCase()) : "Object",
            parameter.getSimpleName());
            marshallers.writeSerializationCode(
                new Marshaller.Context(
                    serializeBuilder, parameter.asType(), "unsafe_" + parameter.getSimpleName()));
      } else {
        addSerializeParameterWithGetter(encodedType, parameter, serializeBuilder);
      }
    }
    return serializeBuilder.build();
  }

  // Sanitizes the type parameter. If it's a TypeVariable or WildcardType this will get the erasure.
  private TypeMirror sanitizeTypeParameter(TypeMirror type) {
    if (Marshallers.isVariableOrWildcardType(type)) {
      return env.getTypeUtils().erasure(type);
    }
    if (!(type instanceof DeclaredType)) {
      return type;
    }
    DeclaredType declaredType = (DeclaredType) type;
    for (TypeMirror typeMirror : declaredType.getTypeArguments()) {
      if (Marshallers.isVariableOrWildcardType(typeMirror)) {
        return env.getTypeUtils().erasure(type);
      }
    }
    return type;
  }

  private String findGetterForClass(VariableElement parameter, TypeElement type) {
    List<ExecutableElement> methods =
        ElementFilter.methodsIn(env.getElementUtils().getAllMembers(type));

    ImmutableSet.Builder<String> possibleGetterNamesBuilder =
        ImmutableSet.<String>builder().add(parameter.getSimpleName().toString());

    if (parameter.asType().getKind() == TypeKind.BOOLEAN) {
      possibleGetterNamesBuilder.add(
          addCamelCasePrefix(parameter.getSimpleName().toString(), "is"));
    } else {
      possibleGetterNamesBuilder.add(
          addCamelCasePrefix(parameter.getSimpleName().toString(), "get"));
    }
    ImmutableSet<String> possibleGetterNames = possibleGetterNamesBuilder.build();

    for (ExecutableElement element : methods) {
      if (!element.getModifiers().contains(Modifier.STATIC)
          && !element.getModifiers().contains(Modifier.PRIVATE)
          && possibleGetterNames.contains(element.getSimpleName().toString())
          && findRelationWithGenerics(parameter.asType(), element.getReturnType())
              != Relation.UNRELATED_TO) {
        return element.getSimpleName().toString();
      }
    }

    throw new IllegalArgumentException(
        type
            + ": No getter found corresponding to parameter "
            + parameter.getSimpleName()
            + ", "
            + parameter.asType());
  }

  private static String addCamelCasePrefix(String name, String prefix) {
    return prefix + firstLetterUpper(name);
  }

  private static String firstLetterUpper(String str) {
    return Character.toUpperCase(str.charAt(0)) + (str.length() == 1 ? "" : str.substring(1));
  }

  private void addSerializeParameterWithGetter(
      TypeElement encodedType, VariableElement parameter, MethodSpec.Builder serializeBuilder) {
    String getter = turnGetterIntoExpression(findGetterForClass(parameter, encodedType));
    marshallers.writeSerializationCode(
        new Marshaller.Context(serializeBuilder, parameter.asType(), getter));
  }

  private static String turnGetterIntoExpression(String getterName) {
    return "input." + getterName + "()";
  }

  private MethodSpec buildSerializeMethodWithInstantiatorForAutoValue(
      TypeElement encodedType, List<? extends VariableElement> fields, AutoCodec annotation) {
    MethodSpec.Builder serializeBuilder =
        AutoCodecUtil.initializeSerializeMethodBuilder(encodedType, annotation, env);
    for (VariableElement parameter : fields) {
      addSerializeParameterWithGetter(encodedType, parameter, serializeBuilder);
    }
    return serializeBuilder.build();
  }

  /**
   * Adds a body to the deserialize method that extracts serialized parameters.
   *
   * <p>Parameter values are extracted into local variables with the same name as the parameter
   * suffixed with a trailing underscore. For example, {@code target} becomes {@code target_}. This
   * is to avoid name collisions with variables used internally by AutoCodec.
   */
  private void buildDeserializeBody(
      MethodSpec.Builder builder, List<? extends VariableElement> fields) {
    for (VariableElement parameter : fields) {
      String paramName = parameter.getSimpleName() + "_";
      marshallers.writeDeserializationCode(
          new Marshaller.Context(builder, parameter.asType(), paramName));
    }
  }

  /**
   * Invokes the instantiator and returns the value.
   *
   * <p>Used by the {@link AutoCodec.Strategy#INSTANTIATOR} strategy.
   */
  private static void addReturnNew(
      MethodSpec.Builder builder,
      TypeElement type,
      ExecutableElement instantiator,
      Object builderVar,
      ProcessingEnvironment env) {
    List<? extends TypeMirror> allThrown = instantiator.getThrownTypes();
    if (!allThrown.isEmpty()) {
      builder.beginControlFlow("try");
    }
    TypeName typeName = TypeName.get(env.getTypeUtils().erasure(type.asType()));
    String parameters =
        instantiator
            .getParameters()
            .stream()
            .map(AutoCodecProcessor::handleFromParameter)
            .collect(Collectors.joining(", "));
    if (instantiator.getKind().equals(ElementKind.CONSTRUCTOR)) {
      builder.addStatement("return new $T($L)", typeName, parameters);
    } else if (builderVar == null) { // Otherwise, it's a factory method.
      builder.addStatement("return $T.$L($L)", typeName, instantiator.getSimpleName(), parameters);
    } else {
      builder.addStatement(
          "return $L.$L($L)", builderVar, instantiator.getSimpleName(), parameters);
    }
    if (!allThrown.isEmpty()) {
      for (TypeMirror thrown : allThrown) {
        builder.nextControlFlow("catch ($T e)", TypeName.get(thrown));
        builder.addStatement(
            "throw new $T(\"$L instantiator threw an exception\", e)",
            SerializationException.class,
            type.getQualifiedName());
      }
      builder.endControlFlow();
    }
  }

  /**
   * Coverts a constructor parameter to a String representing its handle within deserialize.
   */
  private static String handleFromParameter(VariableElement parameter) {
    return parameter.getSimpleName() + "_";
  }

  /**
   * Adds fields to the codec class to hold offsets and adds a constructor to initialize them.
   *
   * <p>For a parameter with name {@code target}, the field will have name {@code target_offset}.
   *
   * @param parameters constructor parameters
   */
  private void initializeUnsafeOffsets(
      TypeSpec.Builder builder,
      TypeElement encodedType,
      List<? extends VariableElement> parameters) {
    MethodSpec.Builder constructor = MethodSpec.constructorBuilder();
    for (VariableElement param : parameters) {
      Optional<FieldValueAndClass> field =
          getFieldByNameRecursive(encodedType, param.getSimpleName().toString());
      if (!field.isPresent()) {
        // Will attempt to use a getter for this field instead.
        continue;
      }
      builder.addField(
          TypeName.LONG, param.getSimpleName() + "_offset", Modifier.PRIVATE, Modifier.FINAL);
      constructor.beginControlFlow("try");
      constructor.addStatement(
          "this.$L_offset = $T.getInstance().objectFieldOffset($T.class.getDeclaredField(\"$L\"))",
          param.getSimpleName(),
          UnsafeProvider.class,
          ClassName.get(field.get().declaringClassType),
          param.getSimpleName());
      constructor.nextControlFlow("catch ($T e)", NoSuchFieldException.class);
      constructor.addStatement("throw new $T(e)", IllegalStateException.class);
      constructor.endControlFlow();
    }
    builder.addMethod(constructor.build());
  }

  /** The value of a field, as well as the class that directly declares it. */
  private static class FieldValueAndClass {
    final VariableElement value;
    final TypeElement declaringClassType;

    FieldValueAndClass(VariableElement value, TypeElement declaringClassType) {
      this.value = value;
      this.declaringClassType = declaringClassType;
    }
  }

  private Optional<FieldValueAndClass> getFieldByNameRecursive(TypeElement type, String name) {
    Optional<VariableElement> field =
        ElementFilter.fieldsIn(type.getEnclosedElements())
            .stream()
            .filter(f -> f.getSimpleName().contentEquals(name))
            .findAny();

    if (field.isPresent()) {
      return Optional.of(new FieldValueAndClass(field.get(), type));
    }
    if (type.getSuperclass().getKind() != TypeKind.NONE) {
      // Applies the erased superclass type so that it can be used in `T.class`.
      return getFieldByNameRecursive(
          (TypeElement)
              env.getTypeUtils().asElement(env.getTypeUtils().erasure(type.getSuperclass())),
          name);
    }
    return Optional.empty();
  }

  /** True when {@code type} has the same type as {@code clazz}. */
  private boolean matchesType(TypeMirror type, Class<?> clazz) {
    return env.getTypeUtils()
        .isSameType(
            type, env.getElementUtils().getTypeElement((clazz.getCanonicalName())).asType());
  }

  /** Emits a note to BUILD log during annotation processing for debugging. */
  private void note(String note) {
    env.getMessager().printMessage(Diagnostic.Kind.NOTE, note);
  }
}
