package gov.nasa.ammos.plandev.procedural.processor;

import com.squareup.javapoet.AnnotationSpec;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;
import gov.nasa.ammos.plandev.procedural.scheduling.annotations.Template;
import gov.nasa.ammos.plandev.procedural.scheduling.annotations.WithDefaults;
import gov.nasa.ammos.plandev.merlin.framework.ValueMapper;

import gov.nasa.ammos.plandev.merlin.protocol.model.InputType;
import gov.nasa.ammos.plandev.merlin.protocol.types.ValueSchema;
import gov.nasa.ammos.plandev.procedural.scheduling.ProcedureMapper;
import gov.nasa.ammos.plandev.procedural.scheduling.annotations.SchedulingProcedure;
import gov.nasa.ammos.plandev.procedural.scheduling.annotations.WithMappers;

import gov.nasa.ammos.plandev.procedural.constraints.annotations.ConstraintProcedure;

import javax.annotation.processing.Completion;
import javax.annotation.processing.Filer;
import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.Processor;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.annotation.Repeatable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class ProcedureProcessor implements Processor {
  // Effectively final, late-initialized
  private Messager messager = null;
  private Filer filer = null;
  private Elements elementUtils = null;
  private Types typeUtils = null;

  @Override
  public Set<String> getSupportedOptions() {
    return Set.of();
  }

  /** Elements marked by these annotations will be treated as processing roots. */
  @Override
  public Set<String> getSupportedAnnotationTypes() {
    return Set.of(
        SchedulingProcedure.class.getCanonicalName(),
        ConstraintProcedure.class.getCanonicalName(),
        WithMappers.class.getCanonicalName()
    );
  }

  @Override
  public SourceVersion getSupportedSourceVersion() {
    return SourceVersion.latest();
  }

  @Override
  public void init(final ProcessingEnvironment processingEnv) {
    this.messager = processingEnv.getMessager();
    this.filer = processingEnv.getFiler();
    this.elementUtils = processingEnv.getElementUtils();
    this.typeUtils = processingEnv.getTypeUtils();
  }

  @Override
  public boolean process(final Set<? extends TypeElement> annotations, final RoundEnvironment roundEnv) {
    final var mapperClassElements = new ArrayList<TypeElement>();
    PackageElement packageElement = null;
    for (final var packageElement$ : roundEnv.getElementsAnnotatedWith(WithMappers.class)) {
      if (packageElement != null) throw new RuntimeException("Multiple packages annotated with WithMappers");
      if (packageElement$.getKind() != ElementKind.PACKAGE) throw new RuntimeException("Only packages can be annotated with WithMappers");
      packageElement = (PackageElement) packageElement$;
    }
    if (packageElement == null) return false;

    for (final var withMappersAnnotation : getRepeatableAnnotation(packageElement, WithMappers.class)) {
      final var attribute = getAnnotationAttribute(withMappersAnnotation, "value").orElseThrow();

      if (!(attribute.getValue() instanceof DeclaredType)) {
        throw new RuntimeException(
            "Mappers class not yet defined " +
            packageElement +
            withMappersAnnotation +
            attribute);
      }

      mapperClassElements.add((TypeElement) ((DeclaredType) attribute.getValue()).asElement());
    }

    final var typeRules = new ArrayList<TypeRule>();
    for (final var factory : mapperClassElements) {
      typeRules.addAll(parseValueMappers(factory));
    }
    //we now have all value mappers

    final var schedulingProcedures = roundEnv.getElementsAnnotatedWith(SchedulingProcedure.class);
    final var constraintProcedures = roundEnv.getElementsAnnotatedWith(ConstraintProcedure.class);

    final var generatedClassName = ClassName.get(packageElement.getQualifiedName() + ".generated", "AutoValueMappers");
    final var procedureToTypeRecord = new HashMap<Element, ProcedureTypeRecord>();
    for (final var procedure : schedulingProcedures) {
      final var procedureElement = (TypeElement) procedure;
      procedureToTypeRecord.put(procedure, parseProcedureType(packageElement, procedureElement));
      typeRules.add(AutoValueMappers.recordTypeRule(procedureElement, generatedClassName));
    }

    for (final var procedure : constraintProcedures) {
      final var procedureElement = (TypeElement) procedure;
      procedureToTypeRecord.put(procedure, parseProcedureType(packageElement, procedureElement));
      typeRules.add(AutoValueMappers.recordTypeRule(procedureElement, generatedClassName));
    }

    final var generatedFiles = new ArrayList<JavaFile>();

    final var allProcedures = Stream.concat(schedulingProcedures.stream(),constraintProcedures.stream()).collect(Collectors.toSet());

    generatedFiles.add(AutoValueMappers.generateAutoValueMappers(generatedClassName, allProcedures, List.of()));

    // For each procedure, generate a file that implements Procedure, Supplier<ValueMapper>
    for (final var procedure : schedulingProcedures) {
      final TypeName procedureType = TypeName.get(procedure.asType());

      final var valueMapperCode = new Resolver(typeUtils, elementUtils, typeRules)
          .applyRules(new TypePattern.ClassPattern(ClassName.get(ValueMapper.class), List.of(new TypePattern.ClassPattern((ClassName) procedureType, List.of()))));
      if (valueMapperCode.isEmpty()) throw new Error("Could not generate a valuemapper for procedure " + procedure.getSimpleName());

      final var typeSpec = generateInputType(packageElement, procedureToTypeRecord.get(procedure).inputType(), "InputMapper", typeRules);

      generatedFiles.add(JavaFile
          .builder(procedureToTypeRecord.get(procedure).inputType().mapper().name.packageName(), TypeSpec
              .classBuilder(procedureToTypeRecord.get(procedure).inputType().mapper().name)
              .addType(typeSpec.get())
              .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
              .addSuperinterface(ParameterizedTypeName.get(ClassName.get(ProcedureMapper.class), procedureType))
              .addMethod(MethodSpec
                             .methodBuilder("valueSchema")
                             .addModifiers(Modifier.PUBLIC)
                             .addAnnotation(Override.class)
                             .returns(ValueSchema.class)
                             .addStatement("return $L.getValueSchema()", valueMapperCode.get())
                             .build())
              .addMethod(MethodSpec
                             .methodBuilder("getInputType")
                             .addModifiers(Modifier.PUBLIC)
                             .addAnnotation(Override.class)
                             .returns(ParameterizedTypeName.get(
                                 ClassName.get(InputType.class),
                                 ClassName.get(procedureToTypeRecord.get(procedure).inputType().declaration())))
                             .addStatement("return new $T()", procedureToTypeRecord.get(procedure).inputType().mapper().name.nestedClass(typeSpec.get().name))
                             .build())
              .build())
          .skipJavaLangImports(true)
          .build());
    }

    // For each procedure, generate a file that implements Procedure, Supplier<ValueMapper>
    for (final var procedure : constraintProcedures) {
      final TypeName procedureType = TypeName.get(procedure.asType());

      this.messager.printMessage(
          Diagnostic.Kind.NOTE,
          "Looking at: " + procedure.toString());

      final var valueMapperCode = new Resolver(typeUtils, elementUtils, typeRules)
          .applyRules(new TypePattern.ClassPattern(ClassName.get(ValueMapper.class), List.of(new TypePattern.ClassPattern((ClassName) procedureType, List.of()))));
      if (valueMapperCode.isEmpty()) throw new Error("Could not generate a valuemapper for procedure " + procedure.getSimpleName());

      final var typeSpec = generateInputType(packageElement, procedureToTypeRecord.get(procedure).inputType(), "InputMapper", typeRules);


      generatedFiles.add(JavaFile
                             .builder(procedureToTypeRecord.get(procedure).inputType().mapper().name.packageName(), TypeSpec
                                 .classBuilder(procedureToTypeRecord.get(procedure).inputType().mapper().name)
                                 .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                                 .addSuperinterface(ParameterizedTypeName.get(ClassName.get(gov.nasa.ammos.plandev.procedural.constraints.ProcedureMapper.class), procedureType))
                                 .addType(typeSpec.get())
                                 .addMethod(MethodSpec
                                                .methodBuilder("valueSchema")
                                                .addModifiers(Modifier.PUBLIC)
                                                .addAnnotation(Override.class)
                                                .returns(ValueSchema.class)
                                                .addStatement("return $L.getValueSchema()", valueMapperCode.get())
                                                .build())
                                 .addMethod(MethodSpec
                                                .methodBuilder("getInputType")
                                                .addModifiers(Modifier.PUBLIC)
                                                .addAnnotation(Override.class)
                                                .returns(ParameterizedTypeName.get(
                                                    ClassName.get(InputType.class),
                                                    ClassName.get(procedureToTypeRecord.get(procedure).inputType().declaration())))
                                                .addStatement("return new $T()", procedureToTypeRecord.get(procedure).inputType().mapper().name.nestedClass(typeSpec.get().name))
                                                .build())
                                 .build())
                             .skipJavaLangImports(true)
                             .build());
    }

    for (final var generatedFile : generatedFiles) {
      this.messager.printMessage(
          Diagnostic.Kind.NOTE,
          "Generating " + generatedFile.packageName + "." + generatedFile.typeSpec.name);
        try {
            generatedFile.writeTo(this.filer);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    // Allow other annotation processors to process the framework annotations.
    return false;
  }

  private ProcedureTypeRecord parseProcedureType(final PackageElement jarElement, final TypeElement procedureElement)
  {
    final var fullyQualifiedClassName = procedureElement.getQualifiedName();
    final var name = procedureElement.getSimpleName().toString();
    final MapperRecord mapper = MapperRecord.generatedFor(ClassName.get(procedureElement), jarElement);
    final List<ParameterRecord> parameters = this.getExportParameters(procedureElement);

    /*
    The following parameter was created as a result of AERIE-1295/1296/1297 on JIRA
    In order to allow for optional/required parameters, the processor
    must extract the factory method call that creates the default
    template values for some activity. Additionally, a helper method
    is used to determine whether some activity is written as a
    class (old-style) or as a record (new-style) by determining
    whether there are @Parameter tags (old-style) or not
     */
    final var defaultsStyle = this.getExportDefaultsStyle(procedureElement);

    return new ProcedureTypeRecord(
        fullyQualifiedClassName.toString(),
        name,
        new InputTypeRecord(name, procedureElement, parameters, mapper, defaultsStyle));
  }

  /** Parse a list of parameters from an export type element, depending on the export defaults style in use. */
  private List<ParameterRecord> getExportParameters(final TypeElement exportTypeElement)
  {
    return exportTypeElement.getEnclosedElements().stream()
                            .filter(e -> e.getKind() == ElementKind.RECORD_COMPONENT) // Element must be a record component
                            .map(e -> new ParameterRecord(e.getSimpleName().toString(), e.asType(), e))
                            .toList();
  }

  private ExportDefaultsStyle getExportDefaultsStyle(final TypeElement exportTypeElement)
  {
    for (final var element : exportTypeElement.getEnclosedElements()) {
      if (element.getAnnotation(Template.class) != null)
        return ExportDefaultsStyle.AllStaticallyDefined;
      if (element.getAnnotation(WithDefaults.class) != null)
        return ExportDefaultsStyle.SomeStaticallyDefined;
    }
    return ExportDefaultsStyle.NoneDefined; // No default arguments provided
  }

  /** Generate an `InputType` implementation. */
  public Optional<TypeSpec> generateInputType(
      PackageElement packageElement,
      final InputTypeRecord inputType,
      final String name,
      final List<TypeRule> typeRules) {
    final var mapperBlocks$ = generateParameterMapperBlocks(typeRules, inputType);
    if (mapperBlocks$.isEmpty()) return Optional.empty();
    final var mapperBlocks = mapperBlocks$.get();

    final var mapperMethodMaker = MapperMethodMaker.make(inputType);
    return Optional.of(TypeSpec
                           .classBuilder(name)
                           .addOriginatingElement(packageElement)
                           // The fields and methods of the activity determines the overall behavior of this class.
                           .addOriginatingElement(inputType.declaration())
                           .addSuperinterface(ParameterizedTypeName.get(
                               ClassName.get(gov.nasa.ammos.plandev.merlin.protocol.model.InputType.class),
                               ClassName.get(inputType.declaration())))
                           .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                           .addFields(
                               inputType.parameters()
                                        .stream()
                                        .map(parameter -> FieldSpec
                                            .builder(
                                                ParameterizedTypeName.get(
                                                    ClassName.get(gov.nasa.ammos.plandev.merlin.framework.ValueMapper.class),
                                                    TypeName.get(parameter.type).box()),
                                                "mapper_" + parameter.name)
                                            .addModifiers(Modifier.PRIVATE, Modifier.FINAL)
                                            .build())
                                        .collect(Collectors.toList()))
                           .addMethod(
                               MethodSpec
                                   .constructorBuilder()
                                   .addModifiers(Modifier.PUBLIC)
                                   /* Suppress unchecked warnings because the resolver has to
                                       put some big casting in for Class parameters
                                    */
                                   .addAnnotation(
                                       AnnotationSpec
                                           .builder(SuppressWarnings.class)
                                           .addMember("value", "$S", "unchecked")
                                           .build())
                                   .addCode(
                                       inputType.parameters()
                                                .stream()
                                                .map(parameter -> CodeBlock
                                                    .builder()
                                                    .addStatement(
                                                        "this.mapper_$L =\n$L",
                                                        parameter.name,
                                                        mapperBlocks.get(parameter.name)))
                                                .reduce(CodeBlock.builder(), (x, y) -> x.add(y.build()))
                                                .build())
                                   .build())
                           .addMethod(mapperMethodMaker.makeGetRequiredParametersMethod())
                           .addMethod(mapperMethodMaker.makeGetParametersMethod())
                           .addMethod(mapperMethodMaker.makeGetArgumentsMethod())
                           .addMethod(mapperMethodMaker.makeInstantiateMethod())
                           .addMethod(mapperMethodMaker.makeGetValidationFailuresMethod())
                           .build());
  }


  @Override
  public Iterable<? extends Completion> getCompletions(
      final Element element,
      final AnnotationMirror annotation,
      final ExecutableElement member,
      final String userText)
  {
    return Collections::emptyIterator;
  }

  private Optional<Map<String, CodeBlock>> generateParameterMapperBlocks(List<TypeRule> typeRules, final InputTypeRecord inputType)
  {
    final var resolver = new Resolver(this.typeUtils, this.elementUtils, typeRules);
    var failed = false;
    final var mapperBlocks = new HashMap<String, CodeBlock>();

    for (final var parameter : inputType.parameters()) {
      final var mapperBlock = resolver.instantiateNullableMapperFor(parameter.type);
      if (mapperBlock.isPresent()) {
        mapperBlocks.put(parameter.name, mapperBlock.get());
      } else {
        failed = true;
        messager.printMessage(
            Diagnostic.Kind.ERROR,
            "Failed to generate value mapper for parameter",
            parameter.element);
      }
    }

    return failed ? Optional.empty() : Optional.of(mapperBlocks);
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

    private List<AnnotationMirror> getRepeatableAnnotation(final Element element, final Class<? extends Annotation> annotationClass)
    {
      final var containerClass = annotationClass.getAnnotation(Repeatable.class).value();

      final var annotationType = this.elementUtils.getTypeElement(annotationClass.getCanonicalName()).asType();
      final var containerType = this.elementUtils.getTypeElement(containerClass.getCanonicalName()).asType();

      final var mirrors = new ArrayList<AnnotationMirror>();
      for (final var mirror : element.getAnnotationMirrors()) {
        if (this.typeUtils.isSameType(annotationType, mirror.getAnnotationType())) {
          mirrors.add(mirror);
        } else if (this.typeUtils.isSameType(containerType, mirror.getAnnotationType())) {
          // SAFETY: a container annotation has a value() attribute that is an array of annotations
          @SuppressWarnings("unchecked")
          final var containedMirrors =
              (List<AnnotationMirror>)
                  getAnnotationAttribute(mirror, "value")
                      .orElseThrow()
                      .getValue();

          mirrors.addAll(containedMirrors);
        }
      }

      return mirrors;
    }

  private List<TypeRule> parseValueMappers(final TypeElement factory) {
    final var rules = new ArrayList<TypeRule>();

    for (final var element : factory.getEnclosedElements()) {
      if (element.getKind().equals(ElementKind.METHOD)) {
        rules.add(this.parseValueMapperMethod((ExecutableElement) element, ClassName.get(factory)));
      }
    }
    return rules;
  }

  private TypeRule parseValueMapperMethod(final ExecutableElement element, final ClassName factory) {
    if (!element.getModifiers().containsAll(Set.of(Modifier.PUBLIC, Modifier.STATIC))) {
      throw new RuntimeException(
          "Value Mapper method must be public and static " +
          element
      );
    }

    final var head = TypePattern.from(element.getReturnType());
    final var enumBoundedTypeParameters = getEnumBoundedTypeParameters(element);
    final var method = element.getSimpleName().toString();
    final var parameters = new ArrayList<TypePattern>();
    for (final var parameter : element.getParameters()) {
      parameters.add(TypePattern.from(parameter));
    }

    return new TypeRule(head, enumBoundedTypeParameters, parameters, factory, method);
  }

  private Set<String> getEnumBoundedTypeParameters(final ExecutableElement element) {
    final var enumBoundedTypeParameters = new HashSet<String>();
    // Ensure type parameters are unbounded or bounded only by enum type.
    // Supporting value mapper resolvers for types like:
    // - `List<? extends Foo>` or
    // - `List<? extends Map<? super Foo, ? extends Bar>>`
    // is not straightforward.
    for (final var typeParameter : element.getTypeParameters()) {
      final var bounds = typeParameter.getBounds();
      for (final var bound : bounds) {
        final var erasure = typeUtils.erasure(bound);
        final var objectType = elementUtils.getTypeElement("java.lang.Object").asType();
        final var enumType = typeUtils.erasure(elementUtils.getTypeElement("java.lang.Enum").asType());
        if (typeUtils.isSameType(erasure, objectType)) {
          // Nothing to do
        } else if (typeUtils.isSameType(erasure, enumType)) {
          enumBoundedTypeParameters.add(typeParameter.getSimpleName().toString());
        } else {
          throw new RuntimeException(
              "Value Mapper method type parameter must be unbounded, or bounded by enum type only" + element
          );
        }
      }
    }
    return enumBoundedTypeParameters;
  }
}
