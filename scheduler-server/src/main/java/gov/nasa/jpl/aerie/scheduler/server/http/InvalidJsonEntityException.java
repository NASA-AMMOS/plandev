package gov.nasa.jpl.aerie.scheduler.server.http;

import java.util.List;

import static gov.nasa.jpl.aerie.json.JsonParseResult.FailureReason;

public class InvalidJsonEntityException extends Exception {

  public final List<FailureReason> failures;

  public InvalidJsonEntityException(List<FailureReason> failures) {
    super("JSON Parsing Exception was caused by the following failures:\n\t"+
          String.join(",\n\t", failures.stream().map(FailureReason::toString).toList()));
    this.failures = List.copyOf(failures);
  }
}
