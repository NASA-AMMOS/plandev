package gov.nasa.jpl.aerie.e2e.types;

import com.fasterxml.jackson.databind.node.ObjectNode;
public record TypescriptFile(String filePath, String content){
  public static TypescriptFile fromJSON(ObjectNode json){
    return new TypescriptFile(json.get("filePath").textValue(), json.get("content").textValue());
  }

}
