package gov.nasa.jpl.aerie.e2e.types;

import javax.json.JsonObject;

public record TypescriptFile(String filePath, String content){
  public static TypescriptFile fromJSON(JsonObject json){
    return new TypescriptFile(json.getString("filePath"), json.getString("content"));
  }

}
