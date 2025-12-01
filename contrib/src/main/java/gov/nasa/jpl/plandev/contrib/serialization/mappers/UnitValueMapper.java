package gov.nasa.jpl.plandev.contrib.serialization.mappers;

import gov.nasa.jpl.plandev.merlin.framework.Result;
import gov.nasa.jpl.plandev.merlin.framework.ValueMapper;
import gov.nasa.jpl.plandev.merlin.protocol.types.SerializedValue;
import gov.nasa.jpl.plandev.merlin.protocol.types.Unit;
import gov.nasa.jpl.plandev.merlin.protocol.types.ValueSchema;

import java.util.Map;

public record UnitValueMapper() implements ValueMapper<Unit> {
  @Override
  public ValueSchema getValueSchema() {
    return ValueSchema.ofStruct(Map.of());
  }

  @Override
  public Result<Unit, String> deserializeValue(final SerializedValue serializedValue) {
    return Result.success(Unit.UNIT);
  }

  @Override
  public SerializedValue serializeValue(final Unit value) {
    return SerializedValue.of(Map.of());
  }
}
