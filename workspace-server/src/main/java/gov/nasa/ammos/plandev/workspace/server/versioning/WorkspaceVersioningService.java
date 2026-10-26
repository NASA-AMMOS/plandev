package gov.nasa.ammos.plandev.workspace.server.versioning;

import gov.nasa.ammos.plandev.workspace.server.WorkspaceService;
import gov.nasa.ammos.plandev.workspace.server.exceptions.WorkspaceFileOpException;
import gov.nasa.ammos.plandev.workspace.server.exceptions.WorkspaceHistoryException;
import gov.nasa.ammos.plandev.workspace.server.postgres.NoSuchWorkspaceException;
import gov.nasa.ammos.plandev.workspace.server.types.Workspace;
import org.eclipse.jgit.api.errors.GitAPIException;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

public interface WorkspaceVersioningService {
  // Workspace-level methods
  void initializeWorkspace(Workspace workspace) throws IOException, GitAPIException;
  /**
   * Lock and delete the workspace and its history.
   *
   * @param workspace the workspace to be deleted
   * @param service the WorkspaceService to perform the deletion
   * */
  boolean deleteWorkspace(final Workspace workspace, final WorkspaceService service) throws Exception;
  void requireLocked(final Workspace... workspaces);
  boolean isManaged(final Workspace workspace) throws IOException, NoSuchWorkspaceException;

  // Read-only File-level methods
  Map<Path, LastEdit> lastEdits(final Workspace workspace, final Set<Path> paths)
  throws IOException, WorkspaceFileOpException;
  LastEdit lastEdit(final Workspace workspace, Path filePath)
  throws IOException, WorkspaceFileOpException;

  // Mutations
  /**
   * Run a mutation against a single workspace. See {@link #mutate(LinkedHashMap, String, WorkspaceGitService.Mutation, Predicate)}.
   *
   * @param workspace the Workspace to run the mutation against.
   * @param message the message to be recorded in the workspace's history.
   * @param userId the SeqDev user to be associated with the changes
   * @param mutation the mutation to be executed against the workspace
   * @param succeeded the algorithm for determining if the result of the mutation is a success.
   *    In the event of a failure, changes to the workspace will be reverted.
   * @return the result of running mutation
   */
  <T> T mutate(
      final Workspace workspace,
      final String message,
      final String userId,
      final WorkspaceGitService.Mutation<T> mutation,
      final Predicate<T> succeeded
  ) throws Exception;

  /**
   * Run a mutation across one or more workspaces under their locks, then record it in each workspace's history.
   *
   * @param commits the involved workspaces, in commit order, each with its commit message
   * @param userId the PlanDev user the commits are authored by
   * @param mutation performs validation and the filesystem change; may return a failure result instead of throwing
   * @param succeeded whether a returned result is a success; a failure result is rolled back and returned as-is
   * @return the mutation's result
   * @throws WorkspaceHistoryException if history could not be recorded (the change was rolled back), was recorded in
   *     only some workspaces, or the repository could not be restored
   * @throws Exception anything the mutation throws, after the workspaces have been restored
   */
  <T> T mutate(
      final LinkedHashMap<Workspace, String> commits,
      final String userId,
      final WorkspaceGitService.Mutation<T> mutation,
      final Predicate<T> succeeded
  ) throws Exception;
}
