package gov.nasa.ammos.plandev.permissions;

public sealed interface PermissionType permits PlanPermissionType, WorkspacePermissionType {}
