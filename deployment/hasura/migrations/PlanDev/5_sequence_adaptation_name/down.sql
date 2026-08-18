alter table sequencing.sequence_adaptation
  drop constraint sequence_adaptation_name_unique_key,
  drop column name;

call migrations.mark_migration_rolled_back('5');
