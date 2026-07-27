alter table merlin.mission_model
  drop column external_capabilities;

call migrations.mark_migration_rollback(41);
