package gov.nasa.jpl.aerie.merlin.server.remotes.postgres;

import gov.nasa.jpl.aerie.types.Timestamp;
import org.intellij.lang.annotations.Language;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Optional;

/*package-local*/ final class GetPlanAction implements AutoCloseable {
  private static final @Language("SQL") String sql = """
    select
      p.name,
      p.revision,
      p.model_id,
      to_char(p.start_time, 'YYYY-DDD"T"HH24:MI:SS.FF6') as start_time,
      to_char(p.start_time + p.duration, 'YYYY-DDD"T"HH24:MI:SS.FF6') as end_time
    from merlin.plan as p
    where p.id = ?
    """;

  private final PreparedStatement statement;

  public GetPlanAction(final Connection connection) throws SQLException {
    this.statement = connection.prepareStatement(sql);
  }

  public Optional<PlanRecord> get(final long planId) throws SQLException {
    this.statement.setLong(1, planId);

    try (final var results = this.statement.executeQuery()) {
      if (!results.next()) return Optional.empty();

      final var name = results.getString(1);
      final var revision = results.getLong(2);
      final var missionModelId = results.getLong(3);
      final var startTimestamp = Timestamp.fromString(results.getString(4));
      final var endTimestamp = Timestamp.fromString(results.getString(5));

      return Optional.of(
          new PlanRecord(
              planId,
              revision,
              name,
              missionModelId,
              startTimestamp,
              endTimestamp));
    }
  }

  @Override
  public void close() throws SQLException {
    this.statement.close();
  }
}
