package gov.nasa.jpl.aerie.json;

import javax.json.Json;
import javax.json.JsonObject;
import javax.json.JsonString;
import javax.json.JsonValue;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Map;

public final class PathJsonParser implements JsonParser<Path> {
  public static final PathJsonParser pathP = new PathJsonParser();

  @Override
  public JsonObject getSchema(final SchemaCache anchors) {
    return Json
        .createObjectBuilder()
        .add("type", "string")
        .add("pattern", "(?:/?[^/]+)(?:/[^/]+)*/?")
        .build();
  }

  @Override
  public JsonParseResult<Path> parse(final JsonValue json) {
    if (!(json instanceof JsonString str)) return JsonParseResult.failure("expected string");

    try {
      return JsonParseResult.success(Path.of(str.getString()));
    } catch (final InvalidPathException ex) {
      return JsonParseResult.failure("invalid path");
    }
  }

  @Override
  public JsonValue unparse(final Path value) {
    return Json.createValue(value.toString());
  }
}
