package gov.nasa.jpl.aerie.merlin.framework;

import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;
import gov.nasa.jpl.aerie.merlin.protocol.types.ValueSchema;

/**
 * A mapping between (a) the mission-specific representation of a data type defined by a mission model (b) to a
 * mission-agnostic representation of that data type.
 */
public interface ValueMapper<T> {
  /**
   * Gets a schema for the kind of value understood by this ValueMapper.
   *
   * @return A parameter schema.
   */
  ValueSchema getValueSchema();

  /**
    * Produces an mission model-specific domain value from a mission-agnostic representation.
   *
   * @param serializedValue A mission-agnostic representation of a domain value.
   * @return Either an mission model-specific domain object, or a deserialization failure.
   */
  Result<T, String> deserializeValue(SerializedValue serializedValue);

  /**
   * Produces a mission-agnostic representation of an mission model-specific domain value.
   *
   * @param value An mission model-specific domain object.
   * @return A mission-agnostic representation of {@code value}.
   */
  SerializedValue serializeValue(T value);
}
