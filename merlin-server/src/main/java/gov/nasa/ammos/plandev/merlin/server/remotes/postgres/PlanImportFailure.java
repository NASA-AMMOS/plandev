package gov.nasa.ammos.plandev.merlin.server.remotes.postgres;

import gov.nasa.ammos.plandev.json.FormattedError;

import javax.json.Json;
import java.time.Instant;

public record PlanImportFailure(
    String type,
    String message,
    FormattedError error,
    String trace,
    Instant timestamp
) {
  public PlanImportFailure(
      String type,
      String message,
      FormattedError error
  ) {
    this(type, message, error, error.getTrace().orElse("No trace generated."), Instant.now());
  }

  public String toJsonString() {
    return Json.createObjectBuilder()
               .add("type", type)
               .add("message", message)
               .add("data", error.toJson())
               .add("trace", trace)
               .add("timestamp", timestamp.toString())
               .build()
               .toString();
  }
}
