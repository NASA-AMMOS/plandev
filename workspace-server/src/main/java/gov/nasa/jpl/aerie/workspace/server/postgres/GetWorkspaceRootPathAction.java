package gov.nasa.jpl.aerie.workspace.server.postgres;

import org.intellij.lang.annotations.Language;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/*package-local*/ final class GetWorkspaceRootPathAction implements AutoCloseable {
  private static final @Language("SQL") String sql = """
    select disk_location
    from sequencing.workspace
    where id = ?;
    """;

  private final PreparedStatement statement;

  public GetWorkspaceRootPathAction(final Connection connection) throws SQLException {
    this.statement = connection.prepareStatement(sql);
  }

  public Path get(final int workspaceId) throws SQLException, NoSuchWorkspaceException {
    this.statement.setInt(1, workspaceId);
    try(final var res = statement.executeQuery()) {
      if(res.next()) {
        return Path.of(res.getString("disk_location"));
      }
      throw new NoSuchWorkspaceException(workspaceId);
    }
  }

  @Override
  public void close() throws SQLException {
    this.statement.close();
  }
}
