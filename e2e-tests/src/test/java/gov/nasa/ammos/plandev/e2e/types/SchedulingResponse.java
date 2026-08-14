package gov.nasa.jpl.aerie.e2e.types;

import javax.json.JsonObject;

public record SchedulingResponse(
    int analysisId,
    Integer datasetId,
    String status,
    JsonObject reason
){
  public static SchedulingResponse fromJSON(JsonObject json){
    return new SchedulingResponse(
      json.getInt("analysisId"),
      json.containsKey("datasetId") ? json.getInt("datasetId") : null,
      json.getString("status"),
      json.getJsonObject("reason")
    );
  }
}
