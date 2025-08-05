package gov.nasa.jpl.aerie.merlin.processor.generator;

import com.squareup.javapoet.AnnotationSpec;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterSpec;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;
import com.squareup.javapoet.TypeVariableName;
import com.squareup.javapoet.WildcardTypeName;
import gov.nasa.jpl.aerie.merlin.framework.ActivityMapper;
import gov.nasa.jpl.aerie.merlin.framework.ActivityValueMapper;
import gov.nasa.jpl.aerie.merlin.framework.EmptyInputType;
import gov.nasa.jpl.aerie.merlin.framework.ModelActions;
import gov.nasa.jpl.aerie.merlin.framework.Result;
import gov.nasa.jpl.aerie.merlin.framework.ValueMapper;
import gov.nasa.jpl.aerie.merlin.processor.MissionModelProcessor;
import gov.nasa.jpl.aerie.merlin.processor.Resolver;
import gov.nasa.jpl.aerie.merlin.processor.TypePattern;
import gov.nasa.jpl.aerie.merlin.processor.metamodel.ActivityTypeRecord;
import gov.nasa.jpl.aerie.merlin.processor.metamodel.EffectModelRecord;
import gov.nasa.jpl.aerie.merlin.processor.metamodel.InputTypeRecord;
import gov.nasa.jpl.aerie.merlin.processor.metamodel.MissionModelRecord;
import gov.nasa.jpl.aerie.merlin.protocol.driver.Initializer;
import gov.nasa.jpl.aerie.merlin.protocol.driver.Scheduler;
import gov.nasa.jpl.aerie.merlin.protocol.driver.Topic;
import gov.nasa.jpl.aerie.merlin.protocol.model.InputType;
import gov.nasa.jpl.aerie.merlin.protocol.model.MerlinPlugin;
import gov.nasa.jpl.aerie.merlin.protocol.model.ModelType;
import gov.nasa.jpl.aerie.merlin.protocol.model.OutputType;
import gov.nasa.jpl.aerie.merlin.protocol.model.SchedulerModel;
import gov.nasa.jpl.aerie.merlin.protocol.model.SchedulerPlugin;
import gov.nasa.jpl.aerie.merlin.protocol.model.Task;
import gov.nasa.jpl.aerie.merlin.protocol.model.TaskFactory;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import gov.nasa.jpl.aerie.merlin.protocol.types.DurationType;
import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;
import gov.nasa.jpl.aerie.merlin.protocol.types.TaskStatus;
import gov.nasa.jpl.aerie.merlin.protocol.types.Unit;
import gov.nasa.jpl.aerie.merlin.protocol.types.ValueSchema;

import javax.annotation.processing.Generated;
import javax.annotation.processing.Messager;
import javax.lang.model.element.Modifier;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Auto-generates Java source files from mission model metamodels. */
public record MissionModelGenerator(Elements elementUtils, Types typeUtils, Messager messager) {

  private static final String COMPUTED_ATTRIBUTES_VALUE_MAPPER_FIELD_NAME = "computedAttributesValueMapper";

  /** Generate `GeneratedMerlinPlugin` class. */
  public JavaFile generateMerlinPlugin(final MissionModelRecord missionModel) {
    final var typeName = missionModel.getMerlinPluginName();

    final var typeSpec =
        TypeSpec
            .classBuilder(typeName)
            .addAnnotation(
                AnnotationSpec
                    .builder(javax.annotation.processing.Generated.class)
                    .addMember("value", "$S", MissionModelProcessor.class.getCanonicalName())
                    .build())
            .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
            .addSuperinterface(MerlinPlugin.class)
            .addMethod(
                MethodSpec
                    .methodBuilder("getModelType")
                    .addAnnotation(Override.class)
                    .addModifiers(Modifier.PUBLIC)
                    .returns(missionModel.getModelTypeName())
                    .addStatement(
                        "return new $T()",
                        missionModel.getModelTypeName())
                    .build())
            .build();

    return JavaFile
        .builder(typeName.packageName(), typeSpec)
        .skipJavaLangImports(true)
        .build();
  }

  /** Generate `GeneratedSchedulerPlugin` class. */
  public JavaFile generateSchedulerPlugin(final MissionModelRecord missionModel) {
    final var typeName = missionModel.getSchedulerPluginName();

    final var typeSpec =
        TypeSpec
            .classBuilder(typeName)
            .addAnnotation(
                AnnotationSpec
                    .builder(javax.annotation.processing.Generated.class)
                    .addMember("value", "$S", MissionModelProcessor.class.getCanonicalName())
                    .build())
            .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
            .addSuperinterface(SchedulerPlugin.class)
            .addMethod(
                MethodSpec
                    .methodBuilder("getSchedulerModel")
                    .addAnnotation(Override.class)
                    .addModifiers(Modifier.PUBLIC)
                    .returns(missionModel.getSchedulerModelName())
                    .addStatement("return new $T()",
                                  missionModel.getSchedulerModelName())
                    .build())
            .build();

    return JavaFile
        .builder(typeName.packageName(), typeSpec)
        .skipJavaLangImports(true)
        .build();
  }

  /** Generate `ConfigurationMapper` class. */
  public Optional<JavaFile> generateMissionModelConfigurationMapper(final MissionModelRecord missionModel, final InputTypeRecord configType) {
    return generateInputType(missionModel, configType, configType.activityMapper().name.simpleName())
        .map(typeSpec -> JavaFile
            .builder(configType.activityMapper().name.packageName(), typeSpec)
            .skipJavaLangImports(true)
            .build());
  }

  /** Generate `GeneratedModelType` class. */
  public JavaFile generateModelType(final MissionModelRecord missionModel) {
    final var typeName = missionModel.getModelTypeName();

    final var typeSpec =
        TypeSpec
            .classBuilder(typeName)
            .addAnnotation(
                AnnotationSpec
                    .builder(javax.annotation.processing.Generated.class)
                    .addMember("value", "$S", MissionModelProcessor.class.getCanonicalName())
                    .build())
            .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
            .addSuperinterface(
                ParameterizedTypeName.get(
                    ClassName.get(ModelType.class),
                    missionModel.modelConfigurationType()
                        .map($ -> ClassName.get($.declaration()))
                        .orElse(ClassName.get(Unit.class)),
                    ClassName.get(missionModel.topLevelModel())))
            .addMethod(
                MethodSpec
                    .methodBuilder("getDirectiveTypes")
                    .addModifiers(Modifier.PUBLIC)
                    .addAnnotation(Override.class)
                    .returns(
                        ParameterizedTypeName.get(
                            ClassName.get(Map.class),
                            ClassName.get(String.class),
                            ParameterizedTypeName.get(
                                ClassName.get(gov.nasa.jpl.aerie.merlin.framework.ActivityMapper.class),
                                ClassName.get(missionModel.topLevelModel()),
                                WildcardTypeName.subtypeOf(Object.class),
                                WildcardTypeName.subtypeOf(Object.class))))
                    .addStatement(
                        "return $T.$L",
                        missionModel.getTypesName(),
                        "directiveTypes")
                    .build())
            .addMethod(
                MethodSpec
                    .methodBuilder("getConfigurationType")
                    .addModifiers(Modifier.PUBLIC)
                    .addAnnotation(Override.class)
                    .returns(
                        missionModel.modelConfigurationType()
                            .map(configType -> configType.activityMapper().name)
                            .orElse(ClassName.get(EmptyInputType.class)))
                    .addStatement(
                        "return new $T()",
                        missionModel.modelConfigurationType()
                            .map(configType -> configType.activityMapper().name)
                            .orElse(ClassName.get(EmptyInputType.class)))
                .build())
            .addMethod(
                MethodSpec
                    .methodBuilder("instantiate")
                    .addModifiers(Modifier.PUBLIC)
                    .addAnnotation(Override.class)
                    .addParameter(
                        ClassName.get(Instant.class),
                        "planStart",
                        Modifier.FINAL)
                    .addParameter(
                        missionModel.modelConfigurationType()
                            .map($ -> ClassName.get($.declaration()))
                            .orElse(ClassName.get(Unit.class)),
                        "configuration",
                        Modifier.FINAL)
                    .addParameter(
                        ClassName.get(gov.nasa.jpl.aerie.merlin.protocol.driver.Initializer.class),
                        "builder",
                        Modifier.FINAL)
                    .returns(ClassName.get(missionModel.topLevelModel()))
                    .addStatement(
                        "$T.registerTopics($L)",
                        missionModel.getTypesName(),
                        "builder")
                    .addCode("\n")
                    .addStatement(
                        "final var $L = new $T($L)",
                        "registrar",
                        gov.nasa.jpl.aerie.merlin.framework.Registrar.class,
                        "builder")
                    .addCode("\n")
                    .addStatement(
                        "return $T.initializing($L, () -> $L)",
                        gov.nasa.jpl.aerie.merlin.framework.InitializationContext.class,
                        "builder",
                        generateMissionModelInstantiation(missionModel))
                    .build())
            .build();

    return JavaFile
        .builder(typeName.packageName(), typeSpec)
        .skipJavaLangImports(true)
        .build();
  }

  private static CodeBlock generateMissionModelInstantiation(final MissionModelRecord missionModel) {
    final var modelClassName = ClassName.get(missionModel.topLevelModel());
    return missionModel.modelConfigurationType()
        .map(configType -> missionModel.expectsPlanStart() ?
            CodeBlock.of(
                "new $T($L, $L, $L)",
                modelClassName,
                "registrar",
                "planStart",
                "configuration") :
            CodeBlock.of(
                "new $T($L, $L)",
                modelClassName,
                "registrar",
                "configuration"))
        .orElseGet(() -> missionModel.expectsPlanStart() ?
            CodeBlock.of(
                "new $T($L, $L)",
                modelClassName,
                "registrar",
                "planStart") :
            CodeBlock.of(
                "new $T($L)",
                modelClassName,
                "registrar"));
  }

  /** Generate `GeneratedSchedulerModel` class. */
  public JavaFile generateSchedulerModel(final MissionModelRecord missionModel) {
    final var typeName = missionModel.getSchedulerModelName();
    final var durationValueMapperCodeBlock = generateDurationMapperBlock(missionModel);

    final var typeSpec =
        TypeSpec
            .classBuilder(typeName)
            .addAnnotation(
                AnnotationSpec
                    .builder(javax.annotation.processing.Generated.class)
                    .addMember("value", "$S", MissionModelProcessor.class.getCanonicalName())
                    .build())
            .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
            .addSuperinterface(SchedulerModel.class)
            .addMethod(
                MethodSpec
                    .methodBuilder("getDurationTypes")
                    .addModifiers(Modifier.PUBLIC)
                    .addAnnotation(Override.class)
                    .returns(ParameterizedTypeName.get(Map.class, String.class, DurationType.class))
                    .addStatement("final var result = new $T()", ParameterizedTypeName.get(HashMap.class, String.class, DurationType.class))
                    .addCode(
                        missionModel
                            .activityTypes()
                            .stream()
                            .map(
                                activityTypeRecord ->
                                    CodeBlock
                                        .builder()
                                        .addStatement("result.put(\"$L\", $T.$L)",
                                                      activityTypeRecord.name(),
                                                      DurationType.class,
                                                      activityTypeRecord
                                                          .effectModel()
                                                          .map($ -> {
                                                            if ($.durationParameter().isPresent()) return CodeBlock.of("controllable(\"$L\")", $.durationParameter().get());
                                                            else if ($.fixedDurationExpr().isPresent()) return CodeBlock.of("fixed($L.$L)", activityTypeRecord.fullyQualifiedClass(), $.fixedDurationExpr().get());
                                                            else if ($.parametricDuration().isPresent()) return CodeBlock.of("parametric($$ -> (new $L().new InputMapper()).instantiate($$).$L())", activityTypeRecord.inputType().activityMapper().name, $.parametricDuration().get());
                                                            else return CodeBlock.of("uncontrollable()");
                                                          })
                                                          .orElse(CodeBlock.of("fixed($T.ZERO)", ClassName.get(Duration.class)))))
                            .reduce((x, y) -> x.add("$L", y.build()))
                            .orElse(CodeBlock.builder()).build())
                    .addStatement("return result")
                    .build())
            .addMethod(MethodSpec
                           .methodBuilder("getMaximumDurations")
                           .addModifiers(Modifier.PUBLIC)
                           .addAnnotation(Override.class)
                           .returns(ParameterizedTypeName.get(Map.class, String.class, Duration.class))
                           .addStatement("final var result = new $T()", ParameterizedTypeName.get(HashMap.class, String.class, Duration.class))
                           .addCode(
                               missionModel
                                   .activityTypes()
                                   .stream()
                                   .filter(a -> a.effectModel().isPresent())
                                   .filter(a -> a.effectModel().get().maximumDuration().isPresent())
                                   .map(
                                       activityTypeRecord ->
                                           CodeBlock
                                               .builder()
                                               .addStatement("result.put(\"$L\", $L)",
                                                             activityTypeRecord.name(),
                                                             activityTypeRecord
                                                                 .effectModel()
                                                                 .map($ -> CodeBlock.of("$L.$L", activityTypeRecord.fullyQualifiedClass(), $.maximumDuration().get()))
                                                                 .get()))
                                   .reduce((x, y) -> x.add("$L", y.build()))
                                   .orElse(CodeBlock.builder()).build())
                           .addStatement("return result")
                           .build())
            .addMethod(MethodSpec
                           .methodBuilder("serializeDuration")
                           .addModifiers(Modifier.PUBLIC)
                           .addAnnotation(Override.class)
                           .returns(SerializedValue.class)
                           .addParameter(
                               TypeName.get(Duration.class),
                               "duration",
                               Modifier.FINAL)
                           .addStatement(
                               "return $L.serializeValue(duration)", durationValueMapperCodeBlock.get()
                           )
                           .build())
            .addMethod(MethodSpec
                           .methodBuilder("deserializeDuration")
                           .addModifiers(Modifier.PUBLIC)
                           .addAnnotation(Override.class)
                           .addParameter(
                               TypeName.get(SerializedValue.class),
                               "serializedDuration",
                               Modifier.FINAL)
                           .addStatement(
                               "return $L.deserializeValue(serializedDuration).getSuccessOrThrow()", durationValueMapperCodeBlock.get()
                           )
                           .returns(Duration.class)
                           .build())
            .build();

    return JavaFile
        .builder(typeName.packageName(), typeSpec)
        .skipJavaLangImports(true)
        .build();
  }

  /** Generate `ActivityActions` class. */
  public JavaFile generateActivityActions(final MissionModelRecord missionModel) {
    final var typeName = missionModel.getActivityActionsName();

    final var typeSpec =
        TypeSpec
            .classBuilder(typeName)
            // The location of the mission model package determines where to put this class.
            .addOriginatingElement(missionModel.$package())
            // TODO: List found task spec types as dependencies of this generated file.
            .addAnnotation(
                AnnotationSpec
                    .builder(javax.annotation.processing.Generated.class)
                    .addMember("value", "$S", MissionModelProcessor.class.getCanonicalName())
                    .build())
            .addModifiers(Modifier.PUBLIC)
            .superclass(gov.nasa.jpl.aerie.merlin.framework.ModelActions.class)
            .addMethod(MethodSpec.constructorBuilder().addModifiers(Modifier.PRIVATE).build())
            .addMethods(
                missionModel.activityTypes()
                    .stream()
                    .flatMap(entry -> Stream
                        .of(
                            MethodSpec
                                .methodBuilder("spawn")
                                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                                .addParameter(
                                    ClassName.get(missionModel.topLevelModel()),
                                    "model",
                                    Modifier.FINAL)
                                .addParameter(
                                    ClassName.get(entry.inputType().declaration()),
                                    "activity",
                                    Modifier.FINAL)
                                .addStatement(
                                    "final var $L = $T.$L",
                                    "mapper",
                                    missionModel.getTypesName(),
                                    entry.inputType().activityMapper().name.canonicalName().replace(".", "_"))
                                .addStatement(
                                    "$T.spawnWithSpan($L.getTaskFactory($L, $L))",
                                    gov.nasa.jpl.aerie.merlin.framework.ModelActions.class,
                                    "mapper",
                                    "model",
                                    "activity")
                                .build(),
                            MethodSpec
                                .methodBuilder("defer")
                                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                                .addParameter(
                                    ClassName.get(Duration.class),
                                    "duration",
                                    Modifier.FINAL)
                                .addParameter(
                                    ClassName.get(missionModel.topLevelModel()),
                                    "model",
                                    Modifier.FINAL)
                                .addParameter(
                                    ClassName.get(entry.inputType().declaration()),
                                    "activity",
                                    Modifier.FINAL)
                                .addStatement(
                                    "final var $L = $T.$L",
                                    "mapper",
                                    missionModel.getTypesName(),
                                    entry.inputType().activityMapper().name.canonicalName().replace(".", "_"))
                                .addStatement(
                                    "$T.deferWithSpan($L, $L.getTaskFactory($L, $L))",
                                    gov.nasa.jpl.aerie.merlin.framework.ModelActions.class,
                                    "duration",
                                    "mapper",
                                    "model",
                                    "activity")
                                .build(),
                            MethodSpec
                                .methodBuilder("defer")
                                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                                .addParameter(
                                    ParameterSpec
                                        .builder(
                                            TypeName.LONG,
                                            "quantity")
                                        .addModifiers(Modifier.FINAL)
                                        .build())
                                .addParameter(
                                    ParameterSpec
                                        .builder(
                                            gov.nasa.jpl.aerie.merlin.protocol.types.Duration.class,
                                            "unit")
                                        .addModifiers(Modifier.FINAL)
                                        .build())
                                .addParameter(
                                    ClassName.get(missionModel.topLevelModel()),
                                    "model",
                                    Modifier.FINAL)
                                .addParameter(
                                    ClassName.get(entry.inputType().declaration()),
                                    "activity",
                                    Modifier.FINAL)
                                .addStatement(
                                    "defer($L.times($L), $L, $L)",
                                    "unit",
                                    "quantity",
                                    "model",
                                    "activity")
                                .build(),
                            MethodSpec
                                .methodBuilder("call")
                                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                                .addParameter(
                                    ClassName.get(missionModel.topLevelModel()),
                                    "model",
                                    Modifier.FINAL)
                                .addParameter(
                                    ClassName.get(entry.inputType().declaration()),
                                    "activity",
                                    Modifier.FINAL)
                                .addStatement(
                                    "final var $L = $T.$L",
                                    "mapper",
                                    missionModel.getTypesName(),
                                    entry.inputType().activityMapper().name.canonicalName().replace(".", "_"))
                                .addStatement(
                                    "$T.callWithSpan($L.getTaskFactory($L, $L))",
                                    gov.nasa.jpl.aerie.merlin.framework.ModelActions.class,
                                    "mapper",
                                    "model",
                                    "activity")
                                .build()))
                    .collect(Collectors.toList()))
            .build();

    return JavaFile
        .builder(typeName.packageName(), typeSpec)
        .skipJavaLangImports(true)
        .build();
  }

  /** Generate `ActivityTypes` class. */
  public JavaFile generateActivityTypes(final MissionModelRecord missionModel) {
    final var typeName = missionModel.getTypesName();

    final var typeSpec =
        TypeSpec
            .classBuilder(typeName)
            .addAnnotation(
                AnnotationSpec
                    .builder(javax.annotation.processing.Generated.class)
                    .addMember("value", "$S", MissionModelProcessor.class.getCanonicalName())
                    .build())
            .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
            .addFields(
                missionModel.activityTypes()
                    .stream()
                    .map(activityType -> FieldSpec
                        .builder(
                            activityType.inputType().activityMapper().name,
                            activityType.inputType().activityMapper().name.canonicalName().replace(".", "_"),
                            Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                        .initializer("new $T()", activityType.inputType().activityMapper().name)
                        .build())
                    .toList())
            .addField(
                FieldSpec
                    .builder(
                        ParameterizedTypeName.get(
                            ClassName.get(Map.class),
                            ClassName.get(String.class),
                            ParameterizedTypeName.get(
                                ClassName.get(gov.nasa.jpl.aerie.merlin.framework.ActivityMapper.class),
                                ClassName.get(missionModel.topLevelModel()),
                                WildcardTypeName.subtypeOf(Object.class),
                                WildcardTypeName.subtypeOf(Object.class))),
                        "directiveTypes",
                        Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                    .initializer(
                        "$T.ofEntries($>$>$L$<$<)",
                        ClassName.get(Map.class),
                        missionModel.activityTypes()
                            .stream()
                            .map(activityType -> CodeBlock
                                .builder()
                                .add(
                                    "\n$T.entry($S, $L)",
                                    ClassName.get(Map.class),
                                    activityType.name(),
                                    activityType.inputType().activityMapper().name.canonicalName().replace(".", "_")))
                            .reduce((x, y) -> x.add(",").add(y.build()))
                            .orElse(CodeBlock.builder())
                            .build())
                    .build())
            .addMethod(
                MethodSpec
                    .methodBuilder("registerTopics")
                    .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                    .addParameter(TypeName.get(Initializer.class), "initializer", Modifier.FINAL)
                    .addStatement(
                        "$L.forEach((name, mapper) -> registerDirectiveType($L, name, mapper))",
                        "directiveTypes",
                        "initializer")
                    .build())
            .addMethod(
                MethodSpec
                    .methodBuilder("registerDirectiveType")
                    .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
                    .addTypeVariable(TypeVariableName.get("Input"))
                    .addTypeVariable(TypeVariableName.get("Output"))
                    .addParameter(ClassName.get(Initializer.class), "initializer", Modifier.FINAL)
                    .addParameter(ClassName.get(String.class), "name", Modifier.FINAL)
                    .addParameter(
                        ParameterizedTypeName.get(
                            ClassName.get(ActivityMapper.class),
                            ClassName.get(missionModel.topLevelModel()),
                            TypeVariableName.get("Input"),
                            TypeVariableName.get("Output")),
                        "mapper",
                        Modifier.FINAL)
                    .addStatement(
                        "$L.topic($L, $L, $L)",
                        "initializer",
                        CodeBlock.of("$S + $L", "ActivityType.Input.", "name"),
                        CodeBlock.of("$L.getInputTopic()", "mapper"),
                        CodeBlock.of("$L.getInputAsOutput()", "mapper"))
                    .addStatement(
                        "$L.topic($L, $L, $L)",
                        "initializer",
                        CodeBlock.of("$S + $L", "ActivityType.Output.", "name"),
                        CodeBlock.of("$L.getOutputTopic()", "mapper"),
                        CodeBlock.of("$L.getOutputType()", "mapper"))
                    .build()
            )
            .build();

    return JavaFile
        .builder(typeName.packageName(), typeSpec)
        .skipJavaLangImports(true)
        .build();
  }

  private record ComputedAttributesCodeBlocks(TypeName typeName, FieldSpec fieldDef) {}

  /** Generate an `InputType` implementation. */
  public Optional<TypeSpec> generateInputType(final MissionModelRecord missionModel, final InputTypeRecord inputType, final String name) {
    final var mapperBlocks$ = generateParameterMapperBlocks(missionModel, inputType);
    if (mapperBlocks$.isEmpty()) return Optional.empty();
    final var mapperBlocks = mapperBlocks$.get();

    final var mapperMethodMaker = MapperMethodMaker.make(inputType);

    return Optional.of(TypeSpec
        .classBuilder(name)
        // The location of the missionModel package determines where to put this class.
        .addOriginatingElement(missionModel.$package())
        // The fields and methods of the activity determines the overall behavior of this class.
        .addOriginatingElement(inputType.declaration())
        // TODO: Add an originating element for each of the mapper rulesets associated with the mission model.
        .addAnnotation(
            AnnotationSpec
                .builder(javax.annotation.processing.Generated.class)
                .addMember("value", "$S", MissionModelProcessor.class.getCanonicalName())
                .build())
        .addSuperinterface(ParameterizedTypeName.get(
            ClassName.get(gov.nasa.jpl.aerie.merlin.protocol.model.InputType.class),
            ClassName.get(inputType.declaration())))
        .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
        .addFields(
            inputType.parameters()
                .stream()
                .map(parameter -> FieldSpec
                    .builder(
                        ParameterizedTypeName.get(
                            ClassName.get(gov.nasa.jpl.aerie.merlin.framework.ValueMapper.class),
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

  /** Generate `ActivityValueMappers` class. */
  public JavaFile generateActivityValueMappers(final MissionModelRecord missionModel) {
    final var typeName = missionModel.getTypesName();

    final var typeSpec =
        TypeSpec
            .classBuilder("ActivityValueMappers")
            .addAnnotation(
                AnnotationSpec
                    .builder(javax.annotation.processing.Generated.class)
                    .addMember("value", "$S", MissionModelProcessor.class.getCanonicalName())
                    .build())
            .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
            .addMethods(
                missionModel.activityTypes()
                            .stream()
                            .map(activityType -> MethodSpec
                                .methodBuilder(activityType.inputType().valueMapper().name.canonicalName().replace(".", "_"))
                                     .returns(ParameterizedTypeName.get(
                                             ClassName.get(gov.nasa.jpl.aerie.merlin.framework.ValueMapper.class),
                                             TypeName.get(activityType.inputType().declaration().asType())))
                                .addModifiers(Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                                .addCode(CodeBlock.builder().add("return new $T(new $T(), $S);",
                                                                 ParameterizedTypeName.get(
                                                                     ClassName.get(gov.nasa.jpl.aerie.merlin.framework.ActivityValueMapper.class),
                                                                     TypeName.get(activityType.inputType().declaration().asType())),
                                                                 activityType.inputType().activityMapper().name,
                                                                 activityType.name()).build())
                                .build())
                            .toList()
            )
            .build();

    return JavaFile
        .builder(typeName.packageName(), typeSpec)
        .skipJavaLangImports(true)
        .build();
  }

  private Optional<ComputedAttributesCodeBlocks> getComputedAttributesCodeBlocks(
      final MissionModelRecord missionModel,
      final ActivityTypeRecord activityType)
  {
    final Optional<CodeBlock> effectModelReturnMapperBlock;
    final TypeName computedAttributesTypeName;
    final Optional<EffectModelRecord> effectModel;
    effectModel = activityType.effectModel();
    final var typeMirror = effectModel.flatMap(EffectModelRecord::returnType);
    if (typeMirror.isPresent()) {
      effectModelReturnMapperBlock = new Resolver(this.typeUtils, this.elementUtils, missionModel.typeRules())
                  .instantiateNullableMapperFor(typeMirror.get());
      if (effectModelReturnMapperBlock.isEmpty()) {
        this.messager.printMessage(
            Diagnostic.Kind.ERROR,
            "Failed to generate value mapper for effect model return type "
            + typeMirror.get()
            + " of activity "
            + activityType.name());
        return Optional.empty();
      }
      computedAttributesTypeName = TypeName.get(typeMirror.get());
    } else {
      effectModelReturnMapperBlock = new Resolver(
          this.typeUtils, this.elementUtils, missionModel.typeRules()).applyRules(
          new TypePattern.ClassPattern(ClassName.get(ValueMapper.class),
                                       List.of(new TypePattern.ClassPattern(ClassName.get(Unit.class), List.of()))));
      computedAttributesTypeName = TypeName.get(Unit.class);
    }
    return Optional.of(new ComputedAttributesCodeBlocks(
        computedAttributesTypeName,
        FieldSpec
            .builder(
                ParameterizedTypeName.get(
                    ClassName.get(ValueMapper.class),
                    computedAttributesTypeName.box()),
                COMPUTED_ATTRIBUTES_VALUE_MAPPER_FIELD_NAME)
            .addModifiers(Modifier.PRIVATE, Modifier.FINAL)
            .initializer(effectModelReturnMapperBlock.get())
            .build()));
  }

  /** Generate `${activity_name}Mapper` class. */
  public Optional<JavaFile> generateActivityMapper(final MissionModelRecord missionModel, final ActivityTypeRecord activityType) {
    final var computedAttributesCodeBlocks$ = this.getComputedAttributesCodeBlocks(missionModel, activityType);
    if (computedAttributesCodeBlocks$.isEmpty()) return Optional.empty();

    final var inputTypeMapper$ = generateInputType(missionModel, activityType.inputType(), "InputMapper");
    if (inputTypeMapper$.isEmpty()) return Optional.empty();
    final var inputTypeMapper = inputTypeMapper$.get();
    final var computedAttributesCodeBlocks = computedAttributesCodeBlocks$.get();
    final var typeSpec = TypeSpec
        .classBuilder(activityType.inputType().activityMapper().name)
        // The location of the missionModel package determines where to put this class.
        .addOriginatingElement(missionModel.$package())
        // The fields and methods of the activity determines the overall behavior of this class.
        .addOriginatingElement(activityType.inputType().declaration())
        // TODO: Add an originating element for each of the mapper rulesets associated with the mission model.
        .addAnnotation(
            AnnotationSpec
                .builder(Generated.class)
                .addMember("value", "$S", MissionModelProcessor.class.getCanonicalName())
                .build())
        .addSuperinterface(ParameterizedTypeName.get(
            ClassName.get(ActivityMapper.class),
            ClassName.get(missionModel.topLevelModel()),
            ClassName.get(activityType.inputType().declaration()),
            computedAttributesCodeBlocks.typeName().box()))
        .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
        .addType(inputTypeMapper)
        .addType(TypeSpec
            .classBuilder("OutputMapper")
            .addSuperinterface(ParameterizedTypeName.get(
                ClassName.get(OutputType.class),
                computedAttributesCodeBlocks.typeName().box()))
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
            .addField(computedAttributesCodeBlocks.fieldDef())
            .addMethod(makeGetReturnValueSchemaMethod())
            .addMethod(makeSerializeReturnValueMethod(activityType))
            .build())
        .addField(
            FieldSpec
                .builder(
                    ParameterizedTypeName.get(
                        ClassName.get(Topic.class),
                        TypeName.get(activityType.inputType().declaration().asType())),
                    "inputTopic",
                    Modifier.PRIVATE,
                    Modifier.FINAL)
                .initializer("new $T<>()", ClassName.get(Topic.class))
                .build())
        .addField(
            FieldSpec
                .builder(
                    ParameterizedTypeName.get(
                        ClassName.get(Topic.class),
                        activityType.getOutputTypeName()),
                    "outputTopic",
                    Modifier.PRIVATE,
                    Modifier.FINAL)
                .initializer("new $T<>()", ClassName.get(Topic.class))
                .build())
        .addMethod(MethodSpec
            .methodBuilder("getInputType")
            .addModifiers(Modifier.PUBLIC)
            .addAnnotation(Override.class)
            .returns(ParameterizedTypeName.get(
                ClassName.get(InputType.class),
                ClassName.get(activityType.inputType().declaration())))
            .addStatement("return new $T()", activityType.inputType().activityMapper().name.nestedClass(inputTypeMapper.name))
            .build())
        .addMethod(MethodSpec
            .methodBuilder("getOutputType")
            .addModifiers(Modifier.PUBLIC)
            .addAnnotation(Override.class)
            .returns(ParameterizedTypeName.get(
                ClassName.get(OutputType.class),
                computedAttributesCodeBlocks.typeName().box()))
            .addStatement("return new $T()", activityType.inputType().activityMapper().name.nestedClass("OutputMapper"))
            .build())
        .addMethod(
            MethodSpec
                .methodBuilder("getInputTopic")
                .addModifiers(Modifier.PUBLIC)
                .addAnnotation(Override.class)
                .returns(ParameterizedTypeName.get(
                    ClassName.get(Topic.class),
                    TypeName.get(activityType.inputType().declaration().asType())))
                .addStatement("return this.$L", "inputTopic")
                .build())
        .addMethod(
            MethodSpec
                .methodBuilder("getOutputTopic")
                .addModifiers(Modifier.PUBLIC)
                .addAnnotation(Override.class)
                .returns(ParameterizedTypeName.get(
                    ClassName.get(Topic.class),
                    activityType.getOutputTypeName()))
                .addStatement("return this.$L", "outputTopic")
                .build())
        .addMethod(
            MethodSpec
                .methodBuilder("getTaskFactory")
                .addModifiers(Modifier.PUBLIC)
                .addAnnotation(Override.class)
                .returns(ParameterizedTypeName.get(
                    ClassName.get(TaskFactory.class),
                    activityType.getOutputTypeName()))
                .addParameter(
                    ClassName.get(missionModel.topLevelModel()),
                    "model",
                    Modifier.FINAL)
                .addParameter(
                    TypeName.get(activityType.inputType().declaration().asType()),
                    "activity",
                    Modifier.FINAL)
                .addCode(
                    activityType.effectModel()
                        .map(effectModel -> CodeBlock
                            .builder()
                            .add(
                                "return $T.$L(() -> {$>\n$L$<});\n",
                                ModelActions.class,
                                switch (effectModel.executor()) {
                                  case Threaded -> "threaded";
                                  case Replaying -> "replaying";
                                },
                                effectModel.returnType()
                                    .map(returnType -> CodeBlock
                                        .builder()
                                        .addStatement("$T.emit($L, this.$L)", ModelActions.class, "activity", "inputTopic")
                                        .addStatement("final var result = $L.$L($L)", "activity", effectModel.methodName(), "model")
                                        .addStatement("$T.emit(result, this.$L)", ModelActions.class, "outputTopic")
                                        .addStatement("return result")
                                        .build())
                                    .orElseGet(() -> CodeBlock
                                        .builder()
                                        .addStatement("$T.emit($L, this.$L)", ModelActions.class, "activity", "inputTopic")
                                        .addStatement("$L.$L($L)", "activity", effectModel.methodName(), "model")
                                        .addStatement("$T.emit($T.UNIT, this.$L)", ModelActions.class, Unit.class, "outputTopic")
                                        .addStatement("return $T.UNIT", Unit.class)
                                        .build()))
                            .build())
                        .orElseGet(() -> CodeBlock
                            .builder()
                            .add(
                                "return executor -> new $T<>() { public $T<$T> step($T scheduler) {$>\n$L$<} public $T<$T> duplicate($T executor) { return this; }};\n",
                                Task.class,
                                TaskStatus.class,
                                Unit.class,
                                Scheduler.class,
                                CodeBlock.builder()
                                    .addStatement("scheduler.emit($L, $L.this.$L)", "activity", activityType.inputType().mapper().name, "inputTopic")
                                    .addStatement("scheduler.emit($T.UNIT, $L.this.$L)", Unit.class, activityType.inputType().mapper().name, "outputTopic")
                                    .addStatement("return $T.completed($T.UNIT)", TaskStatus.class, Unit.class)
                                    .build(),
                                Task.class,
                                Unit.class,
                                Executor.class)
                            .build()))
                .build())
        .build();

    return Optional.of(JavaFile
        .builder(activityType.inputType().activityMapper().name.packageName(), typeSpec)
        .skipJavaLangImports(true)
        .build());
  }

  private static MethodSpec makeGetReturnValueSchemaMethod() {
    return MethodSpec.methodBuilder("getSchema")
                     .addModifiers(Modifier.PUBLIC)
                     .addAnnotation(Override.class)
                     .returns(ValueSchema.class)
                     .addStatement("return this." + COMPUTED_ATTRIBUTES_VALUE_MAPPER_FIELD_NAME + ".getValueSchema()")
                     .build();
  }

  private static MethodSpec makeSerializeReturnValueMethod(final ActivityTypeRecord activityType) {
    return MethodSpec.methodBuilder("serialize")
                     .addModifiers(Modifier.PUBLIC)
                     .addAnnotation(Override.class)
                     .returns(SerializedValue.class)
                     .addParameter(
                         activityType.effectModel()
                             .flatMap(EffectModelRecord::returnType)
                             .map(TypeName::get)
                             .orElse(TypeName.get(Unit.class)).box(),
                         "returnValue",
                         Modifier.FINAL)
                     .addStatement(
                         "return this." + COMPUTED_ATTRIBUTES_VALUE_MAPPER_FIELD_NAME + ".serializeValue(returnValue)")
                     .build();
  }
  private Optional<CodeBlock> generateDurationMapperBlock(final MissionModelRecord missionModel){
    final var resolver = new Resolver(this.typeUtils, this.elementUtils, missionModel.typeRules());
    final var mapperBlock = resolver.instantiateNullableMapperFor(
        elementUtils.getTypeElement(Duration.class.getName()).asType());
    if (mapperBlock.isPresent()) {
      return mapperBlock;
    } else {
      messager.printMessage(
          Diagnostic.Kind.ERROR,
          "Failed to generate value mapper for Duration");
      return Optional.empty();
    }
  }
  private Optional<Map<String, CodeBlock>> generateParameterMapperBlocks(final MissionModelRecord missionModel, final InputTypeRecord inputType)
  {
    final var resolver = new Resolver(this.typeUtils, this.elementUtils, missionModel.typeRules());
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
}
