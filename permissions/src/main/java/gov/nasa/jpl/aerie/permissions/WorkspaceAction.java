package gov.nasa.jpl.aerie.permissions;

public enum WorkspaceAction implements Action {
  createWorkspace,
  deleteWorkspace,
  listWorkspaceContents,
  readFileDirectory,
  writeFileDirectory,
  deleteFileDirectory,
}
