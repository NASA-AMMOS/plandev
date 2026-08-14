package gov.nasa.jpl.aerie.scheduler.server.remotes.postgres;

import gov.nasa.jpl.aerie.scheduler.server.models.DatasetId;
import gov.nasa.jpl.aerie.scheduler.server.services.ScheduleFailure;
import org.intellij.lang.annotations.Language;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Optional;

/*package-local*/ final class SetRequestStateAction implements AutoCloseable {
  private static final @Language("SQL") String sql = """
    update scheduler.scheduling_request
      set
        status = ?::util_functions.request_status,
        reason = ?::json,
        dataset_id = ?
      where
        specification_id = ? and
        specification_revision = ?
    """;

  private final PreparedStatement statement;

  public SetRequestStateAction(final Connection connection) throws SQLException {
    this.statement = connection.prepareStatement(sql);
  }

  public void apply(
      final long specificationId,
      final long specificationRevision,
      final RequestRecord.Status status,
      final ScheduleFailure failureReason,
      final Optional<DatasetId> datasetId
  ) throws SQLException {
    this.statement.setString(1, status.label);
    PreparedStatements.setFailureReason(this.statement, 2, failureReason);
    if(datasetId.isPresent()) {
      statement.setLong(3, datasetId.get().id());
    } else{
      statement.setNull(3, Types.INTEGER);
    }
    this.statement.setLong(4, specificationId);
    this.statement.setLong(5, specificationRevision);

    final var count = this.statement.executeUpdate();
    if (count < 1) throw new FailedUpdateException("scheduler.scheduling_request");
    if (count > 1) throw new Error("More than one row affected by scheduling_request update by primary key. Is the database corrupted?");
  }

  @Override
  public void close() throws SQLException {
    this.statement.close();
  }
}
