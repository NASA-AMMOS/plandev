package gov.nasa.ammos.plandev.e2e.types;

import javax.json.JsonObject;

public record PlanImportRequest(int requestId, int planId, int modelId) {
  public static PlanImportRequest fromJson(JsonObject obj) {
    return new PlanImportRequest(
        obj.getInt("plan_import_request_id"),
        obj.getInt("plan_id"),
        obj.getInt("model_id")
    );
  }
}
