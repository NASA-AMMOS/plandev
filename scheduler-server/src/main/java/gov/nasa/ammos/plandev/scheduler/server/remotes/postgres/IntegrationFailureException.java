package gov.nasa.jpl.aerie.scheduler.server.remotes.postgres;

public abstract sealed class IntegrationFailureException
    extends RuntimeException
    permits DatabaseException, FailedInsertException, FailedUpdateException
{
  public IntegrationFailureException() {}

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
