package gov.nasa.jpl.plandev.contrib.serialization.mappers;

import gov.nasa.jpl.plandev.merlin.framework.Result;
import gov.nasa.jpl.plandev.merlin.framework.ValueMapper;
import gov.nasa.jpl.plandev.merlin.protocol.types.SerializedValue;
import gov.nasa.jpl.plandev.merlin.protocol.types.ValueSchema;

import java.util.function.Function;

public final class StringValueMapper implements ValueMapper<String> {
  @Override
  public ValueSchema getValueSchema() {
    return ValueSchema.STRING;
  }

  @Override
  public Result<String, String> deserializeValue(final SerializedValue serializedValue) {
    return serializedValue
        .asString()
        .map((Function<String, Result<String, String>>) Result::success)
        .orElseGet(() -> Result.failure("Expected string, got " + serializedValue.toString()));
  }

  @Override
  public SerializedValue serializeValue(final String value) {
    return SerializedValue.of(value);
  }
}
