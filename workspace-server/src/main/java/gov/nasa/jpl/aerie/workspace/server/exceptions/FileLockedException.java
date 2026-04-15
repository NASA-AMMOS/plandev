package gov.nasa.jpl.aerie.workspace.server.exceptions;

import java.nio.file.Path;
import java.util.List;

public class FileLockedException extends Exception {
  public FileLockedException(Path file) {
    super("File %s is currently marked as readOnly.".formatted(file.toString()));
  }

  public FileLockedException(Path file, List<Path> files) {
    super("The following files in " + file.toString() +" are currently marked as readOnly:\n\t - "
          +String.join("\n\t - ", files.stream().map(Path::toString).toList()));
  }
}
