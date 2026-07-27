package gov.nasa.ammos.aerie.procedural.examples.bananaprocedures.generated.procedures;

import gov.nasa.ammos.aerie.procedural.examples.bananaprocedures.generated.AutoValueMappers;
import gov.nasa.ammos.aerie.procedural.scheduling.ProcedureMapper;
import gov.nasa.jpl.aerie.contrib.serialization.rulesets.BasicValueMappers;
import gov.nasa.jpl.aerie.merlin.framework.ValueMapper;
import gov.nasa.jpl.aerie.merlin.protocol.model.InputType;
import gov.nasa.jpl.aerie.merlin.protocol.types.InstantiationException;
import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;
import gov.nasa.jpl.aerie.merlin.protocol.types.UnconstructableArgumentException;
import gov.nasa.jpl.aerie.merlin.protocol.types.ValueSchema;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class SampleProcedure implements ProcedureMapper<gov.nasa.ammos.aerie.procedural.examples.bananaprocedures.procedures.SampleProcedure> {
  @Override
  public ValueSchema valueSchema() {
    return AutoValueMappers.gov_nasa_ammos_aerie_procedural_examples_bananaprocedures_procedures_SampleProcedure(
            BasicValueMappers.$int()).getValueSchema();
  }

  @Override
  public InputType<gov.nasa.ammos.aerie.procedural.examples.bananaprocedures.procedures.SampleProcedure> getInputType(
      ) {
    return new InputMapper();
  }

  public final class InputMapper implements InputType<gov.nasa.ammos.aerie.procedural.examples.bananaprocedures.procedures.SampleProcedure> {
    private final ValueMapper<Integer> mapper_quantity;

    @SuppressWarnings("unchecked")
    public InputMapper() {
      this.mapper_quantity =
          BasicValueMappers.$int();
    }

    @Override
    public List<String> getRequiredParameters() {
      return List.of();
    }

    @Override
    public ArrayList<InputType.Parameter> getParameters() {
      final var parameters = new ArrayList<InputType.Parameter>();
      parameters.add(new InputType.Parameter("quantity", this.mapper_quantity.getValueSchema()));
      return parameters;
    }

    @Override
    public Map<String, SerializedValue> getArguments(
        final gov.nasa.ammos.aerie.procedural.examples.bananaprocedures.procedures.SampleProcedure input) {
      final var arguments = new HashMap<String, SerializedValue>();
      arguments.put("quantity", this.mapper_quantity.serializeValue(input.quantity()));
      return arguments;
    }

    @Override
    public gov.nasa.ammos.aerie.procedural.examples.bananaprocedures.procedures.SampleProcedure instantiate(
        final Map<String, SerializedValue> arguments) throws InstantiationException {
      Optional<Integer> quantity = Optional.empty();

      final var instantiationExBuilder = new InstantiationException.Builder("SampleProcedure");

      for (final var entry : arguments.entrySet()) {
        try {
          switch (entry.getKey()) {
            case "quantity":
              quantity = Optional.ofNullable(this.mapper_quantity.deserializeValue(entry.getValue())
                  .getSuccessOrThrow(failure -> new UnconstructableArgumentException("quantity", failure)));
              break;
            default:
              instantiationExBuilder.withExtraneousArgument(entry.getKey());
          }
        } catch (final UnconstructableArgumentException e) {
          instantiationExBuilder.withUnconstructableArgument(e.parameterName, e.failure);
        }
      }

      quantity.ifPresentOrElse(
          value -> instantiationExBuilder.withValidArgument("quantity", this.mapper_quantity.serializeValue(value)),
          () -> instantiationExBuilder.withMissingArgument("quantity", this.mapper_quantity.getValueSchema()));

      instantiationExBuilder.throwIfAny();
      return new gov.nasa.ammos.aerie.procedural.examples.bananaprocedures.procedures.SampleProcedure(quantity.get());
    }

    @Override
    public List<InputType.ValidationNotice> getValidationFailures(
        final gov.nasa.ammos.aerie.procedural.examples.bananaprocedures.procedures.SampleProcedure input) {
      final var notices = new ArrayList<InputType.ValidationNotice>();
      return notices;
    }
  }
}
