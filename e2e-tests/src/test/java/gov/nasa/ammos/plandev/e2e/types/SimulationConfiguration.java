package gov.nasa.jpl.aerie.e2e.types;

import javax.json.JsonObject;
import java.util.Optional;

public record SimulationConfiguration(
    int id,
    int revision,
    int planId,
    Optional<Integer> simulationTemplateId,
    JsonObject arguments,
    String simulationStartTime,
    String simulationEndTime
) {
  public static SimulationConfiguration fromJSON(JsonObject json) {
    return new SimulationConfiguration(
        json.getInt("id"),
        json.getInt("revision"),
        json.getInt("plan_id"),
        json.isNull("simulation_template_id") ? Optional.empty() : Optional.of(json.getInt("simulation_template_id")),
        json.getJsonObject("arguments"),
        json.getString("simulation_start_time"),
        json.getString("simulation_end_time"));
  }
}
