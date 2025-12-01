package gov.nasa.jpl.plandev.merlin.server.remotes.postgres;

import java.sql.SQLException;

public class DatabaseException extends IntegrationFailureException {
  public final SQLException cause;

  public DatabaseException(final String message, final SQLException cause) {
    super(message, cause);
    this.cause = cause;
  }
}
