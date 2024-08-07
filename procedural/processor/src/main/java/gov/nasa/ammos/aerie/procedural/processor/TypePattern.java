package gov.nasa.ammos.aerie.procedural.processor;

import com.squareup.javapoet.ArrayTypeName;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeVariableName;

import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public sealed interface TypePattern {
  static TypePattern from(final TypeMirror mirror) {
    TypePattern result;
    switch (mirror.getKind()) {
      case BOOLEAN -> result = new PrimitivePattern(Primitive.BOOLEAN);
      case BYTE -> result = new PrimitivePattern(Primitive.BYTE);
      case SHORT -> result = new PrimitivePattern(Primitive.SHORT);
      case INT -> result = new PrimitivePattern(Primitive.INT);
      case LONG -> result = new PrimitivePattern(Primitive.LONG);
      case CHAR -> result = new PrimitivePattern(Primitive.CHAR);
      case FLOAT -> result = new PrimitivePattern(Primitive.FLOAT);
      case DOUBLE -> result = new PrimitivePattern(Primitive.DOUBLE);
      case ARRAY -> result = new ArrayPattern(TypePattern.from(((ArrayType) mirror).getComponentType()));
      case TYPEVAR -> {
        var typeVarName = mirror.toString();
        if (typeVarName.contains(" ")) {
          typeVarName = typeVarName.substring(typeVarName.lastIndexOf(' ') + 1);
        }
        result = new TypeVariablePattern(typeVarName);
      }
      case DECLARED -> {
        // DeclaredType element can be cast as TypeElement because it's a Type
        final var className = ClassName.get((TypeElement) ((DeclaredType) mirror).asElement());
        final var typeArguments = ((DeclaredType) mirror).getTypeArguments();
        final var argumentPatterns = new ArrayList<TypePattern>(typeArguments.size());
        for (final var typeArgument : typeArguments) {
          argumentPatterns.add(TypePattern.from(typeArgument));
        }
        result = new ClassPattern(className, argumentPatterns);
      }

      default -> throw new Error("Cannot construct a pattern for type " + mirror);
    }

    final var annotations = mirror.getAnnotationMirrors();
    for (int i = annotations.size() - 1; i >= 0; i--) {
      final var annotation = annotations.get(i);
      final var className = ClassName.get((TypeElement) annotation.getAnnotationType().asElement());
      result = new AnnotationPattern(className, Optional.of(annotation), result);
    }
    return result;
  }

  static TypePattern from(final VariableElement element) {
    return from(element.asType());
  }

  /**
   * Given a TypePattern, produce a new TypePattern where all type variables whose names are
   * in the given map have been replaced with the corresponding type patterns.
   * @param substitution A map from type variable name to the type pattern with which to replace it
   * @return a TypePattern with the same structure, but with any matching type variables replaced
   * E.g. ClassPattern(List, TypeVariablePattern(T)).substitute(Map.of("T", ClassPattern(Double))) will return
   *   ClassPattern(List, Double)
   */
  TypePattern substitute(Map<String, TypePattern> substitution);

  /**
   * Attempt to match this TypePattern against the given other TypePattern. Throw a UnificationException if they cannot be matched
   * @param other the ground TypePattern to match against this TypePattern
   * @return an assignment of type variables to TypePatterns, if match is successful
   * @throws UnificationException if the two patterns do not match
   */
  Map<String, TypePattern> match(TypePattern other) throws UnificationException;

  /**
   * Whether this TypePattern contains any type variables. Ground is true if there are no type variables present.
   * @return false if there is at least one type variable in this TypePattern. true if there are none.
   */
  boolean isGround();

  /**
   * Whether this TypePattern has the same structure, same types, and same type variable names as the other pattern
   * @param other the other TypePattern to compare with
   * @return true if the two TypePatterns are identical, false otherwise.
   */
  boolean isSyntacticallyEqualTo(TypePattern other);

  /**
   * Represent this TypePattern as a javapoet TypeName
   * @return a javapoet TypeName corresponding to this TypePattern
   *
   * E.g. ClassPattern(List, Double).render() --> ClassName(List, Double)
   */
  TypeName render();

  /**
   * Represent this TypePattern as a javapoet TypeName without generics
   * @return a javapoet TypeName corresponding to this TypePattern without generics
   *
   * E.g. ClassPattern(List, Double).erasure() --> ClassName(List)
   */
  TypeName erasure();

  /**
   * If this TypePattern represents a primitive, return a TypePattern that represents the corresponding Object type.
   * @return a TypePattern that is not a PrimitiveTypePattern
   *
   * E.g. PrimitivePattern(boolean) --> ClassPattern(Boolean)
   *
   * There is no need to recurse: if the top level is not a PrimitiveTypePattern,
   * the entire type pattern is already considered "boxed"
   */
  TypePattern box();

  class UnificationException extends Exception {}

  record TypeVariablePattern(String name) implements TypePattern {
    @Override
    public TypePattern substitute(final Map<String, TypePattern> substitution) {
      return substitution.getOrDefault(this.name, this);
    }

    @Override
    public Map<String, TypePattern> match(final TypePattern other) {
      assert other.isGround();

      return Map.of(this.name, other);
    }

    @Override
    public boolean isGround() {
      return false;
    }

    @Override
    public boolean isSyntacticallyEqualTo(final TypePattern o) {
      if (!(o instanceof TypeVariablePattern)) return false;
      final var other = (TypeVariablePattern) o;

      return this.name.equals(other.name);
    }

    @Override
    public TypeName render() {
      return TypeVariableName.get(this.name);
    }

    @Override
    public TypeName erasure() {
      return this.render();
    }

    @Override
    public TypePattern box() {
      return this;
    }
  }

  record PrimitivePattern(Primitive primitive) implements TypePattern {
    @Override
    public PrimitivePattern substitute(final Map<String, TypePattern> substitution) {
      return this;
    }

    @Override
    public Map<String, TypePattern> match(final TypePattern other) throws UnificationException {
      if (!this.isSyntacticallyEqualTo(other)) throw new UnificationException();

      return Map.of();
    }

    @Override
    public boolean isGround() {
      return true;
    }

    @Override
    public boolean isSyntacticallyEqualTo(final TypePattern o) {
      if (!(o instanceof PrimitivePattern)) return false;
      final var other = (PrimitivePattern) o;

      return this.primitive.equals(other.primitive);
    }

    @Override
    public TypeName render() {
      return this.primitive.toTypeName();
    }

    @Override
    public TypeName erasure() {
      return this.render();
    }

    @Override
    public TypePattern box() {
      return new ClassPattern((ClassName) this.render().box(), List.of());
    }
  }

  enum Primitive {
    BOOLEAN(TypeName.BOOLEAN),
    BYTE(TypeName.BYTE),
    SHORT(TypeName.SHORT),
    INT(TypeName.INT),
    LONG(TypeName.LONG),
    CHAR(TypeName.CHAR),
    FLOAT(TypeName.FLOAT),
    DOUBLE(TypeName.DOUBLE);

    private final TypeName repr;

    Primitive(final TypeName repr) {
      this.repr = repr;
    }

    public TypeName toTypeName() {
      return this.repr;
    }
  }

  record ArrayPattern(TypePattern element) implements TypePattern {
    @Override
    public ArrayPattern substitute(final Map<String, TypePattern> substitution) {
      final var child = this.element.substitute(substitution);
      if (child == this.element) return this;

      return new ArrayPattern(child);
    }

    @Override
    public Map<String, TypePattern> match(final TypePattern o) throws UnificationException {
      if (!(o instanceof ArrayPattern)) throw new UnificationException();
      final var other = (ArrayPattern) o;

      return this.element.match(other.element);
    }

    @Override
    public boolean isGround() {
      return this.element.isGround();
    }

    @Override
    public boolean isSyntacticallyEqualTo(final TypePattern o) {
      if (!(o instanceof ArrayPattern)) return false;
      final var other = (ArrayPattern) o;

      return this.element.isSyntacticallyEqualTo(other.element);
    }

    @Override
    public TypeName render() {
      return ArrayTypeName.of(this.element.render());
    }

    @Override
    public TypeName erasure() {
      return ArrayTypeName.of(this.element.erasure());
    }

    @Override
    public TypePattern box() {
      return this;
    }
  }

  record ClassPattern(ClassName name, List<TypePattern> arguments) implements TypePattern {
    @Override
    public ClassPattern substitute(final Map<String, TypePattern> substitution) {
      final var substitutedArguments = new ArrayList<TypePattern>(this.arguments.size());
      for (final var argument : this.arguments) {
        substitutedArguments.add(argument.substitute(substitution));
      }

      // The `allUnchanged` block can be removed without impacting correctness,
      //   but it exists to avoid needless extra allocations whenever possible.
      var allUnchanged = true;
      for (var i = 0; i < this.arguments.size(); i += 1) {
        if (this.arguments.get(i) != substitutedArguments.get(i)) {
          allUnchanged = false;
          break;
        }
      }
      if (allUnchanged) return this;

      return new ClassPattern(this.name, substitutedArguments);
    }

    @Override
    public Map<String, TypePattern> match(final TypePattern o) throws UnificationException {
      if (!(o instanceof ClassPattern)) throw new UnificationException();
      final var other = (ClassPattern) o;
      if (!this.name.equals(other.name)) throw new UnificationException();
      if (this.arguments.size() != other.arguments.size()) throw new UnificationException();

      // This specialization by arity can be removed without impacting correctness,
      //   but it exists to avoid needless extra work whenever possible.
      if (this.arguments.size() == 0) {
        return Map.of();
      } else if (this.arguments.size() == 1) {
        return this.arguments.get(0).match(other.arguments.get(0));
      }

      final var collectedSubstitution = new HashMap<String, TypePattern>();
      for (var i = 0; i < this.arguments.size(); i += 1) {
        final var substitution = this.arguments.get(i).match(other.arguments.get(i));

        for (final var entry : substitution.entrySet()) {
          if (!collectedSubstitution.containsKey(entry.getKey())) {
            collectedSubstitution.put(entry.getKey(), entry.getValue());
            continue;
          }

          if (collectedSubstitution.get(entry.getKey()).isSyntacticallyEqualTo(entry.getValue())) {
            continue;
          }

          throw new UnificationException();
        }
      }

      return collectedSubstitution;
    }

    @Override
    public boolean isGround() {
      return this.arguments.stream().allMatch(TypePattern::isGround);
    }

    @Override
    public boolean isSyntacticallyEqualTo(final TypePattern o) {
      if (!(o instanceof ClassPattern)) return false;
      final var other = (ClassPattern) o;

      if (this.arguments.size() != other.arguments.size()) return false;

      for (var i = 0; i < this.arguments.size(); i += 1) {
        if (!this.arguments.get(i).isSyntacticallyEqualTo(other.arguments.get(i))) {
          return false;
        }
      }

      return true;
    }

    @Override
    public TypeName render() {
      if (this.arguments.size() == 0) return this.name;

      final var argumentTypeNames = new TypeName[this.arguments.size()];
      for (var i = 0; i < argumentTypeNames.length; i += 1) {
        argumentTypeNames[i] = this.arguments.get(i).render().box();
      }

      return ParameterizedTypeName.get(this.name, argumentTypeNames);
    }

    @Override
    public TypeName erasure() {
      return this.name;
    }

    @Override
    public TypePattern box() {
      return this;
    }
  }

  record AnnotationPattern(ClassName annotationClassName, Optional<AnnotationMirror> payload, TypePattern target) implements TypePattern {
    @Override
    public AnnotationPattern substitute(final Map<String, TypePattern> substitution) {
      return new AnnotationPattern(this.annotationClassName, this.payload, target.substitute(substitution));
    }

    @Override
    public Map<String, TypePattern> match(final TypePattern o) throws UnificationException {
      if (!(o instanceof final AnnotationPattern other)) throw new UnificationException();
      if (!this.annotationClassName.equals(other.annotationClassName)) throw new UnificationException();
      return this.target.match(other.target);
    }

    @Override
    public boolean isGround() {
      return this.target.isGround();
    }

    @Override
    public boolean isSyntacticallyEqualTo(final TypePattern o) {
      if (!(o instanceof final AnnotationPattern other)) return false;
      if (!this.annotationClassName.equals(other.annotationClassName)) return false;
      return this.target.isSyntacticallyEqualTo(other.target);
    }

    @Override
    public TypeName render() {
      return this.target.render();
    }

    @Override
    public TypeName erasure() {
      return this.render();
    }

    @Override
    public TypePattern box() {
      return new AnnotationPattern(this.annotationClassName, this.payload, this.target.box());
    }
  }
}
