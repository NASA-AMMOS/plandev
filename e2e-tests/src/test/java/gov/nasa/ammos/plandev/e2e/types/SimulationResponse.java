package gov.nasa.jpl.aerie.e2e.types;

import javax.json.JsonObject;

public record SimulationResponse(int simDatasetId, String status, JsonObject reason) {
  public static SimulationResponse fromJSON(JsonObject json) {
    return new SimulationResponse(
      json.getInt("simulationDatasetId"),
      json.getString("status"),
      json.getJsonObject("reason")
    );
  }
}
