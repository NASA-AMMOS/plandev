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

public final class ExternalEventPresenceConstraint implements ProcedureMapper<gov.nasa.jpl.aerie.e2e.procedural.scheduling.procedures.ExternalEventPresenceConstraint> {
  @Override
  public ValueSchema valueSchema() {
    return AutoValueMappers.gov_nasa_jpl_aerie_e2e_procedural_scheduling_procedures_ExternalEventPresenceConstraint(
            BasicValueMappers.string(),
            BasicValueMappers.string(),
            BasicValueMappers.string()).getValueSchema();
  }

  @Override
  public InputType<gov.nasa.jpl.aerie.e2e.procedural.scheduling.procedures.ExternalEventPresenceConstraint> getInputType(
      ) {
    return new InputMapper();
  }

  public final class InputMapper implements InputType<gov.nasa.jpl.aerie.e2e.procedural.scheduling.procedures.ExternalEventPresenceConstraint> {
    private final ValueMapper<String> mapper_eventType;

    private final ValueMapper<String> mapper_derivationGroup;

    private final ValueMapper<String> mapper_sourceKey;

    @SuppressWarnings("unchecked")
    public InputMapper() {
      this.mapper_eventType =
          new NullableValueMapper<>(
              BasicValueMappers.string());
      this.mapper_derivationGroup =
          new NullableValueMapper<>(
              BasicValueMappers.string());
      this.mapper_sourceKey =
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
      parameters.add(new InputType.Parameter("eventType", this.mapper_eventType.getValueSchema()));
      parameters.add(new InputType.Parameter("derivationGroup", this.mapper_derivationGroup.getValueSchema()));
      parameters.add(new InputType.Parameter("sourceKey", this.mapper_sourceKey.getValueSchema()));
      return parameters;
    }

    @Override
    public Map<String, SerializedValue> getArguments(
        final gov.nasa.jpl.aerie.e2e.procedural.scheduling.procedures.ExternalEventPresenceConstraint input) {
      final var arguments = new HashMap<String, SerializedValue>();
      arguments.put("eventType", this.mapper_eventType.serializeValue(input.eventType()));
      arguments.put("derivationGroup", this.mapper_derivationGroup.serializeValue(input.derivationGroup()));
      arguments.put("sourceKey", this.mapper_sourceKey.serializeValue(input.sourceKey()));
      return arguments;
    }

    @Override
    public gov.nasa.jpl.aerie.e2e.procedural.scheduling.procedures.ExternalEventPresenceConstraint instantiate(
        final Map<String, SerializedValue> arguments) throws InstantiationException {
      Optional<String> eventType = Optional.empty();
      Optional<String> derivationGroup = Optional.empty();
      Optional<String> sourceKey = Optional.empty();

      final var instantiationExBuilder = new InstantiationException.Builder("ExternalEventPresenceConstraint");

      for (final var entry : arguments.entrySet()) {
        try {
          switch (entry.getKey()) {
            case "eventType":
              eventType = Optional.ofNullable(this.mapper_eventType.deserializeValue(entry.getValue())
                  .getSuccessOrThrow(failure -> new UnconstructableArgumentException("eventType", failure)));
              break;
            case "derivationGroup":
              derivationGroup = Optional.ofNullable(this.mapper_derivationGroup.deserializeValue(entry.getValue())
                  .getSuccessOrThrow(failure -> new UnconstructableArgumentException("derivationGroup", failure)));
              break;
            case "sourceKey":
              sourceKey = Optional.ofNullable(this.mapper_sourceKey.deserializeValue(entry.getValue())
                  .getSuccessOrThrow(failure -> new UnconstructableArgumentException("sourceKey", failure)));
              break;
            default:
              instantiationExBuilder.withExtraneousArgument(entry.getKey());
          }
        } catch (final UnconstructableArgumentException e) {
          instantiationExBuilder.withUnconstructableArgument(e.parameterName, e.failure);
        }
      }

      eventType.ifPresentOrElse(
          value -> instantiationExBuilder.withValidArgument("eventType", this.mapper_eventType.serializeValue(value)),
          () -> instantiationExBuilder.withMissingArgument("eventType", this.mapper_eventType.getValueSchema()));
      derivationGroup.ifPresentOrElse(
          value -> instantiationExBuilder.withValidArgument("derivationGroup", this.mapper_derivationGroup.serializeValue(value)),
          () -> instantiationExBuilder.withMissingArgument("derivationGroup", this.mapper_derivationGroup.getValueSchema()));
      sourceKey.ifPresentOrElse(
          value -> instantiationExBuilder.withValidArgument("sourceKey", this.mapper_sourceKey.serializeValue(value)),
          () -> instantiationExBuilder.withMissingArgument("sourceKey", this.mapper_sourceKey.getValueSchema()));

      instantiationExBuilder.throwIfAny();
      return new gov.nasa.jpl.aerie.e2e.procedural.scheduling.procedures.ExternalEventPresenceConstraint(eventType.get(), derivationGroup.get(), sourceKey.get());
    }

    @Override
    public List<InputType.ValidationNotice> getValidationFailures(
        final gov.nasa.jpl.aerie.e2e.procedural.scheduling.procedures.ExternalEventPresenceConstraint input) {
      final var notices = new ArrayList<InputType.ValidationNotice>();
      return notices;
    }
  }
}
