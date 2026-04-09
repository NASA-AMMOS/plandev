package gov.nasa.jpl.aerie.workspace.server.exceptions;

import java.util.Optional;

public class MalformedRequest extends Exception {
  private final Optional<String> details;

  public MalformedRequest(String message) {
    super(message);
    details = Optional.empty();
  }

  public MalformedRequest(String message, String cause) {
    super(message);
    this.details = Optional.of(cause);
  }

  public Optional<String> getDetails() {return details;}
}
