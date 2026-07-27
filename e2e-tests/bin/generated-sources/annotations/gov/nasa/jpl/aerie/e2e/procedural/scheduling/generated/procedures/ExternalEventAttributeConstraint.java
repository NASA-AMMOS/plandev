package gov.nasa.jpl.aerie.e2e.procedural.scheduling.generated.procedures;

import gov.nasa.ammos.aerie.procedural.constraints.ProcedureMapper;
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

public final class ExternalEventAttributeConstraint implements ProcedureMapper<gov.nasa.jpl.aerie.e2e.procedural.scheduling.procedures.ExternalEventAttributeConstraint> {
  @Override
  public ValueSchema valueSchema() {
    return AutoValueMappers.gov_nasa_jpl_aerie_e2e_procedural_scheduling_procedures_ExternalEventAttributeConstraint(
            BasicValueMappers.string()).getValueSchema();
  }

  @Override
  public InputType<gov.nasa.jpl.aerie.e2e.procedural.scheduling.procedures.ExternalEventAttributeConstraint> getInputType(
      ) {
    return new InputMapper();
  }

  public final class InputMapper implements InputType<gov.nasa.jpl.aerie.e2e.procedural.scheduling.procedures.ExternalEventAttributeConstraint> {
    private final ValueMapper<String> mapper_codeValue;

    @SuppressWarnings("unchecked")
    public InputMapper() {
      this.mapper_codeValue =
          new NullableValueMapper<>(
              BasicValueMappers.string());
    }

    @Override
    public List<String> getRequiredParameters() {
      return List.of();
    }

    @Override
    public ArrayList<InputType.Parameter> getParameters() {
      final var parameters = new ArrayList<InputType.Parameter>();
      parameters.add(new InputType.Parameter("codeValue", this.mapper_codeValue.getValueSchema()));
      return parameters;
    }

    @Override
    public Map<String, SerializedValue> getArguments(
        final gov.nasa.jpl.aerie.e2e.procedural.scheduling.procedures.ExternalEventAttributeConstraint input) {
      final var arguments = new HashMap<String, SerializedValue>();
      arguments.put("codeValue", this.mapper_codeValue.serializeValue(input.codeValue()));
      return arguments;
    }

    @Override
    public gov.nasa.jpl.aerie.e2e.procedural.scheduling.procedures.ExternalEventAttributeConstraint instantiate(
        final Map<String, SerializedValue> arguments) throws InstantiationException {
      Optional<String> codeValue = Optional.empty();

      final var instantiationExBuilder = new InstantiationException.Builder("ExternalEventAttributeConstraint");

      for (final var entry : arguments.entrySet()) {
        try {
          switch (entry.getKey()) {
            case "codeValue":
              codeValue = Optional.ofNullable(this.mapper_codeValue.deserializeValue(entry.getValue())
                  .getSuccessOrThrow(failure -> new UnconstructableArgumentException("codeValue", failure)));
              break;
            default:
              instantiationExBuilder.withExtraneousArgument(entry.getKey());
          }
        } catch (final UnconstructableArgumentException e) {
          instantiationExBuilder.withUnconstructableArgument(e.parameterName, e.failure);
        }
      }

      codeValue.ifPresentOrElse(
          value -> instantiationExBuilder.withValidArgument("codeValue", this.mapper_codeValue.serializeValue(value)),
          () -> instantiationExBuilder.withMissingArgument("codeValue", this.mapper_codeValue.getValueSchema()));

      instantiationExBuilder.throwIfAny();
      return new gov.nasa.jpl.aerie.e2e.procedural.scheduling.procedures.ExternalEventAttributeConstraint(codeValue.get());
    }

    @Override
    public List<InputType.ValidationNotice> getValidationFailures(
        final gov.nasa.jpl.aerie.e2e.procedural.scheduling.procedures.ExternalEventAttributeConstraint input) {
      final var notices = new ArrayList<InputType.ValidationNotice>();
      return notices;
    }
  }
}
