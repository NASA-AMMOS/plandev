package gov.nasa.ammos.plandev.permissions;

public enum WorkspacePermissionType implements PermissionType {
  /**
   * The given role is always allowed to perform this action
   */
  NO_CHECK,
  /**
   * The given user must be the owner of the given workspace
   */
  OWNER,
  /**
   * The given user must be a collaborator on the given workspace,
   * or a member of a role the workspace has granted access to
   */
  COLLABORATOR,
  /**
   * The given user must be either the owner or a collaborator on the given workspace,
   * or a member of a role the workspace has granted access to
   */
  OWNER_COLLABORATOR,
}
