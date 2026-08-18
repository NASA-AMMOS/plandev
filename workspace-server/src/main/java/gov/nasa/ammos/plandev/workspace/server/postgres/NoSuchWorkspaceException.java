package gov.nasa.ammos.plandev.workspace.server.postgres;

public class NoSuchWorkspaceException extends Exception {
  private final int workspaceId;

  public NoSuchWorkspaceException(final int workspaceId) {
    super("No such workspace exists with id "+workspaceId+".");
    this.workspaceId = workspaceId;
  }

  public int getWorkspaceId() {
    return workspaceId;
  }
}
