package gov.nasa.jpl.aerie.merlin.processor;

import com.squareup.javapoet.AnnotationSpec;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterSpec;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;
import com.squareup.javapoet.TypeVariableName;
import gov.nasa.jpl.aerie.contrib.serialization.mappers.RecordValueMapper;
import gov.nasa.jpl.aerie.merlin.framework.Result;
import gov.nasa.jpl.aerie.merlin.framework.ValueMapper;
import gov.nasa.jpl.aerie.merlin.framework.annotations.AutoValueMapper;
import gov.nasa.jpl.aerie.merlin.processor.metamodel.MissionModelRecord;
import gov.nasa.jpl.aerie.merlin.processor.metamodel.TypeRule;
import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;
import gov.nasa.jpl.aerie.merlin.protocol.types.ValueSchema;

import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.Parameterizable;
import javax.lang.model.element.RecordComponentElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeMirror;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class AutoValueMappers {
  static TypeRule recordTypeRule(final Element autoValueMapperElement, final ClassName generatedClassName) throws InvalidMissionModelException {
    if (!autoValueMapperElement.getKind().equals(ElementKind.RECORD)) {
      throw new InvalidMissionModelException(
          "@%s.%s is only allowed on records".formatted(
              AutoValueMapper.class.getSimpleName(),
              AutoValueMapper.Record.class.getSimpleName()),
          autoValueMapperElement);
    }

    final var componentsAndMappers = getComponentsAndMappers(autoValueMapperElement);
    return new TypeRule(
        new TypePattern.ClassPattern(
            ClassName.get(ValueMapper.class),
            List.of(TypePattern.from(autoValueMapperElement.asType()))),
        Set.of(),
        componentsAndMappers.mappers()
            .stream()
            .map(mapper -> (TypePattern) new TypePattern.ClassPattern(
                ClassName.get(ValueMapper.class),
                List.of(mapper.typePattern().box())))
            .toList(),
        generatedClassName,
        ClassName.get((TypeElement) autoValueMapperElement).canonicalName().replace(".", "_"));
  }

  static TypeRule annotationTypeRule(final Element autoValueMapperElement, final ClassName generatedClassName) throws InvalidMissionModelException {
    if (!autoValueMapperElement.getKind().equals(ElementKind.ANNOTATION_TYPE)) {
      throw new InvalidMissionModelException(
          "@%s.%s is only allowed on annotations".formatted(
              AutoValueMapper.class.getSimpleName(),
              AutoValueMapper.Annotation.class.getSimpleName()),
          autoValueMapperElement);
    }

    final var typeMirrors = new HashSet<TypeMirror>();
    for (final var enclosedElement : autoValueMapperElement.getEnclosedElements()) {
      if (!(enclosedElement.getKind() == ElementKind.METHOD)) continue;
      typeMirrors.add(((ExecutableElement) enclosedElement).getReturnType());
    }

    return new TypeRule(
        new TypePattern.ClassPattern(
            ClassName.get(ValueMapper.class),
            List.of(TypePattern.from(autoValueMapperElement.asType()))),
        Set.of(),
        typeMirrors
            .stream()
            .map(component -> (TypePattern) new TypePattern.ClassPattern(
                ClassName.get(ValueMapper.class),
                List.of(TypePattern.from(component).box())))
            .toList(),
        generatedClassName,
        ClassName.get((TypeElement) autoValueMapperElement).canonicalName().replace(".", "_"));
  }

  static JavaFile generateAutoValueMappers(final MissionModelRecord missionModel, final Iterable<? extends Element> recordTypes, final Iterable<? extends Element> annotationTypes) {
    final var typeName = missionModel.getAutoValueMappersName();

    final var builder =
        TypeSpec
            .classBuilder(typeName)
            .addAnnotation(
                AnnotationSpec
                    .builder(javax.annotation.processing.Generated.class)
                    .addMember("value", "$S", MissionModelProcessor.class.getCanonicalName())
                    .build())
            .addAnnotation(
                AnnotationSpec
                    .builder(SuppressWarnings.class)
                    .addMember("value", "$S", "unchecked")
                    .build())
            .addModifiers(Modifier.PUBLIC, Modifier.FINAL);

    for (final var record : recordTypes) {
      final var methodName = ClassName.get((TypeElement) record).canonicalName().replace(".", "_");
      final var componentMappers = getComponentsAndMappers(record);

      builder.addMethod(
          MethodSpec
              .methodBuilder(methodName)
              .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
              .addTypeVariables(((TypeElement) record).getTypeParameters().stream().map(TypeVariableName::get).toList())
              .returns(ParameterizedTypeName.get(ClassName.get(ValueMapper.class), TypeName.get(record.asType())))
              .addParameters(
                  componentMappers
                      .mappers()
                      .stream()
                      .map(mapper -> ParameterSpec
                          .builder(
                              ParameterizedTypeName.get(
                                  ClassName.get(ValueMapper.class),
                                  mapper.typePattern().render().box()),
                              mapper.identifier(),
                              Modifier.FINAL)
                          .build())
                      .toList())
              .addCode(
                  CodeBlock
                      .builder()
                      .add("return new $T<>($>\n", ClassName.get(RecordValueMapper.class))
                      .add("$L$T.class,\n",
                           // SAFETY: This cast cannot fail - it merely exists to compensate for an inability
                           // to express a MyRecord<T>.class. Instead, we use MyRecord.class, and cast it, via
                           // Object, to a `Class<MyRecord<T>>`. We need to go via Object because Java generics
                           // do not allow casting a Foo<Bar> to a Foo<Bar<Baz>> - since Bar may be a container
                           // that already contains non-Baz objects. In this case, since MyRecord.class is not
                           // a container, the Java type checker is being overly conservative
                           castIfGeneric(record),
                           ClassName.get((TypeElement) record))
                      .add("$T.of($>\n", List.class)
                      .add(CodeBlock.join(
                          componentMappers.components()
                              .stream()
                              .map(recordComponent ->
                                       CodeBlock
                                           .builder()
                                           .add(
                                               "new $T<>($>\n" + "$S,\n" + "$T::$L,\n" + "$L" + "$<)",
                                               RecordValueMapper.Component.class,
                                               recordComponent.componentName(),
                                               ClassName.get((TypeElement) record),
                                               recordComponent.componentName(),
                                               recordComponent.mapperIdentifier())
                                           .build())
                              .toList(),
                          ",\n"))
                      .add("$<)$<);")
                      .build())
              .build());
    }

    record Property(String name, TypeMirror type, String mapperName) {}
    for (final var annotation : annotationTypes) {
      final var methodName = ClassName.get((TypeElement) annotation).canonicalName().replace(".", "_");
      final var properties = new ArrayList<Property>();
      final var necessaryMappers = new HashMap<TypeMirror, String>();
      for (final var element : annotation.getEnclosedElements()) {
        if (!(element.getKind() == ElementKind.METHOD)) continue;
        final var typeMirror = ((ExecutableElement) element).getReturnType();
        final var elementName = element.toString().replace("(", "").replace(")", "");
        final var valueMapperIdentifier = getIdentifier(typeMirror.toString()) + "ValueMapper";
        properties.add(new Property(elementName, typeMirror, valueMapperIdentifier));
        necessaryMappers.put(typeMirror, valueMapperIdentifier);
      }

      builder.addMethod(
          MethodSpec
              .methodBuilder(methodName)
              .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
              .returns(ParameterizedTypeName.get(ClassName.get(ValueMapper.class), TypeName.get(annotation.asType())))
              .addParameters(
                  necessaryMappers
                      .entrySet()
                      .stream()
                      .map(mapperRequest -> ParameterSpec
                          .builder(
                              ParameterizedTypeName.get(
                                  ClassName.get(ValueMapper.class),
                                  ClassName.get(mapperRequest.getKey()).box()),
                              mapperRequest.getValue(),
                              Modifier.FINAL)
                          .build())
                      .toList())
              .addCode(
                  CodeBlock
                      .builder()
                      .add("return $L;\n",
                           TypeSpec
                               .anonymousClassBuilder(CodeBlock.of(""))
                               .addSuperinterface(
                                   ParameterizedTypeName.get(ClassName.get(ValueMapper.class), TypeName.get(annotation.asType())))
                               .addMethod(
                                   MethodSpec
                                       .methodBuilder("getValueSchema")
                                       .addModifiers(Modifier.PUBLIC)
                                       .returns(ValueSchema.class)
                                       .addStatement("final var schema = new $T<$T, $T>()", HashMap.class, String.class, ValueSchema.class)
                                       .addCode(
                                           properties
                                               .stream()
                                               .map($ -> CodeBlock.builder().addStatement("schema.put($S, $L.getValueSchema())", $.name, $.mapperName))
                                               .reduce((x, y) -> x.add("$L", y.build()))
                                               .orElse(CodeBlock.builder())
                                               .build())
                                       .addStatement("return $T.ofStruct(schema)", ValueSchema.class)
                                       .build())
                               .addMethod(
                                   MethodSpec
                                       .methodBuilder("deserializeValue")
                                       .addModifiers(Modifier.PUBLIC)
                                       .addParameter(SerializedValue.class, "value")
                                       .returns(
                                           ParameterizedTypeName.get(
                                               ClassName.get(Result.class),
                                               TypeName.get(annotation.asType()),
                                               ClassName.get(String.class)))
                                       .addStatement(
                                           "return $T.success($L)",
                                           Result.class,
                                           TypeSpec
                                               .anonymousClassBuilder("")
                                               .addSuperinterface(annotation.asType())
                                               .addMethod(
                                                   MethodSpec
                                                       .methodBuilder("annotationType")
                                                       .addModifiers(Modifier.PUBLIC)
                                                       .addAnnotation(Override.class)
                                                       .returns(ParameterizedTypeName.get(
                                                           ClassName.get(Class.class),
                                                           TypeName.get(annotation.asType())))
                                                       .addStatement("return $T.class", annotation.asType())
                                                       .build())
                                               .addMethods(
                                                   properties
                                                       .stream()
                                                       .map($ ->
                                                         MethodSpec
                                                             .methodBuilder($.name)
                                                             .addModifiers(Modifier.PUBLIC)
                                                             .addAnnotation(Override.class)
                                                             .returns(TypeName.get($.type))
                                                             .addStatement(
                                                                 "return $L.deserializeValue($L.asMap().get().get($S)).getSuccessOrThrow()",
                                                                 $.mapperName,
                                                                 "value",
                                                                  $.name)
                                                             .build())
                                                       .toList())
                                               .build())
                                       .build())
                               .addMethod(
                                   MethodSpec
                                       .methodBuilder("serializeValue")
                                       .addModifiers(Modifier.PUBLIC)
                                       .addParameter(ClassName.get(annotation.asType()), "value")
                                       .returns(SerializedValue.class)
                                       .addStatement(
                                           "return $T.of($T.of($L))",
                                           SerializedValue.class,
                                           Map.class,
                                           String.join(
                                               ",",
                                               properties
                                                   .stream()
                                                   .map($ -> CodeBlock.of("$S, $L.serializeValue($L.$L())", $.name, $.mapperName, "value", $.name).toString())
                                                   .toList())
                                           )
                                       .build())
                               .build())
                      .build())
              .build());
    }

    return JavaFile
        .builder(typeName.packageName(), builder.build())
        .skipJavaLangImports(true)
        .build();
  }

  private static CodeBlock castIfGeneric(final Element record) {
    final var typeParameters = ((Parameterizable) record).getTypeParameters();
    if (typeParameters.isEmpty()) {
      return CodeBlock.of("");
    } else {
      return CodeBlock.of("(Class<$T>) (Object) ", TypeName.get(record.asType()));
    }
  }

  /**
   * Turn a given string into a valid Java identifier
   * @param str a string that may or may not be a valid Java identifier
   * @return a string that is a valid Java identifier
   *
   * Any illegal characters are replaced by their UTF_8 integer representation as a decimal number surrounded by underscores.
   * This helps ensure that strings the differ only in illegal characters get unique identifiers. (e.g. "m/s" and "m*s")
   *
   * E.g. "<Hello (Worl%d!)" becomes "_60_Hello_32__40_Worl_37_d_33__41_"
   */
  public static String getIdentifier(String str) {
    if (str.length() == 0) throw new IllegalArgumentException("Cannot turn empty string into an identifier");
    final var identifier = new StringBuilder();
    if (Character.isJavaIdentifierStart(str.charAt(0))) {
      identifier.append(str.charAt(0));
    } else {
      identifier.append('_');
      identifier.append((int) str.charAt(0));
      identifier.append('_');
    }
    for (int i = 1; i < str.length(); i++) {
      if (Character.isJavaIdentifierPart(str.charAt(i))) {
        identifier.append(str.charAt(i));
      } else {
        identifier.append('_');
        identifier.append((int) str.charAt(i));
        identifier.append('_');
      }
    }
    return identifier.toString();
  }

  /**
   * @param components In order, all the record component names and corresponding mapper identifiers
   * @param mappers In order, all the mapper identifiers and types
   */
  record ComponentsAndMappers(List<ComponentMapper> components, List<MapperType> mappers) {}

  /**
   * Correlates the name of a component with the parameter name of its corresponding mapper.
   * @param componentName the name of the record component (i.e. the "field name")
   * @param mapperIdentifier the identifier which will hold the mapper corresponding to this field
   */
  record ComponentMapper(String componentName, String mapperIdentifier) {}

  /**
   * Associates the identifier of a mapper with its TypePattern.
   * @param identifier the unique identifier for this mapper.
   * @param typePattern the type pattern describing this mapper.
   */
  record MapperType(String identifier, TypePattern typePattern) {}

  /**
   * Given a Record element, generate the parameter names and TypePatterns corresponding
   * to its mapper dependencies.
   *
   * @param element A Record element
   * @return enough information to 1) call the record constructor, in order, with the right types, and 2) define a method with the list of required value mappers, and 3) generate a call to that method
   */
  private static ComponentsAndMappers getComponentsAndMappers(final Element element) {
    final var components = new ArrayList<ComponentMapper>();
    final var mappers = new ArrayList<MapperType>();
    for (final var enclosedElement : element.getEnclosedElements()) {
      final var componentIdentifier = getIdentifier(enclosedElement.getSimpleName().toString());
      if (!(enclosedElement instanceof RecordComponentElement el)) continue;
      final TypeMirror typeMirror = el.getAccessor().getReturnType();
      final var typePattern = TypePattern.from(typeMirror).box();
      final var mapperIdentifier = componentIdentifier + "_ValueMapper";
      mappers.add(new MapperType(mapperIdentifier, typePattern));
      components.add(new ComponentMapper(componentIdentifier, mapperIdentifier));
    }
    return new ComponentsAndMappers(components, mappers);
  }
}
