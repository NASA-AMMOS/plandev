create table merlin.anchor_validation_status(
  activity_id integer not null,
  plan_id integer not null,
  reason_invalid text default null,
  primary key (activity_id, plan_id),
  foreign key (activity_id, plan_id)
    references merlin.activity_directive
    on update cascade
    on delete cascade
);

create index anchor_validation_plan_id_index on merlin.anchor_validation_status (plan_id);

comment on index merlin.anchor_validation_plan_id_index is e''
  'A similar index to that on activity_directive, as we often want to filter by plan_id';

comment on table merlin.anchor_validation_status is e''
  'The validation status of the anchor of a single activity_directive within a plan.';
comment on column merlin.anchor_validation_status.activity_id is e''
  'The synthetic identifier for the activity_directive.\n'
  'Unique within a given plan.';
comment on column merlin.anchor_validation_status.plan_id is e''
  'The plan within which the activity_directive is located';
comment on column merlin.anchor_validation_status.reason_invalid is e''
  'If null, the anchor is valid. If not null, this contains a reason why the anchor is invalid.';

-- `language sql` (not plpgsql) so the planner can inline this into the calling
-- query and pick the (anchor_id, plan_id) index. The plpgsql wrapper was opaque
-- to the planner and chose a worse plan.
create function merlin.get_dependent_activities(_activity_id int, _plan_id int)
  returns table(activity_id int, total_offset interval)
  stable
  language sql as $$
  with recursive d_activities(activity_id, anchor_id, anchored_to_start, start_offset, total_offset) as (
      select ad.id, ad.anchor_id, ad.anchored_to_start, ad.start_offset, ad.start_offset
      from merlin.activity_directive ad
      where (ad.anchor_id, ad.plan_id) = (_activity_id, _plan_id) -- select all activities anchored to this one
    union
      select ad.id, ad.anchor_id, ad.anchored_to_start, ad.start_offset, da.total_offset + ad.start_offset
      from merlin.activity_directive ad, d_activities da
      where (ad.anchor_id, ad.plan_id) = (da.activity_id, _plan_id) -- select all activities anchored to those in the selection
        and ad.anchored_to_start  -- stop at next end-time anchor
  ) select da.activity_id, da.total_offset
  from d_activities da;
$$;

comment on function merlin.get_dependent_activities(_activity_id int, _plan_id int) is e''
'Get the collection of activities that depend on the given activity, with offset relative to the specified activity';

/*
    An activity directive may have a negative offset from its anchor's start time.
    If its anchor is anchored to the end time of another activity (or so on up the chain), the activity with a
    negative offset must come out to have a positive offset relative to that end time anchor.
*/
create procedure merlin.validate_nonnegative_net_end_offset(_activity_id integer, _plan_id integer)
  security definer
  language plpgsql as $$
declare
  end_anchor_id integer;
  offset_from_end_anchor interval;
  _anchor_id integer;
  _start_offset interval;
  _anchored_to_start boolean;
begin
  select anchor_id, start_offset, anchored_to_start
  from merlin.activity_directive
  where (id, plan_id) = (_activity_id, _plan_id)
  into _anchor_id, _start_offset, _anchored_to_start;

  if (_anchor_id is not null)           -- if the activity is anchored to the plan, then it can't be anchored to the end of another activity directive
  then
    /*
      Postgres ANDs don't "short-circuit" -- all clauses are evaluated. Therefore, this query is placed here so that
      it only runs iff the outer 'if' is true
    */
    with recursive end_time_anchor(activity_id, anchor_id, anchored_to_start, start_offset, total_offset) as (
      select _activity_id, _anchor_id, _anchored_to_start, _start_offset, _start_offset
      union
      select ad.id, ad.anchor_id, ad.anchored_to_start, ad.start_offset, eta.total_offset + ad.start_offset
      from merlin.activity_directive ad, end_time_anchor eta
      where (ad.id, ad.plan_id) = (eta.anchor_id, _plan_id)
        and eta.anchor_id is not null                               -- stop at plan
        and eta.anchored_to_start                                   -- or stop at end time anchor
    ) select into end_anchor_id, offset_from_end_anchor
        anchor_id, total_offset from end_time_anchor eta -- get the id of the activity that the selected activity is anchored to
    where not eta.anchored_to_start and eta.anchor_id is not null
    limit 1;

    if end_anchor_id is not null and offset_from_end_anchor < '0' then
      raise notice 'Activity Directive % has a net negative offset relative to an end-time anchor on Activity Directive %.', _activity_id, end_anchor_id;

      insert into merlin.anchor_validation_status (activity_id, plan_id, reason_invalid)
      values (_activity_id, _plan_id, 'Activity Directive ' || _activity_id || ' has a net negative offset relative to an end-time' ||
                                      ' anchor on Activity Directive ' || end_anchor_id ||'.')
      on conflict (activity_id, plan_id) do update
        set reason_invalid = 'Activity Directive ' || excluded.activity_id || ' has a net negative offset relative to an end-time' ||
                             ' anchor on Activity Directive ' || end_anchor_id ||'.';
    end if;
  end if;
end
$$;
comment on procedure merlin.validate_nonnegative_net_end_offset(_activity_id integer, _plan_id integer) is e''
  'Returns true if the specified activity has a net negative offset from a non-plan activity end-time anchor. Otherwise, returns false.\n'
  'If true, writes to anchor_validation_status.';

-- An activity may not have a start time before the plan
create procedure merlin.validate_nonegative_net_plan_start(_activity_id integer, _plan_id integer)
  security definer
  language plpgsql as $$
declare
  net_offset interval;
  _anchor_id integer;
  _start_offset interval;
  _anchored_to_start boolean;
begin
  select anchor_id, start_offset, anchored_to_start
  from merlin.activity_directive
  where (id, plan_id) = (_activity_id, _plan_id)
  into _anchor_id, _start_offset, _anchored_to_start;

  if (_anchored_to_start) then -- only need to check if anchored to start
    with recursive anchors(activity_id, anchor_id, anchored_to_start, start_offset, total_offset) as (
        select _activity_id, _anchor_id, _anchored_to_start, _start_offset, _start_offset
      union
        select ad.id, ad.anchor_id, ad.anchored_to_start, ad.start_offset, anchors.total_offset + ad.start_offset
        from merlin.activity_directive ad, anchors
        where anchors.anchor_id is not null                               -- stop at plan
          and  (ad.id, ad.plan_id) = (anchors.anchor_id, _plan_id)
          and anchors.anchored_to_start                                  -- or, stop at end-time offset
    )
    select total_offset  -- get the id of the activity that the selected activity is anchored to
    from anchors a
    where a.anchor_id is null
      and a.anchored_to_start
    limit 1
    into net_offset;

    if(net_offset < '0') then
      raise notice 'Activity Directive % has a net negative offset relative to Plan Start.', _activity_id;

      insert into merlin.anchor_validation_status (activity_id, plan_id, reason_invalid)
      values (_activity_id, _plan_id, 'Activity Directive ' || _activity_id || ' has a net negative offset relative to Plan Start.')
      on conflict (activity_id, plan_id) do update
        set reason_invalid = 'Activity Directive ' || excluded.activity_id || ' has a net negative offset relative to Plan Start.';
   end if;
  end if;
  end
$$;
comment on procedure merlin.validate_nonegative_net_plan_start(_activity_id integer, _plan_id integer) is e''
  'Returns true if the specified activity has a net negative offset from plan start. Otherwise, returns false.\n'
  'If true, writes to anchor_validation_status.';

/*
  When an activity directive is anchored to another activity directive's end time, it must simplify to a positive offset,
  as simulation can't handle a negative offset (since simulation won't know its start time until the anchoring activity has finished,
  which will always place the anchored activity's start time in the past)

  Throws an exception if:
    - The activity is anchored to itself
    - A cycle is detected
  For all other invalid states, it writes to 'anchor_validation_status's 'reason_invalid' field and then returns.
  If the activity's anchor is valid, then the 'reason_invalid' field on the activity's entry in 'anchor_validation_status' is set to ''.
*/
create function merlin.validate_anchors()
  returns trigger
  security definer
  language plpgsql as $$
declare
  end_anchor_id integer;
  invalid_descendant_act_ids integer[];
  offset_from_end_anchor interval;
  offset_from_plan_start interval;
begin
  -- Clear the reason invalid field (if an exception is thrown, this will be rolled back)
  insert into merlin.anchor_validation_status (activity_id, plan_id, reason_invalid)
  values (new.id, new.plan_id, '')
  on conflict (activity_id, plan_id) do update
    set reason_invalid = '';

  -- An activity cannot anchor to itself
  if(new.anchor_id = new.id) then
    raise exception 'Cannot anchor activity % to itself.', new.anchor_id;
  end if;

  -- Validate that no cycles were added
  if exists(
      with recursive history(activity_id, anchor_id, is_cycle, path) as (
        select new.id, new.anchor_id, false, array[new.id]
        union all
        select ad.id, ad.anchor_id,
               ad.id = any(path),
               path || ad.id
        from merlin.activity_directive ad, history h
        where (ad.id, ad.plan_id) = (h.anchor_id, new.plan_id)
          and not is_cycle
      ) select * from history
      where is_cycle
      limit 1
    ) then
    raise exception 'Cycle detected. Cannot apply changes.';
  end if;

  /*
    An activity directive may have a negative offset from its anchor's start time.
    If its anchor is anchored to the end time of another activity (or so on up the chain), the activity with a
    negative offset must come out to have a positive offset relative to that end time anchor.
  */
  call merlin.validate_nonnegative_net_end_offset(new.id, new.plan_id);
  call merlin.validate_nonegative_net_plan_start(new.id, new.plan_id);

  /*
    Everything below validates that the activities anchored to this one did not become invalid as a result of these changes.

    This only checks descendent start-time anchors, as we know that the state after an end-time anchor is valid
    (As if it no longer is, it will be caught when that activity's row is processed by this trigger)
  */
  -- Get the total offset from the most recent end-time anchor earlier in this activity's chain (or null if there is none)
  with recursive end_time_anchor(activity_id, anchor_id, anchored_to_start, start_offset, total_offset) as (
    select new.id, new.anchor_id, new.anchored_to_start, new.start_offset, new.start_offset
    union
    select ad.id, ad.anchor_id, ad.anchored_to_start, ad.start_offset, eta.total_offset + ad.start_offset
    from merlin.activity_directive ad, end_time_anchor eta
    where (ad.id, ad.plan_id) = (eta.anchor_id, new.plan_id)
      and eta.anchor_id is not null                               -- stop at plan
      and eta.anchored_to_start                                   -- or stop at end time anchor
  ) select into end_anchor_id, offset_from_end_anchor
        anchor_id, total_offset from end_time_anchor eta -- get the id of the activity that the selected activity is anchored to
  where not eta.anchored_to_start and eta.anchor_id is not null
  limit 1;

  -- Not null iff the activity being looked at has some end anchor to another activity in its chain
  if offset_from_end_anchor is not null then
    select array_agg(activity_id)
    from merlin.get_dependent_activities(new.id, new.plan_id)
    where total_offset + offset_from_end_anchor < '0'
    into invalid_descendant_act_ids;

    if invalid_descendant_act_ids is not null then
      raise info 'The following Activity Directives now have a net negative offset relative to an end-time anchor on Activity Directive %: % \n'
        'There may be additional activities that are invalid relative to this activity.',
        end_anchor_id, array_to_string(invalid_descendant_act_ids, ',');

      insert into merlin.anchor_validation_status (activity_id, plan_id, reason_invalid)
      select id, new.plan_id, 'Activity Directive ' || id || ' has a net negative offset relative to an end-time' ||
                              ' anchor on Activity Directive ' || end_anchor_id ||'.'
      from unnest(invalid_descendant_act_ids) as id
      on conflict (activity_id, plan_id) do update
      set reason_invalid = 'Activity Directive ' || excluded.activity_id || ' has a net negative offset relative to an end-time' ||
                           ' anchor on Activity Directive ' || end_anchor_id ||'.';
    end if;
  end if;

  -- Gets the total offset from plan start (or null if there's an end-time anchor in the way)
  with recursive anchors(activity_id, anchor_id, anchored_to_start, start_offset, total_offset) as (
    select new.id, new.anchor_id, new.anchored_to_start, new.start_offset, new.start_offset
    union
    select ad.id, ad.anchor_id, ad.anchored_to_start, ad.start_offset, anchors.total_offset + ad.start_offset
    from merlin.activity_directive ad, anchors
    where anchors.anchor_id is not null                               -- stop at plan
      and (ad.id, ad.plan_id) = (anchors.anchor_id, new.plan_id)
      and anchors.anchored_to_start                                  -- or, stop at end-time offset
  )
  select total_offset  -- get the id of the activity that the selected activity is anchored to
  from anchors a
  where a.anchor_id is null
    and a.anchored_to_start
  limit 1
  into offset_from_plan_start;

  -- Not null iff the activity being looked at is connected to plan start via a chain of start anchors
  if offset_from_plan_start is not null then
    -- Validate descendents
    invalid_descendant_act_ids := null;
    select array_agg(activity_id)
    from merlin.get_dependent_activities(new.id, new.plan_id)
    where total_offset + offset_from_plan_start < '0'
    into invalid_descendant_act_ids;  -- grab all and split

    if invalid_descendant_act_ids is not null then
      raise info 'The following Activity Directives now have a net negative offset relative to Plan Start: % \n'
        'There may be additional activities that are invalid relative to this activity.',
        array_to_string(invalid_descendant_act_ids, ',');

      insert into merlin.anchor_validation_status (activity_id, plan_id, reason_invalid)
      select id, new.plan_id, 'Activity Directive ' || id || ' has a net negative offset relative to Plan Start.'
      from unnest(invalid_descendant_act_ids) as id
      on conflict (activity_id, plan_id) do update
        set reason_invalid = 'Activity Directive ' || excluded.activity_id || ' has a net negative offset relative to Plan Start.';
    end if;
  end if;

  -- These are both null iff the activity is anchored to plan end
  if(offset_from_plan_start is null and offset_from_end_anchor is null) then
    -- All dependent activities should have no errors, as Plan End can have an offset of any value.
    insert into merlin.anchor_validation_status (activity_id, plan_id, reason_invalid)
    select da.activity_id, new.plan_id, ''
    from merlin.get_dependent_activities(new.id, new.plan_id) as da
    on conflict (activity_id, plan_id) do update
      set reason_invalid = '';
  end if;

  -- Remove the error from the dependent activities that wouldn't have been flagged by the earlier checks.
  insert into merlin.anchor_validation_status (activity_id, plan_id, reason_invalid)
  select da.activity_id, new.plan_id, ''
  from merlin.get_dependent_activities(new.id, new.plan_id) as da
  where total_offset + offset_from_plan_start >= '0'
    or total_offset + offset_from_end_anchor >= '0' -- only one of these checks will run depending on which one has `null` behind the offset
  on conflict (activity_id, plan_id) do update
    set reason_invalid = '';

  return new;
end $$;

create constraint trigger validate_anchors_update_trigger
  after update
  on merlin.activity_directive
  deferrable initially deferred
  for each row
  when (old.anchor_id is distinct from new.anchor_id -- != but allows for one side to be null
    or old.anchored_to_start != new.anchored_to_start
    or old.start_offset != new.start_offset)
execute procedure merlin.validate_anchors();


--  The insert trigger is separate in order to allow the update trigger to have a 'when' clause
create constraint trigger validate_anchors_insert_trigger
  after insert
  on merlin.activity_directive
  deferrable initially deferred
  for each row
execute procedure merlin.validate_anchors();

