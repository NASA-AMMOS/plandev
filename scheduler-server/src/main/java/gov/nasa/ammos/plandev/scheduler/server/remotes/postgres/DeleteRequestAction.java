package gov.nasa.jpl.aerie.scheduler.server.remotes.postgres;

import org.intellij.lang.annotations.Language;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/*package-local*/ final class DeleteRequestAction implements AutoCloseable {
  private final @Language("SQL") String sql = """
    delete from scheduler.scheduling_request
    where
      analysis_id = ?
    """;

  private final PreparedStatement statement;

  public DeleteRequestAction(final Connection connection) throws SQLException {
    this.statement = connection.prepareStatement(sql);
  }

  public void apply(final long analysisId) throws SQLException {
    this.statement.setLong(1, analysisId);

    this.statement.execute();
  }

  @Override
  public void close() throws SQLException {
    this.statement.close();
  }
}
