package gov.nasa.jpl.aerie.merlin.server.remotes.postgres;

import gov.nasa.jpl.aerie.json.JsonParser;
import gov.nasa.jpl.aerie.merlin.driver.engine.ProfileSegment;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import org.intellij.lang.annotations.Language;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;

public final class PostProfileSegmentsAction implements AutoCloseable {
  private final @Language("SQL") String sql = """
      insert into merlin.profile_segment (dataset_id, profile_id, start_offset, dynamics, is_gap)
      values (?, ?, ?::interval, ?::jsonb, ?)
    """;
  private final PreparedStatement statement;

  public PostProfileSegmentsAction(final Connection connection) throws SQLException {
    this.statement = connection.prepareStatement(sql);
  }

  public <Dynamics> void apply(
      final long datasetId,
      final ProfileRecord profileRecord,
      final List<ProfileSegment<Optional<Dynamics>>> segments,
      final JsonParser<Dynamics> dynamicsP
      ) throws SQLException {

    // Each profile segment's duration part is the duration for which the dynamics hold
    // before the next one begins. Since order in the database is not guaranteed
    // we need to convert to offsets from the simulation start so order can be preserved
    var accumulatedOffset = Duration.ZERO;
    for (final var pair : segments) {
      final var duration = pair.extent();
      final var dynamics = pair.dynamics();

      this.statement.setLong(1, datasetId);
      this.statement.setLong(2, profileRecord.id());
      PreparedStatements.setDuration(this.statement, 3, accumulatedOffset);
      if (dynamics.isPresent()) {
        this.statement.setString(4, serializeDynamics(dynamics.get(), dynamicsP));
        this.statement.setBoolean(5, false);
      } else {
        this.statement.setString(4, "null");
        this.statement.setBoolean(5, true);
      }

      this.statement.addBatch();

      accumulatedOffset = Duration.add(accumulatedOffset, duration);
    }

    final var results = this.statement.executeBatch();
    for (final var result : results) {
      if (result == Statement.EXECUTE_FAILED) throw new FailedInsertException("merlin.profile_segment");
    }
  }

  private <Dynamics> String serializeDynamics(final Dynamics dynamics, final JsonParser<Dynamics> dynamicsP) {
    return dynamicsP.unparse(dynamics).toString();
  }

  @Override
  public void close() throws SQLException {
    this.statement.close();
  }
}
