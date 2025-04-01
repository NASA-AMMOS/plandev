create table hasura.migrate_plan_to_model_return_value(result text);
create function hasura.migrate_plan_to_model(_plan_id integer, _new_model_id integer, hasura_session json)
  returns hasura.migrate_plan_to_model_return_value
  volatile
  language plpgsql as $$
declare
  _requester_username text;
  _function_permission permissions.permission;
  _open_merge_count integer;
begin
  _requester_username := (hasura_session ->> 'x-hasura-user-id');
  _function_permission := permissions.get_function_permissions('migrate_plan_to_model', hasura_session);
  perform permissions.raise_if_plan_merge_permission('migrate_plan_to_model', _function_permission);

  -- Check for open merge requests
  select count(*) into _open_merge_count
  from merlin.merge_request
  where _plan_id = migrate_plan_to_model._plan_id
    and status in ('pending', 'in-progress');

  if _open_merge_count > 0 then
      raise exception 'Cannot migrate plan %: it has open merge requests.', _plan_id;
  end if;

  -- Create snapshot before migration
  perform merlin.create_snapshot(_plan_id, 'Migration to model '|| _new_model_id || ' on ' || NOW(), 'Automatic snapshot before attempting migration to model id ' || _new_model_id || ' on ' || NOW(), _requester_username);

  -- Perform model migration
  update merlin.plan
  set model_id = _new_model_id
  where id = _plan_id;

  return row('success')::hasura.migrate_plan_to_model_return_value;
end
$$;


create table hasura.check_model_compatability_return_value(result json);
create function hasura.check_model_compatability(_plan_id integer, _new_model_id integer, hasura_session json)
  returns hasura.check_model_compatability_return_value
  volatile
  language plpgsql as $$
declare
  _requester_username text;
  _function_permission permissions.permission;
  _old_model_id integer;
  _param_mismatch_count integer := 0;
  _missing_count integer := 0;

begin
  _requester_username := (hasura_session ->> 'x-hasura-user-id');
  _function_permission := permissions.get_function_permissions('migrate_plan_to_model', hasura_session);
  perform permissions.raise_if_plan_merge_permission('migrate_plan_to_model', _function_permission);

  -- Get the old model ID associated with the plan
  select model_id into _old_model_id from merlin.plan where id = _plan_id;

  -- Count missing activity types (activities that exist in the old model but not in the new model)
  select count(*)
  into _missing_count
  from merlin.activity_directive ad
         join merlin.activity_type old_at on old_at.model_id = _old_model_id and old_at.name = ad.name
         left join merlin.activity_type new_at on new_at.model_id = _new_model_id and new_at.name = ad.name
  where ad.plan_id = _plan_id
    and new_at.model_id is null;

  -- Count activity types with parameter mismatches
  select count(*)
  into _param_mismatch_count
  from merlin.activity_directive ad
         join merlin.activity_type old_at on old_at.model_id = _old_model_id and old_at.name = ad.name
         join merlin.activity_type new_at on new_at.model_id = _new_model_id and new_at.name = ad.name
  where ad.plan_id = _plan_id
    and old_at.parameters <> new_at.parameters;

  -- Return JSON object with counts
  return row(json_build_object(
      'missing_count', _missing_count,
      'param_mismatch_count', _param_mismatch_count
         ))::hasura.check_model_compatability_return_value;
end
$$;
