package gov.nasa.ammos.plandev.workspace.server.versioning;

import gov.nasa.ammos.plandev.workspace.server.exceptions.WorkspaceFileOpException;
import gov.nasa.ammos.plandev.workspace.server.postgres.NoSuchWorkspaceException;
import org.eclipse.jgit.api.errors.GitAPIException;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

public interface WorkspaceVersioningService {
  // Workspace-level methods
  void initializeNewWorkspace(Path repoPath) throws IOException, GitAPIException;
  void requireLocked(final int... workspaceIds);
  boolean isManaged(Path root) throws IOException;

  // File-level methods
  Map<Path, LastEdit> lastEdits(final int workspaceId, final Set<Path> paths)
  throws IOException, NoSuchWorkspaceException, WorkspaceFileOpException;
  LastEdit lastEdit(final int workspaceId, Path filePath)
  throws IOException, NoSuchWorkspaceException, WorkspaceFileOpException;
}
