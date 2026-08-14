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
import java.util.stream.Collectors;

/** Method maker for defaults style where no default arguments are provided (for example, a record class). */
/*package-private*/ final class NoneDefinedMethodMaker extends MapperMethodMaker {

  public NoneDefinedMethodMaker(final InputTypeRecord inputType) {
    super(inputType);
  }

  @Override
  public MethodSpec makeInstantiateMethod() {
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
                    ".empty()"
                )
            )
            .reduce(CodeBlock.builder(), (x, y) -> x.add(y.build()))
            .build()).addCode("\n");

    methodBuilder = makeArgumentAssignments(methodBuilder, (builder, parameter) -> builder
        .addStatement(
            "$L = Optional.ofNullable(this.mapper_$L.deserializeValue($L.getValue())$W.getSuccessOrThrow(failure -> new $T(\"$L\", failure)))",
            parameter.name,
            parameter.name,
            "entry",
            UnconstructableArgumentException.class,
            parameter.name));

    // Add return statement with instantiation of class with parameters
    methodBuilder = methodBuilder.addStatement(
        "return new $T($L)",
        inputType.declaration(),
        inputType.parameters().stream().map(
            parameter -> parameter.name + ".get()").collect(Collectors.joining(", ")));

    return methodBuilder.build();
  }
}
