package gov.nasa.jpl.aerie.permissions.exceptions;

import gov.nasa.jpl.aerie.permissions.Action;
import gov.nasa.jpl.aerie.permissions.HasuraAction;
import gov.nasa.jpl.aerie.permissions.PermissionType;
import gov.nasa.jpl.aerie.permissions.gql.PlanId;

public class Unauthorized extends Exception {
  public Unauthorized(final String role, final Action action) {
    super("Role '%s' is not allowed to perform action '%s'".formatted(role, action));
  }

  public Unauthorized(
      final HasuraAction action,
      final String role,
      final String username,
      final PermissionType permissionType,
      final PlanId planId) {
    super("User '%s' with role '%s' cannot perform '%s' because they are not a '%s' for plan with id '%d'".formatted(
        username,
        role,
        action,
        permissionType,
        planId.id()));
  }
}
