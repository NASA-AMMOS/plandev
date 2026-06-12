-- Drop snapshot name uniqueness constraint
alter table merlin.plan_snapshot
  drop constraint snapshot_name_unique_per_plan;

-- Update Plan Snapshot to include plan bounds
alter table merlin.plan_snapshot
add column plan_start_time timestamptz,
add column plan_duration interval;

comment on column merlin.plan_snapshot.plan_start_time is e''
  'The start time of the plan at the time the snapshot was taken.';
comment on column merlin.plan_snapshot.plan_duration is e''
  'The duration of the plan at the time the snapshot was taken.';

-- Data Migration: fill in default info for these columns
update merlin.plan_snapshot
set plan_start_time = p.start_time,
    plan_duration = p.duration
from merlin.plan p
where p.id = plan_id;

/*
  Snapshots are kept around after plan deletion for use in finding the merge base
  Since plan bounds can't currently change between a parent and a branch, we can use the
   "plan_snapshot_parent" and "plan_latest_snapshot" tables to find what the bounds were
 */
-- Create a temporary function to walk down the snapshot history tree
create function merlin.get_snapshot_children(starting_snapshot_id integer)
  returns SETOF integer
  language plpgsql as $$
begin
  return query with recursive children(id) as (
    values(starting_snapshot_id) --base case
    union
    select psp.snapshot_id
    from merlin.plan_snapshot_parent psp
    join children on id = psp.parent_snapshot_id --recursive case
  ) select * from children;
end
$$;

-- "latest_snapshot_associations" gets the latest snapshot on every plan, as well as that plan's boundaries
with latest_snapshot_associations (snapshot_id, new_start_time, new_duration) as (
  select distinct on (snapshot_id)
    snapshot_id, start_time, duration
  from merlin.plan_latest_snapshot pls
         join merlin.plan p on pls.plan_id = p.id
),
-- "ascendants" walks up the snapshot history chain and finds all older related snapshots
  ascendants (snapshot_id, new_start_time, new_duration) as (
    select distinct on (snapshot_id)
      merlin.get_snapshot_history(lsa.snapshot_id) as snapshot_id,
      lsa.new_start_time,
      lsa.new_duration
    from latest_snapshot_associations lsa
  ),
-- "descendants" walks down the snapshot history chain and finds all newer related snapshots
  descendants (snapshot_id, new_start_time, new_duration) as (
    select distinct on (snapshot_id)
      merlin.get_snapshot_children(a.snapshot_id) as snapshot_id,
      a.new_start_time,
      a.new_duration
    from ascendants a
)
update merlin.plan_snapshot ps
set plan_start_time = d.new_start_time,
    plan_duration = d.new_duration
from descendants d  -- descendants contains the snapshot information of all snapshots that are somehow related to a plan
where ps.snapshot_id = d.snapshot_id
  and ps.plan_id is null;

/*
 If a snapshot still has NULL values for "plan_id", "plan_start_time" and "plan_duration", then that means:
 1. It is not the latest snapshot on any plan
 2. It is not related to the latest snapshot on any plan

 This means that these snapshots aren't related to any plan in the database.
 That means that they will never be used as a merge base.
 As such they can be deleted
 ... but for now, they will have "default" boundaries set
 */
update merlin.plan_snapshot
set plan_start_time = '1950-01-01 T00:00:00Z',
    plan_duration = '00:00:00'
where plan_id is null and plan_start_time is null and plan_duration is null;

-- drop temporary function
drop function merlin.get_snapshot_children(starting_snapshot_id integer);

-- Add not null argument to new columns
alter table merlin.plan_snapshot
alter column plan_start_time set not null,
alter column plan_duration set not null;

-- Update Create and Restore snapshot functions
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

  insert into merlin.plan_snapshot(plan_id, model_id, revision, plan_start_time, plan_duration,
                                   snapshot_name, description, taken_by)
    select id, model_id, revision, start_time, duration,
           _snapshot_name, _description, _user
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

create or replace procedure merlin.restore_from_snapshot(_plan_id integer, _snapshot_id integer)
	language plpgsql as $$
	declare
		_snapshot_name text;
		_plan_name text;
		_model_id integer;
    _plan_start_time timestamptz;
    _plan_duration interval;
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

    select model_id, plan_start_time, plan_duration
    from merlin.plan_snapshot
    where snapshot_id = _snapshot_id
    into _model_id, _plan_start_time, _plan_duration;
    if not exists(select from merlin.mission_model m where m.id = _model_id) then
      raise exception 'Cannot Restore: Model with ID % does not exist.', _model_id;
    end if;

		-- Catch Plan_Locked
		call merlin.plan_locked_exception(_plan_id);

    -- Update model_id and bounds of the plan
    update merlin.plan
    set model_id = _model_id,
        start_time = _plan_start_time,
        duration = _plan_duration
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

-- Create trigger to create snapshot/cascade plan bounds changes
create function merlin.cascade_plan_bounds_update()
  returns trigger
  language plpgsql as $$
begin
  -- prevent adjustment if the plan is locked
  if old.is_locked then
    raise exception 'Cannot adjust bounds of locked plan.';
  end if;

  -- Take a backup snapshot
  perform merlin.create_snapshot(
      old.id,
      'Plan Bound Adjustment',
      'Automatic snapshot made before adjusting plan bounds from ' ||
      '['|| old.start_time ||' - '|| old.start_time + old.duration || '] to ' ||
      '[' || new.start_time || ' - ' || new.start_time + new.duration || ']',
      null);

  -- Update activities that are anchored to the plan bounds
  update merlin.activity_directive ad
  set start_offset = start_offset + (new.start_time - old.start_time)
  where anchor_id is null
    and anchored_to_start -- anchored to plan start
    and ad.plan_id = old.id;

  update merlin.activity_directive ad
  set start_offset = start_offset + (new.duration - old.duration)
  where anchor_id is null
    and not anchored_to_start -- anchored to plan end
    and ad.plan_id = old.id;

  -- Update associated dataset offsets (simulation and plan)
  update merlin.simulation_dataset
  set offset_from_plan_start = offset_from_plan_start + (new.start_time - old.start_time)
  from merlin.simulation sim_spec
  where simulation_id = sim_spec.id
    and sim_spec.plan_id = old.id;

  update merlin.plan_dataset
  set offset_from_plan_start = offset_from_plan_start + (new.start_time - old.start_time)
  where plan_id = old.id;

  return new;
end;
$$;

create trigger cascade_plan_bounds_on_update
  before update on merlin.plan
  for each row
  when (old.start_time is distinct from new.start_time or old.duration is distinct from new.duration)
execute function merlin.cascade_plan_bounds_update();

-- Prevent "Update Plan Revision on Directive Change" from firing during other triggers
create or replace trigger increment_plan_revision_on_directive_update_trigger
  after update on merlin.activity_directive
  for each row
  when (pg_trigger_depth() < 1)
execute function merlin.increment_plan_revision_on_directive_update();

-- Update Plan Merge Functions to block merging plans with different bounds
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

create or replace procedure merlin.begin_merge(_merge_request_id integer, review_username text)
  language plpgsql as $$
  declare
    validate_id integer;
    validate_status merlin.merge_request_status;
    validate_non_no_op_status merlin.activity_change_type;
    snapshot_id_supplying integer;
    plan_id_receiving integer;
    merge_base_id integer;
    start_time_receiving timestamptz;
    duration_receiving interval;
    start_time_supplying timestamptz;
    duration_supplying interval;
begin
  -- validate id and status
  select id, status
    from merlin.merge_request
    where _merge_request_id = id
    into validate_id, validate_status;

  if validate_id is null then
    raise exception 'Request ID % is not present in merge_request table.', _merge_request_id;
  end if;

  if validate_status != 'pending' then
    raise exception 'Cannot begin request. Merge request % is not in pending state.', _merge_request_id;
  end if;

  -- select from merge-request the snapshot_sc (s_sc) and plan_rc (p_rc) ids
  select plan_id_receiving_changes, snapshot_id_supplying_changes
    from merlin.merge_request
    where id = _merge_request_id
    into plan_id_receiving, snapshot_id_supplying;

  -- ensure that the plans cover the same boundaries
  select start_time, duration
  from merlin.plan
  where plan.id = plan_id_receiving
  into start_time_receiving, duration_receiving;

  select plan_start_time, plan_duration
  from merlin.plan_snapshot ps
  where ps.snapshot_id = snapshot_id_supplying
  into start_time_supplying, duration_supplying;

  if start_time_receiving is distinct from start_time_supplying or
     duration_receiving is distinct from duration_supplying then
    raise exception 'Cannot begin merge request between plans with different bounds.';
  end if;

  -- ensure the plan receiving changes isn't locked
  if (select is_locked from merlin.plan where plan.id=plan_id_receiving) then
    raise exception 'Cannot begin merge request. Plan to receive changes is locked.';
  end if;

  -- lock plan_rc
  update merlin.plan
    set is_locked = true
    where plan.id = plan_id_receiving;

  -- get merge base (mb)
  select merlin.get_merge_base(plan_id_receiving, snapshot_id_supplying)
    into merge_base_id;

  -- update the status to "in progress"
  update merlin.merge_request
    set status = 'in-progress',
    merge_base_snapshot_id = merge_base_id,
    reviewer_username = review_username
    where id = _merge_request_id;

  -- perform diff between mb and s_sc (s_diff)
    -- delete is B minus A on key
    -- add is A minus B on key
    -- A intersect B is no op
    -- A minus B on everything except everything currently in the table is modify
  create temp table supplying_diff(
    activity_id integer,
    change_type merlin.activity_change_type not null
  );

  insert into supplying_diff (activity_id, change_type)
  select activity_id, 'delete'
  from(
    select id as activity_id
    from merlin.plan_snapshot_activities
      where snapshot_id = merge_base_id
    except
    select id as activity_id
    from merlin.plan_snapshot_activities
      where snapshot_id = snapshot_id_supplying) a;

  insert into supplying_diff (activity_id, change_type)
  select activity_id, 'add'
  from(
    select id as activity_id
    from merlin.plan_snapshot_activities
      where snapshot_id = snapshot_id_supplying
    except
    select id as activity_id
    from merlin.plan_snapshot_activities
      where snapshot_id = merge_base_id) a;

  insert into supplying_diff (activity_id, change_type)
    select activity_id, 'none'
      from(
        select psa.id as activity_id, name, tags.tag_ids_activity_snapshot(psa.id, merge_base_id),
               source_scheduling_goal_id, source_scheduling_goal_invocation_id, created_at, start_offset, type, arguments,
               metadata, anchor_id, anchored_to_start
        from merlin.plan_snapshot_activities psa
        where psa.snapshot_id = merge_base_id
    intersect
      select id as activity_id, name, tags.tag_ids_activity_snapshot(psa.id, snapshot_id_supplying),
             source_scheduling_goal_id, source_scheduling_goal_invocation_id, created_at, start_offset, type, arguments,
             metadata, anchor_id, anchored_to_start
        from merlin.plan_snapshot_activities psa
        where psa.snapshot_id = snapshot_id_supplying) a;

  insert into supplying_diff (activity_id, change_type)
    select activity_id, 'modify'
    from(
      select id as activity_id from merlin.plan_snapshot_activities
        where snapshot_id = merge_base_id or snapshot_id = snapshot_id_supplying
      except
      select activity_id from supplying_diff) a;

  -- perform diff between mb and p_rc (r_diff)
  create temp table receiving_diff(
     activity_id integer,
     change_type merlin.activity_change_type not null
  );

  insert into receiving_diff (activity_id, change_type)
  select activity_id, 'delete'
  from(
        select id as activity_id
        from merlin.plan_snapshot_activities
        where snapshot_id = merge_base_id
        except
        select id as activity_id
        from merlin.activity_directive
        where plan_id = plan_id_receiving) a;

  insert into receiving_diff (activity_id, change_type)
  select activity_id, 'add'
  from(
        select id as activity_id
        from merlin.activity_directive
        where plan_id = plan_id_receiving
        except
        select id as activity_id
        from merlin.plan_snapshot_activities
        where snapshot_id = merge_base_id) a;

  insert into receiving_diff (activity_id, change_type)
  select activity_id, 'none'
  from(
        select id as activity_id, name, tags.tag_ids_activity_snapshot(id, merge_base_id),
               source_scheduling_goal_id, source_scheduling_goal_invocation_id, created_at, start_offset, type, arguments,
               metadata, anchor_id, anchored_to_start
        from merlin.plan_snapshot_activities psa
        where psa.snapshot_id = merge_base_id
        intersect
        select id as activity_id, name, tags.tag_ids_activity_directive(id, plan_id_receiving),
               source_scheduling_goal_id, source_scheduling_goal_invocation_id, created_at, start_offset, type, arguments,
               metadata, anchor_id, anchored_to_start
        from merlin.activity_directive ad
        where ad.plan_id = plan_id_receiving) a;

  insert into receiving_diff (activity_id, change_type)
  select activity_id, 'modify'
  from (
        (select id as activity_id
         from merlin.plan_snapshot_activities
         where snapshot_id = merge_base_id
         union
         select id as activity_id
         from merlin.activity_directive
         where plan_id = plan_id_receiving)
        except
        select activity_id
        from receiving_diff) a;


  -- perform diff between s_diff and r_diff
      -- upload the non-conflicts into merge_staging_area
      -- upload conflict into conflicting_activities
  create temp table diff_diff(
    activity_id integer,
    change_type_supplying merlin.activity_change_type not null,
    change_type_receiving merlin.activity_change_type not null
  );

  -- this is going to require us to do the "none" operation again on the remaining modifies
  -- but otherwise we can just dump the 'adds' and 'none' into the merge staging area table

  -- 'delete' against a 'delete' does not enter the merge staging area table
  -- receiving 'delete' against supplying 'none' does not enter the merge staging area table

  insert into merlin.merge_staging_area (
    merge_request_id, activity_id, name, tags, source_scheduling_goal_id, source_scheduling_goal_invocation_id,
    created_at, created_by, last_modified_by,
    start_offset, type, arguments, metadata, anchor_id, anchored_to_start, change_type
         )
  -- 'adds' can go directly into the merge staging area table
  select _merge_request_id, activity_id, name, tags.tag_ids_activity_snapshot(s_diff.activity_id, psa.snapshot_id),
         source_scheduling_goal_id, source_scheduling_goal_invocation_id, created_at, created_by, last_modified_by,
         start_offset, type, arguments, metadata, anchor_id, anchored_to_start, change_type
    from supplying_diff as  s_diff
    join merlin.plan_snapshot_activities psa
      on s_diff.activity_id = psa.id
    where snapshot_id = snapshot_id_supplying and change_type = 'add'
  union
  -- an 'add' between the receiving plan and merge base is actually a 'none'
  select _merge_request_id, activity_id, name, tags.tag_ids_activity_directive(r_diff.activity_id, ad.plan_id),
         source_scheduling_goal_id, source_scheduling_goal_invocation_id, created_at, created_by, last_modified_by,
         start_offset, type, arguments, metadata, anchor_id, anchored_to_start, 'none'::merlin.activity_change_type
    from receiving_diff as r_diff
    join merlin.activity_directive ad
      on r_diff.activity_id = ad.id
    where plan_id = plan_id_receiving and change_type = 'add';

  -- put the rest in diff_diff
  insert into diff_diff (activity_id, change_type_supplying, change_type_receiving)
  select activity_id, supplying_diff.change_type as change_type_supplying, receiving_diff.change_type as change_type_receiving
    from receiving_diff
    join supplying_diff using (activity_id)
  where receiving_diff.change_type != 'add' or supplying_diff.change_type != 'add';

  -- ...except for that which is not recorded
  delete from diff_diff
    where (change_type_receiving = 'delete' and  change_type_supplying = 'delete')
       or (change_type_receiving = 'delete' and change_type_supplying = 'none');

  insert into merlin.merge_staging_area (
    merge_request_id, activity_id, name, tags, source_scheduling_goal_id, source_scheduling_goal_invocation_id,
    created_at, created_by, last_modified_by,
    start_offset, type, arguments, metadata, anchor_id, anchored_to_start, change_type
  )
  -- receiving 'none' and 'modify' against 'none' in the supplying side go into the merge staging area as 'none'
  select _merge_request_id, activity_id, name, tags.tag_ids_activity_directive(diff_diff.activity_id, plan_id),
         source_scheduling_goal_id, source_scheduling_goal_invocation_id, created_at, created_by, last_modified_by,
         start_offset, type, arguments, metadata, anchor_id, anchored_to_start, 'none'
    from diff_diff
    join merlin.activity_directive
      on activity_id=id
    where plan_id = plan_id_receiving
      and change_type_supplying = 'none'
      and (change_type_receiving = 'modify' or change_type_receiving = 'none')
  union
  -- supplying 'modify' against receiving 'none' go into the merge staging area as 'modify'
  select _merge_request_id, activity_id, name, tags.tag_ids_activity_snapshot(diff_diff.activity_id, snapshot_id),  source_scheduling_goal_id, source_scheduling_goal_invocation_id, created_at,
         created_by, last_modified_by, start_offset, type, arguments, metadata, anchor_id, anchored_to_start, change_type_supplying
    from diff_diff
    join merlin.plan_snapshot_activities p
      on diff_diff.activity_id = p.id
    where snapshot_id = snapshot_id_supplying
      and (change_type_receiving = 'none' and diff_diff.change_type_supplying = 'modify')
  union
  -- supplying 'delete' against receiving 'none' go into the merge staging area as 'delete'
    select _merge_request_id, activity_id, name, tags.tag_ids_activity_directive(diff_diff.activity_id, plan_id),  source_scheduling_goal_id, source_scheduling_goal_invocation_id, created_at,
         created_by, last_modified_by, start_offset, type, arguments, metadata, anchor_id, anchored_to_start, change_type_supplying
    from diff_diff
    join merlin.activity_directive p
      on diff_diff.activity_id = p.id
    where plan_id = plan_id_receiving
      and (change_type_receiving = 'none' and diff_diff.change_type_supplying = 'delete');

  -- 'modify' against a 'modify' must be checked for equality first.
  with false_modify as (
    select activity_id, name, tags.tag_ids_activity_directive(dd.activity_id, psa.snapshot_id) as tags,
           source_scheduling_goal_id, source_scheduling_goal_invocation_id, created_at, start_offset, type, arguments, metadata, anchor_id, anchored_to_start
    from merlin.plan_snapshot_activities psa
    join diff_diff dd
      on dd.activity_id = psa.id
    where psa.snapshot_id = snapshot_id_supplying
      and (dd.change_type_receiving = 'modify' and dd.change_type_supplying = 'modify')
    intersect
    select activity_id, name, tags.tag_ids_activity_directive(dd.activity_id, ad.plan_id) as tags,
           source_scheduling_goal_id, source_scheduling_goal_invocation_id, created_at, start_offset, type, arguments, metadata, anchor_id, anchored_to_start
    from diff_diff dd
    join merlin.activity_directive ad
      on dd.activity_id = ad.id
    where ad.plan_id = plan_id_receiving
      and (dd.change_type_supplying = 'modify' and dd.change_type_receiving = 'modify'))
  insert into merlin.merge_staging_area (
    merge_request_id, activity_id, name, tags, source_scheduling_goal_id, source_scheduling_goal_invocation_id,
    created_at, created_by, last_modified_by,
    start_offset, type, arguments, metadata, anchor_id, anchored_to_start, change_type)
    select _merge_request_id, ad.id, ad.name, tags,  ad.source_scheduling_goal_id, ad.source_scheduling_goal_invocation_id,
           ad.created_at, ad.created_by, ad.last_modified_by, ad.start_offset, ad.type, ad.arguments, ad.metadata,
           ad.anchor_id, ad.anchored_to_start, 'none'
    from false_modify fm
    left join merlin.activity_directive ad
      on (ad.plan_id, ad.id) = (plan_id_receiving, fm.activity_id);

  -- 'modify' against 'delete' and inequal 'modify' against 'modify' goes into conflict table (aka everything left in diff_diff)
  insert into merlin.conflicting_activities (merge_request_id, activity_id, change_type_supplying, change_type_receiving)
  select begin_merge._merge_request_id, activity_id, change_type_supplying, change_type_receiving
  from (select begin_merge._merge_request_id, activity_id
        from diff_diff
        except
        select msa.merge_request_id, activity_id
        from merlin.merge_staging_area msa) a
  join diff_diff using (activity_id);

  -- Fail if there are no differences between the snapshot and the plan getting merged
  validate_non_no_op_status := null;
  select change_type_receiving
  from merlin.conflicting_activities
  where merge_request_id = _merge_request_id
  limit 1
  into validate_non_no_op_status;

  if validate_non_no_op_status is null then
    select change_type
    from merlin.merge_staging_area msa
    where merge_request_id = _merge_request_id
    and msa.change_type != 'none'
    limit 1
    into validate_non_no_op_status;

    if validate_non_no_op_status is null then
      raise exception 'Cannot begin merge. The contents of the two plans are identical.';
    end if;
  end if;


  -- clean up
  drop table supplying_diff;
  drop table receiving_diff;
  drop table diff_diff;
end
$$;

call migrations.mark_migration_applied(35);
