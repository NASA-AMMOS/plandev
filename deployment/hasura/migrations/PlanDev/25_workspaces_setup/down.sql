----------------------
----- SEQUENCING -----
----------------------
-- Revert workspace table
alter table sequencing.workspace
  drop column parcel_id,
  drop column disk_location;

-- Drop workspace collaborators
drop table sequencing.workspace_collaborators;

--------------
----- UI -----
--------------
-- Revoke Sequencing User access to the UI Schema (table permissions will be handled by dropping the tables)
do $$
  declare
    seq_user text;
  begin
    select grantee
    from information_schema.role_table_grants
    where table_schema = 'sequencing'
      and table_name = 'user_sequence'
      and privilege_type = 'INSERT'
      and grantee != (select current_user)
    limit 1
    into seq_user;

    execute format('revoke usage on schema ui from %I', seq_user);
  end
$$;


-- File extension information
drop table ui.file_extension_content_type;

-- Supported content types
drop type ui.supported_content_types;

----------------------
----- MIGRATIONS -----
----------------------
-- Update "mark_migration_rolled_back"
drop procedure migrations.mark_migration_rolled_back(_migration_id int);
create procedure migrations.mark_migration_rolled_back(_migration_id varchar)
language plpgsql as $$
begin
  delete from migrations.schema_migrations
  where migration_id = _migration_id;
end;
$$;
comment on procedure migrations.mark_migration_rolled_back is e''
  'Given an identifier for a migration, remove that migration from the applied set';

-- Update "mark_migration_applied"
drop procedure migrations.mark_migration_applied(_migration_id integer, _pause_after boolean);
create procedure migrations.mark_migration_applied(_migration_id varchar)
language plpgsql as $$
begin
  insert into migrations.schema_migrations (migration_id)
  values (_migration_id);
end;
$$;
comment on procedure migrations.mark_migration_applied is e''
  'Given an identifier for a migration, add that migration to the applied set';

-- Convert migration_id to varchar
drop view migrations.applied_migrations;
alter table migrations.schema_migrations
  alter column migration_id type varchar using migration_id::varchar;
create view migrations.applied_migrations as
  select migration_id::int
  from migrations.schema_migrations;

-- Remove additional tracking information
alter table migrations.schema_migrations
  drop column after_done,
  drop column pause_after;

call migrations.mark_migration_rolled_back('25')
