create or replace procedure merlin.commit_merge(_request_id integer)
  language plpgsql as $$
  declare
    validate_noConflicts integer;
    plan_id_R integer;
    snapshot_id_S integer;
begin
  if(select id from merlin.merge_request where id = _request_id) is null then
    raise exception 'Invalid merge request id %.', _request_id;
  end if;

  -- Stop if this merge is not 'in-progress'
  if (select status from merlin.merge_request where id = _request_id) != 'in-progress' then
    raise exception 'Cannot commit a merge request that is not in-progress.';
  end if;

  -- Stop if any conflicts have not been resolved
  select * from merlin.conflicting_activities
  where merge_request_id = _request_id and resolution = 'none'
  limit 1
  into validate_noConflicts;

  if(validate_noConflicts is not null) then
    raise exception 'There are unresolved conflicts in merge request %. Cannot commit merge.', _request_id;
  end if;

  select plan_id_receiving_changes from merlin.merge_request mr where mr.id = _request_id into plan_id_R;
  select snapshot_id_supplying_changes from merlin.merge_request mr where mr.id = _request_id into snapshot_id_S;

  insert into merlin.merge_staging_area(
    merge_request_id, activity_id, name, tags, source_scheduling_goal_id, source_scheduling_goal_invocation_id,
    created_at, created_by, last_modified_by,
    start_offset, type, arguments, metadata, anchor_id, anchored_to_start, change_type)
    -- gather delete data from the opposite tables
    select  _request_id, activity_id, name, tags.tag_ids_activity_directive(ca.activity_id, ad.plan_id),
            source_scheduling_goal_id, source_scheduling_goal_invocation_id, created_at, created_by, last_modified_by,
            start_offset, type, arguments, metadata, anchor_id, anchored_to_start,
            'delete'::merlin.activity_change_type
      from  merlin.conflicting_activities ca
      join  merlin.activity_directive ad
        on  ca.activity_id = ad.id
      where ca.resolution = 'supplying'
        and ca.merge_request_id = _request_id
        and plan_id = plan_id_R
        and ca.change_type_supplying = 'delete'
    union
    select  _request_id, activity_id, name, tags.tag_ids_activity_snapshot(ca.activity_id, psa.snapshot_id),
            source_scheduling_goal_id, source_scheduling_goal_invocation_id, created_at,
            created_by, last_modified_by, start_offset, type, arguments, metadata, anchor_id, anchored_to_start,
            'delete'::merlin.activity_change_type
      from  merlin.conflicting_activities ca
      join  merlin.plan_snapshot_activities psa
        on  ca.activity_id = psa.id
      where ca.resolution = 'receiving'
        and ca.merge_request_id = _request_id
        and snapshot_id = snapshot_id_S
        and ca.change_type_receiving = 'delete'
    union
    select  _request_id, activity_id, name, tags.tag_ids_activity_directive(ca.activity_id, ad.plan_id),
            source_scheduling_goal_id, source_scheduling_goal_invocation_id, created_at, created_by, last_modified_by,
            start_offset, type, arguments, metadata, anchor_id, anchored_to_start,
            'none'::merlin.activity_change_type
      from  merlin.conflicting_activities ca
      join  merlin.activity_directive ad
        on  ca.activity_id = ad.id
      where ca.resolution = 'receiving'
        and ca.merge_request_id = _request_id
        and plan_id = plan_id_R
        and ca.change_type_receiving = 'modify'
    union
    select  _request_id, activity_id, name, tags.tag_ids_activity_snapshot(ca.activity_id, psa.snapshot_id),
            source_scheduling_goal_id, source_scheduling_goal_invocation_id, created_at, created_by, last_modified_by,
            start_offset, type, arguments, metadata, anchor_id, anchored_to_start,
            'modify'::merlin.activity_change_type
      from  merlin.conflicting_activities ca
      join  merlin.plan_snapshot_activities psa
        on  ca.activity_id = psa.id
      where ca.resolution = 'supplying'
        and ca.merge_request_id = _request_id
        and snapshot_id = snapshot_id_S
        and ca.change_type_supplying = 'modify';

  -- Unlock so that updates can be written
  update merlin.plan
  set is_locked = false
  where id = plan_id_R;

  -- Update the plan's activities to match merge-staging-area's activities
  -- Add
  insert into merlin.activity_directive(
                id, plan_id, name, source_scheduling_goal_id, source_scheduling_goal_invocation_id, created_at,
                created_by, last_modified_by, start_offset, type, arguments, metadata, anchor_id, anchored_to_start )
  select  activity_id, plan_id_R, name, source_scheduling_goal_id, source_scheduling_goal_invocation_id, created_at,
          created_by, last_modified_by, start_offset, type, arguments, metadata, anchor_id, anchored_to_start
   from merlin.merge_staging_area
  where merge_staging_area.merge_request_id = _request_id
    and change_type = 'add';

  -- Modify
  insert into merlin.activity_directive(
    id, plan_id, "name", source_scheduling_goal_id, source_scheduling_goal_invocation_id, created_at, created_by,
    last_modified_by, start_offset, "type", arguments, metadata, anchor_id, anchored_to_start )
  select  activity_id, plan_id_R, "name", source_scheduling_goal_id, source_scheduling_goal_invocation_id, created_at, created_by, last_modified_by,
          start_offset, "type", arguments, metadata, anchor_id, anchored_to_start
  from merlin.merge_staging_area
  where merge_staging_area.merge_request_id = _request_id
    and change_type = 'modify'
  on conflict (id, plan_id)
  do update
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
    using merlin.merge_staging_area msa
    where adt.directive_id = msa.activity_id
      and adt.plan_id = plan_id_R
      and msa.merge_request_id = _request_id
      and msa.change_type = 'modify';

  insert into tags.activity_directive_tags(plan_id, directive_id, tag_id)
    select plan_id_R, activity_id, t.id
    from merlin.merge_staging_area msa
    inner join tags.tags t -- Inner join because it's specifically inserting into a tags-association table, so if there are no valid tags we do not want a null value for t.id
    on t.id = any(msa.tags)
    where msa.merge_request_id = _request_id
      and (change_type = 'modify'
       or change_type = 'add')
    on conflict (directive_id, plan_id, tag_id) do nothing;
  -- Presets
  insert into merlin.preset_to_directive(preset_id, activity_id, plan_id)
  select pts.preset_id, pts.activity_id, plan_id_R
  from merlin.merge_staging_area msa
  inner join merlin.preset_to_snapshot_directive pts using (activity_id)
  where pts.snapshot_id = snapshot_id_S
    and msa.merge_request_id = _request_id
    and (msa.change_type = 'add'
     or msa.change_type = 'modify')
  on conflict (activity_id, plan_id)
    do update
    set preset_id = excluded.preset_id;

  -- Delete
  delete from merlin.activity_directive ad
  using merlin.merge_staging_area msa
  where ad.id = msa.activity_id
    and ad.plan_id = plan_id_R
    and msa.merge_request_id = _request_id
    and msa.change_type = 'delete';

  -- Clean up
  delete from merlin.conflicting_activities where merge_request_id = _request_id;
  delete from merlin.merge_staging_area where merge_staging_area.merge_request_id = _request_id;

  update merlin.merge_request
  set status = 'accepted'
  where id = _request_id;

  -- Attach snapshot history
  insert into merlin.plan_latest_snapshot(plan_id, snapshot_id)
  select plan_id_receiving_changes, snapshot_id_supplying_changes
  from merlin.merge_request
  where id = _request_id;
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

alter table merlin.plan_snapshot_activities
  add column source_scheduling_goal_invocation_id integer;

alter table merlin.merge_staging_area
  add column source_scheduling_goal_invocation_id integer;

comment on column merlin.merge_staging_area.source_scheduling_goal_invocation_id is e''
  'The invocation id of the scheduling goal that generated this activity directive to be committed.';

alter table merlin.activity_directive_changelog
  add column source_scheduling_goal_invocation_id integer;

create or replace function merlin.store_activity_directive_change()
  returns trigger
  language plpgsql as $$
begin
  insert into merlin.activity_directive_changelog (
    revision,
    plan_id,
    activity_directive_id,
    name,
    source_scheduling_goal_id,
    source_scheduling_goal_invocation_id,
    start_offset,
    type,
    arguments,
    changed_arguments_at,
    metadata,
    changed_by,
    anchor_id,
    anchored_to_start)
  values (
    (select coalesce(max(revision), -1) + 1
     from merlin.activity_directive_changelog
     where plan_id = new.plan_id
      and activity_directive_id = new.id),
    new.plan_id,
    new.id,
    new.name,
    new.source_scheduling_goal_id,
    new.source_scheduling_goal_invocation_id,
    new.start_offset,
    new.type,
    new.arguments,
    new.last_modified_arguments_at,
    new.metadata,
    new.last_modified_by,
    new.anchor_id,
    new.anchored_to_start);

  return new;
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

  insert into merlin.plan_snapshot(plan_id, revision, snapshot_name, description, taken_by)
    select id, revision, _snapshot_name, _description, _user
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

		-- Catch Plan_Locked
		call merlin.plan_locked_exception(_plan_id);

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

create or replace function hasura.restore_activity_changelog(
  _plan_id integer,
  _activity_directive_id integer,
  _revision integer,
  hasura_session json
)
  returns setof merlin.activity_directive
  volatile
  language plpgsql as $$
declare
  _function_permission permissions.permission;
begin
  _function_permission :=
      permissions.get_function_permissions('restore_activity_changelog', hasura_session);
  if not _function_permission = 'NO_CHECK' then
    call permissions.check_general_permissions(
      'restore_activity_changelog',
      _function_permission, _plan_id,
      (hasura_session ->> 'x-hasura-user-id')
    );
  end if;

  if not exists(select id from merlin.plan where id = _plan_id) then
    raise exception 'Plan % does not exist', _plan_id;
  end if;

  if not exists(select id from merlin.activity_directive where (id, plan_id) = (_activity_directive_id, _plan_id)) then
    raise exception 'Activity Directive % does not exist in Plan %', _activity_directive_id, _plan_id;
  end if;

  if not exists(select revision
                from merlin.activity_directive_changelog
                where (plan_id, activity_directive_id, revision) =
                      (_plan_id, _activity_directive_id, _revision))
  then
    raise exception 'Changelog Revision % does not exist for Plan % and Activity Directive %', _revision, _plan_id, _activity_directive_id;
  end if;

  return query
  update merlin.activity_directive as ad
  set name                                  = c.name,
      source_scheduling_goal_id             = c.source_scheduling_goal_id,
      source_scheduling_goal_invocation_id  = c.source_scheduling_goal_invocation_id,
      start_offset                          = c.start_offset,
      type                                  = c.type,
      arguments                             = c.arguments,
      last_modified_arguments_at            = c.changed_arguments_at,
      metadata                              = c.metadata,
      anchor_id                             = c.anchor_id,
      anchored_to_start                     = c.anchored_to_start,
      last_modified_at                      = c.changed_at,
      last_modified_by                      = c.changed_by
  from merlin.activity_directive_changelog as c
  where ad.id                    = _activity_directive_id
    and c.activity_directive_id  = _activity_directive_id
    and ad.plan_id               = _plan_id
    and c.plan_id                = _plan_id
    and c.revision               = _revision
  returning ad.*;
end
$$;

call migrations.mark_migration_applied('20');
