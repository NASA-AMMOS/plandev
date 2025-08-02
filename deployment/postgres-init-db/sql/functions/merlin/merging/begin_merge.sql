/*
  Plans are merged following a three way merge (https://en.wikipedia.org/wiki/Merge_(version_control)#Three-way_merge)
  algorithm. After beginning a merge, the activities will be placed into one of two areas:
  a Merge Staging Area (MSA) and a Conflicting Activities table (CA).

  Where they will go is decided as follows:

  Difference btwn Source and MB | Difference btwn Target and MB | Outcome
  ------------------------------+-------------------------------+--------------------
            Add                 |             --                | Into MSA as Add
            --                  |             Add               | Into MSA as None
            None                |             None              | Into MSA as None
            Modify              |             None              | Into MSA as Modify
            Delete              |             None              | Into MSA as Delete
            None                |             Modify            | Into MSA as None
            Modify (Equal)      |             Modify (Equal)    | Into MSA as None
            Modify (Inequal)    |             Modify (Inequal)  | Into CA
            Delete              |             Modify            | Into CA
            None                |             Delete            | Dropped
            Modify              |             Delete            | Into CA
            Delete              |             Delete            | Dropped
 */
create procedure merlin.begin_merge(_merge_request_id integer, review_username text)
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
