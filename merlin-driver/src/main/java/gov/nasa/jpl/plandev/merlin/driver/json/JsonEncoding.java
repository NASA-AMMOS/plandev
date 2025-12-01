package gov.nasa.jpl.plandev.merlin.driver.json;

import gov.nasa.jpl.plandev.merlin.protocol.types.SerializedValue;

import javax.json.JsonValue;

import static gov.nasa.jpl.plandev.merlin.driver.json.SerializedValueJsonParser.serializedValueP;

public final class JsonEncoding {
  public static JsonValue encode(final SerializedValue value) {
    return serializedValueP.unparse(value);
  }

  public static SerializedValue decode(final JsonValue value) {
    return serializedValueP
        .parse(value)
        .getSuccessOrThrow($ -> new Error("Unable to parse JSON as SerializedValue: " + $));
  }
}
