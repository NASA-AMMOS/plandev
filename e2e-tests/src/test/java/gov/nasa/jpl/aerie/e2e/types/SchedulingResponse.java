package gov.nasa.jpl.aerie.e2e.types;

import com.fasterxml.jackson.databind.node.ObjectNode;
public record SchedulingResponse(
    int analysisId,
    Integer datasetId,
    String status,
    ObjectNode reason
){
  public static SchedulingResponse fromJSON(ObjectNode json){
    return new SchedulingResponse(
      json.get("analysisId").intValue(),
      json.has("datasetId") ? json.get("datasetId").intValue() : null,
      json.get("status").textValue(),
      json.get("reason")
    );
  }
}
