package gov.nasa.jpl.aerie.e2e.types;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Optional;

public record ConstraintError(String message, String stack, Optional<Location> location ){
  record Location(int column, int line){
    public static Location fromJSON(ObjectNode json){
      return new Location(json.get("column").intValue(), json.get("line").intValue());
    }
  };

  public static ConstraintError fromJSON(ObjectNode json){
    return new ConstraintError(
        json.get("message").textValue(),
        json.get("stack").textValue(),
        json.get("location").isEmpty() ?
            Optional.empty() :
            Optional.of(Location.fromJSON(json.get("location"))));
  }
};
