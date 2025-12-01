package gov.nasa.jpl.plandev.merlin.server.remotes;

import java.nio.file.Path;

public class MissionModelAccessException extends RuntimeException {
  private final Path path;

  public MissionModelAccessException(final Path path, final Throwable cause) {
    super(cause);
    this.path = path;
  }

  public Path getPath() {
    return this.path;
  }
}
