package gov.nasa.jpl.aerie.e2e.types;

import com.fasterxml.jackson.databind.node.ObjectNode;
public record ResourceType(String name, ValueSchema schema){
  public static ResourceType fromJSON(ObjectNode json){
    return new ResourceType(json.get("name").textValue(), ValueSchema.fromJSON(json.get("schema")));
  }
}
