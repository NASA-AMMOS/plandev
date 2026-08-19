package gov.nasa.ammos.plandev.permissions;

public enum WorkspaceAction implements Action {
  create_workspace,
  delete_file_directory,
  delete_workspace,
  list_workspace_contents,
  read_file_directory,
  write_file_directory,
}
