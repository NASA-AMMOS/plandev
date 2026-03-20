package gov.nasa.jpl.aerie.json;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class SchemaCache {
  private final Map<JsonParser<?>, String> anchors = new HashMap<>();

  public ObjectNode lookup(final JsonParser<?> parser) {
    if (this.anchors.containsKey(parser)) {
      final var node = JsonNodeFactory.instance.objectNode();
      node.put("$ref", "#" + this.anchors.get(parser));
      return node;
    } else {
      final var anchor = this.add(parser);
      final var node = JsonNodeFactory.instance.objectNode();
      node.put("$anchor", anchor);
      node.setAll(parser.getSchema(this));
      return node;
    }
  }

  public ObjectNode lookupUncached(final JsonParser<?> parser) {
    if (this.anchors.containsKey(parser)) {
      final var node = JsonNodeFactory.instance.objectNode();
      node.put("$ref", "#" + this.anchors.get(parser));
      return node;
    } else {
      return parser.getSchema();
    }
  }

  public String add(final JsonParser<?> parser) {
    final var anchor = "_" + UUID.randomUUID();
    this.anchors.put(parser, anchor);
    return anchor;
  }
}
