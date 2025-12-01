-- Ensure the update trigger has the correct "when" condition
create or replace trigger update_scheduling_specification_goal
  before update on scheduler.scheduling_specification_goals
  for each row
  when (OLD.priority is distinct from NEW.priority and pg_trigger_depth() < 1)
execute function scheduler.update_scheduling_specification_goal_func();

-- Add a "depth" condition to this trigger to avoid needlessly increasing the spec's revision
create or replace trigger increment_revision_on_goal_update
  before insert or update on scheduler.scheduling_specification_goals
  for each row
  when (pg_trigger_depth() < 1)
execute function scheduler.increment_spec_revision_on_goal_spec_update();

-- Update Scheduling Spec (Plan) Delete function to work per-statement
create or replace function scheduler.delete_scheduling_specification_goal_func()
  returns trigger
  language plpgsql as $$
  declare
    r scheduler.scheduling_specification_goals;
begin
  -- Perform updates in reverse-priority order to ensure that there are no gaps
  for r in select * from removed_rows order by priority desc loop
    update scheduler.scheduling_specification_goals
    set priority = priority - 1
    where specification_id = r.specification_id
      and priority > r.priority;
  end loop;
  return null;
end;
$$;

create or replace trigger delete_scheduling_specification_goal
  after delete on scheduler.scheduling_specification_goals
  referencing old table as removed_rows
  for each statement
execute function scheduler.delete_scheduling_specification_goal_func();


-- Update Scheduling Spec (Model) Delete function to work per-statement
create or replace function scheduler.delete_scheduling_model_specification_goal_func()
  returns trigger
  language plpgsql as $$
  declare
    r scheduler.scheduling_model_specification_goals;
begin
  -- Perform updates in reverse-priority order to ensure that there are no gaps
  for r in select * from removed_rows order by priority desc loop
    update scheduler.scheduling_model_specification_goals
    set priority = priority - 1
    where model_id = r.model_id
      and priority > r.priority;
  end loop;
  return null;
end;
$$;

create or replace trigger delete_scheduling_model_specification_goal
  after delete on scheduler.scheduling_model_specification_goals
  referencing old table as removed_rows
  for each statement
execute function scheduler.delete_scheduling_model_specification_goal_func();

call migrations.mark_migration_applied('2');
