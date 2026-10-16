package gov.nasa.ammos.plandev.workspace.server.exceptions;

import java.util.List;

/** A later commit of a cross-workspace mutation failed after earlier workspaces were committed. */
public final class PartialMutationException extends WorkspaceHistoryException {
  public final List<Integer> committedWorkspaceIds;
  public final int restoredWorkspaceId;

  public PartialMutationException(final List<Integer> committed, final int restored, final Throwable cause) {
    super(Kind.PARTIALLY_APPLIED,
          "Workspace(s) %s were updated but workspace %d could not be and was restored".formatted(committed, restored),
          cause);
    this.committedWorkspaceIds = List.copyOf(committed);
    this.restoredWorkspaceId = restored;
  }
}
