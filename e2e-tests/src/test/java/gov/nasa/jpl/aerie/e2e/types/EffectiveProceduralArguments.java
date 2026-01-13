package gov.nasa.jpl.aerie.e2e.types;

import javax.json.JsonObject;
import javax.json.JsonValue;
import java.util.Optional;

public record EffectiveProceduralArguments(
    int goalId,
    boolean success,
    Optional<JsonObject> arguments,
    Optional<JsonValue> errors)
{
  public static EffectiveProceduralArguments fromJSON(JsonObject json) {
    return new EffectiveProceduralArguments(
        json.getInt("id"),
        json.getBoolean("success"),
        json.containsKey("arguments") ? Optional.of(json.getJsonObject("arguments")) : Optional.empty(),
        json.containsKey("errors") ? Optional.of(json.get("errors")) : Optional.empty());
  }
}
