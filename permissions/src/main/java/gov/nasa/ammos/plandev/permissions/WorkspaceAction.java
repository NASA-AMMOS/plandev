package gov.nasa.ammos.plandev.permissions;

public enum WorkspaceAction implements Action {
  create_workspace,
  delete_file_directory,
  delete_workspace,
  list_workspace_contents,
  read_file_directory,
  write_file_directory;

  /**
   * Whether a workspace's role grants confer this action. Role grants give file editing access only;
   * deleting the workspace stays with its owner and named collaborators, whatever the role's permission type is.
   */
  public boolean roleGrantable() {
    return this != delete_workspace;
  }
}
