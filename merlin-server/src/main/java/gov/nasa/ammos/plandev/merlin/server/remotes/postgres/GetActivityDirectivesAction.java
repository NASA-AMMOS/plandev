package gov.nasa.jpl.aerie.merlin.server.remotes.postgres;

import org.intellij.lang.annotations.Language;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import static gov.nasa.jpl.aerie.merlin.server.remotes.postgres.PostgresParsers.activityArgumentsP;
import static gov.nasa.jpl.aerie.merlin.server.remotes.postgres.PostgresParsers.getJsonColumn;

/*package-local*/ final class GetActivityDirectivesAction implements AutoCloseable {
  private static final @Language("SQL") String sql = """
    select
      a.id,
      a.type,
      ceil(extract(epoch from a.start_offset) * 1000*1000) as start_offset_in_micros,
      a.arguments,
      a.anchor_id,
      a.anchored_to_start
    from merlin.activity_directive as a
    where a.plan_id = ?
    """;

  private final PreparedStatement statement;

  public GetActivityDirectivesAction(final Connection connection) throws SQLException {
    this.statement = connection.prepareStatement(sql);
  }

  public List<ActivityDirectiveRecord> get(final long planId) throws SQLException {
    this.statement.setLong(1, planId);

    final var activities = new ArrayList<ActivityDirectiveRecord>();
    try (final var results = this.statement.executeQuery()) {
      while (results.next()) {
        activities.add(
            new ActivityDirectiveRecord(
                results.getLong("id"),
                results.getString("type"),
                results.getLong("start_offset_in_micros"),
                getJsonColumn(results, "arguments", activityArgumentsP)
                    .getSuccessOrThrow($ -> new Error("Corrupt activity arguments cannot be parsed: " + $.reason())),
                (Integer)results.getObject("anchor_id"),
                results.getBoolean("anchored_to_start"))
        );
      }
    }

    return activities;
  }

  @Override
  public void close() throws SQLException {
    this.statement.close();
  }
}
