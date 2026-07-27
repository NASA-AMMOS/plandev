package gov.nasa.jpl.aerie.e2e.procedural.scheduling.generated.procedures;

import gov.nasa.ammos.aerie.procedural.scheduling.ProcedureMapper;
import gov.nasa.ammos.aerie.procedural.scheduling.plan.DeletedAnchorStrategy;
import gov.nasa.jpl.aerie.contrib.serialization.mappers.NullableValueMapper;
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

public final class DeleteBiteBananasGoal implements ProcedureMapper<gov.nasa.jpl.aerie.e2e.procedural.scheduling.procedures.DeleteBiteBananasGoal> {
  @Override
  public ValueSchema valueSchema() {
    return AutoValueMappers.gov_nasa_jpl_aerie_e2e_procedural_scheduling_procedures_DeleteBiteBananasGoal(
            BasicValueMappers.$enum(
                DeletedAnchorStrategy.class)).getValueSchema();
  }

  @Override
  public InputType<gov.nasa.jpl.aerie.e2e.procedural.scheduling.procedures.DeleteBiteBananasGoal> getInputType(
      ) {
    return new InputMapper();
  }

  public final class InputMapper implements InputType<gov.nasa.jpl.aerie.e2e.procedural.scheduling.procedures.DeleteBiteBananasGoal> {
    private final ValueMapper<DeletedAnchorStrategy> mapper_anchorStrategy;

    @SuppressWarnings("unchecked")
    public InputMapper() {
      this.mapper_anchorStrategy =
          new NullableValueMapper<>(
              BasicValueMappers.$enum(
                  DeletedAnchorStrategy.class));
    }

    @Override
    public List<String> getRequiredParameters() {
      return List.of();
    }

    @Override
    public ArrayList<InputType.Parameter> getParameters() {
      final var parameters = new ArrayList<InputType.Parameter>();
      parameters.add(new InputType.Parameter("anchorStrategy", this.mapper_anchorStrategy.getValueSchema()));
      return parameters;
    }

    @Override
    public Map<String, SerializedValue> getArguments(
        final gov.nasa.jpl.aerie.e2e.procedural.scheduling.procedures.DeleteBiteBananasGoal input) {
      final var arguments = new HashMap<String, SerializedValue>();
      arguments.put("anchorStrategy", this.mapper_anchorStrategy.serializeValue(input.anchorStrategy()));
      return arguments;
    }

    @Override
    public gov.nasa.jpl.aerie.e2e.procedural.scheduling.procedures.DeleteBiteBananasGoal instantiate(
        final Map<String, SerializedValue> arguments) throws InstantiationException {
      Optional<DeletedAnchorStrategy> anchorStrategy = Optional.empty();

      final var instantiationExBuilder = new InstantiationException.Builder("DeleteBiteBananasGoal");

      for (final var entry : arguments.entrySet()) {
        try {
          switch (entry.getKey()) {
            case "anchorStrategy":
              anchorStrategy = Optional.ofNullable(this.mapper_anchorStrategy.deserializeValue(entry.getValue())
                  .getSuccessOrThrow(failure -> new UnconstructableArgumentException("anchorStrategy", failure)));
              break;
            default:
              instantiationExBuilder.withExtraneousArgument(entry.getKey());
          }
        } catch (final UnconstructableArgumentException e) {
          instantiationExBuilder.withUnconstructableArgument(e.parameterName, e.failure);
        }
      }

      anchorStrategy.ifPresentOrElse(
          value -> instantiationExBuilder.withValidArgument("anchorStrategy", this.mapper_anchorStrategy.serializeValue(value)),
          () -> instantiationExBuilder.withMissingArgument("anchorStrategy", this.mapper_anchorStrategy.getValueSchema()));

      instantiationExBuilder.throwIfAny();
      return new gov.nasa.jpl.aerie.e2e.procedural.scheduling.procedures.DeleteBiteBananasGoal(anchorStrategy.get());
    }

    @Override
    public List<InputType.ValidationNotice> getValidationFailures(
        final gov.nasa.jpl.aerie.e2e.procedural.scheduling.procedures.DeleteBiteBananasGoal input) {
      final var notices = new ArrayList<InputType.ValidationNotice>();
      return notices;
    }
  }
}
