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

public final class DumbRecurrenceGoalWithTemplateDefaults implements ProcedureMapper<gov.nasa.jpl.aerie.e2e.procedural.scheduling.procedures.DumbRecurrenceGoalWithTemplateDefaults> {
  @Override
  public ValueSchema valueSchema() {
    return AutoValueMappers.gov_nasa_jpl_aerie_e2e_procedural_scheduling_procedures_DumbRecurrenceGoalWithTemplateDefaults(
            BasicValueMappers.$int(),
            BasicValueMappers.$int()).getValueSchema();
  }

  @Override
  public InputType<gov.nasa.jpl.aerie.e2e.procedural.scheduling.procedures.DumbRecurrenceGoalWithTemplateDefaults> getInputType(
      ) {
    return new InputMapper();
  }

  public final class InputMapper implements InputType<gov.nasa.jpl.aerie.e2e.procedural.scheduling.procedures.DumbRecurrenceGoalWithTemplateDefaults> {
    private final ValueMapper<Integer> mapper_quantity;

    private final ValueMapper<Integer> mapper_biteSize;

    @SuppressWarnings("unchecked")
    public InputMapper() {
      this.mapper_quantity =
          BasicValueMappers.$int();
      this.mapper_biteSize =
          BasicValueMappers.$int();
    }

    @Override
    public List<String> getRequiredParameters() {
      return List.of();
    }

    @Override
    public ArrayList<InputType.Parameter> getParameters() {
      final var parameters = new ArrayList<InputType.Parameter>();
      parameters.add(new InputType.Parameter("quantity", this.mapper_quantity.getValueSchema()));
      parameters.add(new InputType.Parameter("biteSize", this.mapper_biteSize.getValueSchema()));
      return parameters;
    }

    @Override
    public Map<String, SerializedValue> getArguments(
        final gov.nasa.jpl.aerie.e2e.procedural.scheduling.procedures.DumbRecurrenceGoalWithTemplateDefaults input) {
      final var arguments = new HashMap<String, SerializedValue>();
      arguments.put("quantity", this.mapper_quantity.serializeValue(input.quantity()));
      arguments.put("biteSize", this.mapper_biteSize.serializeValue(input.biteSize()));
      return arguments;
    }

    @Override
    public gov.nasa.jpl.aerie.e2e.procedural.scheduling.procedures.DumbRecurrenceGoalWithTemplateDefaults instantiate(
        final Map<String, SerializedValue> arguments) throws InstantiationException {
      final var template = gov.nasa.jpl.aerie.e2e.procedural.scheduling.procedures.DumbRecurrenceGoalWithTemplateDefaults.create();
      Optional<Integer> quantity = Optional.ofNullable(template.quantity());
      Optional<Integer> biteSize = Optional.ofNullable(template.biteSize());

      final var instantiationExBuilder = new InstantiationException.Builder("DumbRecurrenceGoalWithTemplateDefaults");

      for (final var entry : arguments.entrySet()) {
        try {
          switch (entry.getKey()) {
            case "quantity":
              quantity = Optional.ofNullable(this.mapper_quantity.deserializeValue(entry.getValue())
                  .getSuccessOrThrow(failure -> new UnconstructableArgumentException("quantity", failure)));
              break;
            case "biteSize":
              biteSize = Optional.ofNullable(this.mapper_biteSize.deserializeValue(entry.getValue())
                  .getSuccessOrThrow(failure -> new UnconstructableArgumentException("biteSize", failure)));
              break;
            default:
              instantiationExBuilder.withExtraneousArgument(entry.getKey());
          }
        } catch (final UnconstructableArgumentException e) {
          instantiationExBuilder.withUnconstructableArgument(e.parameterName, e.failure);
        }
      }

      quantity.ifPresentOrElse(
          value -> instantiationExBuilder.withValidArgument("quantity", this.mapper_quantity.serializeValue(value)),
          () -> instantiationExBuilder.withMissingArgument("quantity", this.mapper_quantity.getValueSchema()));
      biteSize.ifPresentOrElse(
          value -> instantiationExBuilder.withValidArgument("biteSize", this.mapper_biteSize.serializeValue(value)),
          () -> instantiationExBuilder.withMissingArgument("biteSize", this.mapper_biteSize.getValueSchema()));

      instantiationExBuilder.throwIfAny();
      return new gov.nasa.jpl.aerie.e2e.procedural.scheduling.procedures.DumbRecurrenceGoalWithTemplateDefaults(quantity.get(), biteSize.get());
    }

    @Override
    public List<InputType.ValidationNotice> getValidationFailures(
        final gov.nasa.jpl.aerie.e2e.procedural.scheduling.procedures.DumbRecurrenceGoalWithTemplateDefaults input) {
      final var notices = new ArrayList<InputType.ValidationNotice>();
      return notices;
    }
  }
}
