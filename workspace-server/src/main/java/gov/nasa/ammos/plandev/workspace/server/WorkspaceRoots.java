package gov.nasa.ammos.plandev.workspace.server;

import gov.nasa.ammos.plandev.workspace.server.postgres.NoSuchWorkspaceException;

import java.nio.file.Path;

/** Resolves a workspace id to the directory on disk that holds its working tree. */
@FunctionalInterface
public interface WorkspaceRoots {
  Path workspaceRootPath(int workspaceId) throws NoSuchWorkspaceException;
}
