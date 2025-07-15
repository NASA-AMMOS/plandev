-- Modify plan snapshot columns and functions
alter table merlin.plan_snapshot
add column model_id integer references merlin.mission_model on delete set null default null;

comment on column merlin.plan_snapshot.model_id is e''
  'The model that this plan was using at the time the snapshot was taken.';

-- Backfill the model_id column for existing snapshots with the current model_id for the snapshot's plan
update merlin.plan_snapshot snap
set model_id = plan.model_id
from merlin.plan
where plan.id = snap.plan_id;


-- Alter snapshot functions
create or replace function merlin.create_snapshot(_plan_id integer, _snapshot_name text, _description text, _user text)
  returns integer -- snapshot id inserted into the table
  language plpgsql as $$
  declare
    validate_plan_id integer;
    inserted_snapshot_id integer;
begin
  select id from merlin.plan where plan.id = _plan_id into validate_plan_id;
  if validate_plan_id is null then
    raise exception 'Plan % does not exist.', _plan_id;
  end if;

  insert into merlin.plan_snapshot(plan_id, model_id, revision, snapshot_name, description, taken_by)
    select id, model_id, revision, _snapshot_name, _description, _user
    from merlin.plan where id = _plan_id
    returning snapshot_id into inserted_snapshot_id;
  insert into merlin.plan_snapshot_activities(
      snapshot_id, id, name, source_scheduling_goal_id, source_scheduling_goal_invocation_id, created_at, created_by,
      last_modified_at, last_modified_by, start_offset, type,
      arguments, last_modified_arguments_at, metadata, anchor_id, anchored_to_start)
    select
      inserted_snapshot_id,                              -- this is the snapshot id
      id, name, source_scheduling_goal_id, source_scheduling_goal_invocation_id, created_at, created_by, -- these are the rest of the data for an activity row
      last_modified_at, last_modified_by, start_offset, type,
      arguments, last_modified_arguments_at, metadata, anchor_id, anchored_to_start
    from merlin.activity_directive where activity_directive.plan_id = _plan_id;
  insert into merlin.preset_to_snapshot_directive(preset_id, activity_id, snapshot_id)
    select ptd.preset_id, ptd.activity_id, inserted_snapshot_id
    from merlin.preset_to_directive ptd
    where ptd.plan_id = _plan_id;
  insert into tags.snapshot_activity_tags(snapshot_id, directive_id, tag_id)
    select inserted_snapshot_id, directive_id, tag_id
    from tags.activity_directive_tags adt
    where adt.plan_id = _plan_id;

  --all snapshots in plan_latest_snapshot for plan plan_id become the parent of the current snapshot
  insert into merlin.plan_snapshot_parent(snapshot_id, parent_snapshot_id)
    select inserted_snapshot_id, snapshot_id
    from merlin.plan_latest_snapshot where plan_latest_snapshot.plan_id = _plan_id;

  --remove all of those entries from plan_latest_snapshot and add this new snapshot.
  delete from merlin.plan_latest_snapshot where plan_latest_snapshot.plan_id = _plan_id;
  insert into merlin.plan_latest_snapshot(plan_id, snapshot_id) values (_plan_id, inserted_snapshot_id);

  return inserted_snapshot_id;
  end;
$$;

comment on function merlin.create_snapshot(integer) is e''
	'See comment on create_snapshot(integer, text, text, text)';

comment on function merlin.create_snapshot(integer, text, text, text) is e''
  'Create a snapshot of the specified plan. A snapshot consists of:'
  '  - The plan''s id, model id, and revision'
  '  - All the activities in the plan'
  '  - The preset status of those activities'
  '  - The tags on those activities'
	'  - When the snapshot was taken'
	'  - Optionally: who took the snapshot, a name for the snapshot, a description of the snapshot';


create or replace procedure merlin.restore_from_snapshot(_plan_id integer, _snapshot_id integer)
	language plpgsql as $$
	declare
		_snapshot_name text;
		_plan_name text;
		_model_id integer;
	begin
		-- Input Validation
		select name from merlin.plan where id = _plan_id into _plan_name;
		if _plan_name is null then
			raise exception 'Cannot Restore: Plan with ID % does not exist.', _plan_id;
		end if;
		if not exists(select snapshot_id from merlin.plan_snapshot where snapshot_id = _snapshot_id) then
			raise exception 'Cannot Restore: Snapshot with ID % does not exist.', _snapshot_id;
		end if;
		if not exists(select snapshot_id from merlin.plan_snapshot where _snapshot_id = snapshot_id and _plan_id = plan_id ) then
			select snapshot_name from merlin.plan_snapshot where snapshot_id = _snapshot_id into _snapshot_name;
			if _snapshot_name is not null then
        raise exception 'Cannot Restore: Snapshot ''%'' (ID %) is not a snapshot of Plan ''%'' (ID %)',
          _snapshot_name, _snapshot_id, _plan_name, _plan_id;
      else
				raise exception 'Cannot Restore: Snapshot % is not a snapshot of Plan ''%'' (ID %)',
          _snapshot_id, _plan_name, _plan_id;
      end if;
    end if;
    select model_id from merlin.plan_snapshot where snapshot_id = _snapshot_id into _model_id;
    if not exists(select from merlin.mission_model m where m.id = _model_id) then
      raise exception 'Cannot Restore: Model with ID % does not exist.', _model_id;
    end if;

		-- Catch Plan_Locked
		call merlin.plan_locked_exception(_plan_id);

    -- Update model_id of the plan
    update merlin.plan
    set model_id = _model_id
    where id = _plan_id;

    -- Record the Union of Activities in Plan and Snapshot
    -- and note which ones have been added since the Snapshot was taken (in_snapshot = false)
    create temp table diff(
			activity_id integer,
			in_snapshot boolean not null
		);
		insert into diff(activity_id, in_snapshot)
		select id as activity_id, true
		from merlin.plan_snapshot_activities where snapshot_id = _snapshot_id;

		insert into diff (activity_id, in_snapshot)
		select activity_id, false
		from(
				select id as activity_id
				from merlin.activity_directive
				where plan_id = _plan_id
			except
				select activity_id
				from diff) a;

		-- Remove any added activities
  delete from merlin.activity_directive ad
		using diff d
		where (ad.id, ad.plan_id) = (d.activity_id, _plan_id)
			and d.in_snapshot is false;

		-- Upsert the rest
		insert into merlin.activity_directive (
		      id, plan_id, name, source_scheduling_goal_id, source_scheduling_goal_invocation_id,
		      created_at, created_by, last_modified_at, last_modified_by,
		      start_offset, type, arguments, last_modified_arguments_at, metadata,
		      anchor_id, anchored_to_start)
		select psa.id, _plan_id, psa.name, psa.source_scheduling_goal_id, psa.source_scheduling_goal_invocation_id,
		       psa.created_at, psa.created_by, psa.last_modified_at, psa.last_modified_by,
		       psa.start_offset, psa.type, psa.arguments, psa.last_modified_arguments_at, psa.metadata,
		       psa.anchor_id, psa.anchored_to_start
		from merlin.plan_snapshot_activities psa
		where psa.snapshot_id = _snapshot_id
		on conflict (id, plan_id) do update
		-- 'last_modified_at' and 'last_modified_arguments_at' are skipped during update, as triggers will overwrite them to now()
		set name = excluded.name,
		    source_scheduling_goal_id = excluded.source_scheduling_goal_id,
		    source_scheduling_goal_invocation_id = excluded.source_scheduling_goal_invocation_id,
		    created_at = excluded.created_at,
		    created_by = excluded.created_by,
		    last_modified_by = excluded.last_modified_by,
		    start_offset = excluded.start_offset,
		    type = excluded.type,
		    arguments = excluded.arguments,
		    metadata = excluded.metadata,
		    anchor_id = excluded.anchor_id,
		    anchored_to_start = excluded.anchored_to_start;

		-- Tags
		delete from tags.activity_directive_tags adt
		using diff d
		where (adt.directive_id, adt.plan_id) = (d.activity_id, _plan_id);

		insert into tags.activity_directive_tags(directive_id, plan_id, tag_id)
		select sat.directive_id, _plan_id, sat.tag_id
		from tags.snapshot_activity_tags sat
		where sat.snapshot_id = _snapshot_id
		on conflict (directive_id, plan_id, tag_id) do nothing;

		-- Presets
		delete from merlin.preset_to_directive
		  where plan_id = _plan_id;
		insert into merlin.preset_to_directive(preset_id, activity_id, plan_id)
			select pts.preset_id, pts.activity_id, _plan_id
			from merlin.preset_to_snapshot_directive pts
			where pts.snapshot_id = _snapshot_id
			on conflict (activity_id, plan_id)
			do update	set preset_id = excluded.preset_id;

		-- Clean up
		drop table diff;
  end
$$;

comment on procedure merlin.restore_from_snapshot(_plan_id integer, _snapshot_id integer) is e''
	'Restore a plan to its state described in the given snapshot.';

-- Update create_merge_request function to check model IDs
create or replace function merlin.create_merge_request(plan_id_supplying integer, plan_id_receiving integer, request_username text)
  returns integer
  language plpgsql as $$
declare
  merge_base_snapshot_id integer;
  validate_planIds integer;
  supplying_snapshot_id integer;
  merge_request_id integer;
  model_id_receiving integer;
  model_id_supplying integer;
begin
  if plan_id_receiving = plan_id_supplying then
    raise exception 'Cannot create a merge request between a plan and itself.';
  end if;
  select id from merlin.plan where plan.id = plan_id_receiving into validate_planIds;
  if validate_planIds is null then
    raise exception 'Plan receiving changes (Plan %) does not exist.', plan_id_receiving;
  end if;
  select id from merlin.plan where plan.id = plan_id_supplying into validate_planIds;
  if validate_planIds is null then
    raise exception 'Plan supplying changes (Plan %) does not exist.', plan_id_supplying;
  end if;

  select merlin.create_snapshot(plan_id_supplying) into supplying_snapshot_id;

  select merlin.get_merge_base(plan_id_receiving, supplying_snapshot_id) into merge_base_snapshot_id;
  if merge_base_snapshot_id is null then
    raise exception 'Cannot create merge request between unrelated plans.';
  end if;

  select model_id from merlin.plan where plan.id = plan_id_receiving into model_id_receiving;
  select model_id from merlin.plan where plan.id = plan_id_supplying into model_id_supplying;
  if model_id_receiving is distinct from model_id_supplying then
    raise exception 'Cannot create merge request: plan supplying changes is using a different model (%) than the receiving plan (%)', model_id_supplying, model_id_receiving;
  end if;

  insert into merlin.merge_request(plan_id_receiving_changes, snapshot_id_supplying_changes, merge_base_snapshot_id, requester_username)
    values(plan_id_receiving, supplying_snapshot_id, merge_base_snapshot_id, request_username)
    returning id into merge_request_id;
  return merge_request_id;
end
$$;

-- Alter set_revisions_and_initialize_dataset_on_insert

create or replace function merlin.set_revisions_and_initialize_dataset_on_insert()
returns trigger
security definer
language plpgsql as $$
declare
  simulation_ref merlin.simulation;
  plan_ref merlin.plan;
  model_ref merlin.mission_model;
  template_ref merlin.simulation_template;
  dataset_ref merlin.dataset;
begin
  -- Set the revisions
  select into simulation_ref * from merlin.simulation where id = new.simulation_id;
  select into plan_ref * from merlin.plan where id = simulation_ref.plan_id;
  select into template_ref * from merlin.simulation_template where id = simulation_ref.simulation_template_id;
  select into model_ref * from merlin.mission_model where id = plan_ref.model_id;
  new.model_revision = model_ref.revision;
  new.plan_revision = plan_ref.revision;
  new.simulation_template_revision = template_ref.revision;
  new.simulation_revision = simulation_ref.revision;
  new.model_id = plan_ref.model_id;

  -- Create the dataset
  insert into merlin.dataset
  default values
  returning * into dataset_ref;
  new.dataset_id = dataset_ref.id;
  new.dataset_revision = dataset_ref.revision;
return new;
end$$;

-- Modify simulation_dataset to include model_id
alter table merlin.simulation_dataset
  add column model_id integer null,
  add constraint simulation_dataset_references_model
    foreign key (model_id)
      references merlin.mission_model
      on update cascade
      on delete set null;

comment on column merlin.simulation_dataset.model_id is e''
  'The model id used for this simulation.';

-- Backfill the model_id column for existing simulation_datasets with the current model_id for the snapshot's plan
update merlin.simulation_dataset
set model_id = (
  select merlin.plan.model_id
  from merlin.simulation
         join merlin.plan on simulation.plan_id = plan.id
  where simulation.id = simulation_dataset.simulation_id
);

-- Add plan migration functions
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

-- #### PERMISSIONS ####

-- add new function_permission key for migrate_plan_to_model
alter type permissions.function_permission_key add value 'migrate_plan_to_model' after 'get_plan_history';

-- temporarily disable permissions validation trigger to avoid uncommitted enum error during migration
alter table permissions.user_role_permission disable trigger validate_permissions_trigger;
-- Update user permissions for model migration (see default_user_roles)
update permissions.user_role_permission
set function_permissions = function_permissions
  || jsonb_build_object('migrate_plan_to_model', 'PLAN_OWNER_COLLABORATOR')
where role = 'user';
-- re-enable permissions validation
alter table permissions.user_role_permission enable trigger validate_permissions_trigger;

-- #### END PERMISSIONS ####

call migrations.mark_migration_applied('23');
