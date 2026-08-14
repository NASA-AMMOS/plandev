package gov.nasa.jpl.aerie.merlin.server.remotes.postgres;

import org.intellij.lang.annotations.Language;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/*package local*/ public class ClaimSimulationAction implements AutoCloseable {
  private static final @Language("SQL") String sql = """
    update merlin.simulation_dataset
      set
        status = 'incomplete'
      where (dataset_id = ? and status = 'pending' and not canceled);
  """;

  private final PreparedStatement statement;

  public ClaimSimulationAction(final Connection connection) throws SQLException {
    this.statement = connection.prepareStatement(sql);
  }

  public void apply(final long datasetId) throws SQLException, UnclaimableSimulationException {
    this.statement.setLong(1, datasetId);

    final var count = this.statement.executeUpdate();
    if (count < 1) {
      throw new UnclaimableSimulationException(datasetId);
    } else if (count > 1) {
      throw new SQLException(
          String.format("Claiming a simulation for dataset id %s returned more than one result row.", datasetId));
    }
  }

  @Override
  public void close() throws SQLException {
    this.statement.close();
  }
}
