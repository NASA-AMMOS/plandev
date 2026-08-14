package gov.nasa.ammos.plandev.permissions;

public sealed interface Action permits HasuraAction, WorkspaceAction {}
