package gov.nasa.jpl.aerie.e2e.types;

import javax.json.JsonObject;
import java.util.List;
import java.util.Optional;

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
    public static SimulatedActivity fromJSON(JsonObject json) {
      return new SimulatedActivity(
          json.getInt("id"),
          json.isNull("activity_directive") ? null : json.getJsonObject("activity_directive").getInt("id"),
          json.isNull("parent_id") ? null : json.getInt("parent_id"),
          json.isNull("duration") ? null : json.getString("duration"),
          json.getString("start_time"),
          json.getString("start_offset"),
          json.getString("type"));
    }
  }

  public record SimulationReason(
    String type,
    String message,
    JsonObject data,
    String trace,
    String timestamp
  ) {
    public static SimulationReason fromJSON(JsonObject json){
      return new SimulationReason(
          json.getString("type"),
          json.getString("message"),
          json.getJsonObject("data"),
          json.getString("trace"),
          json.getString("timestamp"));
    }
  }

  public static SimulationDataset fromJSON(JsonObject json) {
    final var simActivities =
        json.getJsonArray("simulated_activities").getValuesAs(SimulatedActivity::fromJSON);
    return new SimulationDataset(
        SimulationStatus.valueOf(json.getString("status")),
        json.isNull("reason") ? Optional.empty() : Optional.of(SimulationReason.fromJSON(json.getJsonObject("reason"))),
        json.getBoolean("canceled"),
        json.getString("simulation_start_time"),
        json.getString("simulation_end_time"),
        simActivities,
        json.getInt("dataset_id"));
  }

  public enum SimulationStatus{ pending, incomplete, failed, success }
}
