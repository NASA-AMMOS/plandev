package gov.nasa.ammos.plandev.workspace.server.exceptions;

import gov.nasa.ammos.plandev.workspace.server.types.Workspace;

import java.nio.file.Path;

public class NoSuchFileException extends Exception {
  public NoSuchFileException(int workspaceId, Path filePath) {
    super("No such file exists in workspace %d: %s".formatted(workspaceId, filePath.toString()));
  }

  public NoSuchFileException(Workspace workspace, Path filePath) {
    this(workspace.id(), filePath);
  }
}
