----------------------
----- MIGRATIONS -----
----------------------
-- Add new tracking information to schema migrations
alter table migrations.schema_migrations
  add column pause_after boolean not null default false,
  add column after_done boolean not null default false;

alter table migrations.schema_migrations
 alter column pause_after drop default;

comment on column migrations.schema_migrations.pause_after is e''
  'Whether the migration has an external script that must be completed before the next migration can be applied';
comment on column migrations.schema_migrations.after_done is e''
  'If "pause_after" is true, whether the external script has completed';

-- convert migration_id to integer
drop view migrations.applied_migrations;
alter table migrations.schema_migrations
  alter column migration_id type integer using migration_id::integer;
create view migrations.applied_migrations as
  select migration_id
  from migrations.schema_migrations;

-- Update "mark_migration_applied"
drop procedure migrations.mark_migration_applied(_migration_id varchar);
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
comment on procedure migrations.mark_migration_applied is e''
  'Given an identifier for a migration, add that migration to the applied set';

-- Update "mark_migration_rolled_back"
drop procedure migrations.mark_migration_rolled_back(_migration_id varchar);
create procedure migrations.mark_migration_rolled_back(_migration_id integer)
language plpgsql as $$
begin
  delete from migrations.schema_migrations
  where migration_id = _migration_id;
end;
$$;
comment on procedure migrations.mark_migration_rolled_back is e''
  'Given an identifier for a migration, remove that migration from the applied set';

--------------
----- UI -----
--------------
-- Supported content types
create type ui.supported_content_types as enum('Text', 'Binary', 'JSON', 'Sequence', 'Metadata');

comment on type ui.supported_content_types is e''
  'The set of content types that the Aerie UI supports.';

-- Add file extension information
create table ui.file_extension_content_type(
  file_extension text not null,
  content_type ui.supported_content_types not null,

  primary key (file_extension)
);

comment on table ui.file_extension_content_type is e''
  'An association table between file extensions and their content type.'
  'Used for informing the UI how to render files based on the extension.';

-- Initialize data in the table
insert into ui.file_extension_content_type(file_extension, content_type)
values ('.txt', 'Text'),
       ('.bin', 'Binary'),
       ('.json', 'JSON'),
       ('.aerie', 'Metadata'),
       ('.seq', 'Sequence'),
       ('.seqN.txt', 'Sequence'),
       ('.seq.json', 'Sequence'),
       ('.rml', 'Sequence'),
       ('.vml', 'Sequence'),
       ('.sasf', 'Sequence'),
       ('.satf', 'Sequence');

-- Grant Sequencing User access to these tables
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

    execute format('grant usage on schema ui to %I', seq_user);
    execute format('grant select on table ui.file_extension_content_type to %I', seq_user);
    execute format('grant usage on type ui.supported_content_types to %I', seq_user);
  end
$$;

----------------------
----- SEQUENCING -----
----------------------

-- Add workspace collaborators
create table sequencing.workspace_collaborators(
  workspace_id int not null,
  collaborator text not null,

  constraint workspace_collaborators_pkey
    primary key (workspace_id, collaborator),
  constraint workspace_collaborators_plan_id_fkey
    foreign key (workspace_id) references sequencing.workspace
    on update cascade
    on delete cascade,
  constraint workspace_collaborator_collaborator_fkey
    foreign key (collaborator) references permissions.users
    on update cascade
    on delete cascade
);

comment on table sequencing.workspace_collaborators is e''
  'A collection of users who collaborate on the workspace alongside the workspace''s owner.';
comment on column sequencing.workspace_collaborators.workspace_id is e''
  'The plan the user is a collaborator on.';
comment on column sequencing.workspace_collaborators.collaborator is e''
  'The username of the collaborator';

-- Update workspace table
alter table sequencing.workspace
 add column disk_location text,
 add column parcel_id integer,
 add column old_ws_id integer,
 add foreign key (parcel_id)
    references sequencing.parcel
    on update cascade
    on delete restrict;

comment on column sequencing.workspace.id is e''
  'The unique id of the workspace.';
comment on column sequencing.workspace.disk_location is e''
  'The location of the workspace on disk.';
comment on column sequencing.workspace.parcel_id is e''
  'The parcel that files in the workspace use.';
comment on column sequencing.workspace.updated_at is e''
  'Time the workspace was last updated.';

-- Data migration: parcel_id
-- Create a new copy of the workspace for every parcel after the first.

/*
  Big SQL statement that,
   - Updates existing workspaces to have a parcel id
  If there are multiple parcels in use across the user sequences in the workspace, it:
    1) creates a clone workspace for each additional parcel, with the name <OLD WS NAME>(<PARCEL_NAME>). I.E. "My Workspace (2.8.0-Lite)"
    2) moves the user sequences that are using the additional parcels to the correct clone ws
 */
with parcels_per_ws as (
  -- Number each parcel in use on each workspace
  select parcel_id, workspace_id, pname, row_number() over (partition by workspace_id) as wrow
    from (select distinct parcel_id, workspace_id, p.name as pname
  from sequencing.user_sequence us join sequencing.parcel p on us.parcel_id = p.id
  order by workspace_id, parcel_id) a
), inserted_ws(new_wid, name, pid, old_wid) as (
  -- Create a new workspace for each additional parcel used on the workspace
  insert into sequencing.workspace (name, parcel_id, owner, created_at, updated_by, updated_at, old_ws_id)
   select name || ' (' || ppw.pname ||')' as name, ppw.parcel_id, owner, created_at, updated_by, updated_at, ppw.workspace_id
   from parcels_per_ws ppw join sequencing.workspace ws on (ppw.workspace_id = ws.id)
   where ppw.wrow > 1
   returning id, name, parcel_id, old_ws_id
), update_ws as (
  -- Update the already existing workspaces to have parcel ids
  update sequencing.workspace ws
    set parcel_id = ppw.parcel_id
    from parcels_per_ws ppw
    where ws.id = ppw.workspace_id and wrow = 1
)
  -- Move user sequences to the new workspaces as needed
  update sequencing.user_sequence us
    set workspace_id = iws.new_wid
  from inserted_ws iws, parcels_per_ws ppw, sequencing.workspace ws
  where us.parcel_id = iws.pid
    and us.workspace_id = iws.old_wid;


-- Data migration: disk_location
update sequencing.workspace ws
set disk_location = replace(replace(replace(ws.name, '/', '_'), '.', '_'), '~', '_');

-- Fix conflicts
update sequencing.workspace ws
set disk_location = disk_location || '(' || ir.row || ')'
from (
select id, row_number() over (partition by disk_location) - 1 as row
from sequencing.workspace
where disk_location in (
  select disk_location
  from sequencing.workspace
  group by disk_location
  having count(1) > 1)) as ir
where ir.id = ws.id
and row > 0;

-- Remove any empty workspaces (No user sequences, and no set parcel id)
delete from sequencing.workspace
where
  id not in (select workspace_id from sequencing.user_sequence)
  and workspace.parcel_id is null;

-- Set unique and not null to match table definition
alter table sequencing.workspace
 add unique(disk_location),
 alter column disk_location set not null,
 alter column parcel_id set not null,
 drop column old_ws_id;

call migrations.mark_migration_applied(25, true);
