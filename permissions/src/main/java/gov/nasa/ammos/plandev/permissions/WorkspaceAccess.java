package gov.nasa.ammos.plandev.permissions;

/**
 * How a user relates to a workspace's root access policy. The sources of access are kept distinct
 * so that policies for narrower scopes (e.g. folders) can later treat them differently.
 *
 * @param owner the user owns the workspace
 * @param collaborator the user is an explicit collaborator
 * @param roleGranted the user belongs to a role the workspace has granted write access to
 */
public record WorkspaceAccess(boolean owner, boolean collaborator, boolean roleGranted) {
  /**
   * Collaborator-equivalent access: an explicit collaborator, or a member of a granted role
   * (when the action can be conferred by a role grant).
   */
  boolean hasCollaboratorAccess(final WorkspaceAction action) {
    return collaborator || (roleGranted && action.roleGrantable());
  }
}
