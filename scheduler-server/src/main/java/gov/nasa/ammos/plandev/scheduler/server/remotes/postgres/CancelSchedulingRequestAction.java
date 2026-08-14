package gov.nasa.jpl.aerie.scheduler.server.remotes.postgres;

import org.intellij.lang.annotations.Language;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/*package-local*/ final class CancelSchedulingRequestAction implements AutoCloseable {
  private static final @Language("SQL") String sql = """
    update scheduler.scheduling_request
      set
        canceled = true
      where
        specification_id = ? and
        specification_revision = ?
    """;

  private final PreparedStatement statement;

  public CancelSchedulingRequestAction(final Connection connection) throws SQLException {
    this.statement = connection.prepareStatement(sql);
  }

  public void apply(
      final long specificationId,
      final long specificationRevision
  ) throws SQLException {
    this.statement.setLong(1, specificationId);
    this.statement.setLong(2, specificationRevision);

    final var count = this.statement.executeUpdate();
    if (count < 1) throw new FailedUpdateException("scheduling_request");
    if (count > 1) throw new Error("More than one row affected by scheduling_request update by primary key. Is the database corrupted?");
  }

  @Override
  public void close() throws SQLException {
    this.statement.close();
  }
}
