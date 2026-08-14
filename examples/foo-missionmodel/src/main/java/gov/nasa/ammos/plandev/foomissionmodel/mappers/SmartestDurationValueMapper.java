package gov.nasa.jpl.aerie.foomissionmodel.mappers;

import gov.nasa.jpl.aerie.merlin.framework.Result;
import gov.nasa.jpl.aerie.merlin.framework.ValueMapper;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;
import gov.nasa.jpl.aerie.merlin.protocol.types.ValueSchema;

import java.util.Map;

public class SmartestDurationValueMapper implements ValueMapper<Duration> {

  final static String FIELD_NAME = "amountInMicroseconds";

  @Override
  public ValueSchema getValueSchema() {
    return ValueSchema.ofStruct(
        Map.of(FIELD_NAME, ValueSchema.INT)
    );
  }

  @Override
  public Result<Duration, String> deserializeValue(final SerializedValue serializedValue) {
    final var asMap = serializedValue.asMap();
    if(asMap.isEmpty() || !asMap.get().containsKey(FIELD_NAME)){
      return Result.failure("failed to deserialize duration as map or a required field is not present");
    }
    return Result.success(Duration.of(asMap.get().get(FIELD_NAME).asInt().get(), Duration.MICROSECONDS));
  }

  @Override
  public SerializedValue serializeValue(final Duration value) {
    return SerializedValue.of(Map.of(FIELD_NAME, SerializedValue.of(value.in(Duration.MICROSECONDS))));
  }
}
