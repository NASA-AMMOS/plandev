package gov.nasa.jpl.aerie.workspace.server.postgres;

import org.intellij.lang.annotations.Language;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/*package-local*/ final class DeleteWorkspaceAction implements AutoCloseable {
  private static final @Language("SQL") String sql = """
    delete from sequencing.workspace
    where id = ?
    """;

  private final PreparedStatement statement;

  public DeleteWorkspaceAction(Connection connection) throws SQLException {
    this.statement = connection.prepareStatement(sql);
  }

  public boolean delete(int workspaceId) throws SQLException {
    statement.setInt(1, workspaceId);
    final var rowCount = statement.executeUpdate();
    return rowCount == 1;
  }

  @Override
  public void close() throws SQLException {
    this.statement.close();
  }
}
