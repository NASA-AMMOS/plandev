drop view migrations.applied_migrations;
call migrations.mark_migration_rolled_back('12');
