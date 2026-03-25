package gov.nasa.jpl.aerie.contrib.serialization.mappers;

import com.fasterxml.jackson.core.JsonGenerator;
import gov.nasa.jpl.aerie.merlin.framework.Result;
import gov.nasa.jpl.aerie.merlin.framework.ValueMapper;
import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;
import gov.nasa.jpl.aerie.merlin.protocol.types.ValueSchema;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

public final class ListValueMapper<T> implements ValueMapper<List<T>> {
  private final ValueMapper<T> elementMapper;

  public ListValueMapper(final ValueMapper<T> elementMapper) {
    this.elementMapper = elementMapper;
  }

  @Override
  public ValueSchema getValueSchema() {
    return ValueSchema.ofSeries(elementMapper.getValueSchema());
  }

  @Override
  public Result<List<T>, String> deserializeValue(final SerializedValue serializedValue) {
    return serializedValue
        .asList()
        .map((Function<List<SerializedValue>, Result<List<SerializedValue>, String>>) Result::success)
        .orElseGet(() -> Result.failure("Expected list, got " + serializedValue.toString()))
        .match(
            serializedElements -> {
              final var elements = new ArrayList<T>();
              for (final var serializedElement : serializedElements) {
                final var result = this.elementMapper.deserializeValue(serializedElement);
                if (result.getKind() == Result.Kind.Failure) return result.mapSuccess(_left -> null);

                // SAFETY: `result` must be a Success variant.
                elements.add(result.getSuccessOrThrow());
              }
              return Result.success(elements);
            },
            Result::failure
        );
  }

  @Override
  public SerializedValue serializeValue(final List<T> elements) {
    final var serializedElements = new ArrayList<SerializedValue>();
    for (final var element : elements) {
      serializedElements.add(this.elementMapper.serializeValue(element));
    }
    return SerializedValue.of(serializedElements);
  }

  @Override
  public void writeJson(final List<T> elements, final JsonGenerator gen) throws IOException {
    gen.writeStartArray();
    for (final var element : elements) {
      this.elementMapper.writeJson(element, gen);
    }
    gen.writeEndArray();
  }
}
