drop type permissions.workspace_permission_key;
create type permissions.workspace_permission_key
as enum (
  'create_workspace',
  'delete_file_directory',
  'delete_workspace',
  'list_workspace_contents',
  'read_file_directory',
  'write_file_directory'
  );

update permissions.user_role_permission
set workspace_permissions = workspace_permissions  - 'create_file_revision';

call migrations.mark_migration_rolled_back(39);
