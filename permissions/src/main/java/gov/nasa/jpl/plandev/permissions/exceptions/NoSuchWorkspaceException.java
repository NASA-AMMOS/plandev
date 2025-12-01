package gov.nasa.jpl.plandev.permissions.exceptions;

import gov.nasa.jpl.plandev.permissions.gql.WorkspaceId;

public class NoSuchWorkspaceException extends Exception {
  public final WorkspaceId id;

  public NoSuchWorkspaceException(final WorkspaceId id) {
    super("No workspace exists with id '%s'".formatted(id));
    this.id = id;
  }
}
