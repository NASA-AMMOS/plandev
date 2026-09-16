alter table merlin.mission_model
  alter column jar_id drop not null,

  add column is_executable boolean not null default true,
  add constraint mission_model_executable_jar_exists
    check (not is_executable or jar_id is not null),

  comment on column merlin.mission_model.jar_id is e''
    'An uploaded JAR file defining the mission model. Null if the model is not is_executable.';
  comment on column merlin.mission_model.is_executable is e''
    'A flag that is set to true if the model is executable, otherwise false. If true, jar_id must be non-null.';

call migrations.mark_migration_applied('38');
