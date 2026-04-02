package gov.nasa.jpl.aerie.merlin.driver.json;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;

import javax.json.JsonValue;
import java.io.IOException;
import java.io.StringWriter;

import static gov.nasa.jpl.aerie.merlin.driver.json.SerializedValueJsonParser.serializedValueP;

public final class JsonEncoding {
  private static final JsonFactory JSON_FACTORY = new JsonFactory();

  public static JsonValue encode(final SerializedValue value) {
    return serializedValueP.unparse(value);
  }

  public static SerializedValue decode(final JsonValue value) {
    return serializedValueP
        .parse(value)
        .getSuccessOrThrow($ -> new Error("Unable to parse JSON as SerializedValue: " + $));
  }

  public static void writeToGenerator(final SerializedValue value, final JsonGenerator gen) throws IOException {
    value.writeTo(gen);
  }

  public static String writeToString(final SerializedValue value) throws IOException {
    return value.toJsonString();
  }

  public static JsonGenerator createGenerator(final StringWriter writer) throws IOException {
    return JSON_FACTORY.createGenerator(writer);
  }

  public static JsonFactory getJsonFactory() {
    return JSON_FACTORY;
  }
}
