package gov.nasa.jpl.aerie.merlin.server.remotes.postgres;

import org.intellij.lang.annotations.Language;

public class FailedInsertException extends IntegrationFailureException {
  public FailedInsertException(@Language(value="SQL", prefix="SELECT * FROM ") final String table) {
    super("Insert into table `%s` failed".formatted(table));
  }
}
