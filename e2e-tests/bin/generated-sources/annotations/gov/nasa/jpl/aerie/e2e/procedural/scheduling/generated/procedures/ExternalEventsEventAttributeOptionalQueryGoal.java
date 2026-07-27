package gov.nasa.jpl.aerie.e2e.procedural.scheduling.generated.procedures;

import gov.nasa.ammos.aerie.procedural.scheduling.ProcedureMapper;
import gov.nasa.jpl.aerie.e2e.procedural.scheduling.generated.AutoValueMappers;
import gov.nasa.jpl.aerie.merlin.protocol.model.InputType;
import gov.nasa.jpl.aerie.merlin.protocol.types.InstantiationException;
import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;
import gov.nasa.jpl.aerie.merlin.protocol.types.ValueSchema;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class ExternalEventsEventAttributeOptionalQueryGoal implements ProcedureMapper<gov.nasa.jpl.aerie.e2e.procedural.scheduling.procedures.ExternalEventsEventAttributeOptionalQueryGoal> {
  @Override
  public ValueSchema valueSchema() {
    return AutoValueMappers.gov_nasa_jpl_aerie_e2e_procedural_scheduling_procedures_ExternalEventsEventAttributeOptionalQueryGoal().getValueSchema();
  }

  @Override
  public InputType<gov.nasa.jpl.aerie.e2e.procedural.scheduling.procedures.ExternalEventsEventAttributeOptionalQueryGoal> getInputType(
      ) {
    return new InputMapper();
  }

  public final class InputMapper implements InputType<gov.nasa.jpl.aerie.e2e.procedural.scheduling.procedures.ExternalEventsEventAttributeOptionalQueryGoal> {
    @SuppressWarnings("unchecked")
    public InputMapper() {
    }

    @Override
    public List<String> getRequiredParameters() {
      return List.of();
    }

    @Override
    public ArrayList<InputType.Parameter> getParameters() {
      final var parameters = new ArrayList<InputType.Parameter>();
      return parameters;
    }

    @Override
    public Map<String, SerializedValue> getArguments(
        final gov.nasa.jpl.aerie.e2e.procedural.scheduling.procedures.ExternalEventsEventAttributeOptionalQueryGoal input) {
      final var arguments = new HashMap<String, SerializedValue>();
      return arguments;
    }

    @Override
    public gov.nasa.jpl.aerie.e2e.procedural.scheduling.procedures.ExternalEventsEventAttributeOptionalQueryGoal instantiate(
        final Map<String, SerializedValue> arguments) throws InstantiationException {

      final var instantiationExBuilder = new InstantiationException.Builder("ExternalEventsEventAttributeOptionalQueryGoal");

      for (final var entry : arguments.entrySet()) {
        switch (entry.getKey()) {
          default:
            instantiationExBuilder.withExtraneousArgument(entry.getKey());
        }
      }


      instantiationExBuilder.throwIfAny();
      return new gov.nasa.jpl.aerie.e2e.procedural.scheduling.procedures.ExternalEventsEventAttributeOptionalQueryGoal();
    }

    @Override
    public List<InputType.ValidationNotice> getValidationFailures(
        final gov.nasa.jpl.aerie.e2e.procedural.scheduling.procedures.ExternalEventsEventAttributeOptionalQueryGoal input) {
      final var notices = new ArrayList<InputType.ValidationNotice>();
      return notices;
    }
  }
}
