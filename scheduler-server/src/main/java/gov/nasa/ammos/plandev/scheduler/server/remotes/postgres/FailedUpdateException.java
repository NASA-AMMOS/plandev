package gov.nasa.jpl.aerie.scheduler.server.remotes.postgres;

import org.intellij.lang.annotations.Language;

public final class FailedUpdateException extends IntegrationFailureException {
  public FailedUpdateException(@Language(value="SQL", prefix="SELECT * FROM ") final String table) {
    super("Update on table `%s` failed".formatted(table));
  }
}
