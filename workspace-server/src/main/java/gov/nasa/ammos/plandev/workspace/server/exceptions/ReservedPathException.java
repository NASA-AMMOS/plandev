package gov.nasa.ammos.plandev.workspace.server.exceptions;

import java.nio.file.Path;

/**
 * A user-supplied path names a reserved, server-internal part of the workspace (see WorkspacePaths), or goes through
 * a symbolic link, which workspaces do not support.
 */
public class ReservedPathException extends SecurityException {
  public ReservedPathException(final Path path, final String segment) {
    this("Path '%s' is not allowed: '%s' is reserved for internal use.".formatted(path, segment));
  }

  private ReservedPathException(final String message) {
    super(message);
  }

  public static ReservedPathException symbolicLink(final Path path, final String segment) {
    return new ReservedPathException(
        "Path '%s' is not allowed: '%s' is a symbolic link, which workspaces do not support.".formatted(path, segment));
  }
}
