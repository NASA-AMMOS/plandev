package gov.nasa.jpl.aerie.merlin.protocol.model;

import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;
import gov.nasa.jpl.aerie.merlin.protocol.types.ValueSchema;

/**
 * A type of data produced as output by a Merlin model.
 *
 * <p> An implementation of this interface provides reflective, at-a-distance access to an abstract type {@code T}
 * produced by a Merlin model as output. Values of type {@code T} can be {@linkplain #serialize(T) extracted} into
 * a model-agnostic format with a consistent {@linkplain #getSchema() schema} conformed to by all such values. </p>
 *
 * <p> Although it might seem unnecessary for the model to return a value of an opaque type when our only capability
 * is to serialize it to another form, it is particularly useful for a simulation system to be able to separate
 * production of output and serialization. Data transformation can be costly, and offloading serialization to a separate
 * thread or stage of the processing pipeline can allow simulation to proceed at a faster rate. </p>
 *
 * @param <T>
 *   The abstract type of output described by this object.
 */
public interface OutputType<T> {
  /** Gets the schema describing all values produced by {@link #serialize(T)}. */
  ValueSchema getSchema();

  /** Extracts a value conforming to this type's {@linkplain #getSchema() schema} from an opaque value of type {@code T}. */
  SerializedValue serialize(T value);
}
