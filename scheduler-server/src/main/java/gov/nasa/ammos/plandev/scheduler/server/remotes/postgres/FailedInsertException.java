package gov.nasa.jpl.aerie.scheduler.server.remotes.postgres;

import org.intellij.lang.annotations.Language;

public final class FailedInsertException extends IntegrationFailureException {
  public FailedInsertException(@Language(value="SQL", prefix="SELECT * FROM ") final String table) {
    super("Insert into table `%s` failed".formatted(table));
  }
}
