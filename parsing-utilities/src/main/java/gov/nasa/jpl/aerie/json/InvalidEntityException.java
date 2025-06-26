package gov.nasa.jpl.aerie.json;

import java.util.List;

import static gov.nasa.jpl.aerie.json.JsonParseResult.FailureReason;

public class InvalidEntityException extends Exception {

  public final List<FailureReason> failures;

  public InvalidEntityException(List<FailureReason> failures) {
    this.failures = List.copyOf(failures);
  }
}
