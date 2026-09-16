alter table merlin.mission_model
  alter column jar_id set not null,
  drop column is_executable;

call migrations.mark_migration_rolled_back('38');
