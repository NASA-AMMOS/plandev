package gov.nasa.jpl.aerie.workspace.server.postgres;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

public final class WorkspacePostgresRepository {
  private final Path baseRepositoryPath;
  private final DataSource dataSource;

  public WorkspacePostgresRepository(final Path baseRepositoryPath, final DataSource dataSource){
    this.baseRepositoryPath = baseRepositoryPath;
    this.dataSource = dataSource;
  }

  public WorkspacePostgresRepository(final String baseRepositoryPath, final DataSource dataSource){
    this(Path.of(baseRepositoryPath), dataSource);
  }

  public Path workspaceRootPath(int workspaceId) throws NoSuchWorkspaceException {
    try(final var connection = dataSource.getConnection();
        final var getRootPath = new GetWorkspaceRootPathAction(connection)) {
      return baseRepositoryPath.resolve(getRootPath.get(workspaceId));
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  public Map<String, RenderType> getExtensionMapping() throws SQLException {
    try(final var connection = dataSource.getConnection();
        final var getMappingAction = new GetExtensionMappingsAction(connection)) {
      return getMappingAction.get();
    }
  }

  public List<String> getMetadataExtensions() throws SQLException {
    try(final var connection = dataSource.getConnection();
        final var getMappingAction = new GetExtensionMappingsAction(connection)) {
      return getMappingAction.get(RenderType.METADATA);
    }
  }
}
