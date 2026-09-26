package gov.nasa.ammos.plandev.merlin.server.remotes.postgres;

import gov.nasa.ammos.plandev.merlin.protocol.types.SerializedValue;
import gov.nasa.ammos.plandev.types.Timestamp;
import gov.nasa.ammos.plandev.merlin.server.remotes.postgres.SimulationStateRecord.Status;
import org.intellij.lang.annotations.Language;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Map;

import static gov.nasa.ammos.plandev.merlin.server.remotes.postgres.PostgresParsers.simulationArgumentsP;

/*package local*/ final class CreateSimulationDatasetAction implements AutoCloseable {
  private static final @Language("SQL") String sql = """
    insert into merlin.simulation_dataset
      (
        simulation_id,
        simulation_start_time,
        simulation_end_time,
        arguments,
        requested_by,
        status
      )
    values(?, ?::timestamptz, ?::timestamptz, ?::jsonb, ?, ?)
    returning
      dataset_id,
      status,
      reason,
      canceled,
      id
    """;

  private final PreparedStatement statement;

  public CreateSimulationDatasetAction(final Connection connection) throws SQLException {
    this.statement = connection.prepareStatement(sql);
  }

  // TODO: Extend external datasets to support spans and remove this override
  public SimulationDatasetRecord apply(
      final long simulationId,
      final Timestamp simulationStart,
      final Timestamp simulationEnd,
      final Map<String, SerializedValue> arguments,
      final String requestedBy,
      final Status status
  ) throws SQLException {
    this.statement.setLong(1, simulationId);
    PreparedStatements.setTimestamp(this.statement, 2, simulationStart);
    PreparedStatements.setTimestamp(this.statement, 3, simulationEnd);
    this.statement.setString(4, simulationArgumentsP.unparse(arguments).toString());
    this.statement.setString(5, requestedBy);
    this.statement.setString(6, status.label);

    try (final var results = this.statement.executeQuery()) {
      if (!results.next()) throw new FailedInsertException("merlin.simulation_dataset");
      final Status returnedStatus;
      try {
        returnedStatus = Status.fromString(results.getString(2));
      } catch (final Status.InvalidSimulationStatusException ex) {
        throw new Error("Simulation Dataset initialized with invalid state.");
      }

      final var datasetId = results.getLong(1);
      final var reason = PreparedStatements.getFailureReason(results, 3);
      final var state = new SimulationStateRecord(returnedStatus, reason);
      final var canceled = results.getBoolean(4);
      final var simulationDatasetId = results.getLong(5);

      return new SimulationDatasetRecord(
          simulationId,
          datasetId,
          state,
          canceled,
          simulationStart,
          simulationEnd,
          simulationDatasetId
      );
    }
  }

  public SimulationDatasetRecord apply(
      final long simulationId,
      final Timestamp simulationStart,
      final Timestamp simulationEnd,
      final Map<String, SerializedValue> arguments,
      final String requestedBy
  ) throws SQLException {
    return apply(simulationId, simulationStart, simulationEnd, arguments, requestedBy, Status.PENDING);
  }

  @Override
  public void close() throws SQLException {
    this.statement.close();
  }
}
