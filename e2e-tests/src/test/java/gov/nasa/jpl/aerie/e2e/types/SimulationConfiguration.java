package gov.nasa.jpl.aerie.e2e.types;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Optional;

public record SimulationConfiguration(
    int id,
    int revision,
    int planId,
    Optional<Integer> simulationTemplateId,
    ObjectNode arguments,
    String simulationStartTime,
    String simulationEndTime
) {
  public static SimulationConfiguration fromJSON(ObjectNode json) {
    return new SimulationConfiguration(
        json.get("id").intValue(),
        json.get("revision").intValue(),
        json.get("plan_id").intValue(),
        (json.get("simulation_template_id") == null || json.get("simulation_template_id").isNull()) ? Optional.empty() : Optional.of(json.get("simulation_template_id").intValue()),
        json.get("arguments"),
        json.get("simulation_start_time").textValue(),
        json.get("simulation_end_time").textValue());
  }
}
