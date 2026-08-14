package gov.nasa.jpl.aerie.e2e.types;

import javax.json.JsonObject;

public record ResourceType(String name, ValueSchema schema){
  public static ResourceType fromJSON(JsonObject json){
    return new ResourceType(json.getString("name"), ValueSchema.fromJSON(json.getJsonObject("schema")));
  }
}
