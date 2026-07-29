package gov.nasa.ammos.plandev.e2e.types;

import javax.json.JsonObject;
import java.util.Optional;

public record ConstraintError(String message, String stack, Optional<Location> location ){
  record Location(int column, int line){
    public static Location fromJSON(JsonObject json){
      return new Location(json.getJsonNumber("column").intValue(), json.getJsonNumber("line").intValue());
    }
  }

  public static ConstraintError fromJSON(JsonObject json){
    return new ConstraintError(
        json.getString("message"),
        json.getString("stack"),
        json.getJsonObject("location").isEmpty() ?
            Optional.empty() :
            Optional.of(Location.fromJSON(json.getJsonObject("location"))));
  }
}
