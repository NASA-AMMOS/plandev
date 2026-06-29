-- History of Plans notes (planid to planid):
--  - Grab all non-null parents
create function merlin.get_plan_history(starting_plan_id integer)
  returns setof integer
  language plpgsql as $$
  declare
    validate_id integer;
  begin
    select plan.id from merlin.plan where plan.id = starting_plan_id into validate_id;
    if validate_id is null then
      raise exception 'Plan ID % is not present in plan table.', starting_plan_id;
    end if;

    return query with recursive history(id) as (
        values(starting_plan_id)                      -- base case
      union
        select parent_id from merlin.plan p
          join history on history.id = p.id and p.parent_id is not null-- recursive case
    ) select * from history;
  end
$$;

-- History of Snapshot notes (planid to snapshotid(s)):
--  - Get the whole history of both
--  - Get the max snapshot id of the intersection
create function merlin.get_snapshot_history_from_plan(starting_plan_id integer)
  returns setof integer
  language plpgsql as $$
begin
  if not exists(select from merlin.plan where id = starting_plan_id) then
    raise exception 'Plan with ID % does not exist.', starting_plan_id;
  end if;
  return query
    select merlin.get_snapshot_history(snapshot_id)  --runs the recursion
    from merlin.plan_latest_snapshot where plan_id = starting_plan_id; --supplies input for get_snapshot_history
end
$$;

create function merlin.get_snapshot_history(starting_snapshot_id integer)
  returns setof integer
  language plpgsql as $$
  declare
    validate_id integer;
begin
  select plan_snapshot.snapshot_id from merlin.plan_snapshot where plan_snapshot.snapshot_id = starting_snapshot_id into validate_id;
  if validate_id is null then
    raise exception 'Snapshot ID % is not present in plan_snapshot table.', starting_snapshot_id;
  end if;

  return query with recursive history(id) as (
      values(starting_snapshot_id) --base case
    union
      select parent_snapshot_id from merlin.plan_snapshot_parent psp
        join history on id = psp.snapshot_id --recursive case
  ) select * from history;
end
$$;
