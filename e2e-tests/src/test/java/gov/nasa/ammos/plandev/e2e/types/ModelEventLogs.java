package gov.nasa.jpl.aerie.e2e.types;

import javax.json.JsonObject;
import java.util.List;
import java.util.Optional;

public record ModelEventLogs(
    int modelId,
    String modelName,
    String modelVersion,
    List<EventLog> refreshActivityTypesLogs,
    List<EventLog> refreshModelParamsLogs,
    List<EventLog> refreshResourceTypesLogs
) {
  public record EventLog(
    String triggeringUser,
    boolean pending,
    boolean delivered,
    boolean success,
    int tries,
    String createdAt,
    Optional<Integer> status,
    Optional<JsonObject> error,
    Optional<String> errorMessage,
    Optional<String> errorType
  )
  {
    public static EventLog fromJSON(JsonObject json) {
      final Optional<Integer> status = json.isNull("status") ?
          Optional.empty() : Optional.of(json.getInt("status"));
      final Optional<JsonObject> error = json.isNull("error") ?
          Optional.empty() : Optional.of(json.getJsonObject("error"));
      final Optional<String> errorMsg = json.isNull("error_message") ?
          Optional.empty() : Optional.of(json.getString("error_message"));
      final Optional<String> errorType = json.isNull("error") ?
          Optional.empty() : Optional.of(json.getString("error"));

      return new EventLog(
          json.getString("triggering_user"),
          json.getBoolean("pending"),
          json.getBoolean("delivered"),
          json.getBoolean("success"),
          json.getInt("tries"),
          json.getString("created_at"),
          status,
          error,
          errorMsg,
          errorType);
    }
  }

  public static ModelEventLogs fromJSON(JsonObject json) {
    return new ModelEventLogs(
      json.getInt("id"),
      json.getString("name"),
      json.getString("version"),
      json.getJsonArray("refresh_activity_type_logs").getValuesAs(EventLog::fromJSON),
      json.getJsonArray("refresh_model_parameter_logs").getValuesAs(EventLog::fromJSON),
      json.getJsonArray("refresh_resource_type_logs").getValuesAs(EventLog::fromJSON)
    );
  }
}
