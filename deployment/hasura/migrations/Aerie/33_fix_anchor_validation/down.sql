create or replace procedure merlin.validate_nonegative_net_plan_start(_activity_id integer, _plan_id integer)
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

    if (_start_offset < '0' and _anchored_to_start) then -- only need to check if anchored to start or something with a negative offset
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
call migrations.mark_migration_rolled_back(33);
