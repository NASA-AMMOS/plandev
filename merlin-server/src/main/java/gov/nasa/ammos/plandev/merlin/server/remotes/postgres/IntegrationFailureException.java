package gov.nasa.jpl.aerie.merlin.server.remotes.postgres;

public abstract /*sealed*/ class IntegrationFailureException extends RuntimeException {
  public IntegrationFailureException(final String message) {
    super(message);
  }

  public IntegrationFailureException(final Throwable cause) {
    super(cause);
  }

  public IntegrationFailureException(final String message, final Throwable cause) {
    super(message, cause);
  }
}
