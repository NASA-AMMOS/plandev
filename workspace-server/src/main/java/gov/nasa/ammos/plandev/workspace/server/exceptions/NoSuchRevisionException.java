package gov.nasa.ammos.plandev.workspace.server.exceptions;

import java.util.UUID;

public class NoSuchRevisionException extends Exception {
  public NoSuchRevisionException(int workspaceId, UUID revisionId) {
    super("No such revision exists in workspace %d: %s".formatted(workspaceId, revisionId));
  }

  public NoSuchRevisionException(UUID revisionId, String filePath) {
    super("Revision %s is not a revision of %s".formatted(revisionId, filePath));
  }
}
