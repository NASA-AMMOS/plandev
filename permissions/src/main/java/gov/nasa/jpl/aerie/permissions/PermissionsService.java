package gov.nasa.jpl.aerie.permissions;

import gov.nasa.jpl.aerie.permissions.exceptions.NoSuchPlanException;
import gov.nasa.jpl.aerie.permissions.exceptions.NoSuchSchedulingSpecificationException;
import gov.nasa.jpl.aerie.permissions.exceptions.PermissionsServiceException;
import gov.nasa.jpl.aerie.permissions.exceptions.Unauthorized;
import gov.nasa.jpl.aerie.permissions.gql.GraphQLPermissionsService;
import gov.nasa.jpl.aerie.permissions.gql.PlanId;
import gov.nasa.jpl.aerie.permissions.gql.SchedulingSpecificationId;

import java.io.IOException;

public final class PermissionsService {
  private final GraphQLPermissionsService gqlService;

  public PermissionsService(final GraphQLPermissionsService gqlService) {
    this.gqlService = gqlService;
  }
  public void check(final Action action, final String role, final String username, final PlanId planId)
  throws Unauthorized, IOException, PermissionsServiceException, NoSuchPlanException {
    final var permissionType = getActionPermission(action, role);
    final var authorized = canPerformAction(permissionType, username, planId);
    if (!authorized) throw new Unauthorized(action, role, username, permissionType, planId);
  }

    public void check(
      final Action action,
      final String role,
      final String username,
      final SchedulingSpecificationId specificationId)
  throws Unauthorized, IOException, PermissionsServiceException, NoSuchSchedulingSpecificationException,
         NoSuchPlanException
  {
    final var planId = gqlService.getPlanIdFromSchedulingSpecificationId(specificationId);
    check(action, role, username, planId);
  }

  private PermissionType getActionPermission(final Action action, final String role)
  throws Unauthorized, IOException, PermissionsServiceException
  {
    if (role.equals("aerie_admin")) {
      return PermissionType.NO_CHECK;
    }
    return gqlService.getActionPermission(action, role);
  }

  private boolean canPerformAction(
      final PermissionType permissionType,
      final String username,
      final PlanId planId)
  throws IOException, PermissionsServiceException, NoSuchPlanException {
    return switch (permissionType) {
      case NO_CHECK -> true;
      case MISSION_MODEL_OWNER -> gqlService.checkMissionModelOwner(planId, username);
      case OWNER, PLAN_OWNER -> getPlanPermissions(username, planId).isPlanOwner();
      case PLAN_COLLABORATOR -> getPlanPermissions(username, planId).isPlanCollaborator();
      case PLAN_OWNER_COLLABORATOR -> getPlanPermissions(username, planId).isPlanOwnerOrCollaborator();
    };
  }

  private PlanOwnerOrCollaborator getPlanPermissions(final String username, final PlanId planId)
  throws IOException, PermissionsServiceException, NoSuchPlanException
  {
    return gqlService.checkPlanOwnerCollaborator(planId, username);
  }

}
