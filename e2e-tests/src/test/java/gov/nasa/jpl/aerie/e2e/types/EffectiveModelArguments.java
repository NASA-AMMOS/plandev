package gov.nasa.jpl.aerie.e2e.types;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Optional;

public record EffectiveModelArguments(
    boolean success,
    Optional<ObjectNode> arguments,
    Optional<JsonNode> errors)
{
  public static EffectiveModelArguments fromJSON(ObjectNode json) {
    return new EffectiveModelArguments(
        json.get("success").booleanValue(),
        json.has("arguments") ? Optional.of(json.get("arguments")) : Optional.empty(),
        json.has("errors") ? Optional.of(json.get("errors")) : Optional.empty());
  }
}
