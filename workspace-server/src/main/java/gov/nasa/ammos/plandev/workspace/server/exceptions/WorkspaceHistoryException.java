package gov.nasa.ammos.plandev.workspace.server.exceptions;

public class WorkspaceHistoryException extends RuntimeException {
  public enum Kind {
    /** The history could not be recorded; the workspace was restored to its pre-operation state. */
    HISTORY_NOT_RECORDED("HISTORY_NOT_RECORDED"),
    /** A cross-workspace mutation was applied to some workspaces only; see {@link PartialMutationException}. */
    PARTIALLY_APPLIED("WORKSPACE_MUTATION_PARTIAL"),
    /** The repository is not in a state this class can vouch for, and was not (or could not be) repaired. */
    REPOSITORY_INCONSISTENT("WORKSPACE_REPOSITORY_INCONSISTENT"),
    /** There was some other kind of exception relating to. */
    OTHER("WORKSPACE_HISTORY_ERROR");

    public final String errorType;

    Kind(final String errorType) {
      this.errorType = errorType;
    }
  }

  public final Kind kind;

  public WorkspaceHistoryException(final Kind kind, final String message, final Throwable cause) {
    super(message, cause);
    this.kind = kind;
  }

  public WorkspaceHistoryException(final Kind kind, final String message) {
    super(message);
    this.kind = kind;
  }

  public static WorkspaceHistoryException inconsistent(final String message) {
    return new WorkspaceHistoryException(WorkspaceHistoryException.Kind.REPOSITORY_INCONSISTENT, message, null);
  }
}
