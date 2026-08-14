package gov.nasa.jpl.aerie.merlin.server.remotes.postgres;

import gov.nasa.jpl.aerie.types.Timestamp;
import org.intellij.lang.annotations.Language;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Optional;

public class GetSimulationDatasetByIdAction implements AutoCloseable {
  private static final @Language("Sql") String sql = """
    select
          d.simulation_id as simulation_id,
          d.status as status,
          d.reason as reason,
          d.canceled as canceled,
          to_char(d.simulation_start_time, 'YYYY-DDD"T"HH24:MI:SS.FF6') as simulation_start_time,
          to_char(d.simulation_end_time, 'YYYY-DDD"T"HH24:MI:SS.FF6') as simulation_end_time,
          d.dataset_id as dataset_id
      from merlin.simulation_dataset as d
      where
        d.id = ?
    """;

  private final PreparedStatement statement;

  public GetSimulationDatasetByIdAction(final Connection connection) throws SQLException {
    this.statement = connection.prepareStatement(sql);
  }

  public Optional<SimulationDatasetRecord> get(
      final long simulationDatasetId
  ) throws SQLException {
    this.statement.setLong(1, simulationDatasetId);

    try (final var results = this.statement.executeQuery()) {
      if (!results.next()) return Optional.empty();

      final SimulationStateRecord.Status status;
      try {
        status = SimulationStateRecord.Status.fromString(results.getString("status"));
      } catch (final SimulationStateRecord.Status.InvalidSimulationStatusException ex) {
        throw new Error("Simulation Dataset initialized with invalid state.");
      }

      final var simulationId = results.getLong("simulation_id");
      final var reason = PreparedStatements.getFailureReason(results, 3);
      final var canceled = results.getBoolean("canceled");
      final var state = new SimulationStateRecord(status, reason);
      final var simStartTime = Timestamp.fromString(results.getString("simulation_start_time"));
      final var simEndTime = Timestamp.fromString(results.getString("simulation_end_time"));
      final var datasetId = results.getLong("dataset_id");

      return Optional.of(
          new SimulationDatasetRecord(
              simulationId,
              datasetId,
              state,
              canceled,
              simStartTime,
              simEndTime,
              simulationDatasetId));
    }
  }

  @Override
  public void close() throws SQLException {
    this.statement.close();
  }
}
