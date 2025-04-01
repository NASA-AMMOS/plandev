create table hasura.migrate_plan_to_model_return_value(result text);
create function hasura.migrate_plan_to_model(_plan_id integer, _new_model_id integer, hasura_session json)
  returns hasura.migrate_plan_to_model_return_value
  volatile
  language plpgsql as $$
declare
  requester_username text;
  _function_permission permissions.permission;
  open_merge_count integer;
begin
  requester_username := (hasura_session ->> 'x-hasura-user-id');
  _function_permission := permissions.get_function_permissions('migrate_plan_to_model', hasura_session);
  perform permissions.raise_if_plan_merge_permission('migrate_plan_to_model', _function_permission);
  -- Additionally, the user needs to be OWNER of the plan
  call permissions.check_general_permissions('migrate_plan_to_model', _function_permission, _plan_id, requester_username);

  -- Check for open merge requests
  select count(*) into open_merge_count
  from merlin.merge_request
  where _plan_id = migrate_plan_to_model._plan_id
    and status in ('pending', 'in-progress');

  if open_merge_count > 0 then
      raise exception 'Cannot migrate plan %: it has open merge requests.', _plan_id;
  end if;

  -- Create snapshot before migration
  perform merlin.create_snapshot(_plan_id, 'Automatic snapshot for migration', 'Automatic snapshot before attempting migration to model id ' || _new_model_id, requester_username);

  -- Perform model migration
  update merlin.plan
  set model_id = _new_model_id
  where id = _plan_id;

  return row('success')::hasura.migrate_plan_to_model_return_value;
end;
$$;
