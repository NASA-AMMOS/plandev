-- Update schema information on Mission Model
alter table merlin.mission_model
  rename column jar_id to definition_file_id;
alter table merlin.mission_model
  add column is_executable boolean not null default true;
alter table merlin.mission_model
  rename constraint mission_model_references_jar to mission_model_references_file;

comment on column merlin.mission_model.definition_file_id is e''
  'An uploaded file defining the mission model. (JAR file for Java models)';
comment on column merlin.mission_model.is_executable is e''
  'A flag that is set to true if the model is executable, otherwise false.';

-- Update functions that refer to old column name "jar_id"
create or replace function merlin.increment_revision_mission_model_jar_update()
returns trigger
security definer
language plpgsql as $$begin
  update merlin.mission_model
  set revision = revision + 1
  where definition_file_id = new.id
    or definition_file_id = old.id;

  return new;
end$$;

-- Update schema information on Plan
alter table merlin.plan
  add column is_read_only boolean not null default false;

comment on column merlin.plan.is_locked is e''
  'A temporary lock on the receiving plan while a merge is in progress. '
  'Blocks activity directive changes, plan start/duration changes, and plan deletion. '
  'Managed by the merge workflow independently of the is_read_only setting.';
comment on column merlin.plan.is_read_only is e''
  'When true, prohibits changes to the plan''s activity directives and time bounds.';

-- Add readonly check function
create procedure merlin.plan_readonly_exception(plan_id integer)
  language plpgsql as $$
begin
  if(select is_read_only from merlin.plan p where p.id = plan_id limit 1) then
    raise exception 'Plan % is marked as Read Only and cannot be edited.', plan_id;
  end if;
end
$$;

comment on procedure merlin.plan_readonly_exception(plan_id integer) is e''
  'Checks whether the specified plan is marked as "read only", throwing an exception if it is.';

call migrations.mark_migration_applied('38');
