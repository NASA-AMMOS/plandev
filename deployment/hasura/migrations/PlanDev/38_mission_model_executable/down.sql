-- Drop readonly check function
drop procedure merlin.plan_readonly_exception(integer);

-- Drop added column on Plan
alter table merlin.plan
  drop column is_read_only;

comment on column merlin.plan.is_locked is e''
  'A boolean representing whether this plan can be deleted and if changes can happen to the activities of this plan.';

-- Update functions that refer to "definition_file_id"
create or replace function merlin.increment_revision_mission_model_jar_update()
  returns trigger
  security definer
  language plpgsql as $$begin
  update merlin.mission_model
  set revision = revision + 1
  where jar_id = new.id
     or jar_id = old.id;

  return new;
end$$;

-- Drop schema updates to Mission Model
alter table merlin.mission_model
  rename constraint mission_model_references_file to mission_model_references_jar;
alter table merlin.mission_model
  drop column is_executable;
alter table merlin.mission_model
  rename column definition_file_id to jar_id;

comment on column merlin.mission_model.jar_id is e''
    'An uploaded JAR file defining the mission model.';

call migrations.mark_migration_rolled_back('38');
