package gov.nasa.ammos.plandev.workspace.server.exceptions;

import java.nio.file.Path;

public class NoSuchFileException extends Exception {
  public NoSuchFileException(int workspaceId, Path filePath) {
    super("No such file exists in workspace %d: %s".formatted(workspaceId, filePath.toString()));
  }
}
