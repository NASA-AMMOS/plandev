package gov.nasa.ammos.plandev.e2e.types;

import javax.json.JsonObject;

public record PlanImportResponse(
    int requestId,
    int planId,
    int modelId,
    String status,
    JsonObject reason
) {
    public static PlanImportResponse fromJSON(JsonObject json){
      return new PlanImportResponse(
          json.getInt("id"),
          json.getInt("plan_id"),
          json.getInt("model_id"),
          json.getString("status"),
          json.isNull("reason") ? null : json.getJsonObject("reason")
      );
    }
}
