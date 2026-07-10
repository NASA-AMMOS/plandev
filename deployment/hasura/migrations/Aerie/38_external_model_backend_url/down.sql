alter table merlin.mission_model
  drop column external_backend_url;

call migrations.mark_migration_rolled_back(38);
