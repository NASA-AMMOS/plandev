package gov.nasa.ammos.plandev.workspace.server.postgres;

import gov.nasa.ammos.plandev.workspace.server.WorkspaceRoots;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

public final class WorkspacePostgresRepository implements WorkspaceRoots {
  private final Path baseRepositoryPath;
  private final DataSource dataSource;

  public WorkspacePostgresRepository(final Path baseRepositoryPath, final DataSource dataSource){
    this.baseRepositoryPath = baseRepositoryPath;
    this.dataSource = dataSource;
  }

  public WorkspacePostgresRepository(final String baseRepositoryPath, final DataSource dataSource){
    this(Path.of(baseRepositoryPath), dataSource);
  }

  public Path getBaseRepositoryPath() {
    return baseRepositoryPath;
  }

  public int createWorkspace(String workspaceLocation, String workspaceName, String username, int parcelId)
  throws SQLException {
    try(final var connection = dataSource.getConnection();
        final var createWorkspace = new CreateWorkspaceAction(connection)) {
      return createWorkspace.create(workspaceLocation, workspaceName, username, parcelId);
    }
  }

  public boolean deleteWorkspace(int workspaceId) throws SQLException {
    try(final var connection = dataSource.getConnection();
        final var deleteWorkspace = new DeleteWorkspaceAction(connection)) {
      return deleteWorkspace.delete(workspaceId);
    }
  }

  @Override
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
