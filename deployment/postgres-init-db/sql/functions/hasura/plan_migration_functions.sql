create table hasura.migrate_plan_to_model_return_value(result text);
create function hasura.migrate_plan_to_model(plan_id integer, new_model_id integer, hasura_session json)
  returns hasura.migrate_plan_to_model_return_value
  volatile
  language plpgsql as $$
declare
  requester_username text;
  requester_id uuid;
  _function_permission permissions.permission;
  open_merge_count integer;
begin
  requester_username := (hasura_session ->> 'x-hasura-user-id');
  requester_id := requester_username::uuid;
  _function_permission := permissions.get_function_permissions('migrate_plan_to_model', hasura_session);

call permissions.check_plan_permissions('migrate_plan_to_model', _function_permission, plan_id, requester_username);

-- Check for open merge requests
select count(*) into open_merge_count
from merlin.merge_request
where plan_id = migrate_plan_to_model.plan_id
  and status in ('pending', 'in-progress');

if open_merge_count > 0 then
    raise exception 'Cannot migrate plan %: it has open merge requests.', plan_id;
end if;

-- Create snapshot before migration
perform merlin.create_snapshot(plan_id, requester_id, 'Before migrating model');

-- Perform model migration
update merlin.plan
set model_id = new_model_id
where id = plan_id;

return row('success')::hasura.migrate_plan_to_model_return_value;
end;
$$;


create table hasura.rename_activity_types_on_plan_return_value(result text);
create function hasura.rename_activity_types_on_plan(
  plan_id integer,
  renames jsonb,
  hasura_session json
)
  returns hasura.rename_activity_types_on_plan_return_value
  volatile
  language plpgsql as $$
declare
  requester_username text;
  _function_permission permissions.permission;
  old_type text;
  new_type text;
begin
  requester_username := (hasura_session ->> 'x-hasura-user-id');
  _function_permission := permissions.get_function_permissions('rename_activity_types_on_plan', hasura_session);

call permissions.check_plan_permissions('rename_activity_types_on_plan', _function_permission, plan_id, requester_username);

for old_type, new_type in select * from jsonb_each_text(renames) loop
update merlin.activity_directive
set type = new_type
where plan_id = rename_activity_types_on_plan.plan_id
  and type = old_type;
end loop;

return row('success')::hasura.rename_activity_types_on_plan_return_value;
end;
$$;
