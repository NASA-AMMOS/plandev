package gov.nasa.jpl.aerie.e2e.types;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Optional;

public record EffectiveActivityArguments(
      String activityType,
      boolean success,
      Optional<ObjectNode> arguments,
      Optional<JsonNode> errors)
{
  public static EffectiveActivityArguments fromJSON(ObjectNode json) {
    return new EffectiveActivityArguments(
        json.get("typeName").textValue(),
        json.get("success").booleanValue(),
        json.has("arguments") ? Optional.of(json.get("arguments")) : Optional.empty(),
        json.has("errors") ? Optional.of(json.get("errors")) : Optional.empty());
  }
}
