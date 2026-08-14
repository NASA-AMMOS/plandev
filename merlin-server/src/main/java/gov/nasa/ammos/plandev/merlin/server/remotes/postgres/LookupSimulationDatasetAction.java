package gov.nasa.jpl.aerie.merlin.server.remotes.postgres;

import gov.nasa.jpl.aerie.merlin.server.remotes.postgres.SimulationStateRecord.Status;
import gov.nasa.jpl.aerie.types.Timestamp;
import org.intellij.lang.annotations.Language;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Optional;

/*package-local*/ final class LookupSimulationDatasetAction implements AutoCloseable {
  private static final @Language("Sql") String sql = """
    with
      revisions as
        ( select
           s.id as sim_id,
           s.revision as sim_revision,
           t.revision as template_revision,
           p.revision as plan_revision,
           m.revision as model_revision
         from merlin.simulation as s
         left join merlin.simulation_template as t
           on s.simulation_template_id = t.id
         left join merlin.plan as p
           on p.id = s.plan_id
         left join merlin.mission_model as m
           on m.id = p.model_id)
    select
          d.dataset_id as dataset_id,
          d.status as status,
          d.reason as reason,
          d.canceled as canceled,
          to_char(d.simulation_start_time, 'YYYY-DDD"T"HH24:MI:SS.FF6') as simulation_start_time,
          to_char(d.simulation_end_time, 'YYYY-DDD"T"HH24:MI:SS.FF6') as simulation_end_time,
          d.id as id
      from merlin.simulation_dataset as d
      left join revisions as r
        on d.simulation_id = r.sim_id
      where
        d.simulation_id = ? and
        d.simulation_revision = r.sim_revision and
        d.plan_revision = r.plan_revision and
        d.model_revision = r.model_revision and
        (
          d.simulation_template_revision = r.template_revision or
          (
            d.simulation_template_revision is null and
            r.template_revision is null
          )
        )
    """;

  private final PreparedStatement statement;

  public LookupSimulationDatasetAction(final Connection connection) throws SQLException {
    this.statement = connection.prepareStatement(sql);
  }

  public Optional<SimulationDatasetRecord> get(final long simulationId) throws SQLException {
    this.statement.setLong(1, simulationId);

    try (final var results = this.statement.executeQuery()) {
      if (!results.next()) return Optional.empty();

      final Status status;
      try {
        status = Status.fromString(results.getString(2));
      } catch (final Status.InvalidSimulationStatusException ex) {
        throw new Error("Simulation Dataset initialized with invalid state.");
      }

      final var datasetId = results.getLong("dataset_id");
      final var reason = PreparedStatements.getFailureReason(results, 3);
      final var canceled = results.getBoolean("canceled");
      final var simulationStartTime = Timestamp.fromString(results.getString("simulation_start_time"));
      final var simulationEndTime = Timestamp.fromString(results.getString("simulation_end_time"));
      final var simulationDatasetId = results.getLong("id");
      final var state = new SimulationStateRecord(status, reason);

      return Optional.of(
          new SimulationDatasetRecord(
              simulationId,
              datasetId,
              state,
              canceled,
              simulationStartTime,
              simulationEndTime,
              simulationDatasetId));
    }
  }

  @Override
  public void close() throws SQLException {
    this.statement.close();
  }
}
