package gov.nasa.jpl.aerie.merlin.server.remotes.postgres;

import gov.nasa.jpl.aerie.types.Timestamp;
import org.intellij.lang.annotations.Language;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

import static gov.nasa.jpl.aerie.merlin.server.remotes.postgres.PostgresParsers.getJsonColumn;
import static gov.nasa.jpl.aerie.merlin.server.remotes.postgres.PostgresParsers.simulationArgumentsP;

/*package local*/ final class GetSimulationAction implements AutoCloseable {
  private static final @Language("SQL") String sql = """
    select
          s.id,
          s.revision,
          s.simulation_template_id,
          s.arguments,
          to_char(s.simulation_start_time, 'YYYY-DDD"T"HH24:MI:SS.FF6') as simulation_start_time,
          to_char(s.simulation_end_time, 'YYYY-DDD"T"HH24:MI:SS.FF6') as simulation_end_time
      from merlin.simulation as s
      where s.plan_id = ?
    """;

  private final PreparedStatement statement;

  public GetSimulationAction(final Connection connection) throws SQLException {
    this.statement = connection.prepareStatement(sql);
  }

  public SimulationRecord get(final long planId)
  throws SQLException {
      this.statement.setLong(1, planId);
      final ResultSet results = this.statement.executeQuery();

      // Plans should always have a simulation row, unless it was explicitly deleted by a user.
      if (!results.next()) throw new SQLException("Plan "+planId+" does not have a SimulationRecord.");

      final var id = results.getLong("id");
      final var revision = results.getLong("revision");
      final var templateId$ =
          results.getObject("simulation_template_id") == null ?
              Optional.<Long>empty() :
              Optional.of(results.getLong(3));
      final var arguments = getJsonColumn(results, "arguments", simulationArgumentsP)
          .getSuccessOrThrow(
              failureReason -> new Error("Corrupt simulation arguments cannot be parsed: " + failureReason.reason())
          );
      final var simulationStartTime = Timestamp.fromString(results.getString("simulation_start_time"));
      final var simulationEndTime = Timestamp.fromString(results.getString("simulation_end_time"));
      return new SimulationRecord(id, revision, planId, templateId$, arguments, simulationStartTime, simulationEndTime);
  }

  @Override
  public void close() throws SQLException {
    this.statement.close();
  }
}
