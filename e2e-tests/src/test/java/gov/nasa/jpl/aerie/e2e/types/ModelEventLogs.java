package gov.nasa.jpl.aerie.e2e.types;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Optional;
import java.util.stream.StreamSupport;

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
    Optional<ObjectNode> error,
    Optional<String> errorMessage,
    Optional<String> errorType
  )
  {
    public static EventLog fromJSON(ObjectNode json) {
      final Optional<Integer> status = (json.get("status") == null || json.get("status").isNull()) ?
          Optional.empty() : Optional.of(json.get("status").intValue());
      final Optional<ObjectNode> error = (json.get("error") == null || json.get("error").isNull()) ?
          Optional.empty() : Optional.of(json.get("error"));
      final Optional<String> errorMsg = (json.get("error_message") == null || json.get("error_message").isNull()) ?
          Optional.empty() : Optional.of(json.get("error_message").textValue());
      final Optional<String> errorType = (json.get("error") == null || json.get("error").isNull()) ?
          Optional.empty() : Optional.of(json.get("error").textValue());

      return new EventLog(
          json.get("triggering_user").textValue(),
          json.get("pending").booleanValue(),
          json.get("delivered").booleanValue(),
          json.get("success").booleanValue(),
          json.get("tries").intValue(),
          json.get("created_at").textValue(),
          status,
          error,
          errorMsg,
          errorType);
    }
  }

  public static ModelEventLogs fromJSON(ObjectNode json) {
    return new ModelEventLogs(
      json.get("id").intValue(),
      json.get("name").textValue(),
      json.get("version").textValue(),
      StreamSupport.stream(json.get("refresh_activity_type_logs").spliterator(), false).map(e -> EventLog.fromJSON((ObjectNode) e)).toList(),
      StreamSupport.stream(json.get("refresh_model_parameter_logs").spliterator(), false).map(e -> EventLog.fromJSON((ObjectNode) e)).toList(),
      StreamSupport.stream(json.get("refresh_resource_type_logs").spliterator(), false).map(e -> EventLog.fromJSON((ObjectNode) e)).toList()
    );
  }
}
