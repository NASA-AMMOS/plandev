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
create procedure migrations.mark_migration_rolled_back(_migration_id int)
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



perform migrations.mark_migration_applied(24, true);
