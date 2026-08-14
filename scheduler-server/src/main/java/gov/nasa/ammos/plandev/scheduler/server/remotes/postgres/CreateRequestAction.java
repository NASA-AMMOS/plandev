package gov.nasa.jpl.aerie.scheduler.server.remotes.postgres;

import org.intellij.lang.annotations.Language;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;

import static gov.nasa.jpl.aerie.scheduler.server.remotes.postgres.PostgresParsers.simulationArgumentsP;
import static gov.nasa.jpl.aerie.scheduler.server.remotes.postgres.PreparedStatements.getDatasetId;

/*package-local*/ final class CreateRequestAction implements AutoCloseable {
  private static final DateTimeFormatter TIMESTAMP_FORMAT =
      new DateTimeFormatterBuilder()
          .appendPattern("uuuu-MM-dd HH:mm:ss")
          .appendFraction(ChronoField.MICRO_OF_SECOND, 0, 6, true)
          .appendOffset("+HH:mm:ss", "+00")
          .toFormatter();
  private final @Language("SQL") String sql = """
      insert into scheduler.scheduling_request (
        specification_id,
        specification_revision,
        plan_revision,
        horizon_start,
        horizon_end,
        simulation_arguments,
        requested_by)
      values (?, ?, ?, ?::timestamptz, ?::timestamptz, ?::jsonb, ?)
      returning
        analysis_id,
        status,
        reason,
        canceled,
        dataset_id
    """;

  private final PreparedStatement statement;

  public CreateRequestAction(final Connection connection) throws SQLException {
    this.statement = connection.prepareStatement(sql);
  }

  public RequestRecord apply(final SpecificationRecord specification, final String requestedBy) throws SQLException {
    this.statement.setLong(1, specification.id());
    this.statement.setLong(2, specification.revision());
    this.statement.setLong(3, specification.planRevision());
    //TODO: extract PreparedStatements into a shared library and replace these calls
    this.statement.setString(4, TIMESTAMP_FORMAT.format(specification.horizonStartTimestamp().time()));
    this.statement.setString(5, TIMESTAMP_FORMAT.format(specification.horizonEndTimestamp().time()));
    this.statement.setString(6, simulationArgumentsP.unparse(specification.simulationArguments()).toString());
    this.statement.setString(7, requestedBy);

    final var result = this.statement.executeQuery();
    if (!result.next()) throw new FailedInsertException("scheduler.scheduling_request");

    final RequestRecord.Status status;
    try {
      status = RequestRecord.Status.fromString(result.getString("status"));
    } catch (final RequestRecord.Status.InvalidRequestStatusException ex) {
      throw new Error("Scheduling request initialized with invalid state.");
    }

    final var analysis_id = result.getLong("analysis_id");
    final var failureReason$ = PreparedStatements.getFailureReason(result, "reason");
    final var canceled = result.getBoolean("canceled");
    final var datasetId = getDatasetId(result, "dataset_id");

    return new RequestRecord(
        specification.id(),
        analysis_id,
        specification.revision(),
        specification.planRevision(),
        status,
        failureReason$,
        canceled,
        datasetId
    );
  }

  @Override
  public void close() throws SQLException {
    this.statement.close();
  }
}
