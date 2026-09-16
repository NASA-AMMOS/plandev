alter table merlin.mission_model
  alter column jar_id set not null,
  drop column is_executable;

  comment on column merlin.mission_model.jar_id is e''
    'An uploaded JAR file defining the mission model.';


call migrations.mark_migration_rolled_back('38');
