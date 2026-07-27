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

public final class StayWellFed implements ProcedureMapper<gov.nasa.ammos.aerie.procedural.examples.bananaprocedures.procedures.StayWellFed> {
  @Override
  public ValueSchema valueSchema() {
    return AutoValueMappers.gov_nasa_ammos_aerie_procedural_examples_bananaprocedures_procedures_StayWellFed(
            BasicValueMappers.$double()).getValueSchema();
  }

  @Override
  public InputType<gov.nasa.ammos.aerie.procedural.examples.bananaprocedures.procedures.StayWellFed> getInputType(
      ) {
    return new InputMapper();
  }

  public final class InputMapper implements InputType<gov.nasa.ammos.aerie.procedural.examples.bananaprocedures.procedures.StayWellFed> {
    private final ValueMapper<Double> mapper_bitePeriodHours;

    @SuppressWarnings("unchecked")
    public InputMapper() {
      this.mapper_bitePeriodHours =
          BasicValueMappers.$double();
    }

    @Override
    public List<String> getRequiredParameters() {
      return List.of();
    }

    @Override
    public ArrayList<InputType.Parameter> getParameters() {
      final var parameters = new ArrayList<InputType.Parameter>();
      parameters.add(new InputType.Parameter("bitePeriodHours", this.mapper_bitePeriodHours.getValueSchema()));
      return parameters;
    }

    @Override
    public Map<String, SerializedValue> getArguments(
        final gov.nasa.ammos.aerie.procedural.examples.bananaprocedures.procedures.StayWellFed input) {
      final var arguments = new HashMap<String, SerializedValue>();
      arguments.put("bitePeriodHours", this.mapper_bitePeriodHours.serializeValue(input.bitePeriodHours()));
      return arguments;
    }

    @Override
    public gov.nasa.ammos.aerie.procedural.examples.bananaprocedures.procedures.StayWellFed instantiate(
        final Map<String, SerializedValue> arguments) throws InstantiationException {
      Optional<Double> bitePeriodHours = Optional.empty();

      final var instantiationExBuilder = new InstantiationException.Builder("StayWellFed");

      for (final var entry : arguments.entrySet()) {
        try {
          switch (entry.getKey()) {
            case "bitePeriodHours":
              bitePeriodHours = Optional.ofNullable(this.mapper_bitePeriodHours.deserializeValue(entry.getValue())
                  .getSuccessOrThrow(failure -> new UnconstructableArgumentException("bitePeriodHours", failure)));
              break;
            default:
              instantiationExBuilder.withExtraneousArgument(entry.getKey());
          }
        } catch (final UnconstructableArgumentException e) {
          instantiationExBuilder.withUnconstructableArgument(e.parameterName, e.failure);
        }
      }

      bitePeriodHours.ifPresentOrElse(
          value -> instantiationExBuilder.withValidArgument("bitePeriodHours", this.mapper_bitePeriodHours.serializeValue(value)),
          () -> instantiationExBuilder.withMissingArgument("bitePeriodHours", this.mapper_bitePeriodHours.getValueSchema()));

      instantiationExBuilder.throwIfAny();
      return new gov.nasa.ammos.aerie.procedural.examples.bananaprocedures.procedures.StayWellFed(bitePeriodHours.get());
    }

    @Override
    public List<InputType.ValidationNotice> getValidationFailures(
        final gov.nasa.ammos.aerie.procedural.examples.bananaprocedures.procedures.StayWellFed input) {
      final var notices = new ArrayList<InputType.ValidationNotice>();
      return notices;
    }
  }
}
