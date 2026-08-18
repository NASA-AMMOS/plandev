package gov.nasa.ammos.plandev.permissions.exceptions;

import gov.nasa.ammos.plandev.permissions.Action;
import gov.nasa.ammos.plandev.permissions.HasuraAction;
import gov.nasa.ammos.plandev.permissions.PlanPermissionType;
import gov.nasa.ammos.plandev.permissions.WorkspaceAction;
import gov.nasa.ammos.plandev.permissions.WorkspacePermissionType;
import gov.nasa.ammos.plandev.permissions.gql.PlanId;
import gov.nasa.ammos.plandev.permissions.gql.WorkspaceId;

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
