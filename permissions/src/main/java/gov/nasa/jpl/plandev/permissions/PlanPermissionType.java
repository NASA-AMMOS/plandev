package gov.nasa.jpl.plandev.permissions;

public enum PlanPermissionType implements PermissionType {
  /**
   * The given role is always allowed to perform this action
   */
  NO_CHECK,
  /**
   * The given user must be the owner of the relevant object. The interpretation can vary by action
   */
  OWNER,
  /**
   * The given user must be the owner of the given mission model
   */
  MISSION_MODEL_OWNER,
  /**
   * The given user must be the plan owner of the given plan
   */
  PLAN_OWNER,
  /**
   * The given user must be a plan collaborator on the given plan
   */
  PLAN_COLLABORATOR,
  /**
   * The given user must be either the plan owner or a plan collaborator on the given plan
   */
  PLAN_OWNER_COLLABORATOR,
}
