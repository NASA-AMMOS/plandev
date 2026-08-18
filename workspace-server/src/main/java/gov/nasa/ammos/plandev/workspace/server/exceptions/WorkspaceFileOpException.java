package gov.nasa.ammos.plandev.workspace.server.exceptions;

public class WorkspaceFileOpException extends Exception {
  public WorkspaceFileOpException(String msg) {
    super(msg);
  }

  public WorkspaceFileOpException(String msg, Exception e) {
    super(msg, e);
  }
}
