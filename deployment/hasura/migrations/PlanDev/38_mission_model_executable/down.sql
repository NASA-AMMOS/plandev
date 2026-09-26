-- Restore Branching/Merging Functions
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
  start_time_receiving timestamptz;
  duration_receiving interval;
  start_time_supplying timestamptz;
  duration_supplying interval;
begin
  if plan_id_receiving = plan_id_supplying then
    raise exception 'Cannot create a merge request between a plan and itself.';
  end if;

  select id, model_id, start_time, duration
  from merlin.plan
  where plan.id = plan_id_receiving
  into validate_planIds, model_id_receiving, start_time_receiving, duration_receiving;
  if validate_planIds is null then
    raise exception 'Plan receiving changes (Plan %) does not exist.', plan_id_receiving;
  end if;

  select id, model_id, start_time, duration
  from merlin.plan
  where plan.id = plan_id_supplying
  into validate_planIds, model_id_supplying, start_time_supplying, duration_supplying;
  if validate_planIds is null then
    raise exception 'Plan supplying changes (Plan %) does not exist.', plan_id_supplying;
  end if;

  select merlin.create_snapshot(plan_id_supplying) into supplying_snapshot_id;

  select merlin.get_merge_base(plan_id_receiving, supplying_snapshot_id) into merge_base_snapshot_id;
  if merge_base_snapshot_id is null then
    raise exception 'Cannot create merge request between unrelated plans.';
  end if;

  if model_id_receiving is distinct from model_id_supplying then
    raise exception 'Cannot create merge request: plan supplying changes is using a different model (%) than the receiving plan (%)', model_id_supplying, model_id_receiving;
  end if;

  if (start_time_receiving is distinct from start_time_supplying) or
     (duration_receiving is distinct from duration_supplying) then
    raise exception 'Cannot create merge request between plans with different bounds.';
  end if;

  insert into merlin.merge_request(plan_id_receiving_changes, snapshot_id_supplying_changes, merge_base_snapshot_id, requester_username)
  values(plan_id_receiving, supplying_snapshot_id, merge_base_snapshot_id, request_username)
  returning id into merge_request_id;
  return merge_request_id;
end
$$;

create or replace function merlin.duplicate_plan(_plan_id integer, new_plan_name text, new_owner text)
  returns integer -- plan_id of the new plan
  security definer
  language plpgsql as $$
declare
  validate_plan_id integer;
  new_plan_id integer;
  created_snapshot_id integer;
begin
  select id from merlin.plan where plan.id = _plan_id into validate_plan_id;
  if(validate_plan_id is null) then
    raise exception 'Plan % does not exist.', _plan_id;
  end if;

  select merlin.create_snapshot(_plan_id) into created_snapshot_id;

  insert into merlin.plan(revision, name, model_id, duration, start_time, parent_id, owner, updated_by)
  select
    0, new_plan_name, model_id, duration, start_time, _plan_id, new_owner, new_owner
  from merlin.plan where id = _plan_id
  returning id into new_plan_id;
  insert into merlin.activity_directive(
    id, plan_id, name, source_scheduling_goal_id, source_scheduling_goal_invocation_id, created_at, created_by,
    last_modified_at, last_modified_by, start_offset, type, arguments,
    last_modified_arguments_at, metadata, anchor_id, anchored_to_start)
  select
    id, new_plan_id, name, source_scheduling_goal_id, source_scheduling_goal_invocation_id, created_at, created_by,
    last_modified_at, last_modified_by, start_offset, type, arguments,
    last_modified_arguments_at, metadata, anchor_id, anchored_to_start
  from merlin.activity_directive where activity_directive.plan_id = _plan_id;

  with source_plan as (
    select simulation_template_id, arguments, simulation_start_time, simulation_end_time
    from merlin.simulation
    where simulation.plan_id = _plan_id
  )
  update merlin.simulation s
  set simulation_template_id = source_plan.simulation_template_id,
      arguments = source_plan.arguments,
      simulation_start_time = source_plan.simulation_start_time,
      simulation_end_time = source_plan.simulation_end_time
  from source_plan
  where s.plan_id = new_plan_id;

  insert into merlin.preset_to_directive(preset_id, activity_id, plan_id)
  select preset_id, activity_id, new_plan_id
  from merlin.preset_to_directive ptd where ptd.plan_id = _plan_id;

  insert into tags.plan_tags(plan_id, tag_id)
  select new_plan_id, tag_id
  from tags.plan_tags pt where pt.plan_id = _plan_id;
  insert into tags.activity_directive_tags(plan_id, directive_id, tag_id)
  select new_plan_id, directive_id, tag_id
  from tags.activity_directive_tags adt where adt.plan_id = _plan_id;

  insert into merlin.plan_latest_snapshot(plan_id, snapshot_id) values(new_plan_id, created_snapshot_id);
  return new_plan_id;
end
$$;

-- Restore Model Migration Function
create or replace function hasura.migrate_plan_to_model(_plan_id integer, _new_model_id integer, hasura_session json)
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

-- Remove new locked triggers
drop trigger forbid_scheduling_goal_insert_plan_readonly on scheduler.scheduling_specification_goals;
drop trigger forbid_scheduling_condition_insert_plan_readonly on scheduler.scheduling_specification_conditions;
drop trigger check_locked_on_update_trigger on merlin.simulation;
drop trigger check_locked_on_delete_trigger on merlin.activity_directive;
drop trigger check_locked_on_update_insert_trigger on merlin.activity_directive;

-- Restore extra "plan_locked_exception" checks to activity directives
create function merlin.check_locked_on_delete()
  returns trigger
  security definer
  language plpgsql as $$
begin
  call merlin.plan_locked_exception(old.plan_id);
  return old;
end $$;

create trigger check_locked_on_delete_trigger
  before delete on merlin.activity_directive
  for each row
execute procedure merlin.check_locked_on_delete();

create or replace function merlin.check_activity_directive_metadata()
  returns trigger
  security definer
  language plpgsql as $$
declare
  _key text;
  _value jsonb;
  _schema jsonb;
  _type text;
  _subValue jsonb;
begin
  call merlin.plan_locked_exception(new.plan_id);
  for _key, _value in
    select * from jsonb_each(new.metadata::jsonb)
    loop
      select schema into _schema from merlin.activity_directive_metadata_schema where key = _key;
      _type := _schema->>'type';
      if _type = 'string' then
        if jsonb_typeof(_value) != 'string' then
          raise exception 'invalid metadata value for key %. Expected: string, Received: %', _key, _value;
        end if;
      elsif _type = 'long_string' then
        if jsonb_typeof(_value) != 'string' then
          raise exception 'invalid metadata value for key %. Expected: string, Received: %', _key, _value;
        end if;
      elsif _type = 'boolean' then
        if jsonb_typeof(_value) != 'boolean' then
          raise exception 'invalid metadata value for key %. Expected: boolean, Received: %', _key, _value;
        end if;
      elsif _type = 'number' then
        if jsonb_typeof(_value) != 'number' then
          raise exception 'invalid metadata value for key %. Expected: number, Received: %', _key, _value;
        end if;
      elsif _type = 'enum' then
        if (_value not in (select * from jsonb_array_elements(_schema->'enumerates'))) then
          raise exception 'invalid metadata value for key %. Expected: %, Received: %', _key, _schema->>'enumerates', _value;
        end if;
      elsif _type = 'enum_multiselect' then
        if jsonb_typeof(_value) != 'array' then
          raise exception 'invalid metadata value for key %. Expected an array of enumerates: %, Received: %', _key, _schema->>'enumerates', _value;
        end if;
        for _subValue in select * from jsonb_array_elements(_value)
          loop
            if (_subValue not in (select * from jsonb_array_elements(_schema->'enumerates'))) then
              raise exception 'invalid metadata value for key %. Expected one of the valid enumerates: %, Received: %', _key, _schema->>'enumerates', _value;
            end if;
          end loop;
      end if;
    end loop;
  return new;
end$$;

create or replace function merlin.activity_directive_set_arguments_updated_at()
  returns trigger
  security definer
  language plpgsql as
$$ begin
  call merlin.plan_locked_exception(new.plan_id);
  new.last_modified_arguments_at = now();

  -- request new validation
  update merlin.activity_directive_validations
  set last_modified_arguments_at = new.last_modified_arguments_at,
      status = 'pending'
  where (directive_id, plan_id) = (new.id, new.plan_id);

  return new;
end $$;

create or replace function merlin.generate_activity_directive_name()
  returns trigger
  security invoker
  language plpgsql as $$begin
  call merlin.plan_locked_exception(new.plan_id);
  if new.name is null then
    new.name = new.type || ' ' || new.id;
  end if;
  return new;
end$$;

-- Remove trigger to cleanup Model when Readonly Plan is deleted
drop trigger cleanup_after_delete_trigger on merlin.plan;
drop function merlin.cascade_delete_readonly_model();

-- Update existing triggers on Plan
alter trigger cleanup_before_delete_trigger on merlin.plan
  rename to cleanup_on_delete_trigger;

create or replace function merlin.cascade_plan_bounds_update()
  returns trigger
  language plpgsql as $$
declare
  old_plan_end timestamptz;
  new_plan_end timestamptz;
  sim_start_horizon timestamptz;
  sim_end_horizon timestamptz;
  start_time_difference interval;
  end_time_difference interval;
begin
  -- Catch Plan_Locked
  call merlin.plan_locked_exception(old.id);

  -- Set variables
  old_plan_end := old.start_time + old.duration;
  new_plan_end := new.start_time + new.duration;
  start_time_difference := old.start_time - new.start_time;
  end_time_difference := old_plan_end - new_plan_end;

  -- Update activities that are anchored to the plan bounds
  update merlin.activity_directive ad
  set start_offset = start_offset + start_time_difference
  where anchor_id is null
    and anchored_to_start -- anchored to plan start
    and ad.plan_id = old.id;

  update merlin.activity_directive ad
  set start_offset = start_offset + end_time_difference
  where anchor_id is null
    and not anchored_to_start -- anchored to plan end
    and ad.plan_id = old.id;

  -- Update associated dataset offsets (simulation and plan)
  update merlin.simulation_dataset
  set offset_from_plan_start = offset_from_plan_start + start_time_difference
  from merlin.simulation sim_spec
  where simulation_id = sim_spec.id
    and sim_spec.plan_id = old.id;

  update merlin.plan_dataset
  set offset_from_plan_start = offset_from_plan_start + start_time_difference
  where plan_id = old.id;

  -- Update sim spec bounds...
  select simulation_start_time, simulation_end_time
  from merlin.simulation s
  where s.plan_id = old.id
  into sim_start_horizon, sim_end_horizon;

  if (sim_start_horizon is not null and sim_end_horizon is not null) then
    -- ... if its bounds = the plan bounds
    if (sim_start_horizon is not distinct from old.start_time) and
       (sim_end_horizon is not distinct from old_plan_end) then
      update merlin.simulation
      set simulation_start_time = new.start_time,
          simulation_end_time = new_plan_end
      where plan_id = new.id;
    else
      -- if the sim horizon is outside the new plan bounds, adjust it to the new plan start
      if (sim_start_horizon < new.start_time or sim_start_horizon >= new_plan_end) then
        -- BUT, if that would put the new sim start after the current sim end, snap both bounds at once
        if(sim_end_horizon < new.start_time) then
          update merlin.simulation
          set simulation_start_time = new.start_time,
              simulation_end_time = new_plan_end
          where plan_id = new.id;
        else
          update merlin.simulation
          set simulation_start_time = new.start_time
          where plan_id = new.id;
        end if;
      end if;
      -- and if the sim end horizon is outside the new plan bounds, adjust it to the new plan end
      if (sim_end_horizon <= new.start_time or sim_end_horizon > new_plan_end) then
        -- BUT, if that would put the new sim end before the current sim start, snap both bounds at once
        if(sim_start_horizon > new_plan_end) then
          update merlin.simulation
          set simulation_start_time = new.start_time,
              simulation_end_time = new_plan_end
          where plan_id = new.id;
        else
          update merlin.simulation
          set simulation_end_time = new_plan_end
          where plan_id = new.id;
        end if;
      end if;
    end if;
  end if;

  return new;
end;
$$;

create or replace function merlin.take_snapshot_before_plan_bounds_update()
  returns trigger
  language plpgsql as $$
declare
  old_plan_end timestamptz;
  new_plan_end timestamptz;
begin
  -- Catch Plan_Locked
  call merlin.plan_locked_exception(old.id);

  -- Set variables
  old_plan_end := old.start_time + old.duration;
  new_plan_end := new.start_time + new.duration;

  -- Take a backup snapshot
  perform merlin.create_snapshot(
      old.id,
      'Plan Bound Adjustment',
      'Automatic snapshot made before adjusting plan bounds from ' ||
      '['|| old.start_time ||' - '|| old_plan_end || '] to ' ||
      '[' || new.start_time || ' - ' || new_plan_end || ']',
      null);
  return new;
end;
$$;

-- Remove utility check functions
drop function scheduler.check_plan_readonly_insert_update();
drop function util_functions.check_plan_locked_readonly_delete();
drop function util_functions.check_plan_locked_readonly_insert_update();

-- Drop readonly check function
drop procedure merlin.plan_readonly_exception(integer);

-- Drop added column on Plan
alter table merlin.plan
  drop column is_read_only;

comment on column merlin.plan.is_locked is e''
  'A boolean representing whether this plan can be deleted and if changes can happen to the activities of this plan.';

-- Update functions that refer to "definition_file_id"
create or replace function merlin.increment_revision_mission_model_jar_update()
  returns trigger
  security definer
  language plpgsql as $$begin
  update merlin.mission_model
  set revision = revision + 1
  where jar_id = new.id
     or jar_id = old.id;

  return new;
end$$;

-- Drop schema updates to Mission Model
alter table merlin.mission_model
  rename constraint mission_model_references_file to mission_model_references_jar;
alter table merlin.mission_model
  drop column is_executable;
alter table merlin.mission_model
  rename column definition_file_id to jar_id;

comment on column merlin.mission_model.jar_id is e''
    'An uploaded JAR file defining the mission model.';

call migrations.mark_migration_rolled_back('38');
