create table migrations.schema_migrations (
  migration_id integer primary key,
  pause_after boolean not null,
  after_done boolean not null default false
);

create procedure migrations.mark_migration_applied(_migration_id integer, _pause_after boolean default false)
language plpgsql as $$
begin
  if (exists(select from migrations.schema_migrations
                    where pause_after and not after_done)) then
    raise object_not_in_prerequisite_state using message='Prior migration has incomplete "after" task.';
  end if;

  insert into migrations.schema_migrations (migration_id, pause_after, after_done)
  values (_migration_id, _pause_after, false);
end
$$;

create procedure migrations.mark_migration_rolled_back(_migration_id integer)
language plpgsql as $$
begin
  delete from migrations.schema_migrations
  where migration_id = _migration_id;
end;
$$;

comment on schema migrations is e''
  'Tables and procedures associated with tracking schema migrations';
comment on table migrations.schema_migrations is e''
  'Tracks what migrations have been applied';
comment on column migrations.schema_migrations.migration_id is e''
  'An identifier for a migration that has been applied';
comment on column migrations.schema_migrations.pause_after is e''
  'Whether the migration has an external script that must be completed before the next migration can be applied';
comment on column migrations.schema_migrations.after_done is e''
  'If "pause_after" is true, whether the external script has completed';
comment on procedure migrations.mark_migration_applied is e''
  'Given an identifier for a migration, add that migration to the applied set';
comment on procedure migrations.mark_migration_rolled_back is e''
  'Given an identifier for a migration, remove that migration from the applied set';
