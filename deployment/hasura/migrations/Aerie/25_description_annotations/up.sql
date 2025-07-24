alter table merlin.activity_type
add column description text default null;
comment on column merlin.activity_type.description is e''
  'The description of this activity type.';

call migrations.mark_migration_applied('25');
