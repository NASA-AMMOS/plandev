package gov.nasa.jpl.aerie.e2e.types;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Optional;
import java.util.stream.StreamSupport;

public record SimulationDataset(
    SimulationStatus status,
    Optional<SimulationReason> reason,
    boolean canceled,
    String simStartTime,
    String simEndTime,
    List<SimulatedActivity> activities,
    Integer datasetId) {
  public record SimulatedActivity(
      int spanId,
      Integer directiveId,
      Integer parentId,
      String duration,
      String startTime,
      String startOffset,
      String type
      ) {
    public static SimulatedActivity fromJSON(ObjectNode json) {
      return new SimulatedActivity(
          json.get("id").intValue(),
          (json.get("activity_directive") == null || json.get("activity_directive").isNull()) ? null : json.get("activity_directive").get("id").intValue(),
          (json.get("parent_id") == null || json.get("parent_id").isNull()) ? null : json.get("parent_id").intValue(),
          (json.get("duration") == null || json.get("duration").isNull()) ? null : json.get("duration").textValue(),
          json.get("start_time").textValue(),
          json.get("start_offset").textValue(),
          json.get("type").textValue());
    }
  }

  public record SimulationReason(
    String type,
    String message,
    ObjectNode data,
    String trace,
    String timestamp
  ) {
    public static SimulationReason fromJSON(ObjectNode json){
      return new SimulationReason(
          json.get("type").textValue(),
          json.get("message").textValue(),
          json.get("data"),
          json.get("trace").textValue(),
          json.get("timestamp").textValue());
    }
  }

  public static SimulationDataset fromJSON(ObjectNode json) {
    final var simActivities =
        StreamSupport.stream(json.get("simulated_activities").spliterator(), false).map(e -> SimulatedActivity.fromJSON((ObjectNode) e)).toList();
    return new SimulationDataset(
        SimulationStatus.valueOf(json.get("status").textValue()),
        (json.get("reason") == null || json.get("reason").isNull()) ? Optional.empty() : Optional.of(SimulationReason.fromJSON(json.get("reason"))),
        json.get("canceled").booleanValue(),
        json.get("simulation_start_time").textValue(),
        json.get("simulation_end_time").textValue(),
        simActivities,
        json.get("dataset_id").intValue());
  }

  public enum SimulationStatus{ pending, incomplete, failed, success }
}
