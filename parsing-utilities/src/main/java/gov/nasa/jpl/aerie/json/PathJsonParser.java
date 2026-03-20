package gov.nasa.jpl.aerie.json;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;

public final class PathJsonParser implements JsonParser<Path> {
  public static final PathJsonParser pathP = new PathJsonParser();

  @Override
  public ObjectNode getSchema(final SchemaCache anchors) {
    final var node = JsonNodeFactory.instance.objectNode();
    node.put("type", "string");
    node.put("pattern", "(?:/?[^/]+)(?:/[^/]+)*/?");
    return node;
  }

  @Override
  public JsonParseResult<Path> parse(final JsonNode json) {
    if (!json.isTextual()) return JsonParseResult.failure("expected string");

    try {
      return JsonParseResult.success(Path.of(json.textValue()));
    } catch (final InvalidPathException ex) {
      return JsonParseResult.failure("invalid path");
    }
  }

  @Override
  public JsonNode unparse(final Path value) {
    return TextNode.valueOf(value.toString());
  }
}
