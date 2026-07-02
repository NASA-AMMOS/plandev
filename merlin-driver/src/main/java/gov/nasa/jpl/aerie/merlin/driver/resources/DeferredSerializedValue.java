package gov.nasa.jpl.aerie.merlin.driver.resources;

import com.fasterxml.jackson.core.JsonGenerator;
import gov.nasa.jpl.aerie.merlin.protocol.model.OutputType;
import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;

import java.io.IOException;
import java.util.Objects;

/**
 * Wraps a raw Java value and its OutputType, deferring serialization until needed.
 * When streaming to JSON, calls OutputType.writeJson() directly, bypassing SerializedValue entirely.
 * When SerializedValue is needed (e.g., for constraints/scheduler), lazily computes and caches it.
 */
public final class DeferredSerializedValue {
  private final Object rawValue;
  private final OutputType<Object> outputType;
  private volatile SerializedValue cached;

  @SuppressWarnings("unchecked")
  public <T> DeferredSerializedValue(final T rawValue, final OutputType<T> outputType) {
    this.rawValue = rawValue;
    this.outputType = (OutputType<Object>) outputType;
  }

  public SerializedValue toSerializedValue() {
    var result = cached;
    if (result == null) {
      result = outputType.serialize(rawValue);
      cached = result;
    }
    return result;
  }

  public void writeJson(final JsonGenerator gen) throws IOException {
    outputType.writeJson(rawValue, gen);
  }

  public boolean sameValueAs(final DeferredSerializedValue other) {
    return other != null && Objects.equals(this.rawValue, other.rawValue);
  }
}
