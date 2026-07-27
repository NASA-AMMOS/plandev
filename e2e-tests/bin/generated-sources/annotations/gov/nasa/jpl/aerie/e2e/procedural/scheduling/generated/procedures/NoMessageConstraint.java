package gov.nasa.jpl.aerie.e2e.procedural.scheduling.generated.procedures;

import gov.nasa.ammos.aerie.procedural.constraints.ProcedureMapper;
import gov.nasa.jpl.aerie.contrib.serialization.rulesets.BasicValueMappers;
import gov.nasa.jpl.aerie.e2e.procedural.scheduling.generated.AutoValueMappers;
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

public final class NoMessageConstraint implements ProcedureMapper<gov.nasa.jpl.aerie.e2e.procedural.scheduling.procedures.NoMessageConstraint> {
  @Override
  public ValueSchema valueSchema() {
    return AutoValueMappers.gov_nasa_jpl_aerie_e2e_procedural_scheduling_procedures_NoMessageConstraint(
            BasicValueMappers.$int(),
            BasicValueMappers.$int()).getValueSchema();
  }

  @Override
  public InputType<gov.nasa.jpl.aerie.e2e.procedural.scheduling.procedures.NoMessageConstraint> getInputType(
      ) {
    return new InputMapper();
  }

  public final class InputMapper implements InputType<gov.nasa.jpl.aerie.e2e.procedural.scheduling.procedures.NoMessageConstraint> {
    private final ValueMapper<Integer> mapper_lowerBound;

    private final ValueMapper<Integer> mapper_upperBound;

    @SuppressWarnings("unchecked")
    public InputMapper() {
      this.mapper_lowerBound =
          BasicValueMappers.$int();
      this.mapper_upperBound =
          BasicValueMappers.$int();
    }

    @Override
    public List<String> getRequiredParameters() {
      return List.of("upperBound");
    }

    @Override
    public ArrayList<InputType.Parameter> getParameters() {
      final var parameters = new ArrayList<InputType.Parameter>();
      parameters.add(new InputType.Parameter("lowerBound", this.mapper_lowerBound.getValueSchema()));
      parameters.add(new InputType.Parameter("upperBound", this.mapper_upperBound.getValueSchema()));
      return parameters;
    }

    @Override
    public Map<String, SerializedValue> getArguments(
        final gov.nasa.jpl.aerie.e2e.procedural.scheduling.procedures.NoMessageConstraint input) {
      final var arguments = new HashMap<String, SerializedValue>();
      arguments.put("lowerBound", this.mapper_lowerBound.serializeValue(input.lowerBound()));
      arguments.put("upperBound", this.mapper_upperBound.serializeValue(input.upperBound()));
      return arguments;
    }

    @Override
    public gov.nasa.jpl.aerie.e2e.procedural.scheduling.procedures.NoMessageConstraint instantiate(
        final Map<String, SerializedValue> arguments) throws InstantiationException {
      final var defaults = new gov.nasa.jpl.aerie.e2e.procedural.scheduling.procedures.NoMessageConstraint.Template();
      Optional<Integer> lowerBound = Optional.empty();
      Optional<Integer> upperBound = Optional.empty();

      lowerBound = Optional.ofNullable(defaults.lowerBound);

      final var instantiationExBuilder = new InstantiationException.Builder("NoMessageConstraint");

      for (final var entry : arguments.entrySet()) {
        try {
          switch (entry.getKey()) {
            case "lowerBound":
              lowerBound = Optional.ofNullable(this.mapper_lowerBound.deserializeValue(entry.getValue())
                  .getSuccessOrThrow(failure -> new UnconstructableArgumentException("lowerBound", failure)));
              break;
            case "upperBound":
              upperBound = Optional.ofNullable(this.mapper_upperBound.deserializeValue(entry.getValue())
                  .getSuccessOrThrow(failure -> new UnconstructableArgumentException("upperBound", failure)));
              break;
            default:
              instantiationExBuilder.withExtraneousArgument(entry.getKey());
          }
        } catch (final UnconstructableArgumentException e) {
          instantiationExBuilder.withUnconstructableArgument(e.parameterName, e.failure);
        }
      }

      lowerBound.ifPresentOrElse(
          value -> instantiationExBuilder.withValidArgument("lowerBound", this.mapper_lowerBound.serializeValue(value)),
          () -> instantiationExBuilder.withMissingArgument("lowerBound", this.mapper_lowerBound.getValueSchema()));
      upperBound.ifPresentOrElse(
          value -> instantiationExBuilder.withValidArgument("upperBound", this.mapper_upperBound.serializeValue(value)),
          () -> instantiationExBuilder.withMissingArgument("upperBound", this.mapper_upperBound.getValueSchema()));

      instantiationExBuilder.throwIfAny();
      return new gov.nasa.jpl.aerie.e2e.procedural.scheduling.procedures.NoMessageConstraint(lowerBound.get(), upperBound.get());
    }

    @Override
    public List<InputType.ValidationNotice> getValidationFailures(
        final gov.nasa.jpl.aerie.e2e.procedural.scheduling.procedures.NoMessageConstraint input) {
      final var notices = new ArrayList<InputType.ValidationNotice>();
      return notices;
    }
  }
}
