-- Captures the state of a plan and all of its activities
create function merlin.create_snapshot(_plan_id integer)
	returns integer
	language plpgsql as $$
begin
	return merlin.create_snapshot(_plan_id, null, null, null);
end
$$;

create function merlin.create_snapshot(_plan_id integer, _snapshot_name text, _description text, _user text)
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
