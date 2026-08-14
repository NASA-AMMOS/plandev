package gov.nasa.jpl.aerie.contrib.serialization.mappers;

import gov.nasa.jpl.aerie.merlin.framework.Result;
import gov.nasa.jpl.aerie.merlin.framework.ValueMapper;
import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;
import gov.nasa.jpl.aerie.merlin.protocol.types.ValueSchema;

public final class NullableValueMapper<T> implements ValueMapper<T> {
  private final ValueMapper<T> valueMapper;

  public NullableValueMapper(final ValueMapper<T> valueMapper) {
    this.valueMapper = valueMapper;
  }

  @Override
  public ValueSchema getValueSchema() {
    return this.valueMapper.getValueSchema();
  }

  @Override
  public Result<T, String> deserializeValue(final SerializedValue serializedValue) {
    if (serializedValue.isNull()) {
      return Result.success(null);
    } else {
      return this.valueMapper.deserializeValue(serializedValue);
    }
  }

  @Override
  public SerializedValue serializeValue(final T value) {
    if (value == null) {
      return SerializedValue.NULL;
    } else {
      return this.valueMapper.serializeValue(value);
    }
  }
}
