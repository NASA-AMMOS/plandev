create view migrations.applied_migrations as
  select migration_id
  from migrations.schema_migrations;
