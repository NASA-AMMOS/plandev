package gov.nasa.jpl.aerie.merlin.framework;

import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;
import gov.nasa.jpl.aerie.merlin.protocol.types.ValueSchema;

import java.lang.annotation.Annotation;
import java.util.List;
import java.util.Map;

public record MetadataValueMapper<T>(String label, SerializedValue metadata, ValueMapper<T> target) implements ValueMapper<T> {
  @Override
  public ValueSchema getValueSchema() {
    return ValueSchema.withMeta(label, metadata, target.getValueSchema());
  }

  @Override
  public Result<T, String> deserializeValue(final SerializedValue serializedValue) {
    return target.deserializeValue(serializedValue);
  }

  @Override
  public SerializedValue serializeValue(final T value) {
    return target.serializeValue(value);
  }

  public static <T, A extends Annotation> ValueMapper<T> annotationValueMapper(String key, ValueMapper<T> TValueMapper, ValueMapper<A> annotationValueMapper, A annotation) {
    return new MetadataValueMapper<>(key, annotationValueMapper.serializeValue(annotation), TValueMapper);
  }
}
