create view migrations.applied_migrations as
  select migration_id::int
  from migrations.schema_migrations;
