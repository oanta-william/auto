/*
 * Copyright (C) 2016 Google, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.auto.value.processor;

import com.google.auto.common.MoreElements;
import com.google.auto.common.MoreTypes;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableBiMap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.Name;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;

/**
 * Classifies methods inside builder types that return builders for properties. For example,
 * if {@code @AutoValue} class Foo has a method {@code ImmutableList<String> bar()} then
 * Foo.Builder can have a method {@code ImmutableList.Builder<String> barBuilder()}. This class
 * checks that a method like {@code barBuilder()} follows the rules, and if so constructs a
 * {@link PropertyBuilder} instance with information about {@code barBuilder}.
 *
 * @author Éamonn McManus
 */
class PropertyBuilderClassifier {
  private final ErrorReporter errorReporter;
  private final Types typeUtils;
  private final Elements elementUtils;
  private final BuilderMethodClassifier builderMethodClassifier;
  private final ImmutableBiMap<ExecutableElement, String> getterToPropertyName;
  private final TypeSimplifier typeSimplifier;
  private final EclipseHack eclipseHack;

  PropertyBuilderClassifier(
      ErrorReporter errorReporter,
      Types typeUtils,
      Elements elementUtils,
      BuilderMethodClassifier builderMethodClassifier,
      ImmutableBiMap<ExecutableElement, String> getterToPropertyName,
      TypeSimplifier typeSimplifier,
      EclipseHack eclipseHack) {
    this.errorReporter = errorReporter;
    this.typeUtils = typeUtils;
    this.elementUtils = elementUtils;
    this.builderMethodClassifier = builderMethodClassifier;
    this.getterToPropertyName = getterToPropertyName;
    ImmutableMap.Builder<String, ExecutableElement> getterToPropertyNameBuilder =
        ImmutableMap.builder();
    for (ExecutableElement getter : getterToPropertyName.keySet()) {
      getterToPropertyNameBuilder.put(getter.getSimpleName().toString(), getter);
    }
    this.typeSimplifier = typeSimplifier;
    this.eclipseHack = eclipseHack;
  }

  /**
   * Information about a property builder, referenced from the autovalue.vm template. A property
   * called bar (defined by a method bar() or getBar()) can have a property builder called
   * barBuilder(). For example, if {@code bar()} returns {@code ImmutableSet<String>} then
   * {@code barBuilder()} might return {@code ImmutableSet.Builder<String>}.
   */
  public static class PropertyBuilder {
    private final String name;
    private final String builderType;
    private final String initializer;
    private final String initEmpty;
    private final String builtToBuilder;
    private final String copyAll;

    PropertyBuilder(
        ExecutableElement propertyBuilderMethod,
        String builderType,
        String initializer,
        String initEmpty,
        String builtToBuilder,
        String copyAll) {
      this.name = propertyBuilderMethod.getSimpleName() + "$";
      this.builderType = builderType;
      this.initializer = initializer;
      this.initEmpty = initEmpty;
      this.builtToBuilder = builtToBuilder;
      this.copyAll = copyAll;
    }

    /** The name of the field to hold this builder. */
    public String getName() {
      return name;
    }

    /** The type of the builder, for example {@code ImmutableSet.Builder<String>}. */
    public String getBuilderType() {
      return builderType;
    }

    /** An initializer for the builder field, for example {@code ImmutableSet.builder()}. */
    public String getInitializer() {
      return initializer;
    }

    /**
     * A method to return an empty collection of the type that this builder builds. For example,
     * if this is an {@code ImmutableList<String>} then the method {@code ImmutableList.of()} will
     * correctly return an empty {@code ImmutableList<String>}, assuming the appropriate context for
     * type inference.
     */
    public String getInitEmpty() {
      return initEmpty;
    }

    /**
     * A method to convert the built type back into a builder. Unfortunately Guava collections
     * don't have this (you can't say {@code myImmutableMap.toBuilder()}), but for other types
     * such as {@code @AutoValue} types this is {@code toBuilder()}.
     */
    public String getBuiltToBuilder() {
      return builtToBuilder;
    }

    /**
     * The method to copy another collection into this builder. It is {@code addAll} for
     * one-dimensional collections like {@code ImmutableList} and {@code ImmutableSet}, and it is
     * {@code putAll} for two-dimensional collections like {@code ImmutableMap} and
     * {@code ImmutableTable}.
     */
    public String getCopyAll() {
      return copyAll;
    }
  }

  // Construct this string so it won't be found by Maven shading and renamed, which is not what
  // we want.
  private static final String COM_GOOGLE_COMMON_COLLECT_IMMUTABLE =
      "com".concat(".google.common.collect.Immutable");

  // Our @AutoValue class `Foo` has a property `Bar bar()` or `Bar getBar()` and we've encountered
  // a builder method like `BarBuilder barBuilder()`. Here `BarBuilder` can have any name (its name
  // doesn't have to be the name of `Bar` with `Builder` stuck on the end), but `barBuilder()` does
  // have to be the name of the property with `Builder` stuck on the end. The requirements for the
  // `BarBuilder` type are:
  // (1) It must have an instance method called `build()` that returns `Bar`. If the type of
  //     `bar()` is `Bar<String>` then the type of `build()` must be `Bar<String>`.
  // (2) `BarBuilder` must have a public no-arg constructor, or `Bar` must have a static method
  //     `builder()` or `newBuilder()` that returns `BarBuilder`.
  // (3) `Bar` must have an instance method `BarBuilder toBuilder()`, or `BarBuilder` must be a
  //      Guava immutable builder like `ImmutableSet.Builder`. (See TODO below for relaxing the
  //      requirement on having a `toBuilder()`.
  //
  // This method outputs an error and returns Optional.absent() if the barBuilder() method has a
  // problem.
  Optional<PropertyBuilder> makePropertyBuilder(ExecutableElement method, String property) {
    TypeMirror barBuilderTypeMirror = builderMethodClassifier.builderMethodReturnType(method);
    if (barBuilderTypeMirror.getKind() != TypeKind.DECLARED) {
      errorReporter.reportError("Method looks like a property builder, but its return type "
          + "is not a class or interface", method);
      return Optional.absent();
    }
    DeclaredType barBuilderDeclaredType = MoreTypes.asDeclared(barBuilderTypeMirror);
    TypeElement barBuilderTypeElement = MoreTypes.asTypeElement(barBuilderTypeMirror);
    Map<String, ExecutableElement> barBuilderNoArgMethods = noArgMethodsOf(barBuilderTypeElement);

    TypeMirror barTypeMirror = getterToPropertyName.inverse().get(property).getReturnType();
    if (barTypeMirror.getKind() != TypeKind.DECLARED) {
      errorReporter.reportError("Method looks like a property builder, but the type of property "
          + property + " is not a class or interface", method);
      return Optional.absent();
    }
    TypeElement barTypeElement = MoreTypes.asTypeElement(barTypeMirror);
    Map<String, ExecutableElement> barNoArgMethods = noArgMethodsOf(barTypeElement);

    // Condition (1), must have build() method returning Bar.
    ExecutableElement build = barBuilderNoArgMethods.get("build");
    if (build == null || build.getModifiers().contains(Modifier.STATIC)) {
      errorReporter.reportError("Method looks like a property builder, but it returns "
          + barBuilderTypeElement + " which does not have a non-static build() method", method);
      return Optional.absent();
    }

    // We've determined that `BarBuilder` has a method `build()`. But it must return `Bar`.
    // And if the type of `bar()` is Bar<String> then `BarBuilder.build()` must return Bar<String>.
    TypeMirror buildType = eclipseHack.methodReturnType(build, barBuilderDeclaredType);
    if (!MoreTypes.equivalence().equivalent(barTypeMirror, buildType)) {
      errorReporter.reportError("Property builder for " + property + " has type "
          + barBuilderTypeElement + " whose build() method returns " + buildType
          + " instead of " + barTypeMirror, method);
      return Optional.absent();
    }

    Optional<ExecutableElement> maybeBuilderMaker =
        builderMaker(barNoArgMethods, barBuilderTypeElement);
    if (!maybeBuilderMaker.isPresent()) {
      errorReporter.reportError("Method looks like a property builder, but its type "
          + barBuilderTypeElement + " does not have a public constructor and " + barTypeElement
          + " does not have a static builder() or newBuilder() method that returns "
          + barBuilderTypeElement, method);
      return Optional.absent();
    }
    ExecutableElement builderMaker = maybeBuilderMaker.get();

    String barBuilderType = typeSimplifier.simplify(barBuilderTypeMirror);
    String barType = typeSimplifier.simplifyRaw(barTypeMirror);
    String initializer = (builderMaker.getKind() == ElementKind.CONSTRUCTOR)
        ? "new " + barBuilderType + "()"
        : barType + "." + builderMaker.getSimpleName() + "()";
    String builtToBuilder = null;
    String copyAll = null;
    ExecutableElement toBuilder = barNoArgMethods.get("toBuilder");
    if (toBuilder != null && !toBuilder.getModifiers().contains(Modifier.STATIC)
        && typeUtils.isAssignable(
            typeUtils.erasure(toBuilder.getReturnType()),
            typeUtils.erasure(barBuilderTypeMirror))) {
      builtToBuilder = toBuilder.getSimpleName().toString();
    } else {
      boolean isGuavaBuilder =
          barBuilderTypeMirror.toString().startsWith(COM_GOOGLE_COMMON_COLLECT_IMMUTABLE)
          && barBuilderType.contains(".Builder<");
      Optional<ExecutableElement> maybeCopyAll = addAllPutAll(barBuilderTypeElement);
      if (maybeCopyAll.isPresent() && isGuavaBuilder) {
        copyAll = maybeCopyAll.get().getSimpleName().toString();
      } else {
        // TODO(emcmanus): relax the condition here by not requiring that there be a way to make
        // BarBuilder from Bar. That is needed if the containing @AutoValue class Foo has a
        // toBuilder() method, or if there is also a setter for bar. Unfortunately we emit a
        // second package-private constructor AutoValue_Foo.Builder(Foo) which can be used instead
        // of toBuilder(). That's not documented, and we should stop doing it, because we can't
        // know if anyone calls the constructor and therefore we have to assume that we'll need
        // to make BarBuilder from Bar.
        errorReporter.reportError("Property builder method returns " + barBuilderTypeMirror
            + " but there is no way to make that type from " + barTypeMirror + ": "
            + barTypeMirror + " does not have a non-static toBuilder() method that returns "
            + barBuilderTypeMirror, method);
        return Optional.absent();
      }
    }
    ExecutableElement barOf = barNoArgMethods.get("of");
    boolean hasOf = (barOf != null && barOf.getModifiers().contains(Modifier.STATIC));
    String initEmpty = hasOf ? barType + ".of()" : null;

    PropertyBuilder propertyBuilder = new PropertyBuilder(
        method, barBuilderType, initializer, initEmpty, builtToBuilder, copyAll);
    return Optional.of(propertyBuilder);
  }

  private static final ImmutableSet<String> BUILDER_METHOD_NAMES =
      ImmutableSet.of("builder", "newBuilder");

  // (2) `BarBuilder must have a public no-arg constructor, or `Bar` must have a visible static
  //      method `builder()` or `newBuilder()` that returns `BarBuilder`.
  private Optional<ExecutableElement> builderMaker(
      Map<String, ExecutableElement> barNoArgMethods, TypeElement barBuilderTypeElement) {
    for (String builderMethodName : BUILDER_METHOD_NAMES) {
      ExecutableElement method = barNoArgMethods.get(builderMethodName);
      if (method != null
          && method.getModifiers().contains(Modifier.STATIC)
          && typeUtils.isSameType(
              typeUtils.erasure(method.getReturnType()),
              typeUtils.erasure(barBuilderTypeElement.asType()))) {
        // TODO(emcmanus): check visibility. We don't want to require public for @AutoValue
        // builders. By not checking visibility we risk accepting something as a builder maker
        // and then failing when the generated code tries to call Bar.builder(). But the risk
        // seems small.
        return Optional.of(method);
      }
    }
    for (ExecutableElement constructor :
        ElementFilter.constructorsIn(barBuilderTypeElement.getEnclosedElements())) {
      if (constructor.getParameters().isEmpty()
          && constructor.getModifiers().contains(Modifier.PUBLIC)) {
        return Optional.of(constructor);
      }
    }
    return Optional.absent();
  }

  private Map<String, ExecutableElement> noArgMethodsOf(TypeElement type) {
    // Can't easily use ImmutableMap here because getAllMembers could return more than one method
    // with the same name.
    Map<String, ExecutableElement> methods = new LinkedHashMap<String, ExecutableElement>();
    for (ExecutableElement method : ElementFilter.methodsIn(elementUtils.getAllMembers(type))) {
      if (method.getParameters().isEmpty()) {
        methods.put(method.getSimpleName().toString(), method);
      }
    }
    return methods;
  }

  private Optional<ExecutableElement> addAllPutAll(TypeElement barBuilderTypeElement) {
    for (ExecutableElement method :
        MoreElements.getLocalAndInheritedMethods(barBuilderTypeElement, typeUtils, elementUtils)) {
      Name name = method.getSimpleName();
      if (name.contentEquals("addAll") || name.contentEquals("putAll")) {
        return Optional.of(method);
      }
    }
    return Optional.absent();
  }
}
