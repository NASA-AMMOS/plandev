package gov.nasa.ammos.plandev.workspace.server.postgres;

import gov.nasa.ammos.plandev.workspace.server.WorkspaceRepository;
import gov.nasa.ammos.plandev.workspace.server.types.Workspace;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

public final class WorkspacePostgresRepository implements WorkspaceRepository {
  private final Path baseRepositoryPath;
  private final DataSource dataSource;

  public WorkspacePostgresRepository(final Path baseRepositoryPath, final DataSource dataSource){
    this.baseRepositoryPath = baseRepositoryPath;
    this.dataSource = dataSource;
  }

  @Override
  public Path getBaseRepositoryPath() {
    return baseRepositoryPath;
  }

  @Override
  public int createWorkspace(String workspaceLocation, String workspaceName, String username, int parcelId)
  throws SQLException {
    try(final var connection = dataSource.getConnection();
        final var createWorkspace = new CreateWorkspaceAction(connection)) {
      return createWorkspace.create(workspaceLocation, workspaceName, username, parcelId);
    }
  }

  /**
   * Get the root path for a given workspace, returning an object with the id and root path combined
   * @param workspaceId the DB id for the workspace
   * @return A {@link Workspace} containing the DB id and the file system path to the root of the workspace
   * @throws NoSuchWorkspaceException if there is no workspace under with that id
   */
  @Override
  public Workspace getWorkspace(final int workspaceId) throws SQLException, NoSuchWorkspaceException {
    try(final var connection = dataSource.getConnection();
        final var getRootPath = new GetWorkspaceRootPathAction(connection)) {
      return new Workspace(workspaceId, baseRepositoryPath.resolve(getRootPath.get(workspaceId)));
    }
  }

  @Override
  public boolean deleteWorkspace(Workspace workspace) throws SQLException {
    try(final var connection = dataSource.getConnection();
        final var deleteWorkspace = new DeleteWorkspaceAction(connection)) {
      return deleteWorkspace.delete(workspace.id());
    }
  }

  @Override
  public Map<String, RenderType> getExtensionMapping() throws SQLException {
    try(final var connection = dataSource.getConnection();
        final var getMappingAction = new GetExtensionMappingsAction(connection)) {
      return getMappingAction.get();
    }
  }

  @Override
  public List<String> getMetadataExtensions() throws SQLException {
    try(final var connection = dataSource.getConnection();
        final var getMappingAction = new GetExtensionMappingsAction(connection)) {
      return getMappingAction.get(RenderType.METADATA);
    }
  }
}
