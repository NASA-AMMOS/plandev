package gov.nasa.jpl.aerie.workspace.server.postgres;

public class NoSuchWorkspaceException extends Exception {
  public NoSuchWorkspaceException(final int workspaceId) {
    super("No such workspace exists with id "+workspaceId+".");
  }
}
