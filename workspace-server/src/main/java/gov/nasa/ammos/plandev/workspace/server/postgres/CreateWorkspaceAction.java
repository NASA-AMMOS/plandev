package gov.nasa.jpl.aerie.workspace.server.postgres;

import org.intellij.lang.annotations.Language;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/*package-local*/ final class CreateWorkspaceAction implements AutoCloseable {
  private static final @Language("SQL") String sql = """
    insert into sequencing.workspace(disk_location, name, parcel_id, owner, updated_by)
    values (?, ?, ?, ?, ?)
    returning id;
    """;

  private final PreparedStatement statement;

  public CreateWorkspaceAction(Connection connection) throws SQLException {
    this.statement = connection.prepareStatement(sql);
  }

  public int create(String workspaceLocation, String workspaceName, String username, int parcelId)
  throws SQLException {
    statement.setString(1, workspaceLocation);
    statement.setString(2, workspaceName);
    statement.setInt(3, parcelId);
    statement.setString(4, username);
    statement.setString(5, username);
    try(final var res = statement.executeQuery()) {
      if(!res.next()) {
        throw new SQLException("Insert failed");
      }
      return res.getInt("id");
    }
  }

  @Override
  public void close() throws SQLException {
    this.statement.close();
  }
}
