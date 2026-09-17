alter table merlin.mission_model
  rename constraint mission_model_references_file to mission_model_references_jar;

alter table merlin.mission_model
  drop column is_executable;

alter table merlin.mission_model
  rename column definition_file_id to jar_id;

comment on column merlin.mission_model.jar_id is e''
    'An uploaded JAR file defining the mission model.';

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

alter table merlin.plan
  drop column is_read_only;

comment on column merlin.plan.is_locked is e''
  'A boolean representing whether this plan can be deleted and if changes can happen to the activities of this plan.';

call migrations.mark_migration_rolled_back('38');
