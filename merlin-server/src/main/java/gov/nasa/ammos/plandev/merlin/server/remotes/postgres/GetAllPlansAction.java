package gov.nasa.jpl.aerie.merlin.server.remotes.postgres;

import gov.nasa.jpl.aerie.types.Timestamp;
import org.intellij.lang.annotations.Language;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/*package-local*/ final class GetAllPlansAction implements AutoCloseable {
  private static final @Language("SQL") String sql = """
    select
      p.id,
      p.revision,
      p.name,
      p.model_id,
      to_char(p.start_time, 'YYYY-DDD"T"HH24:MI:SS.FF6') as start_time,
      to_char(p.start_time + p.duration, 'YYYY-DDD"T"HH24:MI:SS.FF6') as end_time
    from merlin.plan as p
    """;

  private final PreparedStatement statement;

  public GetAllPlansAction(final Connection connection) throws SQLException {
    this.statement = connection.prepareStatement(sql);
  }

  public List<PlanRecord> get() throws SQLException {
    try (final var results = this.statement.executeQuery()) {
      final var plans = new ArrayList<PlanRecord>();

      while (results.next()) {
        final var id = results.getLong("id");
        final var revision = results.getLong("revision");
        final var name = results.getString("name");
        final var modelId = results.getLong("model_id");
        final var startTimestamp = Timestamp.fromString(results.getString("start_time"));
        final var endTimestamp = Timestamp.fromString(results.getString("end_time"));

        plans.add(
            new PlanRecord(
                id,
                revision,
                name,
                modelId,
                startTimestamp,
                endTimestamp));
      }

      return plans;
    }
  }

  @Override
  public void close() throws SQLException {
    this.statement.close();
  }
}
