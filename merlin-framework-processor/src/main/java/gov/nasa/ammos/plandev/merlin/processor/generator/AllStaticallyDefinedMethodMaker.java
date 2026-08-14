package gov.nasa.jpl.aerie.merlin.processor.generator;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeName;
import gov.nasa.jpl.aerie.merlin.framework.annotations.Export;
import gov.nasa.jpl.aerie.merlin.processor.TypePattern;
import gov.nasa.jpl.aerie.merlin.processor.metamodel.InputTypeRecord;
import gov.nasa.jpl.aerie.merlin.protocol.types.InstantiationException;
import gov.nasa.jpl.aerie.merlin.protocol.types.UnconstructableArgumentException;

import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/** Method maker for defaults style where all default arguments are provided within a @Template static method. */
/*package-private*/ final class AllStaticallyDefinedMethodMaker extends MapperMethodMaker {

  public AllStaticallyDefinedMethodMaker(final InputTypeRecord inputType) {
    super(inputType);
  }

  @Override
  public MethodSpec makeInstantiateMethod() {
    final var activityTypeName = inputType.declaration().getSimpleName().toString();

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

    for (final var element : inputType.declaration().getEnclosedElements()) {
      if (element.getKind() != ElementKind.METHOD && element.getKind() != ElementKind.CONSTRUCTOR) continue;
      if (element.getAnnotation(Export.Template.class) == null) continue;
      var templateName = element.getSimpleName().toString();
      methodBuilder = methodBuilder.addStatement("final var template = $L.$L()", activityTypeName, templateName);

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
                      ".ofNullable(template." + parameter.name + "())"
                  )
              )
              .reduce(CodeBlock.builder(), (x, y) -> x.add(y.build()))
              .build()).addCode("\n");

        methodBuilder = makeArgumentAssignments(methodBuilder, (builder, parameter) -> builder
            .addStatement(
                "$L = $L(this.mapper_$L.deserializeValue($L.getValue())$W.getSuccessOrThrow(failure -> new $T(\"$L\", failure)))",
                parameter.name,
                "Optional.ofNullable",
                parameter.name,
                "entry",
                UnconstructableArgumentException.class,
                parameter.name));
      break;
    }

    // Add return statement with instantiation of class with parameters
    methodBuilder = methodBuilder.addStatement(
        "return new $T($L)",
        inputType.declaration(),
        inputType.parameters().stream().map(
            parameter -> parameter.name + ".get()").collect(Collectors.joining(", ")));

    return methodBuilder.build();
  }
}
