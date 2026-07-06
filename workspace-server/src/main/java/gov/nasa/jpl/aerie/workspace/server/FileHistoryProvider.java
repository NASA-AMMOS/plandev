package gov.nasa.jpl.aerie.workspace.server;

import gov.nasa.jpl.aerie.workspace.server.exceptions.WorkspaceFileOpException;
import gov.nasa.jpl.aerie.workspace.server.postgres.NoSuchWorkspaceException;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

/**
 * Supplies a file's created / last-edited provenance derived from git history (Approach 2), so the workspace
 * metadata sidecar need not store these volatile fields. Implemented by {@code WorkspaceVersioningService};
 * consumed by {@code WorkspaceFileSystemService} via setter injection (breaking the construction cycle —
 * versioning already depends on the file-system service).
 */
public interface FileHistoryProvider {
  /**
   * The file's provenance from {@code git log --follow}: the earliest commit touching the path is its
   * creation, the latest is its most recent edit. Empty if the workspace is not a git repo or the path has
   * no committed history (caller falls back to any stored sidecar values).
   */
  Optional<FileHistoryInfo> fileHistory(int workspaceId, Path filePath)
  throws NoSuchWorkspaceException, IOException, WorkspaceFileOpException;

  /**
   * Batched provenance for every file currently in the workspace, keyed by workspace-root-relative path.
   * Computed in a single pass over git history (O(commits), not O(files)) so callers like directory listing
   * can enrich a whole tree without an N+1 history walk. Path-based (not rename-following): a renamed file's
   * "created" reads as when it appeared at its current path. Empty if the workspace is not a git repo.
   */
  Map<String, FileHistoryInfo> fileHistories(int workspaceId)
  throws NoSuchWorkspaceException, IOException, WorkspaceFileOpException;

  /** Author + ISO-8601 time of a file's first (created) and latest (last-edited) commit. */
  record FileHistoryInfo(String createdBy, String createdAt, String lastEditedBy, String lastEditedAt) {}
}
