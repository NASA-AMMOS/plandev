package gov.nasa.jpl.aerie.e2e.types;

import javax.json.JsonObject;
import javax.json.JsonValue;
import java.util.Optional;

public record EffectiveActivityArguments(
      String activityType,
      boolean success,
      Optional<JsonObject> arguments,
      Optional<JsonValue> errors)
{
  public static EffectiveActivityArguments fromJSON(JsonObject json) {
    return new EffectiveActivityArguments(
        json.getString("typeName"),
        json.getBoolean("success"),
        json.containsKey("arguments") ? Optional.of(json.getJsonObject("arguments")) : Optional.empty(),
        json.containsKey("errors") ? Optional.of(json.get("errors")) : Optional.empty());
  }
}
