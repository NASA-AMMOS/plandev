package gov.nasa.jpl.aerie.e2e.types;

import javax.json.JsonObject;
import java.util.Optional;

public record SchedulingRequest(
    int analysisId,
    int specificationId,
    int specificationRevision,
    SchedulingStatus status,
    boolean canceled,
    Optional<SchedulingReason> reason
) {
  public enum SchedulingStatus { pending, incomplete, failed, success }

  public record SchedulingReason(
      String type,
      String message,
      String trace,
      JsonObject data
  )
  {
    public static SchedulingReason fromJSON(JsonObject json) {
      return new SchedulingReason(
          json.getString("type"),
          json.getString("message"),
          json.getString("trace"),
          json.getJsonObject("data")
      );
    }
  }

  public static SchedulingRequest fromJSON(JsonObject json) {
    return new SchedulingRequest(
        json.getInt("analysis_id"),
        json.getInt("specification_id"),
        json.getInt("specification_revision"),
        SchedulingStatus.valueOf(json.getString("status")),
        json.getBoolean("canceled"),
        json.isNull("reason") ? Optional.empty() : Optional.of(SchedulingReason.fromJSON(json.getJsonObject("reason")))
    );
  }
}
