package gov.nasa.jpl.aerie.scheduler.server.remotes.postgres;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import org.intellij.lang.annotations.Language;

/*package local*/ public class ClaimRequestAction implements AutoCloseable {
  private static final @Language("SQL") String sql = """
    update scheduler.scheduling_request
    set status = 'incomplete'
    where (analysis_id = ? and status = 'pending' and not canceled)
    returning
      specification_id,
      specification_revision,
      plan_revision,
      status,
      reason,
      canceled,
      dataset_id;
  """;

  private final PreparedStatement statement;

  public ClaimRequestAction(final Connection connection) throws SQLException {
    this.statement = connection.prepareStatement(sql);
  }

  public RequestRecord apply(final long analysisId) throws SQLException, UnclaimableRequestException {
    this.statement.setLong(1, analysisId);

    final var resultSet = this.statement.executeQuery();
    if (!resultSet.next()) {
      throw new UnclaimableRequestException(analysisId);
    }

    final var specificationId = resultSet.getLong("specification_id");
    final var specificationRevision = resultSet.getLong("specification_revision");
    final var planRevision = resultSet.getLong("plan_revision");

    final RequestRecord.Status status;
    try {
      status = RequestRecord.Status.fromString(resultSet.getString("status"));
    } catch (final RequestRecord.Status.InvalidRequestStatusException ex) {
      throw new Error(
          String.format(
              "Scheduling request for specification with ID %d and revision %d has invalid state %s",
              specificationId,
              specificationRevision,
              ex.invalidStatus));
    }

    final var failureReason$ = PreparedStatements.getFailureReason(resultSet, "reason");
    final var canceled = resultSet.getBoolean("canceled");
    final var datasetId = PreparedStatements.getDatasetId(resultSet, "dataset_id");

    final var request = new RequestRecord(
      specificationId,
      analysisId,
      specificationRevision,
      planRevision,
      status,
      failureReason$,
      canceled,
      datasetId
    );

    if (resultSet.next()) {
      throw new SQLException(
          String.format("Claiming a scheduling request with analysis id %s returned more than one result row.", analysisId));
    }

    return request;
  }

  @Override
  public void close() throws SQLException {
    this.statement.close();
  }
}
