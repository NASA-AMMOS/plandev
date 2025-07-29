package gov.nasa.jpl.aerie.e2e.types;

import javax.json.JsonObject;

public record ConstraintError(String message, String stack, Location location ){
  record Location(int column, int line){
    public static Location fromJSON(JsonObject json){
      if (!json.containsKey("column") || !json.containsKey("line") || json.isNull("column") || json.isNull("line")) return null;
      else return new Location(json.getJsonNumber("column").intValue(), json.getJsonNumber("line").intValue());
    }
  };

  public static ConstraintError fromJSON(JsonObject json){
    return new ConstraintError(json.getString("message"),json.getString("stack"),Location.fromJSON(json.getJsonObject("location")));
  }
};
