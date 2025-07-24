
alter table merlin.activity_type
  drop column description;

call migrations.mark_migration_rolled_back('25');
