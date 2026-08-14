package gov.nasa.jpl.aerie.contrib.serialization.mappers;

import gov.nasa.jpl.aerie.merlin.framework.Result;
import gov.nasa.jpl.aerie.merlin.framework.ValueMapper;
import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;
import gov.nasa.jpl.aerie.merlin.protocol.types.ValueSchema;

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
