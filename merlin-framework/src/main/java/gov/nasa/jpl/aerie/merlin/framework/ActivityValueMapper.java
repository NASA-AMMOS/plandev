package gov.nasa.jpl.aerie.merlin.framework;

import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;
import gov.nasa.jpl.aerie.merlin.protocol.types.ValueSchema;

import java.util.Map;

public record ActivityValueMapper<T>(ActivityMapper<?, T, ?> activityMapper, String ActivityName) implements ValueMapper<T> {
  @Override
  public ValueSchema getValueSchema() {
    return ValueSchema.withMeta("activity",
                                SerializedValue.of(Map.of("value", SerializedValue.of(ActivityName))),
                                activityMapper.getInputAsOutput().getSchema());
  }

  public Result<T, String> deserialize(
      final Map<String, SerializedValue> arguments) {
    try {
      return Result.success(activityMapper.getInputType().instantiate(arguments));
    } catch (Exception e) {
      return Result.failure("Failed to instantiate activity DecomposingSpawnChild. Error: %s".formatted(e.getMessage()));
    }
  }

  @Override
  public Result<T, String> deserializeValue(
      final SerializedValue serializedValue) {
    return serializedValue
        .asMap()
        .map(Map::copyOf)
        .map(arguments -> deserialize(arguments))
        .orElseGet(() -> Result.failure("Expected map from string to serialized value, but got: " + serializedValue));
  }

  @Override
  public SerializedValue serializeValue(
      final T value) {
    return activityMapper.getInputAsOutput().serialize(value);
  }
}
