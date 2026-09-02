-- Add new Permissions Types
create type permissions.workspace_permission
 as enum (
   'NO_CHECK',
   'OWNER',
   'COLLABORATOR',
   'OWNER_COLLABORATOR'
  );
create type permissions.workspace_permission_key
 as enum (
   'create_workspace',
   'delete_file_directory',
   'delete_workspace',
   'list_workspace_contents',
   'read_file_directory',
   'write_file_directory'
  );

-- Add new permissions to permissions table
alter table permissions.user_role_permission
  add column workspace_permissions jsonb not null default '{}';

comment on table permissions.user_role_permission is e''
  'Permissions for a role that cannot be expressed in Hasura. Permissions take the form {KEY:PERMISSION}.'
  'A list of valid KEYs and PERMISSIONs can be found at https://nasa-ammos.github.io/aerie-docs/deployment/advanced-permissions/#action-and-function-permissions';

comment on column permissions.user_role_permission.workspace_permissions is ''
  'The permissions the role has on Workspace Functions.';

-- Update trigger functions
create function permissions.validate_action_permissions_json(_action_permissions jsonb)
returns table(key_error_msg text, value_error_msg text, plan_merge_error_msg text)
language plpgsql as $$
declare
  key_error_msg text;
  value_error_msg text;
  plan_merge_error_msg text;
begin
  key_error_msg = '';
  value_error_msg = '';
  plan_merge_error_msg = '';

  -- Do all the validation checks up front
  -- Duplicate keys are not checked for, as as all but the last instance is removed
  -- during conversion of JSON Text to JSONB (https://www.postgresql.org/docs/14/datatype-json.html)
  create temp table _validate_actions_table as
  select
    jsonb_object_keys(_action_permissions) as action_key,
    _action_permissions ->> jsonb_object_keys(_action_permissions) as action_permission,
    jsonb_object_keys(_action_permissions) = any(enum_range(null::permissions.action_permission_key)::text[]) as valid_action_key,
    _action_permissions ->> jsonb_object_keys(_action_permissions) = any(enum_range(null::permissions.permission)::text[]) as valid_action_permission,
  	_action_permissions ->> jsonb_object_keys(_action_permissions) = any(enum_range('PLAN_OWNER_SOURCE'::permissions.permission, 'PLAN_OWNER_COLLABORATOR_TARGET'::permissions.permission)::text[]) as is_plan_merge_permission;

  -- Get any invalid Action Keys
  if exists(select from _validate_actions_table where not valid_action_key)
  then
    key_error_msg = 'The following action keys are not valid: '
                 || (select string_agg(action_key, ', ')
                     from _validate_actions_table
                     where not valid_action_key);
  end if;

  -- Get any values that aren't Action Permissions
  if exists(select from _validate_actions_table where not valid_action_permission)
  then
    value_error_msg = 'The following action keys have invalid permissions: {'
                || (select string_agg(action_key || ': ' || action_permission, ', ')
                    from _validate_actions_table
                    where not valid_action_permission)
                ||e'}';
  end if;

	-- Check that no Actions have Plan Merge Permissions
  if exists(select from _validate_actions_table where is_plan_merge_permission)
  then
    plan_merge_error_msg = 'The following action keys may not take plan merge permissions: {'
                || (select string_agg(action_key || ': ' || action_permission, ', ')
                    from _validate_actions_table
                    where is_plan_merge_permission)
                ||e'}';
  end if;

  -- Drop Temp Table
  drop table _validate_actions_table;

  return query select key_error_msg, value_error_msg, plan_merge_error_msg;
end;
$$;

create function permissions.validate_function_permissions_json(_function_permissions jsonb)
returns table(key_error_msg text, value_error_msg text, plan_merge_error_msg text)
language plpgsql as $$
declare
  key_error_msg text;
  value_error_msg text;
  plan_merge_error_msg text;
  plan_merge_fns text[];
begin
  key_error_msg = '';
  value_error_msg = '';
  plan_merge_error_msg = '';

  plan_merge_fns := '{
    "begin_merge",
    "cancel_merge",
    "commit_merge",
    "create_merge_rq",
    "deny_merge",
    "get_conflicting_activities",
    "get_non_conflicting_activities",
    "set_resolution",
    "set_resolution_bulk",
    "withdraw_merge_rq"
    }';

  -- Do all the validation checks up front
  -- Duplicate keys are not checked for, as as all but the last instance is removed
  -- during conversion of JSON Text to JSONB (https://www.postgresql.org/docs/14/datatype-json.html)
  create temp table _validate_functions_table as
  select
    jsonb_object_keys(_function_permissions) as function_key,
    _function_permissions ->> jsonb_object_keys(_function_permissions) as function_permission,
    jsonb_object_keys(_function_permissions) = any(enum_range(null::permissions.function_permission_key)::text[]) as valid_function_key,
    _function_permissions ->> jsonb_object_keys(_function_permissions) = any(enum_range(null::permissions.permission)::text[]) as valid_function_permission,
    jsonb_object_keys(_function_permissions) = any(plan_merge_fns) as is_plan_merge_key,
  	_function_permissions ->> jsonb_object_keys(_function_permissions) = any(enum_range('PLAN_OWNER_SOURCE'::permissions.permission, 'PLAN_OWNER_COLLABORATOR_TARGET'::permissions.permission)::text[]) as is_plan_merge_permission;

  -- Get any invalid Function Keys
  if exists(select from _validate_functions_table where not valid_function_key)
  then
   key_error_msg = 'The following function keys are not valid: '
                || (select string_agg(function_key, ', ')
                     from _validate_functions_table
                     where not valid_function_key);
  end if;

  -- Get any values that aren't Function Permissions
  if exists(select from _validate_functions_table where not valid_function_permission)
  then
    value_error_msg = 'The following function keys have invalid permissions: {'
                || (select string_agg(function_key || ': ' || function_permission, ', ')
                    from _validate_functions_table
                    where not valid_function_permission)
                || '}';
  end if;

  -- Check that no non-Plan Merge Functions have Plan Merge Permissions
  if exists(select from _validate_functions_table where is_plan_merge_permission and not is_plan_merge_key)
  then
    plan_merge_error_msg = 'The following function keys may not take plan merge permissions: {'
                || (select string_agg(function_key || ': ' || function_permission, ', ')
                    from _validate_functions_table
                    where is_plan_merge_permission and not is_plan_merge_key)
                  || '}';
  end if;

  -- Drop Temp Tables
  drop table _validate_functions_table;

  return query select key_error_msg, value_error_msg, plan_merge_error_msg;
end;
$$;

create function permissions.validate_workspace_permissions_json(_workspace_permissions jsonb)
returns table(key_error_msg text, value_error_msg text)
language plpgsql as $$
declare
  key_error_msg text;
  value_error_msg text;
begin
  key_error_msg = '';
  value_error_msg = '';

  -- Do all the validation checks up front
  -- Duplicate keys are not checked for, as as all but the last instance is removed
  -- during conversion of JSON Text to JSONB (https://www.postgresql.org/docs/14/datatype-json.html)
  create temp table _validate_workspaces_table as
  select
    jsonb_object_keys(_workspace_permissions) as workspace_key,
    _workspace_permissions ->> jsonb_object_keys(_workspace_permissions) as workspace_permission,
    jsonb_object_keys(_workspace_permissions) = any(enum_range(null::permissions.workspace_permission_key)::text[]) as valid_workspace_key,
    _workspace_permissions ->> jsonb_object_keys(_workspace_permissions) = any(enum_range(null::permissions.workspace_permission)::text[]) as valid_workspace_permission;

  -- Ensure that "create_workspace", if the permission is provided, is set to "NO_CHECK"
  -- if not, flag the key as having an invalid permission
  update _validate_workspaces_table
  set valid_workspace_permission = false
  where workspace_key = 'create_workspace' and workspace_permission != 'NO_CHECK';

  -- Get any invalid Workspace Action Keys
  if exists(select from _validate_workspaces_table where not valid_workspace_key)
  then
    key_error_msg = 'The following workspace keys are not valid: '
                 || (select string_agg(workspace_key, ', ')
                     from _validate_workspaces_table
                     where not valid_workspace_key)
                 ||e'';
  end if;

  -- Get any values that aren't Workspace Permissions
  if exists(select from _validate_workspaces_table where not valid_workspace_permission)
  then
    value_error_msg = 'The following workspace keys have invalid permissions: {'
                || (select string_agg(workspace_key || ': ' || workspace_permission, ', ')
                    from _validate_workspaces_table
                    where not valid_workspace_permission)
                ||e'}';
  end if;

  -- Drop Temp Table
  drop table _validate_workspaces_table;

  return query select key_error_msg, value_error_msg;
end;
$$;

create or replace function permissions.validate_permissions_json()
returns trigger
language plpgsql as $$
  declare
    action_error_msgs record;
    function_error_msgs record;
    workspace_error_msgs record;
    key_error_msg text;
    value_error_msg text;
    plan_merge_error_msg text;
begin
  select *
  from permissions.validate_action_permissions_json(new.action_permissions)
  into action_error_msgs;

  select *
  from permissions.validate_function_permissions_json(new.function_permissions)
  into function_error_msgs;

  select *
  from permissions.validate_workspace_permissions_json(new.workspace_permissions)
  into workspace_error_msgs;

  with key_errors(msg) as (
    values (action_error_msgs.key_error_msg),
           (function_error_msgs.key_error_msg),
           (workspace_error_msgs.key_error_msg)
  ) select string_agg(msg, e'\n')
    from key_errors
    where msg != ''
    into key_error_msg;

  with value_errors(msg) as (
    values (action_error_msgs.value_error_msg),
           (function_error_msgs.value_error_msg),
           (workspace_error_msgs.value_error_msg)
  ) select string_agg(msg, e'\n')
    from value_errors
    where msg != ''
    into value_error_msg;

  with plan_merge_errors(msg) as (
    values (action_error_msgs.plan_merge_error_msg),
           (function_error_msgs.plan_merge_error_msg)
  ) select string_agg(msg, e'\n')
    from plan_merge_errors
    where msg != ''
    into plan_merge_error_msg;

  -- Raise if there were invalid Action/Function Keys
  if key_error_msg != '' then
    raise exception using
      message = 'invalid keys in supplied row',
      detail = key_error_msg,
      errcode = 'invalid_json_text',
      hint = 'Visit https://nasa-ammos.github.io/aerie-docs/deployment/advanced-permissions/#action-and-function-permissions for a list of valid keys.';
  end if;

  -- Raise if there were invalid Action/Function Permissions
  if value_error_msg != '' then
    raise exception using
      message = 'invalid permissions in supplied row',
      detail = value_error_msg,
      errcode = 'invalid_json_text',
      hint = 'Visit https://nasa-ammos.github.io/aerie-docs/deployment/advanced-permissions/#action-and-function-permissions for a list of valid Permissions.';
  end if;

  -- Raise if Plan Merge Permissions were improperly applied
  if plan_merge_error_msg != '' then
    raise exception using
      message = 'invalid permissions in supplied row',
      detail = plan_merge_error_msg,
      errcode = 'invalid_json_text',
      hint = 'Visit https://nasa-ammos.github.io/aerie-docs/deployment/advanced-permissions/#action-and-function-permissions for more information.';
  end if;

  return new;
end
$$;


-- Initialize default permissions for user roles
-- No filter so that all custom roles end up with basic read permissions
update permissions.user_role_permission
set workspace_permissions = '{
      "list_workspace_contents": "NO_CHECK",
      "read_file_directory": "NO_CHECK"
    }';

-- Reset admin to {}
update permissions.user_role_permission
set workspace_permissions = '{}'
where role = 'aerie_admin';

-- Set user permissions
update permissions.user_role_permission
set workspace_permissions = '{
      "create_workspace": "NO_CHECK",
      "delete_file_directory": "OWNER_COLLABORATOR",
      "delete_workspace": "OWNER",
      "list_workspace_contents": "NO_CHECK",
      "read_file_directory": "NO_CHECK",
      "write_file_directory": "OWNER_COLLABORATOR"
    }'
where role = 'user';

call migrations.mark_migration_applied(30);
