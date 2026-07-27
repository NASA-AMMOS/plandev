package gov.nasa.jpl.aerie.e2e.procedural.scheduling.generated.procedures;

import gov.nasa.ammos.aerie.procedural.scheduling.ProcedureMapper;
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

public final class ActivityAutoDeletionGoal implements ProcedureMapper<gov.nasa.jpl.aerie.e2e.procedural.scheduling.procedures.ActivityAutoDeletionGoal> {
  @Override
  public ValueSchema valueSchema() {
    return AutoValueMappers.gov_nasa_jpl_aerie_e2e_procedural_scheduling_procedures_ActivityAutoDeletionGoal(
            BasicValueMappers.$boolean()).getValueSchema();
  }

  @Override
  public InputType<gov.nasa.jpl.aerie.e2e.procedural.scheduling.procedures.ActivityAutoDeletionGoal> getInputType(
      ) {
    return new InputMapper();
  }

  public final class InputMapper implements InputType<gov.nasa.jpl.aerie.e2e.procedural.scheduling.procedures.ActivityAutoDeletionGoal> {
    private final ValueMapper<Boolean> mapper_deleteAtBeginning;

    @SuppressWarnings("unchecked")
    public InputMapper() {
      this.mapper_deleteAtBeginning =
          BasicValueMappers.$boolean();
    }

    @Override
    public List<String> getRequiredParameters() {
      return List.of();
    }

    @Override
    public ArrayList<InputType.Parameter> getParameters() {
      final var parameters = new ArrayList<InputType.Parameter>();
      parameters.add(new InputType.Parameter("deleteAtBeginning", this.mapper_deleteAtBeginning.getValueSchema()));
      return parameters;
    }

    @Override
    public Map<String, SerializedValue> getArguments(
        final gov.nasa.jpl.aerie.e2e.procedural.scheduling.procedures.ActivityAutoDeletionGoal input) {
      final var arguments = new HashMap<String, SerializedValue>();
      arguments.put("deleteAtBeginning", this.mapper_deleteAtBeginning.serializeValue(input.deleteAtBeginning()));
      return arguments;
    }

    @Override
    public gov.nasa.jpl.aerie.e2e.procedural.scheduling.procedures.ActivityAutoDeletionGoal instantiate(
        final Map<String, SerializedValue> arguments) throws InstantiationException {
      Optional<Boolean> deleteAtBeginning = Optional.empty();

      final var instantiationExBuilder = new InstantiationException.Builder("ActivityAutoDeletionGoal");

      for (final var entry : arguments.entrySet()) {
        try {
          switch (entry.getKey()) {
            case "deleteAtBeginning":
              deleteAtBeginning = Optional.ofNullable(this.mapper_deleteAtBeginning.deserializeValue(entry.getValue())
                  .getSuccessOrThrow(failure -> new UnconstructableArgumentException("deleteAtBeginning", failure)));
              break;
            default:
              instantiationExBuilder.withExtraneousArgument(entry.getKey());
          }
        } catch (final UnconstructableArgumentException e) {
          instantiationExBuilder.withUnconstructableArgument(e.parameterName, e.failure);
        }
      }

      deleteAtBeginning.ifPresentOrElse(
          value -> instantiationExBuilder.withValidArgument("deleteAtBeginning", this.mapper_deleteAtBeginning.serializeValue(value)),
          () -> instantiationExBuilder.withMissingArgument("deleteAtBeginning", this.mapper_deleteAtBeginning.getValueSchema()));

      instantiationExBuilder.throwIfAny();
      return new gov.nasa.jpl.aerie.e2e.procedural.scheduling.procedures.ActivityAutoDeletionGoal(deleteAtBeginning.get());
    }

    @Override
    public List<InputType.ValidationNotice> getValidationFailures(
        final gov.nasa.jpl.aerie.e2e.procedural.scheduling.procedures.ActivityAutoDeletionGoal input) {
      final var notices = new ArrayList<InputType.ValidationNotice>();
      return notices;
    }
  }
}
