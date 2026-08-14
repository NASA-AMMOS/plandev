package gov.nasa.jpl.aerie.merlin.server.remotes.postgres;

import org.intellij.lang.annotations.Language;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/*package-local*/ final class UpdateSimulationConfigurationRevisionAction implements AutoCloseable {
  private final @Language("SQL") String sql = """
        update merlin.simulation
        set revision = revision
        where plan_id = ?;
        """;

  private final PreparedStatement statement;

  public UpdateSimulationConfigurationRevisionAction(final Connection connection) throws SQLException {
    this.statement = connection.prepareStatement(sql);
  }

  public void apply(final long planId)
  throws SQLException
  {
    this.statement.setLong(1, planId);
    final var count = this.statement.executeUpdate();
    if (count > 1) throw new Error("More than one row affected by sim config update by unique key. Is the database corrupted?");
    if (count == 0) throw new SQLException("No simulation configuration exists for plan "+planId);
  }

  @Override
  public void close() throws SQLException {
    this.statement.close();
  }
}
