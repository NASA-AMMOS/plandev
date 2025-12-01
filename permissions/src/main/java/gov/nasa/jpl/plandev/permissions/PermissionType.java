package gov.nasa.jpl.plandev.permissions;

public sealed interface PermissionType permits PlanPermissionType, WorkspacePermissionType {}
