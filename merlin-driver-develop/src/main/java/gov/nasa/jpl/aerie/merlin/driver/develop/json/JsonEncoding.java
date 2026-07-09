package gov.nasa.jpl.aerie.merlin.driver.develop.json;

import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;

import javax.json.JsonValue;

public final class JsonEncoding {
  public static JsonValue encode(final SerializedValue value) {
    return SerializedValueJsonParser.serializedValueP.unparse(value);
  }

  public static SerializedValue decode(final JsonValue value) {
    return SerializedValueJsonParser.serializedValueP
        .parse(value)
        .getSuccessOrThrow($ -> new Error("Unable to parse JSON as SerializedValue: " + $));
  }
}
