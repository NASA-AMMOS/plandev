package gov.nasa.jpl.aerie.e2e.types;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Optional;

public record EffectiveProceduralArguments(
    int goalId,
    boolean success,
    Optional<ObjectNode> arguments,
    Optional<JsonNode> errors)
{
  public static EffectiveProceduralArguments fromJSON(ObjectNode json) {
    return new EffectiveProceduralArguments(
        json.get("id").intValue(),
        json.get("success").booleanValue(),
        json.has("arguments") ? Optional.of(json.get("arguments")) : Optional.empty(),
        json.has("errors") ? Optional.of(json.get("errors")) : Optional.empty());
  }
}
