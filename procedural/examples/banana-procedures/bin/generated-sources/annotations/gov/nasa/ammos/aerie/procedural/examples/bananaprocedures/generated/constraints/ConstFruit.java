package gov.nasa.ammos.aerie.procedural.examples.bananaprocedures.generated.constraints;

import gov.nasa.ammos.aerie.procedural.constraints.ProcedureMapper;
import gov.nasa.ammos.aerie.procedural.examples.bananaprocedures.generated.AutoValueMappers;
import gov.nasa.jpl.aerie.merlin.protocol.model.InputType;
import gov.nasa.jpl.aerie.merlin.protocol.types.InstantiationException;
import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;
import gov.nasa.jpl.aerie.merlin.protocol.types.ValueSchema;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class ConstFruit implements ProcedureMapper<gov.nasa.ammos.aerie.procedural.examples.bananaprocedures.constraints.ConstFruit> {
  @Override
  public ValueSchema valueSchema() {
    return AutoValueMappers.gov_nasa_ammos_aerie_procedural_examples_bananaprocedures_constraints_ConstFruit().getValueSchema();
  }

  @Override
  public InputType<gov.nasa.ammos.aerie.procedural.examples.bananaprocedures.constraints.ConstFruit> getInputType(
      ) {
    return new InputMapper();
  }

  public final class InputMapper implements InputType<gov.nasa.ammos.aerie.procedural.examples.bananaprocedures.constraints.ConstFruit> {
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
        final gov.nasa.ammos.aerie.procedural.examples.bananaprocedures.constraints.ConstFruit input) {
      final var arguments = new HashMap<String, SerializedValue>();
      return arguments;
    }

    @Override
    public gov.nasa.ammos.aerie.procedural.examples.bananaprocedures.constraints.ConstFruit instantiate(
        final Map<String, SerializedValue> arguments) throws InstantiationException {

      final var instantiationExBuilder = new InstantiationException.Builder("ConstFruit");

      for (final var entry : arguments.entrySet()) {
        switch (entry.getKey()) {
          default:
            instantiationExBuilder.withExtraneousArgument(entry.getKey());
        }
      }


      instantiationExBuilder.throwIfAny();
      return new gov.nasa.ammos.aerie.procedural.examples.bananaprocedures.constraints.ConstFruit();
    }

    @Override
    public List<InputType.ValidationNotice> getValidationFailures(
        final gov.nasa.ammos.aerie.procedural.examples.bananaprocedures.constraints.ConstFruit input) {
      final var notices = new ArrayList<InputType.ValidationNotice>();
      return notices;
    }
  }
}
