/*
  Surround the permission type change by disabling and reenabling the validation trigger
    For some reason, at a certain number of changes to the permissions enums, we begin to encounter
    cache misses when bulk migrating, causing migrations to fail with the error "ERROR:  cache lookup failed for type 19553"
    when creating the temp table during the permissions validation function.
  This causes the bulk migration to fail, though it will then pass when immediately rerun (as it will re-cache the indexes)
  Disabling the trigger while editing these enums seems to mitigate the issue.
 */
alter table permissions.user_role_permission
  disable trigger validate_permissions_trigger;

update permissions.user_role_permission
set action_permissions = action_permissions - 'expand_all_templates' - 'assign_activities_by_filter';

drop type permissions.action_permission_key;

create type permissions.action_permission_key
  as enum (
    'check_constraints',
    'create_expansion_rule',
    'create_expansion_set',
    'expand_all_activities',
    'insert_ext_dataset',
    'resource_samples',
    'schedule',
    'sequence_seq_json_bulk',
    'simulate'
  );

alter table permissions.user_role_permission
  enable trigger validate_permissions_trigger;

call migrations.mark_migration_rolled_back('22');
