package gov.nasa.jpl.aerie.merlin.server.remotes.postgres;

import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import org.intellij.lang.annotations.Language;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;

/*package-local*/ final class UpdateProfileDurationAction implements AutoCloseable {
  private final @Language("SQL") String sql = """
      update merlin.profile
      set duration = ?::interval
      where dataset_id=? and id=?;
    """;
  private final PreparedStatement statement;

  public UpdateProfileDurationAction(final Connection connection) throws SQLException {
    this.statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
  }

  public void apply(
      final long datasetId,
      final long profileId,
      final Duration newDuration
  ) throws SQLException {
    PreparedStatements.setDuration(this.statement, 1, newDuration);
    this.statement.setLong(2, datasetId);
    this.statement.setLong(3, profileId);
    this.statement.executeUpdate();
  }

  @Override
  public void close() throws SQLException {
    this.statement.close();
  }
}
