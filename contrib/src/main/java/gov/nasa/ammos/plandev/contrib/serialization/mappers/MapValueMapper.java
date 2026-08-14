package gov.nasa.jpl.aerie.contrib.serialization.mappers;

import gov.nasa.jpl.aerie.merlin.framework.Result;
import gov.nasa.jpl.aerie.merlin.framework.ValueMapper;
import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;
import gov.nasa.jpl.aerie.merlin.protocol.types.ValueSchema;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

public final class MapValueMapper<K, V> implements ValueMapper<Map<K, V>> {
  private final ValueMapper<K> keyMapper;
  private final ValueMapper<V> elementMapper;

  public MapValueMapper(final ValueMapper<K> keyMapper, final ValueMapper<V> elementMapper) {
    this.keyMapper = keyMapper;
    this.elementMapper = elementMapper;
  }

  @Override
  public ValueSchema getValueSchema() {
    return ValueSchema.ofSeries(ValueSchema.ofStruct(Map.of(
        "key", keyMapper.getValueSchema(),
        "value", elementMapper.getValueSchema())));
  }

  @Override
  public Result<Map<K, V>, String> deserializeValue(final SerializedValue serializedValue) {
    return serializedValue
        .asList()
        .map((Function<List<SerializedValue>, Result<List<SerializedValue>, String>>) Result::success)
        .orElseGet(() -> Result.failure("Expected list, got " + serializedValue.toString()))
        .match(
            serializedList -> {
              final var map = new HashMap<K, V>();
              for (final SerializedValue element : serializedList) {
                final var elementResult = element
                    .asMap()
                    .map((Function<Map<String, SerializedValue>, Result<Map<String, SerializedValue>, String>>) Result::success)
                    .orElseGet(() -> Result.failure("Expected map, got " + element));

                if (elementResult.getKind().equals(Result.Kind.Failure)) return Result.failure(elementResult.getFailureOrThrow());
                final var elementSpec = elementResult.getSuccessOrThrow();

                final var keyResult = keyMapper
                    .deserializeValue(elementSpec.get("key"));
                if (keyResult.getKind().equals(Result.Kind.Failure)) return Result.failure(keyResult.getFailureOrThrow());
                final var key = keyResult.getSuccessOrThrow();

                final var valueResult = elementMapper
                    .deserializeValue(elementSpec.get("value"));
                if (valueResult.getKind().equals(Result.Kind.Failure)) return Result.failure(valueResult.getFailureOrThrow());
                final var value = valueResult.getSuccessOrThrow();

                map.put(key, value);
              }

              return Result.success(map);
            },
            Result::failure
        );
  }

  @Override
  public SerializedValue serializeValue(final Map<K, V> fields) {
    final List<SerializedValue> elementSpecs = new ArrayList<>(fields.size());
    for (final var entry : fields.entrySet()) {
      elementSpecs.add(SerializedValue.of(
          Map.of(
              "key", keyMapper.serializeValue(entry.getKey()),
              "value", elementMapper.serializeValue(entry.getValue()))));
    }
    return SerializedValue.of(elementSpecs);
  }
}
