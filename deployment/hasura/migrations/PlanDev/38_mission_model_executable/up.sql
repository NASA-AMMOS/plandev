alter table merlin.mission_model
  alter column jar_id drop not null,

  add column is_executable boolean not null default true,
  add constraint mission_model_executable_jar_exists
    check (not is_executable or jar_id is not null),

call migrations.mark_migration_applied('38');
