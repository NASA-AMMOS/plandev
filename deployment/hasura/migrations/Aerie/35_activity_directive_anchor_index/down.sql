create or replace function merlin.get_dependent_activities(_activity_id int, _plan_id int)
  returns table(activity_id int, total_offset interval)
  stable
  language plpgsql as $$
begin
  return query
  with recursive d_activities(activity_id, anchor_id, anchored_to_start, start_offset, total_offset) as (
      select ad.id, ad.anchor_id, ad.anchored_to_start, ad.start_offset, ad.start_offset
      from merlin.activity_directive ad
      where (ad.anchor_id, ad.plan_id) = (_activity_id, _plan_id)
    union
      select ad.id, ad.anchor_id, ad.anchored_to_start, ad.start_offset, da.total_offset + ad.start_offset
      from merlin.activity_directive ad, d_activities da
      where (ad.anchor_id, ad.plan_id) = (da.activity_id, _plan_id)
        and ad.anchored_to_start
  ) select da.activity_id, da.total_offset
  from d_activities da;
end;
$$;

drop index if exists merlin.activity_directive_anchor_id_index;

call migrations.mark_migration_rolled_back(35);
