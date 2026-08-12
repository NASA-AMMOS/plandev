package gov.nasa.jpl.aerie.contrib.serialization.mappers;

import com.fasterxml.jackson.core.JsonGenerator;
import gov.nasa.jpl.aerie.merlin.framework.Result;
import gov.nasa.jpl.aerie.merlin.framework.ValueMapper;
import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;
import gov.nasa.jpl.aerie.merlin.protocol.types.ValueSchema;

import java.io.IOException;
import java.math.BigDecimal;
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

  @Override
  public void writeJson(final Double value, final JsonGenerator gen) throws IOException {
    gen.writeNumber(BigDecimal.valueOf(value));
  }
}
