package gov.nasa.jpl.aerie.merlin.framework;

import gov.nasa.jpl.aerie.merlin.protocol.model.InputType;
import gov.nasa.jpl.aerie.merlin.protocol.types.InstantiationException;
import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;
import gov.nasa.jpl.aerie.merlin.protocol.types.Unit;

import java.util.List;
import java.util.Map;

public final class EmptyInputType implements InputType<Unit> {
  @Override
  public List<Parameter> getParameters() {
    return List.of();
  }

  @Override
  public List<String> getRequiredParameters() {
    return List.of();
  }

  @Override
  public Unit instantiate(final Map<String, SerializedValue> arguments)
  throws InstantiationException
  {
    final var invalidArgsExBuilder = new InstantiationException.Builder(getClass().getSimpleName());
    arguments.forEach((k, v) -> invalidArgsExBuilder.withExtraneousArgument(k));
    invalidArgsExBuilder.throwIfAny();

    return Unit.UNIT;
  }

  @Override
  public Map<String, SerializedValue> getArguments(final Unit configuration) {
    return Map.of();
  }

  @Override
  public List<ValidationNotice> getValidationFailures(final Unit configuration) {
    return List.of();
  }
}
