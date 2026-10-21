package gov.nasa.ammos.plandev.workspace.server;

import gov.nasa.ammos.plandev.workspace.server.postgres.NoSuchWorkspaceException;
import gov.nasa.ammos.plandev.workspace.server.postgres.RenderType;
import gov.nasa.ammos.plandev.workspace.server.types.Workspace;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

/** Resolves a workspace id to the directory on disk that holds its working tree. */
public interface WorkspaceRepository {
  Path getBaseRepositoryPath();

  // Workspace Management
  int createWorkspace(String workspaceLocation, String workspaceName, String username, int parcelId) throws SQLException;
  Workspace getWorkspace(int workspaceId) throws SQLException, NoSuchWorkspaceException;
  boolean deleteWorkspace(Workspace workspace) throws SQLException;

  // File Extension Methods
  Map<String, RenderType> getExtensionMapping() throws SQLException;
  List<String> getMetadataExtensions() throws SQLException;
}
