drop view merlin.scheduling_sources;

call migrations.mark_migration_rolled_back(35);
