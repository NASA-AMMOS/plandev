package gov.nasa.ammos.aerie.procedural.examples.bananaprocedures.generated.constraints;

import gov.nasa.ammos.aerie.procedural.constraints.ProcedureMapper;
import gov.nasa.ammos.aerie.procedural.examples.bananaprocedures.generated.AutoValueMappers;
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

public final class FruitThreshold implements ProcedureMapper<gov.nasa.ammos.aerie.procedural.examples.bananaprocedures.constraints.FruitThreshold> {
  @Override
  public ValueSchema valueSchema() {
    return AutoValueMappers.gov_nasa_ammos_aerie_procedural_examples_bananaprocedures_constraints_FruitThreshold(
            BasicValueMappers.$int()).getValueSchema();
  }

  @Override
  public InputType<gov.nasa.ammos.aerie.procedural.examples.bananaprocedures.constraints.FruitThreshold> getInputType(
      ) {
    return new InputMapper();
  }

  public final class InputMapper implements InputType<gov.nasa.ammos.aerie.procedural.examples.bananaprocedures.constraints.FruitThreshold> {
    private final ValueMapper<Integer> mapper_threshold;

    @SuppressWarnings("unchecked")
    public InputMapper() {
      this.mapper_threshold =
          BasicValueMappers.$int();
    }

    @Override
    public List<String> getRequiredParameters() {
      return List.of();
    }

    @Override
    public ArrayList<InputType.Parameter> getParameters() {
      final var parameters = new ArrayList<InputType.Parameter>();
      parameters.add(new InputType.Parameter("threshold", this.mapper_threshold.getValueSchema()));
      return parameters;
    }

    @Override
    public Map<String, SerializedValue> getArguments(
        final gov.nasa.ammos.aerie.procedural.examples.bananaprocedures.constraints.FruitThreshold input) {
      final var arguments = new HashMap<String, SerializedValue>();
      arguments.put("threshold", this.mapper_threshold.serializeValue(input.threshold()));
      return arguments;
    }

    @Override
    public gov.nasa.ammos.aerie.procedural.examples.bananaprocedures.constraints.FruitThreshold instantiate(
        final Map<String, SerializedValue> arguments) throws InstantiationException {
      final var defaults = new gov.nasa.ammos.aerie.procedural.examples.bananaprocedures.constraints.FruitThreshold.Template();
      Optional<Integer> threshold = Optional.empty();

      threshold = Optional.ofNullable(defaults.threshold);

      final var instantiationExBuilder = new InstantiationException.Builder("FruitThreshold");

      for (final var entry : arguments.entrySet()) {
        try {
          switch (entry.getKey()) {
            case "threshold":
              threshold = Optional.ofNullable(this.mapper_threshold.deserializeValue(entry.getValue())
                  .getSuccessOrThrow(failure -> new UnconstructableArgumentException("threshold", failure)));
              break;
            default:
              instantiationExBuilder.withExtraneousArgument(entry.getKey());
          }
        } catch (final UnconstructableArgumentException e) {
          instantiationExBuilder.withUnconstructableArgument(e.parameterName, e.failure);
        }
      }

      threshold.ifPresentOrElse(
          value -> instantiationExBuilder.withValidArgument("threshold", this.mapper_threshold.serializeValue(value)),
          () -> instantiationExBuilder.withMissingArgument("threshold", this.mapper_threshold.getValueSchema()));

      instantiationExBuilder.throwIfAny();
      return new gov.nasa.ammos.aerie.procedural.examples.bananaprocedures.constraints.FruitThreshold(threshold.get());
    }

    @Override
    public List<InputType.ValidationNotice> getValidationFailures(
        final gov.nasa.ammos.aerie.procedural.examples.bananaprocedures.constraints.FruitThreshold input) {
      final var notices = new ArrayList<InputType.ValidationNotice>();
      return notices;
    }
  }
}
