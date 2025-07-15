/*
  - Find the latest snapshot that exists in the history of both the receiving plan, and the supplying snapshot
*/
create function merlin.get_merge_base(plan_id_receiving_changes integer, snapshot_id_supplying_changes integer)
  returns integer
  language plpgsql as $$
  declare
    result integer;
begin
  select * from
    (
      select merlin.get_snapshot_history_from_plan(plan_id_receiving_changes) as ids
      intersect
      select merlin.get_snapshot_history(snapshot_id_supplying_changes) as ids
    )
    as ids
    order by ids desc
    limit 1
    into result;
  return result;
end
$$;
