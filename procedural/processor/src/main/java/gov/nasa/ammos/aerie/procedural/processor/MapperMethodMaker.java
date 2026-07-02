package gov.nasa.ammos.aerie.procedural.processor;

import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeName;
import gov.nasa.jpl.aerie.merlin.protocol.model.InputType;
import gov.nasa.jpl.aerie.merlin.protocol.types.UnconstructableArgumentException;

import javax.lang.model.element.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

/**
 * Mapper method generator for all export types (activities and configurations).
 * Generates common methods between all export types.
 */
public abstract sealed class MapperMethodMaker
    permits AllStaticallyDefinedMethodMaker,
    NoneDefinedMethodMaker, SomeStaticallyDefinedMethodMaker
{
  /*package-private*/ final InputTypeRecord inputType;

  public MapperMethodMaker(final InputTypeRecord inputType) {
    this.inputType = inputType;
  }

  public abstract MethodSpec makeInstantiateMethod();

  public /*non-final*/ List<String> getParametersWithDefaults() {
    return inputType.parameters().stream().map(p -> p.name).toList();
  }

  public /*non-final*/ MethodSpec makeGetParametersMethod() {
    return MethodSpec.methodBuilder("getParameters")
        .addModifiers(Modifier.PUBLIC)
        .addAnnotation(Override.class)
        .returns(ParameterizedTypeName.get(
            ArrayList.class,
            InputType.Parameter.class))
        .addStatement(
            "final var $L = new $T()",
            "parameters",
            ParameterizedTypeName.get(
                ArrayList.class,
                InputType.Parameter.class))
        .addCode(
            inputType.parameters()
                .stream()
                .map(parameter -> CodeBlock
                    .builder()
                    .addStatement(
                        "$L.add(new $T($S, this.mapper_$L.getValueSchema()))",
                        "parameters",
                        InputType.Parameter.class,
                        parameter.name,
                        parameter.name))
                .reduce(CodeBlock.builder(), (x, y) -> x.add(y.build()))
                .build())
        .addStatement(
            "return $L",
            "parameters")
        .build();
  }

  public /*non-final*/ MethodSpec makeGetArgumentsMethod() {
    return MethodSpec
        .methodBuilder("getArguments")
        .addModifiers(Modifier.PUBLIC)
        .addAnnotation(Override.class)
        .returns(ParameterizedTypeName.get(
            Map.class,
            String.class,
            gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue.class))
        .addParameter(
            TypeName.get(inputType.declaration().asType()),
            "input",
            Modifier.FINAL)
        .addStatement(
            "final var $L = new $T()",
            "arguments",
            ParameterizedTypeName.get(
                java.util.LinkedHashMap.class,
                String.class,
                gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue.class))
        .addCode(
            inputType.parameters()
                .stream()
                .map(parameter -> CodeBlock
                    .builder()
                    .addStatement(
                        "$L.put($S, this.mapper_$L.serializeValue($L.$L()))",
                        "arguments",
                        parameter.name,
                        parameter.name,
                        "input",
                        parameter.name
                    ))
                .reduce(CodeBlock.builder(), (x, y) -> x.add(y.build()))
                .build())
        .addStatement(
            "return $L",
            "arguments")
        .build();
  }

  public final MethodSpec makeGetRequiredParametersMethod() {
    final var optionalParams = getParametersWithDefaults();
    final var requiredParams = inputType.parameters().stream().filter(p -> !optionalParams.contains(p.name)).toList();

    return MethodSpec.methodBuilder("getRequiredParameters")
        .addModifiers(Modifier.PUBLIC)
        .addAnnotation(Override.class)
        .returns(ParameterizedTypeName.get(
            List.class,
            String.class))
        .addStatement(
            "return $T.of($L)",
            List.class,
            requiredParams.stream().map(p -> "\"%s\"".formatted(p.name)).collect(Collectors.joining(", ")))
        .build();
  }

  protected final MethodSpec.Builder makeArgumentAssignments(
      final MethodSpec.Builder methodBuilder,
      final BiFunction<CodeBlock.Builder, ParameterRecord, CodeBlock.Builder> makeArgumentAssignment)
  {
    var mb = methodBuilder;

    // Condition must be checked for otherwise a try/catch without an exception thrown
    // will not pass compilation
    final var shouldExpectArguments = !inputType.parameters().isEmpty();

    mb = mb
        .addStatement(
            "final var $L = new $T(\"$L\")",
            "instantiationExBuilder",
            gov.nasa.jpl.aerie.merlin.protocol.types.InstantiationException.Builder.class,
            inputType.name())
        .addCode("\n")
        .beginControlFlow(
            "for (final var $L : $L.entrySet())",
            "entry",
            "arguments");

    if (shouldExpectArguments) {
        mb = mb.beginControlFlow("try");
    }

    mb = mb
        .beginControlFlow("switch ($L.getKey())", "entry")
        .addCode(
            inputType.parameters()
                .stream()
                .map(parameter -> {
                    final var caseBuilder = CodeBlock.builder()
                        .add("case $S:\n", parameter.name)
                        .indent();
                    return makeArgumentAssignment.apply(caseBuilder, parameter)
                        .addStatement("break")
                        .unindent();
                })
                .reduce(CodeBlock.builder(), (x, y) -> x.add(y.build()))
                .build())
        .addCode(
            CodeBlock
                .builder()
                .add("default:\n")
                .indent()
                .addStatement(
                    "$L.withExtraneousArgument($L.getKey())",
                    "instantiationExBuilder",
                    "entry")
                .unindent()
                .build())
        .endControlFlow();

    if (shouldExpectArguments) {
      mb = mb
          .nextControlFlow("catch (final $T e)", UnconstructableArgumentException.class)
          .addStatement(
              "$L.withUnconstructableArgument(e.parameterName, e.failure)",
              "instantiationExBuilder"
          )
          .endControlFlow();
    }

    mb = mb
        .endControlFlow()
        .addCode("\n");

    return makeMissingArgumentsCheck(mb);
  }

  public final MethodSpec makeGetValidationFailuresMethod() {
    return MethodSpec
        .methodBuilder("getValidationFailures")
        .addModifiers(Modifier.PUBLIC)
        .addAnnotation(Override.class)
        .returns(ParameterizedTypeName.get(
            java.util.List.class,
            InputType.ValidationNotice.class))
        .addParameter(
            TypeName.get(inputType.declaration().asType()),
            "input",
            Modifier.FINAL)
        .addStatement(
            "final var $L = new $T()",
            "notices",
            ParameterizedTypeName.get(
                java.util.ArrayList.class,
                InputType.ValidationNotice.class))
        .addStatement(
            "return $L",
            "notices")
        .build();
  }

  private MethodSpec.Builder makeMissingArgumentsCheck(final MethodSpec.Builder methodBuilder) {
    // Ensure all parameters are non-null
    return methodBuilder
        .addCode(
            inputType.parameters()
                .stream()
                .map(parameter -> CodeBlock
                    .builder()
                    .addStatement(
                        // Re-serialize value since provided `arguments` map may not contain the value (when using `@WithDefaults` templates)
                        "$L.ifPresentOrElse($Wvalue -> $L.withValidArgument(\"$L\", this.mapper_$L.serializeValue(value)),$W() -> $L.withMissingArgument(\"$L\", this.mapper_$L.getValueSchema()))",
                        parameter.name,
                        "instantiationExBuilder",
                        parameter.name,
                        parameter.name,
                        "instantiationExBuilder",
                        parameter.name,
                        parameter.name))
                .reduce(CodeBlock.builder(), (x, y) -> x.add(y.build()))
            .build())
        .addCode("\n")
        .addStatement(
            "$L.throwIfAny()",
            "instantiationExBuilder");
  }

  static MapperMethodMaker make(final InputTypeRecord inputType) {
    return switch (inputType.defaultsStyle()) {
      case AllStaticallyDefined -> new AllStaticallyDefinedMethodMaker(inputType);
      case NoneDefined -> new NoneDefinedMethodMaker(inputType);
      case SomeStaticallyDefined -> new SomeStaticallyDefinedMethodMaker(inputType);
    };
  }
}
