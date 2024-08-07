package gov.nasa.ammos.aerie.procedural.processor;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;
import com.squareup.javapoet.WildcardTypeName;
import gov.nasa.jpl.aerie.merlin.framework.ValueMapper;
import gov.nasa.ammos.aerie.procedural.processor.TypePattern.ClassPattern;

import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.PrimitiveType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class Resolver {
  private final Types typeUtils;
  private final Elements elementUtils;
  private final List<TypeRule> typeRules;

  public Resolver(final Types typeUtils, final Elements elementUtils, final List<TypeRule> typeRules) {
    this.typeUtils = Objects.requireNonNull(typeUtils);
    this.elementUtils = Objects.requireNonNull(elementUtils);
    this.typeRules = Objects.requireNonNull(typeRules);
  }

  public Optional<CodeBlock> instantiateMapperFor(final TypeMirror mirror) {
    final var mapperCode = applyRules(createInitialGoal(mirror));

    // TODO: Do away with this null-checking stuff, somehow.
    if (mirror.getKind() == TypeKind.DECLARED || mirror.getKind() == TypeKind.ARRAY) {
      return mapperCode.map($ -> CodeBlock.of("$L", $));
    } else {
      return mapperCode;
    }
  }

  public Optional<CodeBlock> instantiateNullableMapperFor(final TypeMirror mirror) {
    final var mapperCode = applyRules(createInitialGoal(mirror));

    // TODO: Do away with this null-checking stuff, somehow.
    if (mirror.getKind() == TypeKind.DECLARED || mirror.getKind() == TypeKind.ARRAY) {
      return mapperCode.map($ -> CodeBlock.of(
          "new $T<>(\n$>$>$L$<$<)",
          gov.nasa.jpl.aerie.contrib.serialization.mappers.NullableValueMapper.class,
          $));
    } else {
      return mapperCode;
    }
  }

  private TypePattern createInitialGoal(final TypeMirror mirror) {
    final List<TypePattern> mapperArguments;
    if (mirror.getKind().isPrimitive()) {
      var typePattern = TypePattern.from(typeUtils.boxedClass((PrimitiveType) mirror).asType());
      final var annotations = new LinkedList<>(mirror.getAnnotationMirrors());
      while (!annotations.isEmpty()) {
        final AnnotationMirror annotation = annotations.removeLast();
        final var className = ClassName.get((TypeElement) annotation.getAnnotationType().asElement());
        typePattern = new TypePattern.AnnotationPattern(className, Optional.of(annotation), typePattern);
      }
      mapperArguments = List.of(typePattern);
    } else {
      mapperArguments = List.of(TypePattern.from(mirror));
    }

    return new ClassPattern(ClassName.get(ValueMapper.class), mapperArguments);
  }

  public Optional<CodeBlock> applyRules(final TypePattern goal) {
    if (goal instanceof ClassPattern && ((ClassPattern)goal).name().equals(ClassName.get(Class.class))) {
      final var pattern = ((ClassPattern)goal).arguments().get(0);
      return Optional.of(
          (pattern.render().equals(pattern.erasure()))
              ? CodeBlock.of("$T.class", pattern.erasure())
              : CodeBlock.of("(Class<$T>) (Object) $T.class", pattern.render(), pattern.erasure()));
    } else {
      for (final var rule : this.typeRules) {
        final var codeBlock = this.applyRule(rule, goal);
        if (codeBlock.isPresent()) return codeBlock;
      }
    }
    return Optional.empty();
  }

  private Optional<CodeBlock> applyRule(final TypeRule rule, final TypePattern goal) {
    // Extract type bindings for the type parameters of the rule
    final Map<String, TypePattern> typeMapping;
    try {
      typeMapping = rule.head().match(goal);
    } catch (TypePattern.UnificationException e) {
      return Optional.empty();
    }

    // Ensure the match satisfies the rule's type parameter bounds
    for (final var name : rule.enumBoundedTypeParameters()) {
      final var pattern = typeMapping.get(name);
      if (pattern == null) return Optional.empty();
      if (!(pattern instanceof ClassPattern)) return Optional.empty();

      // Only enum bounds are supported, but could expand at some point.
      // Supporting value mapper resolvers for types like:
      // - `List<? extends Foo>` or
      // - `List<? extends Map<? super Foo, ? extends Bar>>`
      // is not straightforward.
      final var patternType = elementUtils.getTypeElement(pattern.erasure().toString()).asType();
      final var enumType = typeUtils.erasure(elementUtils.getTypeElement("java.lang.Enum").asType());
      if (!typeUtils.isSubtype(patternType, enumType)) return Optional.empty();
    }

    final var annotationValues = new HashMap<ClassName, AnnotationMirror>();
    for (final var annotation : collectAnnotationParameters(goal)) {
      final var className = ClassName.get((TypeElement) annotation.getAnnotationType().asElement());
      annotationValues.put(className, annotation);
    }

    // Satisfy the subgoals of the rule
    final var dependencies = new ArrayList<CodeBlock>(rule.parameters().size());
    if (rule.name().isPresent()) {
      dependencies.add(CodeBlock.of("$S", rule.name().get()));
    }
    for (final var parameter : rule.parameters()) {
      if (parameter instanceof ClassPattern p && annotationValues.containsKey(p.name())) {
        dependencies.add(CodeBlock.of("$L", generateAnnotationInstance(annotationValues.get(p.name()))));
      } else {
        final var codeBlock = applyRules(parameter.substitute(typeMapping));
        if (codeBlock.isEmpty()) return Optional.empty();
        dependencies.add(codeBlock.get());
      }
    }

    // Build a codeblock satisfying the goal of this rule
    final var builder = CodeBlock.builder();
    builder.add("$T.$L(", rule.factory(), rule.method());
    if (dependencies.size() > 0) {
      final var iter = dependencies.iterator();
      builder.add("\n$>$>$L$<$<", iter.next());
      while (iter.hasNext()) {
        builder.add(",\n$>$>$L$<$<", iter.next());
      }
    }
    builder.add(")");
    return Optional.of(builder.build());
  }

  List<? extends AnnotationMirror> collectAnnotationParameters(TypePattern pattern) {
    final var result = new ArrayList<AnnotationMirror>();
    if (pattern instanceof TypePattern.AnnotationPattern p) {
      result.add(p.payload().get());
      result.addAll(collectAnnotationParameters(p.target()));
    } else if (pattern instanceof TypePattern.PrimitivePattern p) {
      // Do nothing
    } else if (pattern instanceof TypePattern.ClassPattern p) {
      for (final var argument : p.arguments()) {
        result.addAll(collectAnnotationParameters(argument));
      }
    } else if (pattern instanceof TypePattern.ArrayPattern p) {
      result.addAll(collectAnnotationParameters(p.element()));
    } else if (pattern instanceof TypePattern.TypeVariablePattern p) {
      // Do nothing
    } else {
      throw new Error("unhandled subtype of " + TypePattern.class.getCanonicalName() + ": " + pattern);
    }
    return result;
  }

  private TypeSpec generateAnnotationInstance(final AnnotationMirror annotation) {
    final var annotationType = annotation.getAnnotationType();
    final var builder = TypeSpec
        .anonymousClassBuilder("")
        .superclass(annotationType)
        .addMethod(
            MethodSpec
                .methodBuilder("annotationType")
                .addModifiers(Modifier.PUBLIC)
                .addAnnotation(Override.class)
                .returns(ParameterizedTypeName.get(
                    ClassName.get(Class.class),
                    WildcardTypeName.subtypeOf(TypeName.get(annotationType))))
                .addStatement(CodeBlock.of("return $T.class", annotationType))
                .build());
    for (final var enclosedElement : annotation.getAnnotationType().asElement().getEnclosedElements()) {
      if (!(enclosedElement instanceof ExecutableElement el)) continue;
      final String methodName = el.getSimpleName().toString();
      builder
          .addMethod(
              MethodSpec
                  .methodBuilder(methodName)
                  .addModifiers(Modifier.PUBLIC)
                  .addAnnotation(Override.class)
                  .returns(TypeName.get(el.getReturnType()))
                  .addStatement(CodeBlock.of("return $L",
                                             getAnnotationAttribute(annotation, methodName)
                                                 .orElse(el.getDefaultValue())
                                                 .toString()))
                  .build());
    }
    return builder.build();
  }

  private static Optional<AnnotationValue> getAnnotationAttribute(final AnnotationMirror annotationMirror, final String attributeName)
  {
    for (final var entry : annotationMirror.getElementValues().entrySet()) {
      if (Objects.equals(attributeName, entry.getKey().getSimpleName().toString())) {
        return Optional.of(entry.getValue());
      }
    }

    return Optional.empty();
  }
}
