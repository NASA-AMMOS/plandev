package gov.nasa.jpl.plandev.contrib.serialization.mappers;

import gov.nasa.jpl.plandev.merlin.framework.Result;
import gov.nasa.jpl.plandev.merlin.framework.ValueMapper;
import gov.nasa.jpl.plandev.merlin.protocol.types.Duration;
import gov.nasa.jpl.plandev.merlin.protocol.types.SerializedValue;
import gov.nasa.jpl.plandev.merlin.protocol.types.ValueSchema;

import java.util.function.Function;

public final class DurationValueMapper implements ValueMapper<Duration> {
  @Override
  public ValueSchema getValueSchema() {
    return ValueSchema.DURATION;
  }

  @Override
  public Result<Duration, String> deserializeValue(final SerializedValue serializedValue) {
    return serializedValue
        .asInt()
        .map(v -> Duration.of(v, Duration.MICROSECONDS))
        .map((Function<Duration, Result<Duration, String>>) Result::success)
        .orElseGet(() -> Result.failure("Expected integer, got " + serializedValue.toString()));
  }

  @Override
  public SerializedValue serializeValue(final Duration value) {
    return SerializedValue.of(value.in(Duration.MICROSECONDS));
  }
}
