alter table merlin.mission_model
  drop column default_view_id;

call migrations.mark_migration_rolled_back('4');
