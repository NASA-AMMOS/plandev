package gov.nasa.jpl.plandev.contrib.serialization.mappers;

import gov.nasa.jpl.plandev.merlin.framework.Result;
import gov.nasa.jpl.plandev.merlin.framework.ValueMapper;
import gov.nasa.jpl.plandev.merlin.protocol.types.SerializedValue;
import gov.nasa.jpl.plandev.merlin.protocol.types.ValueSchema;

import java.util.function.Function;

public final class FloatValueMapper implements ValueMapper<Float> {
  @Override
  public ValueSchema getValueSchema() {
    return ValueSchema.REAL;
  }

  @Override
  public Result<Float, String> deserializeValue(final SerializedValue serializedValue) {
    return serializedValue
        .asReal()
        .map((Function<Double, Result<Double, String>>) Result::success)
        .orElseGet(() -> Result.failure("Expected real number, got " + serializedValue.toString()))
        .mapSuccess(Number::floatValue);
  }

  @Override
  public SerializedValue serializeValue(final Float value) {
    return SerializedValue.of(value);
  }
}
