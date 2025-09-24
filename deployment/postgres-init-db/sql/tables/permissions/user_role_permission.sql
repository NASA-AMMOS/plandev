create table permissions.user_role_permission(
  role text not null
    primary key
    references permissions.user_roles
      on update cascade
      on delete cascade,
  action_permissions jsonb not null default '{}',
  function_permissions jsonb not null default '{}'
);

comment on table permissions.user_role_permission is e''
  'Permissions for a role that cannot be expressed in Hasura. Permissions take the form {KEY:PERMISSION}.'
  'A list of valid KEYs and PERMISSIONs can be found at https://github.com/NASA-AMMOS/aerie/discussions/983#discussioncomment-6257146';
comment on column permissions.user_role_permission.role is e''
  'The role these permissions apply to.';
comment on column permissions.user_role_permission.action_permissions is ''
  'The permissions the role has on Hasura Actions.';
comment on column permissions.user_role_permission.function_permissions is ''
  'The permissions the role has on Hasura Functions.';

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
                     where not valid_action_key)
                 ||e'\n';
  end if;

  -- Get any values that aren't Action Permissions
  if exists(select from _validate_actions_table where not valid_action_permission)
  then
    value_error_msg = 'The following action keys have invalid permissions: {'
                || (select string_agg(action_key || ': ' || action_permission, ', ')
                    from _validate_actions_table
                    where not valid_action_permission)
                ||e'}\n';
  end if;

	-- Check that no Actions have Plan Merge Permissions
  if exists(select from _validate_actions_table where is_plan_merge_permission)
  then
    plan_merge_error_msg = 'The following action keys may not take plan merge permissions: {'
                || (select string_agg(action_key || ': ' || action_permission, ', ')
                    from _validate_actions_table
                    where is_plan_merge_permission)
                ||e'}\n';
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
    plan_merge_error_msg =
                || 'The following function keys may not take plan merge permissions: {'
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

create function permissions.validate_permissions_json()
returns trigger
language plpgsql as $$
  declare
    action_error_msgs record;
    function_error_msgs record;
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

  key_error_msg = '' || action_error_msgs.key_error_msg || function_error_msgs.key_error_msg;
  value_error_msg = '' || action_error_msgs.value_error_msg || function_error_msgs.value_error_msg;
  plan_merge_error_msg = '' || action_error_msgs.plan_merge_error_msg || function_error_msgs.plan_merge_error_msg;

  -- Raise if there were invalid Action/Function Keys
  if key_error_msg != '' then
    raise exception using
      message = 'invalid keys in supplied row',
      detail = trim(both e'\n' from key_error_msg),
      errcode = 'invalid_json_text',
      hint = 'Visit https://nasa-ammos.github.io/aerie-docs/deployment/advanced-permissions/#action-and-function-permissions for a list of valid keys.';
  end if;

  -- Raise if there were invalid Action/Function Permissions
  if value_error_msg != '' then
    raise exception using
      message = 'invalid permissions in supplied row',
      detail = trim(both e'\n' from value_error_msg),
      errcode = 'invalid_json_text',
      hint = 'Visit https://nasa-ammos.github.io/aerie-docs/deployment/advanced-permissions/#action-and-function-permissions for a list of valid Permissions.';
  end if;

  -- Raise if Plan Merge Permissions were improperly applied
  if plan_merge_error_msg != '' then
    raise exception using
      message = 'invalid permissions in supplied row',
      detail = trim(both e'\n' from plan_merge_error_msg),
      errcode = 'invalid_json_text',
      hint = 'Visit https://nasa-ammos.github.io/aerie-docs/deployment/advanced-permissions/#action-and-function-permissions for more information.';
  end if;

  return new;
end
$$;

create trigger validate_permissions_trigger
  before insert or update on permissions.user_role_permission
  for each row
  execute function permissions.validate_permissions_json();
