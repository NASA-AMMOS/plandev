package gov.nasa.jpl.aerie.contrib.serialization.mappers;

import com.fasterxml.jackson.core.JsonGenerator;
import gov.nasa.jpl.aerie.merlin.framework.Result;
import gov.nasa.jpl.aerie.merlin.framework.ValueMapper;
import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;
import gov.nasa.jpl.aerie.merlin.protocol.types.ValueSchema;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

public final class RecordValueMapper<R extends Record> implements ValueMapper<R> {
  private final Class<R> recordType;
  private final List<Component<R, ?>> components;

  public RecordValueMapper(
      final Class<R> recordType,
      final List<Component<R, ?>> components
  ) {
    this.recordType = recordType;
    this.components = components;
  }

  public record Component<R, T>(
      String name,
      Function<R, T> projection,
      ValueMapper<T> mapper
  ) {}

  @Override
  public ValueSchema getValueSchema() {
    final var valueSchemas = new HashMap<String, ValueSchema>();
    final var metadata = new ArrayList<SerializedValue>();
    for (final var component : this.components) {
      metadata.add(SerializedValue.of(component.name));
      valueSchemas.put(component.name,
                       component.mapper.getValueSchema());
    }
    return components.isEmpty() ?
        ValueSchema.ofStruct(valueSchemas) :
        ValueSchema.withMeta("item_order", SerializedValue.of(metadata), ValueSchema.ofStruct(valueSchemas));
  }

  @Override
  public Result<R, String> deserializeValue(final SerializedValue serializedValue) {
    return serializedValue
        .asMap()
        .map((Function<Map<String, SerializedValue>, Result<R, String>>) (map -> {
          final var validKeys = new HashSet<String>();
          for (final var component : components) {
            if (!map.containsKey(component.name)) return Result.failure("Record missing key %s".formatted(component));
            validKeys.add(component.name);
          }
          for (final var key : map.keySet()) {
            if (!validKeys.contains(key)) return Result.failure("Record has extra key %s".formatted(key));
          }
          final var arguments = new Object[components.size()];
          for (var i = 0; i < components.size(); i++) {
            final var component = components.get(i);
            final var deserializedValue = component.mapper.deserializeValue(map.get(component.name));
            if (deserializedValue.getKind() == Result.Kind.Failure) return Result.failure("Failed to deserialize %s".formatted(component.name));
            arguments[i] = deserializedValue.getSuccessOrThrow();
          }
          try {
            return Result.success(getCanonicalConstructor(this.recordType).newInstance(arguments));
          } catch (InstantiationException | IllegalAccessException | InvocationTargetException | NoSuchMethodException e) {
            return Result.failure(e.toString());
          }
        }))
        .orElseGet(() -> Result.failure("Expected struct, got: "));
  }

  @Override
  public SerializedValue serializeValue(final R value) {
    final var map = new HashMap<String, SerializedValue>();
    for (final var component : components) {
        map.put(component.name, serializeHelper(value, component));
    }
    return SerializedValue.of(map);
  }

  @Override
  public void writeJson(final R value, final JsonGenerator gen) throws IOException {
    gen.writeStartObject();
    for (final var component : components) {
      gen.writeFieldName(component.name);
      writeJsonHelper(value, component, gen);
    }
    gen.writeEndObject();
  }

  private <T> void writeJsonHelper(final R value, final Component<R, T> component, final JsonGenerator gen) throws IOException {
    component.mapper.writeJson(component.projection.apply(value), gen);
  }

  private <T> SerializedValue serializeHelper(final R value, final Component<R, T> component) {
    return component.mapper.serializeValue(component.projection.apply(value));
  }

  private static <R> Constructor<R> getCanonicalConstructor(final Class<R> cls) throws NoSuchMethodException
  {
    final var paramTypes =
        Arrays.stream(cls.getRecordComponents())
              .map(RecordComponent::getType)
              .toArray(Class<?>[]::new);
    return cls.getDeclaredConstructor(paramTypes);
  }
}
