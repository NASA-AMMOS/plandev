package gov.nasa.jpl.aerie.e2e.types;

import com.fasterxml.jackson.databind.node.ObjectNode;
public record SimulationResponse(int simDatasetId, String status, ObjectNode reason) {
  public static SimulationResponse fromJSON(ObjectNode json) {
    return new SimulationResponse(
      json.get("simulationDatasetId").intValue(),
      json.get("status").textValue(),
      json.get("reason")
    );
  }
}
