package gov.nasa.jpl.aerie.json;

import javax.json.Json;
import javax.json.JsonObject;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class SchemaCache {
  private final Map<JsonParser<?>, String> anchors = new HashMap<>();

  public JsonObject lookup(final JsonParser<?> parser) {
    if (this.anchors.containsKey(parser)) {
      return Json.createObjectBuilder().add("$ref", "#" + this.anchors.get(parser)).build();
    } else {
      final var anchor = this.add(parser);

      return Json
          .createObjectBuilder()
          .add("$anchor", anchor)
          .addAll(Json.createObjectBuilder(parser.getSchema(this)))
          .build();
    }
  }

  public JsonObject lookupUncached(final JsonParser<?> parser) {
    if (this.anchors.containsKey(parser)) {
      return Json.createObjectBuilder().add("$ref", "#" + this.anchors.get(parser)).build();
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
