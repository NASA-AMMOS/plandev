alter table merlin.mission_model
  drop column external_identity_hash;

call migrations.mark_migration_rollback(40);
