package gov.nasa.ammos.plandev.workspace.server.exceptions;

import java.nio.file.Path;

/** A user-supplied path names a reserved, server-internal part of the workspace (see WorkspacePaths). */
public class ReservedPathException extends SecurityException {
  public ReservedPathException(final Path path, final String segment) {
    super("Path '%s' is not allowed: '%s' is reserved for internal use.".formatted(path, segment));
  }
}
