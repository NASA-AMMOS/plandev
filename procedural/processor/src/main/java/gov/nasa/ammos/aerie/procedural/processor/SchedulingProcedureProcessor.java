package gov.nasa.ammos.aerie.procedural.processor;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;
import gov.nasa.jpl.aerie.merlin.framework.ValueMapper;
import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;
import gov.nasa.jpl.aerie.merlin.protocol.types.ValueSchema;
import gov.nasa.ammos.aerie.procedural.scheduling.ProcedureMapper;
import gov.nasa.ammos.aerie.procedural.scheduling.annotations.SchedulingProcedure;
import gov.nasa.ammos.aerie.procedural.scheduling.annotations.WithMappers;

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
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public final class SchedulingProcedureProcessor implements Processor {
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
    return Set.of(SchedulingProcedure.class.getCanonicalName(), WithMappers.class.getCanonicalName());
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
    if (packageElement == null) return false; //throw new RuntimeException("Need to annotate a package-info class with WithMappers");

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

    final var procedures = roundEnv.getElementsAnnotatedWith(SchedulingProcedure.class);

    final var generatedClassName = ClassName.get(packageElement.getQualifiedName() + ".generated", "AutoValueMappers");
    for (final var procedure : procedures) {
      final var procedureElement = (TypeElement) procedure;
      typeRules.add(AutoValueMappers.recordTypeRule(procedureElement, generatedClassName));
    }

    final var generatedFiles = new ArrayList<JavaFile>();

    generatedFiles.add(AutoValueMappers.generateAutoValueMappers(generatedClassName, procedures, List.of()));

    // For each procedure, generate a file that implements Procedure, Supplier<ValueMapper>
    for (final var procedure : procedures) {
      final TypeName procedureType = TypeName.get(procedure.asType());
      final ParameterizedTypeName valueMapperType = ParameterizedTypeName.get(
          ClassName.get(ValueMapper.class),
          procedureType);

      final var valueMapperCode = new Resolver(typeUtils, elementUtils, typeRules).applyRules(new TypePattern.ClassPattern(ClassName.get(ValueMapper.class), List.of(new TypePattern.ClassPattern((ClassName) procedureType, List.of()))));
      if (valueMapperCode.isEmpty()) throw new Error("Could not generate a valuemapper for procedure " + procedure.getSimpleName());


      generatedFiles.add(JavaFile
          .builder(generatedClassName.packageName() + ".procedures", TypeSpec
              .classBuilder(procedure.getSimpleName().toString())
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
                             .methodBuilder("serialize")
                             .addModifiers(Modifier.PUBLIC)
                             .addAnnotation(Override.class)
                             .addParameter(procedureType, "procedure")
                             .returns(SerializedValue.class)
                             .addStatement("return $L.serializeValue(procedure)", valueMapperCode.get())
                             .build())
              .addMethod(MethodSpec
                             .methodBuilder("deserialize")
                             .addModifiers(Modifier.PUBLIC)
                             .addAnnotation(Override.class)
                             .addParameter(SerializedValue.class, "value")
                             .returns(procedureType)
                             .addStatement("return $L.deserializeValue(value).getSuccessOrThrow(e -> new $T(e))", valueMapperCode.get(), RuntimeException.class)
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

  @Override
  public Iterable<? extends Completion> getCompletions(
      final Element element,
      final AnnotationMirror annotation,
      final ExecutableElement member,
      final String userText)
  {
    return Collections::emptyIterator;
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
