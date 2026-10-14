/*
  Surround the permission type change by disabling and reenabling the validation trigger
    For some reason, at a certain number of changes to the permissions enums, we begin to encounter
    cache misses when bulk migrating, causing migrations to fail with the error "ERROR:  cache lookup failed for type 19553"
    when creating the temp table during the permissions validation function.
  This causes the bulk migration to fail, though it will then pass when immediately rerun (as it will re-cache the indexes)
  Disabling the trigger while editing these enums seems to mitigate the issue.
 */
alter table permissions.user_role_permission disable trigger validate_permissions_trigger;

drop type permissions.workspace_permission_key;
create type permissions.workspace_permission_key
as enum (
  'create_workspace',
  'create_file_revision',
  'delete_file_directory',
  'delete_workspace',
  'list_workspace_contents',
  'read_file_directory',
  'write_file_directory'
  );

update permissions.user_role_permission
set workspace_permissions = workspace_permissions
                           || jsonb_build_object('create_file_revision', 'OWNER_COLLABORATOR')
where role = 'user';

-- Re-enable the validation trigger
alter table permissions.user_role_permission enable trigger validate_permissions_trigger;

call migrations.mark_migration_applied(39);
