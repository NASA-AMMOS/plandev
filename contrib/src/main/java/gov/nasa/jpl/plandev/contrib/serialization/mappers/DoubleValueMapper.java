package gov.nasa.jpl.plandev.contrib.serialization.mappers;

import gov.nasa.jpl.plandev.merlin.framework.Result;
import gov.nasa.jpl.plandev.merlin.framework.ValueMapper;
import gov.nasa.jpl.plandev.merlin.protocol.types.SerializedValue;
import gov.nasa.jpl.plandev.merlin.protocol.types.ValueSchema;

import java.util.function.Function;

public final class DoubleValueMapper implements ValueMapper<Double> {
  @Override
  public ValueSchema getValueSchema() {
    return ValueSchema.REAL;
  }

  @Override
  public Result<Double, String> deserializeValue(final SerializedValue serializedValue) {
    return serializedValue
        .asReal()
        .map((Function<Double, Result<Double, String>>) Result::success)
        .orElseGet(() -> Result.failure("Expected real number, got " + serializedValue.toString()));
  }

  @Override
  public SerializedValue serializeValue(final Double value) {
    return SerializedValue.of(value);
  }
}
