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
package com.google.devtools.build.android.resources;

import static java.nio.file.StandardOpenOption.CREATE_NEW;

import com.android.SdkConstants;
import com.android.resources.ResourceType;
import com.google.common.base.Splitter;
import com.google.common.collect.Iterables;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.InstructionAdapter;

/**
 * Writes out bytecode for an R.class directly, rather than go through an R.java and compile. This
 * avoids re-parsing huge R.java files and other time spent in the java compiler (e.g., plugins like
 * ErrorProne). A difference is that this doesn't generate line number tables and other debugging
 * information. Also, the order of the constant pool tends to be different.
 */
public class RClassGenerator {
  private static final int JAVA_VERSION = Opcodes.V1_7;
  private static final String SUPER_CLASS = "java/lang/Object";
  private final Path outFolder;
  private final FieldInitializers initializers;
  private final boolean finalFields;
  private static final Splitter PACKAGE_SPLITTER = Splitter.on('.');

  /**
   * Create an RClassGenerator given a collection of initializers.
   *
   * @param outFolder base folder to place the output R class files.
   * @param initializers the list of initializers to use for each inner class
   * @param finalFields true if the fields should be marked final
   */
  public static RClassGenerator with(
      Path outFolder, FieldInitializers initializers, boolean finalFields) {
    return new RClassGenerator(outFolder, initializers, finalFields);
  }

  private RClassGenerator(Path outFolder, FieldInitializers initializers, boolean finalFields) {
    this.outFolder = outFolder;
    this.finalFields = finalFields;
    this.initializers = initializers;
  }

  /**
   * Builds bytecode and writes out R.class file, and R$inner.class files for provided package and
   * symbols.
   */
  public void write(String packageName, FieldInitializers symbolsToWrite) throws IOException {
    writeClasses(packageName, initializers.filter(symbolsToWrite));
  }

  /** Builds bytecode and writes out R.class file, and R$inner.class files for provided package. */
  public void write(String packageName) throws IOException {
    writeClasses(packageName, initializers);
  }

  private void writeClasses(
      String packageName,
      Iterable<Map.Entry<ResourceType, Map<String, FieldInitializer>>> initializersToWrite)
      throws IOException {

    Iterable<String> folders = PACKAGE_SPLITTER.split(packageName);
    Path packageDir = outFolder;
    for (String folder : folders) {
      packageDir = packageDir.resolve(folder);
    }
    // At least create the outFolder that was requested. However, if there are no symbols, don't
    // create the R.class and inner class files (no need to have an empty class).
    Files.createDirectories(packageDir);

    if (Iterables.isEmpty(initializersToWrite)) {
      return;
    }

    Path rClassFile = packageDir.resolve(SdkConstants.FN_COMPILED_RESOURCE_CLASS);

    String packageWithSlashes = packageName.replaceAll("\\.", "/");
    String rClassName = packageWithSlashes.isEmpty() ? "R" : (packageWithSlashes + "/R");
    ClassWriter classWriter = new ClassWriter(ClassWriter.COMPUTE_MAXS);
    classWriter.visit(
        JAVA_VERSION,
        Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER,
        rClassName,
        null, /* signature */
        SUPER_CLASS,
        null /* interfaces */);
    classWriter.visitSource(SdkConstants.FN_RESOURCE_CLASS, null);
    writeConstructor(classWriter);
    // Build the R.class w/ the inner classes, then later build the individual R$inner.class.
    for (Map.Entry<ResourceType, Map<String, FieldInitializer>> entry : initializersToWrite) {
      String innerClassName = rClassName + "$" + entry.getKey().toString();
      classWriter.visitInnerClass(
          innerClassName,
          rClassName,
          entry.getKey().toString(),
          Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_STATIC);
    }
    classWriter.visitEnd();
    Files.write(rClassFile, classWriter.toByteArray(), CREATE_NEW);
    // Now generate the R$inner.class files.
    for (Map.Entry<ResourceType, Map<String, FieldInitializer>> entry : initializersToWrite) {
      writeInnerClass(entry.getValue(), packageDir, rClassName, entry.getKey().toString());
    }
  }

  private void writeInnerClass(
      Map<String, FieldInitializer> initializers,
      Path packageDir,
      String fullyQualifiedOuterClass,
      String innerClass)
      throws IOException {
    ClassWriter innerClassWriter = new ClassWriter(ClassWriter.COMPUTE_MAXS);
    String fullyQualifiedInnerClass =
        writeInnerClassHeader(fullyQualifiedOuterClass, innerClass, innerClassWriter);

    Map<String, FieldInitializer> deferredInitializers = new LinkedHashMap<>();
    int fieldAccessLevel = Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC;
    if (finalFields) {
      fieldAccessLevel |= Opcodes.ACC_FINAL;
    }
    for (Map.Entry<String, FieldInitializer> entry : initializers.entrySet()) {
      FieldInitializer init = entry.getValue();
      JavaIdentifierValidator.validate(
          entry.getKey(), "in class:", fullyQualifiedInnerClass, "and package:", packageDir);
      if (init.writeFieldDefinition(
          entry.getKey(), innerClassWriter, fieldAccessLevel, finalFields)) {
        deferredInitializers.put(entry.getKey(), init);
      }
    }
    if (!deferredInitializers.isEmpty()) {
      writeStaticClassInit(innerClassWriter, fullyQualifiedInnerClass, deferredInitializers);
    }

    innerClassWriter.visitEnd();
    Path innerFile = packageDir.resolve("R$" + innerClass + ".class");
    Files.write(innerFile, innerClassWriter.toByteArray(), CREATE_NEW);
  }

  private String writeInnerClassHeader(
      String fullyQualifiedOuterClass, String innerClass, ClassWriter innerClassWriter) {
    String fullyQualifiedInnerClass = fullyQualifiedOuterClass + "$" + innerClass;
    innerClassWriter.visit(
        JAVA_VERSION,
        Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER,
        fullyQualifiedInnerClass,
        null, /* signature */
        SUPER_CLASS,
        null /* interfaces */);
    innerClassWriter.visitSource(SdkConstants.FN_RESOURCE_CLASS, null);
    writeConstructor(innerClassWriter);
    innerClassWriter.visitInnerClass(
        fullyQualifiedInnerClass,
        fullyQualifiedOuterClass,
        innerClass,
        Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_STATIC);
    return fullyQualifiedInnerClass;
  }

  private static void writeConstructor(ClassWriter classWriter) {
    MethodVisitor constructor =
        classWriter.visitMethod(
            Opcodes.ACC_PUBLIC, "<init>", "()V", null, /* signature */ null /* exceptions */);
    constructor.visitCode();
    constructor.visitVarInsn(Opcodes.ALOAD, 0);
    constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, SUPER_CLASS, "<init>", "()V", false);
    constructor.visitInsn(Opcodes.RETURN);
    constructor.visitMaxs(1, 1);
    constructor.visitEnd();
  }

  private static void writeStaticClassInit(
      ClassWriter classWriter,
      String className,
      Map<String, FieldInitializer> deferredInitializers) {
    MethodVisitor visitor =
        classWriter.visitMethod(
            Opcodes.ACC_STATIC, "<clinit>", "()V", null, /* signature */ null /* exceptions */);
    visitor.visitCode();
    int stackSlotsNeeded = 0;
    InstructionAdapter insts = new InstructionAdapter(visitor);
    for (Map.Entry<String, FieldInitializer> fieldEntry : deferredInitializers.entrySet()) {
      final FieldInitializer fieldInit = fieldEntry.getValue();
      stackSlotsNeeded =
          Math.max(stackSlotsNeeded, fieldInit.writeCLInit(fieldEntry.getKey(), insts, className));
    }
    insts.areturn(Type.VOID_TYPE);
    visitor.visitMaxs(stackSlotsNeeded, 0);
    visitor.visitEnd();
  }
}
