package gov.nasa.jpl.plandev.e2e.types;

import javax.json.JsonObject;
import javax.json.JsonString;
import javax.json.JsonValue;
import java.util.List;

public sealed interface ActivityValidation {
  record Pending() implements ActivityValidation {}
  record Success() implements ActivityValidation {}
  record InstantiationFailure(List<String> extraneousArguments, List<String> missingArguments, List<?> unconstructableArguments) implements ActivityValidation {}
  record ValidationFailure(List<ValidationNotice> notices) implements ActivityValidation {}
  record NoSuchActivityTypeFailure(String message, String activityType) implements ActivityValidation {}
  record NoSuchMissionModelFailure(String message, long modelId) implements ActivityValidation {}

  record ValidationNotice(List<String> subjects, String message) { }
  record UnconstructableArgument(String name, String failure) { }

  static ActivityValidation fromJSON(JsonObject obj) {
    final var status = obj.getString("status");
    if (!status.equals("complete")) {
      return new Pending();
    }
    final var validations = obj.getJsonObject("validations");
    if (validations.getBoolean("success")) {
      return new Success();
    }
    final String type = validations.getString("type");
    final JsonObject errors = validations.getJsonObject("errors");
    return switch (type) {
      case "INSTANTIATION_ERRORS" -> new InstantiationFailure(
          getStringArray(errors, "extraneousArguments"),
          getStringArray(errors, "missingArguments"),
          errors
              .asJsonObject()
              .getJsonArray("unconstructableArguments")
              .getValuesAs(
                  $ -> new UnconstructableArgument(
                      $.asJsonObject().getString("name"),
                      $.asJsonObject().getString("failure"))));
      case "VALIDATION_NOTICES" -> new ValidationFailure(
          errors.getJsonArray("validationNotices")
                .getValuesAs(
                    $ -> new ValidationNotice(
                        getStringArray($, "subjects"),
                        $.asJsonObject().getString("message"))));
      case "NO_SUCH_ACTIVITY_TYPE" -> new NoSuchActivityTypeFailure(errors.getJsonObject("noSuchActivityError").getString("message"), errors.getJsonObject("noSuchActivityError").getString("activity_type"));
      case "NO_SUCH_MISSION_MODEL" -> new NoSuchMissionModelFailure(
          errors.getJsonObject("noSuchMissionModelError").getString("message"),
          errors.getJsonObject("noSuchMissionModelError").getJsonNumber("mission_model_id").longValue()
      );
      default -> throw new RuntimeException("Unhandled error type: " + type);
    };
  }

  static List<String> getStringArray(JsonValue object, String key) {
    return object.asJsonObject().getJsonArray(key).getValuesAs(subj -> ((JsonString) subj).getString());
  }
}
