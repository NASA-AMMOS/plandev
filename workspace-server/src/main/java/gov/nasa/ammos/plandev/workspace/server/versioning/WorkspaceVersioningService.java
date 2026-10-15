package gov.nasa.ammos.plandev.workspace.server.versioning;

import org.eclipse.jgit.api.errors.GitAPIException;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public interface WorkspaceVersioningService {
  // Workspace-level methods
  void initializeNewWorkspace(Path repoPath) throws IOException, GitAPIException;
  void requireLocked(final int... workspaceIds);
  boolean isManaged(Path root) throws IOException;

  // File-level methods
  Map<String, LastEdit> lastEdits(final Path root, final Set<String> paths) throws IOException;
  Optional<LastEdit> lastEdit(Path root, String key) throws IOException;
}
