package gov.nasa.jpl.aerie.contrib.serialization.mappers;

import com.fasterxml.jackson.core.JsonGenerator;
import gov.nasa.jpl.aerie.merlin.framework.Result;
import gov.nasa.jpl.aerie.merlin.framework.ValueMapper;
import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;
import gov.nasa.jpl.aerie.merlin.protocol.types.ValueSchema;

import java.io.IOException;
import java.util.function.Function;

public final class LongValueMapper implements ValueMapper<Long> {
  @Override
  public ValueSchema getValueSchema() {
    return ValueSchema.INT;
  }

  @Override
  public Result<Long, String> deserializeValue(final SerializedValue serializedValue) {
    return serializedValue
        .asInt()
        .map((Function<Long, Result<Long, String>>) Result::success)
        .orElseGet(() -> Result.failure("Expected integral number, got " + serializedValue.toString()));
  }

  @Override
  public SerializedValue serializeValue(final Long value) {
    return SerializedValue.of(value);
  }

  @Override
  public void writeJson(final Long value, final JsonGenerator gen) throws IOException {
    gen.writeNumber(value);
  }
}
