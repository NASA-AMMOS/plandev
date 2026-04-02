-- Reset trigger functions for user_role_permission table
create or replace function permissions.validate_permissions_json()
returns trigger
language plpgsql as $$
  declare
    error_msg text;
    plan_merge_fns text[];
begin
  error_msg = '';

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
    jsonb_object_keys(new.function_permissions) as function_key,
    new.function_permissions ->> jsonb_object_keys(new.function_permissions) as function_permission,
    jsonb_object_keys(new.function_permissions) = any(enum_range(null::permissions.function_permission_key)::text[]) as valid_function_key,
    new.function_permissions ->> jsonb_object_keys(new.function_permissions) = any(enum_range(null::permissions.permission)::text[]) as valid_function_permission,
    jsonb_object_keys(new.function_permissions) = any(plan_merge_fns) as is_plan_merge_key,
  	new.function_permissions ->> jsonb_object_keys(new.function_permissions) = any(enum_range('PLAN_OWNER_SOURCE'::permissions.permission, 'PLAN_OWNER_COLLABORATOR_TARGET'::permissions.permission)::text[]) as is_plan_merge_permission;

  create temp table _validate_actions_table as
  select
    jsonb_object_keys(new.action_permissions) as action_key,
    new.action_permissions ->> jsonb_object_keys(new.action_permissions) as action_permission,
    jsonb_object_keys(new.action_permissions) = any(enum_range(null::permissions.action_permission_key)::text[]) as valid_action_key,
    new.action_permissions ->> jsonb_object_keys(new.action_permissions) = any(enum_range(null::permissions.permission)::text[]) as valid_action_permission,
  	new.action_permissions ->> jsonb_object_keys(new.action_permissions) = any(enum_range('PLAN_OWNER_SOURCE'::permissions.permission, 'PLAN_OWNER_COLLABORATOR_TARGET'::permissions.permission)::text[]) as is_plan_merge_permission;


  -- Get any invalid Action Keys
  if exists(select from _validate_actions_table where not valid_action_key)
  then
    error_msg = 'The following action keys are not valid: '
                 || (select string_agg(action_key, ', ')
                     from _validate_actions_table
                     where not valid_action_key)
                 ||e'\n';
  end if;
  -- Get any invalid Function Keys
  if exists(select from _validate_functions_table where not valid_function_key)
  then
    error_msg = error_msg
                 || 'The following function keys are not valid: '
                 || (select string_agg(function_key, ', ')
                     from _validate_functions_table
                     where not valid_function_key);
  end if;

  -- Raise if there were invalid Action/Function Keys
  if error_msg != '' then
    raise exception using
      message = 'invalid keys in supplied row',
      detail = trim(both e'\n' from error_msg),
      errcode = 'invalid_json_text',
      hint = 'Visit https://nasa-ammos.github.io/aerie-docs/deployment/advanced-permissions/#action-and-function-permissions for a list of valid keys.';
  end if;

  -- Get any values that aren't Action Permissions
  if exists(select from _validate_actions_table where not valid_action_permission)
  then
    error_msg = 'The following action keys have invalid permissions: {'
                || (select string_agg(action_key || ': ' || action_permission, ', ')
                    from _validate_actions_table
                    where not valid_action_permission)
                ||e'}\n';
  end if;

  -- Get any values that aren't Function Permissions
  if exists(select from _validate_functions_table where not valid_function_permission)
  then
    error_msg = error_msg
                || 'The following function keys have invalid permissions: {'
                || (select string_agg(function_key || ': ' || function_permission, ', ')
                    from _validate_functions_table
                    where not valid_function_permission)
                || '}';
  end if;

  -- Raise if there were invalid Action/Function Permissions
  if error_msg != '' then
    raise exception using
      message = 'invalid permissions in supplied row',
      detail = trim(both e'\n' from error_msg),
      errcode = 'invalid_json_text',
      hint = 'Visit https://nasa-ammos.github.io/aerie-docs/deployment/advanced-permissions/#action-and-function-permissions for a list of valid Permissions.';
  end if;

	-- Check that no Actions have Plan Merge Permissions
  if exists(select from _validate_actions_table where is_plan_merge_permission)
  then
    error_msg = 'The following action keys may not take plan merge permissions: {'
                || (select string_agg(action_key || ': ' || action_permission, ', ')
                    from _validate_actions_table
                    where is_plan_merge_permission)
                ||e'}\n';
  end if;

  -- Check that no non-Plan Merge Functions have Plan Merge Permissions
  if exists(select from _validate_functions_table where is_plan_merge_permission and not is_plan_merge_key)
  then
    error_msg = error_msg
                || 'The following function keys may not take plan merge permissions: {'
                || (select string_agg(function_key || ': ' || function_permission, ', ')
                    from _validate_functions_table
                    where is_plan_merge_permission and not is_plan_merge_key)
                  || '}';
  end if;

  -- Raise if Plan Merge Permissions were improperly applied
  if error_msg != '' then
    raise exception using
      message = 'invalid permissions in supplied row',
      detail = trim(both e'\n' from error_msg),
      errcode = 'invalid_json_text',
      hint = 'Visit https://nasa-ammos.github.io/aerie-docs/deployment/advanced-permissions/#action-and-function-permissions for more information.';
  end if;

  -- Drop Temp Tables
  drop table _validate_functions_table;
  drop table _validate_actions_table;

  return new;
end
$$;

drop function permissions.validate_workspace_permissions_json(_workspace_permissions jsonb);
drop function permissions.validate_function_permissions_json(_function_permissions jsonb);
drop function permissions.validate_action_permissions_json(_action_permissions jsonb);

-- Drop new column and reset table comment
comment on table permissions.user_role_permission is e''
  'Permissions for a role that cannot be expressed in Hasura. Permissions take the form {KEY:PERMISSION}.'
  'A list of valid KEYs and PERMISSIONs can be found at https://github.com/NASA-AMMOS/plandev/discussions/983#discussioncomment-6257146';

alter table permissions.user_role_permission
  drop column workspace_permissions;

-- Drop new Enums
drop type permissions.workspace_permission_key;
drop type permissions.workspace_permission;

call migrations.mark_migration_rolled_back(30);
