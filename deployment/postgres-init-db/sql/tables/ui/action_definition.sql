create table ui.action_definition (
  id integer generated always as identity,

  name text not null,
  description text null,
  parameter_schema jsonb not null default '{}'::jsonb,
  settings_schema jsonb not null default '{}'::jsonb,

  action_file_id integer not null,
  workspace_id integer not null,

  created_at timestamptz not null default now(),
  owner text,
  updated_at timestamptz not null default now(),
  updated_by text,

  constraint action_definition_synthetic_key
    primary key (id),

  foreign key (workspace_id)
    references sequencing.workspace (id)
    on delete set null,
  foreign key (owner)
    references permissions.users
    on update cascade
    on delete set null,
  constraint action_definition_references_action_file
    foreign key (action_file_id)
    references merlin.uploaded_file
    on update cascade
    on delete restrict,
  foreign key (updated_by)
    references permissions.users
    on update cascade
    on delete set null
);

create trigger set_timestamp
  before update on ui.action_definition
  for each row
  execute function util_functions.set_updated_at();
