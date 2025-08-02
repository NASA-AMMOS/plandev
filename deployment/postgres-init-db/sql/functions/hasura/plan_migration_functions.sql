create table hasura.migrate_plan_to_model_return_value(result text);

create function hasura.migrate_plan_to_model(_plan_id integer, _new_model_id integer, hasura_session json)
  returns hasura.migrate_plan_to_model_return_value
  volatile
  language plpgsql as $$
declare
  _requester_username  text;
  _function_permission permissions.permission;
  _old_model_id        integer;
  _old_model_name      text;
  _new_model_name      text;
begin
  _requester_username := (hasura_session ->> 'x-hasura-user-id');
  _function_permission := permissions.get_function_permissions('migrate_plan_to_model', hasura_session);
  perform permissions.raise_if_plan_merge_permission('migrate_plan_to_model', _function_permission);
  if not _function_permission = 'NO_CHECK' then
    call permissions.check_general_permissions('migrate_plan_to_model', _function_permission, _plan_id,
                                               _requester_username);
  end if;

  if not exists(select id from merlin.plan where id = _plan_id) then
    raise exception 'Plan % does not exist, not proceeding with plan migration.', _plan_id;
  end if;

  if not exists(select id from merlin.mission_model where id = _new_model_id) then
    raise exception 'Model % does not exist, not proceeding with plan migration.', _new_model_id;
  end if;


  -- Check for open merge requests
  if exists(select
            from merlin.merge_request mr
            where mr.plan_id_receiving_changes = _plan_id
              and status in ('pending', 'in-progress')) then
    raise exception 'Cannot migrate plan %: it has open merge requests.', _plan_id;
  end if;

  -- Get the old model ID associated with the plan
  select model_id into _old_model_id from merlin.plan where id = _plan_id;

  -- Get model names
  select name into _old_model_name from merlin.mission_model where id = _old_model_id;
  select name into _new_model_name from merlin.mission_model where id = _new_model_id;

  -- Create snapshot before migration
  perform merlin.create_snapshot(_plan_id,
                                 'Migration from model ' || _old_model_name || ' (id ' ||
                                 _old_model_id || ') to model ' || _new_model_name || ' (id ' || _new_model_id ||
                                 ') on ' || NOW(),
                                 'Automatic snapshot before migrating from model ' || _old_model_name || ' (id ' ||
                                 _old_model_id || ') to model ' || _new_model_name || ' (id ' || _new_model_id ||
                                 ') on ' || NOW(),
                                 _requester_username);

  -- Perform model migration
  update merlin.plan
  set model_id = _new_model_id
  where id = _plan_id;

  -- invalidate activity validations to re-run validator
  update merlin.activity_directive_validations
  set status = 'pending'
  where plan_id = _plan_id;

  return row ('success')::hasura.migrate_plan_to_model_return_value;
end
$$;

comment on function hasura.migrate_plan_to_model(_plan_id integer, _new_model_id integer, hasura_session json) is e''
  'This function does the following:\n'
  '  * creates a snapshot of the specified plan.\n'
  '  * updates the specified plan to have model_id = _new_model_id.\n'
  '  * invalidates the activity validations, which will trigger the activity validator to run again\n'
  'It will not update the plan if:\n'
  '  * user has incorrect permissions (see default_user_roles for details)\n'
  '  * there are open merge requests for the given plan\n'
  '  * the given plan or model does not exist';


create table hasura.check_model_compatibility_return_value(result json);

create function hasura.check_model_compatibility(_old_model_id integer, _new_model_id integer)
  returns hasura.check_model_compatibility_return_value
  volatile
  language plpgsql as $$
declare
  _removed_activity_types json;
  _modified_activity_types json;

begin

  if not exists (select 1 from merlin.mission_model where id = _old_model_id)
    or not exists (select 1 from merlin.mission_model where id = _new_model_id) then
    raise exception 'One or both models (% and %) do not exist, not proceeding with plan compatibility check.', _old_model_id, _new_model_id;
  end if;

  _removed_activity_types := coalesce((select json_agg(name)
                                       from merlin.activity_type old_at
                                       where old_at.model_id = _old_model_id
                                         and not exists(select
                                                        from merlin.activity_type new_at
                                                        where new_at.name = old_at.name
                                                          and new_at.model_id = _new_model_id)), '[]'::json);

  _modified_activity_types := coalesce((select json_object_agg(types.n, types.t)
                                       from (select type.name as n,
                                                    json_build_object('old_parameter_schema', old_params,
                                                                      'new_parameter_schema', new_params) as t
                                             from (select new_type.name,
                                                          old_type.parameters as old_params,
                                                          new_type.parameters as new_params
                                                   from merlin.activity_type new_type
                                                          left join (select name, parameters
                                                                     from merlin.activity_type
                                                                     where model_id = _old_model_id) old_type
                                                                    using (name)
                                                   where new_type.model_id = _new_model_id
                                                     and old_type.parameters <> new_type.parameters) type) types),
                                      '{}'::json);

  return row (json_build_object(
      'removed_activity_types', _removed_activity_types,
      'modified_activity_types', _modified_activity_types
              ))::hasura.check_model_compatibility_return_value;
end
$$;

comment on function hasura.check_model_compatibility(_old_model_id integer, _new_model_id integer) is e''
  'This function checks whether two models are compatible. It returns a json object containing:\n'
  '  * removed_activity_types - activity types that are in the old model and not in the new model.\n'
  '  * modified_activity_types - activity types with differing parameter schemas, including the old and new schemas.';


create table hasura.check_model_compatibility_for_plan_return_value(result json);

create function hasura.check_model_compatibility_for_plan(_plan_id integer, _new_model_id integer)
  returns hasura.check_model_compatibility_for_plan_return_value
  volatile
  language plpgsql as $$
declare
  _removed json;
  _modified json;
  _old_model_id integer;
  _problematic json;
begin
  -- Get the old model from the plan
  select model_id into _old_model_id
  from merlin.plan
  where id = _plan_id;

  if _old_model_id is null then
    raise exception 'Plan ID % not found.', _plan_id;
  end if;

  -- Get compatibility check result
  select
    (result->'removed_activity_types')::json,
    (result->'modified_activity_types')::json
  into _removed, _modified
  from hasura.check_model_compatibility(_old_model_id, _new_model_id);

  -- Identify problematic activity_directives
  with
    removed_names as (
      select json_array_elements_text(_removed) as name
    ),
    altered_names as (
      select key as name
      from json_each(_modified)
    ),
    problematic as (
      select to_json(ad) as activity_directive, 'removed' as issue
      from merlin.activity_directive ad
             join removed_names r on r.name = ad.type
      where ad.plan_id = _plan_id

      union all

      select to_json(ad) as activity_directive, 'altered' as issue
      from merlin.activity_directive ad
             join altered_names a on a.name = ad.type
      where ad.plan_id = _plan_id
    )
  select json_agg(json_build_object(
      'activity_directive', activity_directive,
      'issue', issue
                  )) into _problematic
  from problematic;

  -- Build final result JSON. Note row(), if we don't include this hasura tries to cast raw json into the composite type
  -- and complains.
  return row(json_build_object(
      'removed_activity_types', _removed,
      'modified_activity_types', _modified,
      'impacted_directives', coalesce(_problematic, '[]'::json))
         )::hasura.check_model_compatibility_for_plan_return_value;
end
$$;

comment on function hasura.check_model_compatibility_for_plan(_plan_id integer, _new_model_id integer) is e''
  'Checks whether a plan is compatible with a given model. It returns a json object containing:\n'
  '  * removed_activity_types - activity types in the old model but not in the new model.\n'
  '  * modified_activity_types - activity types with differing parameter schemas, including the old and new schemas.\n'
  '  * impacted_directives - list of directives in the plan that fall into one of the two categories above.';
