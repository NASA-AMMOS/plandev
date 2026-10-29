package gov.nasa.ammos.plandev.workspace.server.exceptions;

import gov.nasa.ammos.plandev.workspace.server.types.Workspace;

import java.util.List;

/** A later commit of a cross-workspace mutation failed after earlier workspaces were committed. */
public final class PartialMutationException extends WorkspaceHistoryException {
  public final List<Integer> committedWorkspaceIds;
  public final Workspace restoredWorkspace;

  public PartialMutationException(final List<Workspace> committed, final Workspace restored, final Throwable cause) {
    super(Kind.PARTIALLY_APPLIED,
          "Workspace(s) %s were updated but workspace %d could not be and was restored".formatted(committed, restored.id()),
          cause);
    this.committedWorkspaceIds = committed.stream().map(Workspace::id).toList();
    this.restoredWorkspace = restored;
  }
}
