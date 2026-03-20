package gov.nasa.jpl.aerie.serializationbenchmark;

import com.fasterxml.jackson.databind.JsonNode;
import gov.nasa.jpl.aerie.merlin.driver.json.JsonEncoding;
import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;

/**
 * Delegates to JsonEncoding, which now uses direct Jackson internally.
 */
public final class JacksonEncoding {
  public static JsonNode encode(final SerializedValue value) {
    return JsonEncoding.encode(value);
  }

  public static SerializedValue decode(final JsonNode node) {
    return JsonEncoding.decode(node);
  }

  public static String encodeToString(final SerializedValue value) {
    return JsonEncoding.encodeToString(value);
  }

  public static SerializedValue decodeFromString(final String json) {
    return JsonEncoding.decodeFromString(json);
  }
}
