package gov.nasa.jpl.aerie.contrib.serialization.mappers;

import gov.nasa.jpl.aerie.merlin.framework.Result;
import gov.nasa.jpl.aerie.merlin.framework.ValueMapper;
import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;
import gov.nasa.jpl.aerie.merlin.protocol.types.ValueSchema;

import java.nio.file.Path;
import java.util.function.Function;

public final class PathValueMapper implements ValueMapper<Path> {
  @Override
  public ValueSchema getValueSchema() {
    return ValueSchema.PATH;
  }

  @Override
  public Result<Path, String> deserializeValue(final SerializedValue serializedValue) {
    return serializedValue
        .asString()
        .map(Path::of)
        .map((Function<Path, Result<Path, String>>) Result::success)
        .orElseGet(() -> Result.failure("Expected path, got " + serializedValue.toString()));
  }

  @Override
  public SerializedValue serializeValue(final Path value) {
    return SerializedValue.of(value.toAbsolutePath().toString());
  }
}
