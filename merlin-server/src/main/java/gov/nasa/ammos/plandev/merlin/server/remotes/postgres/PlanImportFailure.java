package gov.nasa.ammos.plandev.merlin.server.remotes.postgres;

import gov.nasa.ammos.plandev.json.FormattedError;

import javax.json.Json;
import java.io.PrintWriter;
import java.io.StringWriter;
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
      FormattedError error,
      Throwable cause
  ) {
    this(type, message, error, generateTrace(cause), Instant.now());
  }

  private static String generateTrace(Throwable ex){
    final var sw = new StringWriter();
    try(final var pw = new PrintWriter(sw)) {
      ex.printStackTrace(pw);
    }
    return sw.toString();
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
