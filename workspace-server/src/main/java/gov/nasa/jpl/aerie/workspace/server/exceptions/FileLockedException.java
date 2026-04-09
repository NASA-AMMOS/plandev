package gov.nasa.jpl.aerie.workspace.server.exceptions;

import java.nio.file.Path;

public class FileLockedException extends Exception {
  public FileLockedException(Path file) {
    super("File %s is currently marked as readOnly.".formatted(file.toString()));
  }
}
