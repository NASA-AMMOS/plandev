package gov.nasa.jpl.aerie.permissions.exceptions;

import gov.nasa.jpl.aerie.permissions.Action;
import gov.nasa.jpl.aerie.permissions.HasuraAction;
import gov.nasa.jpl.aerie.permissions.PlanPermissionType;
import gov.nasa.jpl.aerie.permissions.WorkspaceAction;
import gov.nasa.jpl.aerie.permissions.WorkspacePermissionType;
import gov.nasa.jpl.aerie.permissions.gql.PlanId;
import gov.nasa.jpl.aerie.permissions.gql.WorkspaceId;

public class Forbidden extends Exception {
  public Forbidden(final String role, final Action action) {
    super("Role '%s' is not allowed to perform action '%s'".formatted(role, action));
  }

  public Forbidden(
      final HasuraAction action,
      final String role,
      final String username,
      final PlanPermissionType permissionType,
      final PlanId planId) {
    super("User '%s' with role '%s' cannot perform '%s' because they are not a '%s' for plan with id '%d'".formatted(
        username,
        role,
        action,
        permissionType,
        planId.id()));
  }

  public Forbidden(
      final WorkspaceAction action,
      final String role,
      final String username,
      final WorkspacePermissionType permissionType,
      final WorkspaceId planId) {
    super("User '%s' with role '%s' cannot perform '%s' because they are not a '%s' for workspace with id '%d'".formatted(
        username,
        role,
        action,
        permissionType,
        planId.id()));
  }
}
