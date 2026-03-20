package gov.nasa.jpl.aerie.e2e.types;

import com.fasterxml.jackson.databind.node.ObjectNode;
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
      ObjectNode data
  )
  {
    public static SchedulingReason fromJSON(ObjectNode json) {
      return new SchedulingReason(
          json.get("type").textValue(),
          json.get("message").textValue(),
          json.get("trace").textValue(),
          json.get("data")
      );
    }
  }

  public static SchedulingRequest fromJSON(ObjectNode json) {
    return new SchedulingRequest(
        json.get("analysis_id").intValue(),
        json.get("specification_id").intValue(),
        json.get("specification_revision").intValue(),
        SchedulingStatus.valueOf(json.get("status").textValue()),
        json.get("canceled").booleanValue(),
        (json.get("reason") == null || json.get("reason").isNull()) ? Optional.empty() : Optional.of(SchedulingReason.fromJSON(json.get("reason")))
    );
  }
}
