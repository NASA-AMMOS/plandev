package gov.nasa.jpl.aerie.e2e.types;

import javax.json.JsonObject;

public record ResourceType(String name, ValueSchema schema, String description) {
  public static ResourceType fromJSON(JsonObject json){
    final var description = json.isNull("description") ? null : json.getString("description");
    return new ResourceType(json.getString("name"), ValueSchema.fromJSON(json.getJsonObject("schema")), description);
  }
  public ResourceType(String name, ValueSchema schema) {
    this(name, schema, null);
  }
}
