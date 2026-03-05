create table actions.action_definition (
  id integer generated always as identity,

  name text not null,
  description text null,
  settings jsonb not null default '{}'::jsonb,
  archived boolean not null default false,

  workspace_id integer not null,

  created_at timestamptz not null default now(),
  owner text,
  updated_at timestamptz not null default now(),
  updated_by text,

  constraint action_definition_synthetic_key
    primary key (id),

  foreign key (workspace_id)
    references sequencing.workspace (id)
    on delete cascade,
  foreign key (owner)
    references permissions.users
    on update cascade
    on delete set null,
  foreign key (updated_by)
    references permissions.users
    on update cascade
    on delete set null
);

comment on table actions.action_definition is e''
  'User provided Javascript code that will be invoked by Aerie actions and ran on an Aerie server.';
comment on column actions.action_definition.id is e''
  'The ID of the action.';
comment on column actions.action_definition.name is e''
  'The name of the action.';
comment on column actions.action_definition.description is e''
  'The description of the action.';
comment on column actions.action_definition.settings is e''
  'The values provided for the action''s settings.';
comment on column actions.action_definition.archived is e''
  'Whether this action definition is archived (soft-deleted).';
comment on column actions.action_definition.workspace_id is e''
  'The ID of the workspace the action is part of.';
comment on column actions.action_definition.created_at is e''
  'When the action definition was created.';
comment on column actions.action_definition.owner is e''
  'The owner of the action definition.';
comment on column actions.action_definition.updated_at is e''
  'The last time the action definition was updated.';
comment on column actions.action_definition.updated_by is e''
  'The user who last updated the action definition.';

create trigger set_timestamp
  before update on actions.action_definition
  for each row
  execute function util_functions.set_updated_at();
