package gov.nasa.jpl.aerie.e2e.types;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.stream.StreamSupport;

public sealed interface ActivityValidation {
  record Pending() implements ActivityValidation {}
  record Success() implements ActivityValidation {}
  record InstantiationFailure(List<String> extraneousArguments, List<String> missingArguments, List<?> unconstructableArguments) implements ActivityValidation {}
  record ValidationFailure(List<ValidationNotice> notices) implements ActivityValidation {}
  record NoSuchActivityTypeFailure(String message, String activityType) implements ActivityValidation {}
  record NoSuchMissionModelFailure(String message, long modelId) implements ActivityValidation {}

  record ValidationNotice(List<String> subjects, String message) { }
  record UnconstructableArgument(String name, String failure) { }

  static ActivityValidation fromJSON(ObjectNode obj) {
    final var status = obj.get("status").textValue();
    if (!status.equals("complete")) {
      return new Pending();
    }
    final var validations = obj.get("validations");
    if (validations.get("success").booleanValue()) {
      return new Success();
    }
    final String type = validations.get("type").textValue();
    final JsonNode errors = validations.get("errors");
    return switch (type) {
      case "INSTANTIATION_ERRORS" -> new InstantiationFailure(
          getStringArray(errors, "extraneousArguments"),
          getStringArray(errors, "missingArguments"),
          StreamSupport.stream(errors.get("unconstructableArguments").spliterator(), false)
              .map($ -> new UnconstructableArgument(
                      $.get("name").textValue(),
                      $.get("failure").textValue()))
              .toList());
      case "VALIDATION_NOTICES" -> new ValidationFailure(
          StreamSupport.stream(errors.get("validationNotices").spliterator(), false)
                .map($ -> new ValidationNotice(
                        getStringArray($, "subjects"),
                        $.get("message").textValue()))
                .toList());
      case "NO_SUCH_ACTIVITY_TYPE" -> new NoSuchActivityTypeFailure(errors.get("noSuchActivityError").get("message").textValue(), errors.get("noSuchActivityError").get("activity_type").textValue());
      case "NO_SUCH_MISSION_MODEL" -> new NoSuchMissionModelFailure(
          errors.get("noSuchMissionModelError").get("message").textValue(),
          errors.get("noSuchMissionModelError").get("mission_model_id").longValue()
      );
      default -> throw new RuntimeException("Unhandled error type: " + type);
    };
  }

  static List<String> getStringArray(JsonNode object, String key) {
    return StreamSupport.stream(object.get(key).spliterator(), false).map(JsonNode::textValue).toList();
  }
}
