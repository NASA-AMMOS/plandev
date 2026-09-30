-- Used by merlin.get_dependent_activities (called per row from validate_anchors_insert_trigger).
create index activity_directive_anchor_id_index
  on merlin.activity_directive (anchor_id, plan_id);

-- `language sql` (not plpgsql) so the planner can inline this into the calling
-- query and pick the index above. The plpgsql wrapper was opaque to the planner.
create or replace function merlin.get_dependent_activities(_activity_id int, _plan_id int)
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

call migrations.mark_migration_applied(37);
