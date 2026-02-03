package gov.nasa.jpl.aerie.permissions;

import gov.nasa.jpl.aerie.permissions.exceptions.Forbidden;
import gov.nasa.jpl.aerie.permissions.exceptions.GraphQLServiceException;
import gov.nasa.jpl.aerie.permissions.exceptions.NoSuchPlanException;
import gov.nasa.jpl.aerie.permissions.exceptions.NoSuchSchedulingSpecificationException;
import gov.nasa.jpl.aerie.permissions.exceptions.NoSuchWorkspaceException;
import gov.nasa.jpl.aerie.permissions.exceptions.PermissionsException;
import gov.nasa.jpl.aerie.permissions.exceptions.PermissionsFormattedError;
import gov.nasa.jpl.aerie.permissions.gql.GraphQLPermissionsService;
import gov.nasa.jpl.aerie.permissions.gql.PlanId;
import gov.nasa.jpl.aerie.permissions.gql.SchedulingSpecificationId;
import gov.nasa.jpl.aerie.permissions.gql.WorkspaceId;

import java.io.IOException;

public final class PermissionsService {
  private final GraphQLPermissionsService gqlService;

  public PermissionsService(final GraphQLPermissionsService gqlService) {
    this.gqlService = gqlService;
  }

  public void check(final HasuraAction action, final String role, final String username, final PlanId planId)
  throws PermissionsException {
    try {
      final var permissionType = getActionPermission(action, role);
      final var authorized = canPerformAction(permissionType, username, planId);
      if (!authorized) throw new Forbidden(action, role, username, permissionType, planId);
    } catch (Forbidden f) {
      throw new PermissionsException(403, new PermissionsFormattedError(f));
    } catch (NoSuchPlanException nsp) {
      throw new PermissionsException(404, new PermissionsFormattedError(nsp));
    } catch (GraphQLServiceException ex) {
      throw new PermissionsException(500, new PermissionsFormattedError(ex));
    } catch (IOException io) {
      throw new PermissionsException(500, new PermissionsFormattedError(io));
    }
  }

  public void check(
      final HasuraAction action,
      final String role,
      final String username,
      final SchedulingSpecificationId specificationId
  ) throws PermissionsException {
    try {
      final var planId = gqlService.getPlanIdFromSchedulingSpecificationId(specificationId);
      check(action, role, username, planId);
    } catch (NoSuchSchedulingSpecificationException nss) {
      throw new PermissionsException(404, new PermissionsFormattedError(nss));
    } catch (GraphQLServiceException ex) {
      throw new PermissionsException(500, new PermissionsFormattedError(ex));
    } catch (IOException io) {
      throw new PermissionsException(500, new PermissionsFormattedError(io));
    }
  }

  public void check(
      final WorkspaceAction action,
      final String role,
      final String username,
      final WorkspaceId workspaceId
  ) throws PermissionsException {
    try {
      final var permissionType = getWorkspaceActionPermission(action, role);
      final var authorized = canPerformWorkspaceAction(permissionType, username, workspaceId);
      if (!authorized) throw new Forbidden(action, role, username, permissionType, workspaceId);
    } catch (Forbidden f) {
      throw new PermissionsException(403, new PermissionsFormattedError(f));
    } catch (NoSuchWorkspaceException nsp) {
      throw new PermissionsException(404, new PermissionsFormattedError(nsp));
    } catch (GraphQLServiceException ex) {
      throw new PermissionsException(500, new PermissionsFormattedError(ex));
    } catch (IOException io) {
      throw new PermissionsException(500, new PermissionsFormattedError(io));
    }
  }

  public void checkCoarseGrained(final Action action, final String role)
  throws PermissionsException
  {
    try {
      if (action instanceof WorkspaceAction workspaceAction) {
        getWorkspaceActionPermission(workspaceAction, role);
      } else {
        throw new IllegalArgumentException("Unsupported action subtype: " + action.getClass());
      }
    } catch (Forbidden f) {
      throw new PermissionsException(403, new PermissionsFormattedError(f));
    } catch (GraphQLServiceException ex) {
      throw new PermissionsException(500, new PermissionsFormattedError(ex));
    } catch (IOException io) {
      throw new PermissionsException(500, new PermissionsFormattedError(io));
    }
  }

  private PlanPermissionType getActionPermission(final HasuraAction action, final String role)
  throws Forbidden, IOException, GraphQLServiceException
  {
    if (role.equals("aerie_admin")) {
      return PlanPermissionType.NO_CHECK;
    }
    return gqlService.getActionPermission(action, role);
  }

  private boolean canPerformAction(
      final PlanPermissionType permissionType,
      final String username,
      final PlanId planId)
  throws IOException, GraphQLServiceException, NoSuchPlanException {
    return switch (permissionType) {
      case NO_CHECK -> true;
      case MISSION_MODEL_OWNER -> gqlService.checkMissionModelOwner(planId, username);
      case OWNER, PLAN_OWNER -> getPlanPermissions(username, planId).isOwner();
      case PLAN_COLLABORATOR -> getPlanPermissions(username, planId).isCollaborator();
      case PLAN_OWNER_COLLABORATOR -> getPlanPermissions(username, planId).isOwnerOrCollaborator();
    };
  }

  private OwnerOrCollaborator getPlanPermissions(final String username, final PlanId planId)
  throws IOException, GraphQLServiceException, NoSuchPlanException
  {
    return gqlService.checkPlanOwnerCollaborator(planId, username);
  }

  private WorkspacePermissionType getWorkspaceActionPermission(final WorkspaceAction action, final String role)
  throws Forbidden, IOException, GraphQLServiceException
  {
    if (role.equals("aerie_admin")) {
      return WorkspacePermissionType.NO_CHECK;
    }
    return gqlService.getWorkspaceActionPermission(action, role);
  }

  private boolean canPerformWorkspaceAction(
      final WorkspacePermissionType permissionType,
      final String username,
      final WorkspaceId workspaceId)
  throws IOException, GraphQLServiceException, NoSuchWorkspaceException {
    return switch (permissionType) {
      case NO_CHECK -> true;
      case OWNER -> getWorkspacePermissions(username, workspaceId).isOwner();
      case COLLABORATOR -> getWorkspacePermissions(username, workspaceId).isCollaborator();
      case OWNER_COLLABORATOR -> getWorkspacePermissions(username, workspaceId).isOwnerOrCollaborator();
    };
  }

  private OwnerOrCollaborator getWorkspacePermissions(final String username, final WorkspaceId workspaceId)
  throws IOException, GraphQLServiceException, NoSuchWorkspaceException
  {
    return gqlService.checkWorkspaceOwnerCollaborator(workspaceId, username);
  }
}
