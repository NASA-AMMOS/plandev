package gov.nasa.jpl.aerie.merlin.processor;

import com.squareup.javapoet.ClassName;
import gov.nasa.jpl.aerie.contrib.models.ValidationResult;
import gov.nasa.jpl.aerie.merlin.framework.MetadataValueMapper;
import gov.nasa.jpl.aerie.merlin.framework.Registrar;
import gov.nasa.jpl.aerie.merlin.framework.ValueMapper;
import gov.nasa.jpl.aerie.merlin.framework.annotations.ActivityType;
import gov.nasa.jpl.aerie.merlin.framework.annotations.Export;
import gov.nasa.jpl.aerie.merlin.framework.annotations.MissionModel;
import gov.nasa.jpl.aerie.merlin.processor.metamodel.ActivityTypeRecord;
import gov.nasa.jpl.aerie.merlin.processor.metamodel.ActivityValueMapperRecord;
import gov.nasa.jpl.aerie.merlin.processor.metamodel.InputTypeRecord;
import gov.nasa.jpl.aerie.merlin.processor.metamodel.EffectModelRecord;
import gov.nasa.jpl.aerie.merlin.processor.metamodel.ExportDefaultsStyle;
import gov.nasa.jpl.aerie.merlin.processor.metamodel.ActivityMapperRecord;
import gov.nasa.jpl.aerie.merlin.processor.metamodel.MissionModelRecord;
import gov.nasa.jpl.aerie.merlin.processor.metamodel.ParameterRecord;
import gov.nasa.jpl.aerie.merlin.processor.metamodel.ParameterValidationRecord;
import gov.nasa.jpl.aerie.merlin.processor.metamodel.TypeRule;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import org.apache.commons.lang3.tuple.Pair;

import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import java.lang.annotation.Annotation;
import java.lang.annotation.Repeatable;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/** Parses mission model annotations to record type metamodels. */
/*package-private*/ record MissionModelParser(Elements elementUtils, Types typeUtils) {

  //
  // MISSION MODEL PARSING
  //

  public MissionModelRecord parseMissionModel(final PackageElement missionModelElement)
  throws InvalidMissionModelException
  {
    final var topLevelModel = this.getMissionModel(missionModelElement);

    final var typeRules = new ArrayList<TypeRule>();
    for (final var factory : this.getMissionModelMapperClasses(missionModelElement)) {
      typeRules.addAll(this.parseValueMappers(factory));
    }

    typeRules.addAll(parseMetadataAnnotations(missionModelElement));

    final var activityTypes = new ArrayList<ActivityTypeRecord>();
    for (final var activityTypeElement : this.getMissionModelActivityTypes(missionModelElement)) {
      activityTypes.add(this.parseActivityType(missionModelElement, activityTypeElement));
    }

    return new MissionModelRecord(
        missionModelElement,
        topLevelModel.type,
        topLevelModel.expectsPlanStart,
        topLevelModel.configurationType,
        typeRules,
        activityTypes);
  }

  private record MissionModelTypeRecord(
      TypeElement type,
      boolean expectsPlanStart,
      Optional<InputTypeRecord> configurationType) { }

  private MissionModelTypeRecord getMissionModel(final PackageElement missionModelElement)
  throws InvalidMissionModelException
  {
    final var configurationType = this.getMissionModelConfigurationType(missionModelElement);
    final var modelTypeElement = this.getMissionModelTypeElement(missionModelElement);

    final var parameters = this.getMissionModelConstructor(modelTypeElement).getParameters().stream()
        .map(p -> ClassName.get(p.asType()))
        .toList();
    final var nParameters = parameters.size();
    final var foundPlanStart = parameters.contains(ClassName.get(Instant.class));

    // Ensure model only has one constructor, one of:
    // - (Registrar, Instant, Configuration)
    // - (Registrar, Configuration)
    // - (Registrar, Instant)
    // - (Registrar)
    final var expectedParameters = configurationType
        // Configuration expected
        .map(configType -> foundPlanStart && nParameters > 2 ? // If plan start `Instant` truly is expected
            List.of(ClassName.get(Registrar.class), ClassName.get(Instant.class), ClassName.get(configType.declaration())) :
            List.of(ClassName.get(Registrar.class), ClassName.get(configType.declaration())))
        // No configuration expected
        .orElseGet(() -> foundPlanStart && nParameters > 1 ? // If plan start `Instant` truly is expected
            List.of(ClassName.get(Registrar.class), ClassName.get(Instant.class)) :
            List.of(ClassName.get(Registrar.class)));

    if (!parameters.equals(expectedParameters)) {
      throw new InvalidMissionModelException(
          "The top-level model's constructor is expected to accept %s, found: %s"
              .formatted(expectedParameters.toString(), parameters.toString()),
          missionModelElement);
    }

    // TODO: Consider enrolling the given model in a dependency injection framework,
    //   such that the Cursor can be injected like any other constructor argument,
    //   and indeed such that other arguments can flexibly be supported.
    return new MissionModelTypeRecord(modelTypeElement, foundPlanStart, configurationType);
  }

  private TypeElement getMissionModelTypeElement(final PackageElement missionModelElement)
  throws InvalidMissionModelException
  {
    final var annotationMirror = this
        .getAnnotationMirrorByType(missionModelElement, MissionModel.class)
        .orElseThrow(() -> new InvalidMissionModelException(
            "The missionModel package is somehow missing an @MissionModel annotation",
            missionModelElement));

    final var modelAttribute = getAnnotationAttribute(annotationMirror, "model").orElseThrow();
    if (!(modelAttribute.getValue() instanceof final DeclaredType model)) {
      throw new InvalidMissionModelException(
          "The top-level model is not yet defined",
          missionModelElement,
          annotationMirror,
          modelAttribute);
    }
    return (TypeElement) model.asElement();
  }

  private ExecutableElement getMissionModelConstructor(final TypeElement missionModelTypeElement)
  throws InvalidMissionModelException
  {
    final var constructors = missionModelTypeElement.getEnclosedElements().stream()
        .filter(e -> e.getKind() == ElementKind.CONSTRUCTOR && e.getModifiers().contains(Modifier.PUBLIC))
        .toList();
    if (constructors.isEmpty()) {
      throw new InvalidMissionModelException(
          "The top-level model declares no public constructor",
          missionModelTypeElement);
    }
    if (constructors.size() > 1) {
      throw new InvalidMissionModelException(
          "The top-level model declares more than one public constructor",
          missionModelTypeElement);
    }
    return (ExecutableElement) constructors.get(0);
  }

  private Optional<InputTypeRecord> getMissionModelConfigurationType(final PackageElement missionModelElement)
  throws InvalidMissionModelException
  {
    final var annotationMirror =
        this.getAnnotationMirrorByType(missionModelElement, MissionModel.WithConfiguration.class);
    if (annotationMirror.isEmpty()) return Optional.empty();

    final var attribute = getAnnotationAttribute(annotationMirror.get(), "value").orElseThrow();
    if (!(attribute.getValue() instanceof DeclaredType)) {
      throw new InvalidMissionModelException(
          "MissionModel configuration type not yet defined",
          missionModelElement,
          annotationMirror.get(),
          attribute);
    }

    final var declaration = (TypeElement) ((DeclaredType) attribute.getValue()).asElement();
    final var name = declaration.getSimpleName().toString();
    final var parameters = getExportParameters(declaration);
    final var validations = this.getExportValidations(declaration, parameters);
    final var activityMapper = getExportActivityMapper(missionModelElement, declaration);
    final var valueMapper = getExportValueMapper(missionModelElement, declaration);
    final var defaultsStyle = getExportDefaultsStyle(declaration);
    return Optional.of(new InputTypeRecord(name, declaration, parameters, validations, activityMapper, valueMapper, defaultsStyle));
  }

  private List<TypeElement> getMissionModelMapperClasses(final PackageElement missionModelElement)
  throws InvalidMissionModelException
  {
    final var mapperClassElements = new ArrayList<TypeElement>();

    for (final var withMappersAnnotation : getRepeatableAnnotation(missionModelElement, MissionModel.WithMappers.class)) {
      final var attribute =
          getAnnotationAttribute(withMappersAnnotation, "value").orElseThrow();

      if (!(attribute.getValue() instanceof DeclaredType)) {
        throw new InvalidMissionModelException(
            "Mappers class not yet defined",
            missionModelElement,
            withMappersAnnotation,
            attribute);
      }

      mapperClassElements.add((TypeElement) ((DeclaredType) attribute.getValue()).asElement());
    }

    return mapperClassElements;
  }

  private List<TypeRule> parseValueMappers(final TypeElement factory)
  throws InvalidMissionModelException
  {
    final var rules = new ArrayList<TypeRule>();

    for (final var element : factory.getEnclosedElements()) {
      if (element.getKind().equals(ElementKind.METHOD)) {
        rules.add(this.parseValueMapperMethod((ExecutableElement) element, ClassName.get(factory)));
      }
    }
    return rules;
  }

  private List<TypeRule> parseMetadataAnnotations(final PackageElement missionModelElement) throws InvalidMissionModelException {
    final var rules = new ArrayList<TypeRule>();
    // Generate type rules from annotations
    for (final var namedElement : getMetadataAnnotations(missionModelElement)) {
      final var name = namedElement.getLeft();
      final var annotationElement = namedElement.getRight();
      final var className = ClassName.get(annotationElement);
      rules.add(new TypeRule(
          new TypePattern.ClassPattern(ClassName.get(ValueMapper.class), List.of(
              new TypePattern.AnnotationPattern(
                  className,
                  Optional.empty(),
                  new TypePattern.TypeVariablePattern("T")
              ))),
          Set.of(),
          List.of(
              new TypePattern.ClassPattern(
                  ClassName.get(ValueMapper.class),
                  List.of(new TypePattern.TypeVariablePattern("T"))
              ),
              new TypePattern.ClassPattern(
                  ClassName.get(ValueMapper.class),
                  List.of(new TypePattern.ClassPattern(className, List.of()))),
              new TypePattern.ClassPattern(className, List.of())
          ),
          ClassName.get(MetadataValueMapper.class),
          "annotationValueMapper",
          name
      ));
    }
    return rules;
  }

  private TypeRule parseValueMapperMethod(final ExecutableElement element, final ClassName factory)
  throws InvalidMissionModelException
  {
    if (!element.getModifiers().containsAll(Set.of(Modifier.PUBLIC, Modifier.STATIC))) {
      throw new InvalidMissionModelException(
          "Value Mapper method must be public and static",
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

  private Set<String> getEnumBoundedTypeParameters(final ExecutableElement element)
  throws InvalidMissionModelException
  {
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
          throw new InvalidMissionModelException(
              "Value Mapper method type parameter must be unbounded, or bounded by enum type only",
              element
          );
        }
      }
    }
    return enumBoundedTypeParameters;
  }

  private List<TypeElement> getMissionModelActivityTypes(final PackageElement missionModelElement)
  throws InvalidMissionModelException
  {
    final var activityTypeElements = new ArrayList<TypeElement>();

    for (final var activityTypeAnnotation : getRepeatableAnnotation(
        missionModelElement,
        MissionModel.WithActivityType.class)) {
      final var attribute =
          getAnnotationAttribute(activityTypeAnnotation, "value").orElseThrow();

      if (!(attribute.getValue() instanceof DeclaredType)) {
        throw new InvalidMissionModelException(
            "Activity type not yet defined",
            missionModelElement,
            activityTypeAnnotation,
            attribute);
      }

      // DeclaredType cast works because we checked above that attribute is indeed a DeclaredType
      // TypeElement cast works because the element of a DeclaredType must be a TypeElement
      activityTypeElements.add((TypeElement) ((DeclaredType) attribute.getValue()).asElement());
    }

    return activityTypeElements;
  }

  private List<Pair<String, TypeElement>> getMetadataAnnotations(final PackageElement missionModelElement) throws InvalidMissionModelException {
    final var metadataAnnotations = new ArrayList<Pair<String, TypeElement>>();

    for (final var activityTypeAnnotation : getRepeatableAnnotation(missionModelElement, MissionModel.WithMetadata.class)) {
      // SAFETY: We know that MissionModel.WithMetadata has an "annotation" field
      final var annotation = getAnnotationAttribute(activityTypeAnnotation, "annotation").orElseThrow();

      if (!(annotation.getValue() instanceof DeclaredType declaredType)) {
        throw new InvalidMissionModelException(
            "Metadata annotation class not yet defined",
            missionModelElement,
            activityTypeAnnotation,
            annotation);
      }

      final var kind = declaredType.asElement().getKind();
      if (!kind.equals(ElementKind.ANNOTATION_TYPE)) {
        throw new InvalidMissionModelException(
            "%s annotation applied to non-annotation: %s has kind: %s"
                .formatted(
                    MissionModel.WithMetadata.class.getCanonicalName(),
                    declaredType.asElement().asType(),
                    kind),
            missionModelElement,
            activityTypeAnnotation,
            annotation);
      }
      // SAFETY: We know that the MissionModel.WithMetadata annotation has a name field
      final var name = getAnnotationAttribute(activityTypeAnnotation, "name").orElseThrow();

      // SAFETY: TypeElement cast works because the element of a DeclaredType must be a TypeElement
      metadataAnnotations.add(Pair.of((String) name.getValue(), (TypeElement) declaredType.asElement()));
    }

    return metadataAnnotations;
  }

  //
  // ACTIVITY TYPE PARSING
  //

  private ActivityTypeRecord parseActivityType(final PackageElement missionModelElement, final TypeElement activityTypeElement)
  throws InvalidMissionModelException
  {
    final var fullyQualifiedClassName = activityTypeElement.getQualifiedName();
    final var name = this.getActivityTypeName(activityTypeElement);
    final var activityMapper = getExportActivityMapper(missionModelElement, activityTypeElement);
    final var valueMapper = getExportValueMapper(missionModelElement, activityTypeElement);
    final var parameters = this.getExportParameters(activityTypeElement);
    final var validations = this.getExportValidations(activityTypeElement, parameters);
    final var effectModel = this.getActivityEffectModel(activityTypeElement);

    if (!parameters.isEmpty()) {
      checkForStaticParameters(name, parameters);
    }

    final var durationParameterName = effectModel.flatMap(EffectModelRecord::durationParameter);
    if (durationParameterName.isPresent()) {
      validateControllableDurationParameter(name, parameters, durationParameterName.get());
    }

    /*
    The following parameter was created as a result of AERIE-1295/1296/1297 on JIRA
    In order to allow for optional/required parameters, the processor
    must extract the factory method call that creates the default
    template values for some activity. Additionally, a helper method
    is used to determine whether some activity is written as a
    class (old-style) or as a record (new-style) by determining
    whether there are @Parameter tags (old-style) or not
     */
    final var defaultsStyle = this.getExportDefaultsStyle(activityTypeElement);

    return new ActivityTypeRecord(
        fullyQualifiedClassName.toString(),
        name,
        new InputTypeRecord(name, activityTypeElement, parameters, validations, activityMapper, valueMapper, defaultsStyle),
        effectModel);
  }

  private void checkForStaticParameters(String activityName, List<ParameterRecord> parameters) throws InvalidMissionModelException {
    for (final var parameter: parameters) {
      if (parameter.element.getModifiers().contains(Modifier.STATIC)) {
        throw new InvalidMissionModelException(
            "In activity " + activityName +
            ", parameter \"" + parameter.name +"\"" +
            " is declared as static, but this is not valid for activity parameters");
      }
    }
  }

  private void validateControllableDurationParameter(
      final String activityName,
      final List<ParameterRecord> parameters,
      final String durationParameterName)
  throws InvalidMissionModelException
  {
    final var durationParameterRecord = lookupParameterByName(parameters, durationParameterName);
    if (durationParameterRecord.isEmpty()) {
      throw new InvalidMissionModelException(
          "In activity " + activityName + ", \"" + durationParameterName + "\"" +
          " is declared as the ControllableDuration parameter, but there is no parameter with that name");
    }
    final var record = durationParameterRecord.get();
    if (!this.typeUtils.isSameType(record.type, this.elementUtils.getTypeElement(Duration.class.getName()).asType())) {
      throw new InvalidMissionModelException(
          "In activity " + activityName +
          ", parameter \"" + record.name + "\"" +
          " is declared as the ControllableDuration parameter, but does not have type " + Duration.class.getName() + "." +
          " Instead, it has type " + record.type);
    }
  }

  private static Optional<ParameterRecord> lookupParameterByName(final Iterable<ParameterRecord> parameters, final String name) {
    for (final var param : parameters) {
      if (param.name.equals(name)) {
        return Optional.of(param);
      }
    }
    return Optional.empty();
  }

  private ExportDefaultsStyle getExportDefaultsStyle(final TypeElement exportTypeElement)
  {
    for (final var element : exportTypeElement.getEnclosedElements()) {
      if (element.getAnnotation(Export.Parameter.class) != null)
        return ExportDefaultsStyle.AllDefined;
      if (element.getAnnotation(Export.Template.class) != null)
        return ExportDefaultsStyle.AllStaticallyDefined;
      if (element.getAnnotation(Export.WithDefaults.class) != null)
        return ExportDefaultsStyle.SomeStaticallyDefined;
    }
    return ExportDefaultsStyle.NoneDefined; // No default arguments provided
  }

  private String getActivityTypeName(final TypeElement activityTypeElement)
  throws InvalidMissionModelException
  {
    final var annotationMirror = this
        .getAnnotationMirrorByType(activityTypeElement, ActivityType.class)
        .orElseThrow(() -> new InvalidMissionModelException(
            "An activity is somehow missing an @Activity annotation",
            activityTypeElement));

    final var nameAttribute = getAnnotationAttribute(annotationMirror, "value")
        .orElseThrow(() -> new InvalidMissionModelException(
            "Unable to get value attribute of annotation",
            activityTypeElement,
            annotationMirror));

    return (String) nameAttribute.getValue();
  }

  private ActivityMapperRecord getExportActivityMapper(final PackageElement missionModelElement, final TypeElement exportTypeElement)
  throws InvalidMissionModelException
  {
    final var annotationMirror = this.getAnnotationMirrorByType(exportTypeElement, ActivityType.WithMapper.class);
    if (annotationMirror.isEmpty()) {
      return ActivityMapperRecord.generatedFor(
          ClassName.get(exportTypeElement),
          missionModelElement);
    }

    final var mapperType = (DeclaredType) getAnnotationAttribute(annotationMirror.get(), "value")
        .orElseThrow(() -> new InvalidMissionModelException(
            "Unable to get value attribute of annotation",
            exportTypeElement,
            annotationMirror.get()))
        .getValue();

    return ActivityMapperRecord.custom(
        ClassName.get((TypeElement) mapperType.asElement()));
  }
  private ActivityValueMapperRecord getExportValueMapper(final PackageElement missionModelElement, final TypeElement exportTypeElement)
  throws InvalidMissionModelException
  {
    final var annotationMirror = this.getAnnotationMirrorByType(exportTypeElement, ActivityType.WithMapper.class);
    if (annotationMirror.isEmpty()) {
      return ActivityValueMapperRecord.generatedFor(
          ClassName.get(exportTypeElement),
          missionModelElement);
    }

    final var mapperType = (DeclaredType) getAnnotationAttribute(annotationMirror.get(), "value")
        .orElseThrow(() -> new InvalidMissionModelException(
            "Unable to get value attribute of annotation",
            exportTypeElement,
            annotationMirror.get()))
        .getValue();

    return ActivityValueMapperRecord.custom(
        ClassName.get((TypeElement) mapperType.asElement()));
  }

  private List<ParameterValidationRecord> getExportValidations(
      final TypeElement exportTypeElement,
      final List<ParameterRecord> parameters)
  throws InvalidMissionModelException
  {
    final var validations = new ArrayList<ParameterValidationRecord>();
    final var parameterNames = parameters.stream().map(p -> p.name).collect(Collectors.toUnmodifiableSet());

    for (final var element : exportTypeElement.getEnclosedElements()) {
      if (element.getAnnotation(Export.Validation.class) == null) continue;

      boolean isSimpleValidation = !(element.getKind() == ElementKind.METHOD &&
          ((ExecutableElement) element).getReturnType().toString().equals(ValidationResult.class.getTypeName()));

      final var name = element.getSimpleName().toString();
      final var message = element.getAnnotation(Export.Validation.class).value();
      final var subjects = element.getAnnotation(Export.Validation.Subject.class) == null ?
          new String[] { } :
          element.getAnnotation(Export.Validation.Subject.class).value();

      final var missingSubjects$ = Arrays.stream(subjects)
          .filter(Predicate.not(parameterNames::contains))
          .map("\"%s\""::formatted)
          .reduce((x, y) -> x + ", " + y);

      if (missingSubjects$.isPresent()) {
        throw new InvalidMissionModelException(
            "Validation subjects for validation \"%s\" do not exist: %s"
                .formatted(name, missingSubjects$.get()), exportTypeElement);
      }

      validations.add(new ParameterValidationRecord(name, subjects, message, isSimpleValidation));
    }

    return validations;
  }

  /** Parse a list of parameters from an export type element, depending on the export defaults style in use. */
  private List<ParameterRecord> getExportParameters(final TypeElement exportTypeElement)
  {
    final var defaultsStyle = this.getExportDefaultsStyle(exportTypeElement);
    final Predicate<Element> excludeParamPred = switch (defaultsStyle) {
      case AllDefined -> e -> e.getAnnotation(Export.Parameter.class) == null; // Exclude class members with @Parameter annotations
      default ->         e -> e.getModifiers().contains(Modifier.STATIC);      // Exclude static class members
    };

    return exportTypeElement.getEnclosedElements().stream()
        .filter(e -> e.getKind() == ElementKind.FIELD) // Element must be a field
        .filter(e -> !excludeParamPred.test(e))        // Element must not be deemed excluded for the defaults style
        .map(e -> new ParameterRecord(e.getSimpleName().toString(), e.asType(), e))
        .toList();
  }

  private Optional<EffectModelRecord> getActivityEffectModel(final TypeElement activityTypeElement)
  throws InvalidMissionModelException
  {
    Optional<String> fixedDuration = Optional.empty();
    Optional<String> parameterizedDuration = Optional.empty();
    Optional<String> maximumDuration = Optional.empty();
    for (final var element: activityTypeElement.getEnclosedElements()) {
      if (element.getAnnotation(ActivityType.FixedDuration.class) != null) {
        if (fixedDuration.isPresent()) throw new InvalidMissionModelException(
            "FixedDuration annotation cannot be applied multiple times in one activity type."
        );

        if (element.getKind() == ElementKind.METHOD) {
          if (!(element instanceof ExecutableElement executableElement)) throw new InvalidMissionModelException(
              "FixedDuration method annotation must be an executable element.");

          if (!executableElement.getParameters().isEmpty()) throw new InvalidMissionModelException(
              "FixedDuration annotation must be applied to a method with no arguments."
          );

          fixedDuration = Optional.of(executableElement.getSimpleName().toString() + "()");
        } else if (element.getKind() == ElementKind.FIELD) {
          fixedDuration = Optional.of(element.getSimpleName().toString());
        }
      } else if (element.getAnnotation(ActivityType.ParametricDuration.class) != null) {
        if (parameterizedDuration.isPresent()) throw new InvalidMissionModelException(
            "ParametricDuration annotation cannot be applied multiple times in one activity type."
        );

        if (!(element instanceof ExecutableElement executableElement)) throw new InvalidMissionModelException(
            "ParametricDuration method annotation must be an executable element.");

        if (!executableElement.getParameters().isEmpty()) throw new InvalidMissionModelException(
            "ParametricDuration method must be applied to a method with no arguments."
        );

        parameterizedDuration = Optional.of(executableElement.getSimpleName().toString());
      } else if (element.getAnnotation(ActivityType.MaximumDuration.class) != null){
        if (maximumDuration.isPresent()) throw new InvalidMissionModelException(
            "MaximumDuration annotation cannot be applied multiple times in one activity type."
        );

        if (element.getKind() == ElementKind.METHOD) {
          if (!(element instanceof ExecutableElement executableElement)) throw new InvalidMissionModelException(
              "MaximumDuration method annotation must be an executable element.");

          if (!executableElement.getParameters().isEmpty()) throw new InvalidMissionModelException(
              "MaximumDuration annotation must be applied to a method with no arguments."
          );
          maximumDuration = Optional.of(executableElement.getSimpleName().toString() + "()");
        } else if (element.getKind() == ElementKind.FIELD) {
          maximumDuration = Optional.of(element.getSimpleName().toString());
        }
      }
    }

    for (final var element : activityTypeElement.getEnclosedElements()) {
      if (element.getKind() != ElementKind.METHOD) continue;

      final var executorAnnotation = element.getAnnotation(ActivityType.EffectModel.class);
      if (executorAnnotation == null) continue;

      if (!(element instanceof ExecutableElement executableElement)) continue;

      final var durationTypeAnnotation = element.getAnnotation(ActivityType.ControllableDuration.class);
      final var durationParameter = Optional.ofNullable(durationTypeAnnotation).map(ActivityType.ControllableDuration::parameterName);
      if (durationParameter.isPresent() && fixedDuration.isPresent()) throw new InvalidMissionModelException("Activity cannot have both FixedDuration and ControllableDuration annotations");
      if (
          (durationParameter.isPresent() ? 1 : 0)
          + (fixedDuration.isPresent() ? 1 : 0)
          + (parameterizedDuration.isPresent() ? 1 : 0)
          > 1
      ) {
        throw new InvalidMissionModelException("Only one duration annotation can be applied to an activity type at a time.");
      }

      final var returnType = executableElement.getReturnType();
      final var nonVoidReturnType = returnType.getKind() == TypeKind.VOID
          ? Optional.<TypeMirror>empty()
          : Optional.of(returnType);

      return Optional.of(new EffectModelRecord(element.getSimpleName().toString(), executorAnnotation.value(), nonVoidReturnType, durationParameter, fixedDuration, parameterizedDuration, maximumDuration));
    }

    return Optional.empty();
  }

  //
  // ANNOTATION PARSING
  //

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

  private Optional<AnnotationMirror> getAnnotationMirrorByType(final Element element, final Class<? extends Annotation> annotationClass)
  {
    final var annotationType = this.elementUtils
        .getTypeElement(annotationClass.getCanonicalName())
        .asType();

    for (final var x : element.getAnnotationMirrors()) {
      if (this.typeUtils.isSameType(annotationType, x.getAnnotationType())) {
        return Optional.of(x);
      }
    }

    return Optional.empty();
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
