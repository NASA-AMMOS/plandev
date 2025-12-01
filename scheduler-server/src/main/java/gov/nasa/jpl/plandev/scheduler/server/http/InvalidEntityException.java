package gov.nasa.jpl.plandev.scheduler.server.http;

import java.util.List;

import static gov.nasa.jpl.plandev.json.JsonParseResult.FailureReason;

public class InvalidEntityException extends Exception {

  public final List<FailureReason> failures;

  public InvalidEntityException(List<FailureReason> failures) {
    this.failures = List.copyOf(failures);
  }
}
