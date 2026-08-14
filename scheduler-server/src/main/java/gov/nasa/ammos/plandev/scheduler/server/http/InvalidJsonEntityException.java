package gov.nasa.ammos.plandev.scheduler.server.http;

import java.util.List;

import static gov.nasa.ammos.plandev.json.JsonParseResult.FailureReason;

public class InvalidJsonEntityException extends Exception {

  public final List<FailureReason> failures;

  public InvalidJsonEntityException(List<FailureReason> failures) {
    super("JSON Parsing Exception was caused by the following failures:\n\t"+
          String.join(",\n\t", failures.stream().map(FailureReason::toString).toList()));
    this.failures = List.copyOf(failures);
  }
}
