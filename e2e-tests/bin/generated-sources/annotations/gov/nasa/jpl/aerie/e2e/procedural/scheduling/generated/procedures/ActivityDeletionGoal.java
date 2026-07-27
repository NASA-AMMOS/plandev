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

public final class ActivityDeletionGoal implements ProcedureMapper<gov.nasa.jpl.aerie.e2e.procedural.scheduling.procedures.ActivityDeletionGoal> {
  @Override
  public ValueSchema valueSchema() {
    return AutoValueMappers.gov_nasa_jpl_aerie_e2e_procedural_scheduling_procedures_ActivityDeletionGoal(
            BasicValueMappers.$int(),
            BasicValueMappers.$enum(
                DeletedAnchorStrategy.class),
            BasicValueMappers.$boolean()).getValueSchema();
  }

  @Override
  public InputType<gov.nasa.jpl.aerie.e2e.procedural.scheduling.procedures.ActivityDeletionGoal> getInputType(
      ) {
    return new InputMapper();
  }

  public final class InputMapper implements InputType<gov.nasa.jpl.aerie.e2e.procedural.scheduling.procedures.ActivityDeletionGoal> {
    private final ValueMapper<Integer> mapper_whichToDelete;

    private final ValueMapper<DeletedAnchorStrategy> mapper_anchorStrategy;

    private final ValueMapper<Boolean> mapper_rollback;

    @SuppressWarnings("unchecked")
    public InputMapper() {
      this.mapper_whichToDelete =
          BasicValueMappers.$int();
      this.mapper_anchorStrategy =
          new NullableValueMapper<>(
              BasicValueMappers.$enum(
                  DeletedAnchorStrategy.class));
      this.mapper_rollback =
          BasicValueMappers.$boolean();
    }

    @Override
    public List<String> getRequiredParameters() {
      return List.of();
    }

    @Override
    public ArrayList<InputType.Parameter> getParameters() {
      final var parameters = new ArrayList<InputType.Parameter>();
      parameters.add(new InputType.Parameter("whichToDelete", this.mapper_whichToDelete.getValueSchema()));
      parameters.add(new InputType.Parameter("anchorStrategy", this.mapper_anchorStrategy.getValueSchema()));
      parameters.add(new InputType.Parameter("rollback", this.mapper_rollback.getValueSchema()));
      return parameters;
    }

    @Override
    public Map<String, SerializedValue> getArguments(
        final gov.nasa.jpl.aerie.e2e.procedural.scheduling.procedures.ActivityDeletionGoal input) {
      final var arguments = new HashMap<String, SerializedValue>();
      arguments.put("whichToDelete", this.mapper_whichToDelete.serializeValue(input.whichToDelete()));
      arguments.put("anchorStrategy", this.mapper_anchorStrategy.serializeValue(input.anchorStrategy()));
      arguments.put("rollback", this.mapper_rollback.serializeValue(input.rollback()));
      return arguments;
    }

    @Override
    public gov.nasa.jpl.aerie.e2e.procedural.scheduling.procedures.ActivityDeletionGoal instantiate(
        final Map<String, SerializedValue> arguments) throws InstantiationException {
      Optional<Integer> whichToDelete = Optional.empty();
      Optional<DeletedAnchorStrategy> anchorStrategy = Optional.empty();
      Optional<Boolean> rollback = Optional.empty();

      final var instantiationExBuilder = new InstantiationException.Builder("ActivityDeletionGoal");

      for (final var entry : arguments.entrySet()) {
        try {
          switch (entry.getKey()) {
            case "whichToDelete":
              whichToDelete = Optional.ofNullable(this.mapper_whichToDelete.deserializeValue(entry.getValue())
                  .getSuccessOrThrow(failure -> new UnconstructableArgumentException("whichToDelete", failure)));
              break;
            case "anchorStrategy":
              anchorStrategy = Optional.ofNullable(this.mapper_anchorStrategy.deserializeValue(entry.getValue())
                  .getSuccessOrThrow(failure -> new UnconstructableArgumentException("anchorStrategy", failure)));
              break;
            case "rollback":
              rollback = Optional.ofNullable(this.mapper_rollback.deserializeValue(entry.getValue())
                  .getSuccessOrThrow(failure -> new UnconstructableArgumentException("rollback", failure)));
              break;
            default:
              instantiationExBuilder.withExtraneousArgument(entry.getKey());
          }
        } catch (final UnconstructableArgumentException e) {
          instantiationExBuilder.withUnconstructableArgument(e.parameterName, e.failure);
        }
      }

      whichToDelete.ifPresentOrElse(
          value -> instantiationExBuilder.withValidArgument("whichToDelete", this.mapper_whichToDelete.serializeValue(value)),
          () -> instantiationExBuilder.withMissingArgument("whichToDelete", this.mapper_whichToDelete.getValueSchema()));
      anchorStrategy.ifPresentOrElse(
          value -> instantiationExBuilder.withValidArgument("anchorStrategy", this.mapper_anchorStrategy.serializeValue(value)),
          () -> instantiationExBuilder.withMissingArgument("anchorStrategy", this.mapper_anchorStrategy.getValueSchema()));
      rollback.ifPresentOrElse(
          value -> instantiationExBuilder.withValidArgument("rollback", this.mapper_rollback.serializeValue(value)),
          () -> instantiationExBuilder.withMissingArgument("rollback", this.mapper_rollback.getValueSchema()));

      instantiationExBuilder.throwIfAny();
      return new gov.nasa.jpl.aerie.e2e.procedural.scheduling.procedures.ActivityDeletionGoal(whichToDelete.get(), anchorStrategy.get(), rollback.get());
    }

    @Override
    public List<InputType.ValidationNotice> getValidationFailures(
        final gov.nasa.jpl.aerie.e2e.procedural.scheduling.procedures.ActivityDeletionGoal input) {
      final var notices = new ArrayList<InputType.ValidationNotice>();
      return notices;
    }
  }
}
