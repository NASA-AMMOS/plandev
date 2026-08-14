package gov.nasa.jpl.aerie.merlin.processor.generator;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeName;
import gov.nasa.jpl.aerie.merlin.processor.TypePattern;
import gov.nasa.jpl.aerie.merlin.processor.metamodel.InputTypeRecord;
import gov.nasa.jpl.aerie.merlin.protocol.types.InstantiationException;
import gov.nasa.jpl.aerie.merlin.protocol.types.UnconstructableArgumentException;

import javax.lang.model.element.Modifier;
import java.util.List;
import java.util.Optional;

/** Method maker for defaults style where all default arguments are provided within @Parameter annotations. */
/*package-private*/ final class AllDefinedMethodMaker extends MapperMethodMaker {

  public AllDefinedMethodMaker(final InputTypeRecord inputType) {
    super(inputType);
  }

  @Override
  public MethodSpec makeInstantiateMethod() {
    // Create instantiate Method header
    var methodBuilder = MethodSpec.methodBuilder("instantiate")
        .addModifiers(Modifier.PUBLIC)
        .addAnnotation(Override.class)
        .returns(TypeName.get(inputType.declaration().asType()))
        .addException(InstantiationException.class)
        .addParameter(
            ParameterizedTypeName.get(
                java.util.Map.class,
                String.class,
                gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue.class),
            "arguments",
            Modifier.FINAL);

    methodBuilder = methodBuilder.addStatement(
        "final var template = new $T()",
        TypeName.get(inputType.declaration().asType()));

    methodBuilder = methodBuilder.addCode(
        inputType.parameters()
            .stream()
            .map(parameter -> CodeBlock
                .builder()
                .addStatement(
                    "$T $L = $T$L",
                    new TypePattern.ClassPattern(
                        ClassName.get(Optional.class),
                        List.of(TypePattern.from(parameter.type))).render(),
                    parameter.name,
                    Optional.class,
                    ".ofNullable(template." + parameter.name + ")"
                )
            )
            .reduce(CodeBlock.builder(), (x, y) -> x.add(y.build()))
            .build()).addCode("\n");

    methodBuilder = makeArgumentAssignments(methodBuilder, (builder, parameter) -> builder
        .addStatement(
            "$L = $T.ofNullable(template.$L = this.mapper_$L.deserializeValue($L.getValue())$W.getSuccessOrThrow(failure -> new $T(\"$L\", failure)))",
            parameter.name,
            Optional.class,
            parameter.name,
            parameter.name,
            "entry",
            UnconstructableArgumentException.class,
            parameter.name));

    return methodBuilder.addStatement("return template").build();
  }

  @Override
  public MethodSpec makeGetArgumentsMethod() {
    return MethodSpec
        .methodBuilder("getArguments")
        .addModifiers(Modifier.PUBLIC)
        .addAnnotation(Override.class)
        .returns(ParameterizedTypeName.get(
            java.util.Map.class,
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
                java.util.HashMap.class,
                String.class,
                gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue.class))
        .addCode(
            inputType.parameters()
                .stream()
                .map(parameter -> CodeBlock
                    .builder()
                    .addStatement(
                        "$L.put($S, this.mapper_$L.serializeValue($L.$L))",
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
}
