package gov.nasa.ammos.plandev.merlin.server.remotes.postgres;

import gov.nasa.ammos.plandev.merlin.protocol.types.Duration;
import org.intellij.lang.annotations.Language;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;

public final class UpdateProfileDurationBulkAction implements AutoCloseable {
  private final @Language("SQL") String sql = """
      update merlin.profile
      set duration = ?::interval
      where dataset_id=? and id=?;
    """;
  private final PreparedStatement statement;

  public UpdateProfileDurationBulkAction(final Connection connection) throws SQLException {
    this.statement = connection.prepareStatement(sql);
  }

  public void apply(final long datasetId, final Map<Long, Duration> updatedDurations) throws SQLException {
    this.statement.setLong(2, datasetId);

    for (final var entry : updatedDurations.entrySet()) {
      final var profileId = entry.getKey();
      final var newDuration = entry.getValue();

      PreparedStatements.setDuration(this.statement, 1, newDuration);
      this.statement.setLong(3, profileId);
      this.statement.addBatch();
    }

    this.statement.executeUpdate();
    final var results = this.statement.executeBatch();
    for (final var result : results) {
      if (result == Statement.EXECUTE_FAILED) throw new FailedInsertException("merlin.profile_segment");
    }
  }

  @Override
  public void close() throws SQLException {
    this.statement.close();
  }
}
