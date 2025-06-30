create table sequencing.workspace (
  id integer generated always as identity,

  name text not null,
  disk_location text not null unique,

  -- Additional metadata
  parcel_id integer not null,

  owner text,
  created_at timestamptz not null default now(),
  updated_by text,
  updated_at timestamptz not null default now(),

  constraint workspace_synthetic_key
    primary key (id),
  foreign key (parcel_id)
    references sequencing.parcel
    on update cascade
    on delete restrict,
  foreign key (owner)
    references permissions.users
    on update cascade
    on delete set null,
  foreign key (updated_by)
    references permissions.users
    on update cascade
    on delete set null
);

comment on table sequencing.workspace is e''
  'A container for multiple sequences.';
comment on column sequencing.workspace.id is e''
  'The unique id of the workspace.';
comment on column sequencing.workspace.name is e''
  'The name of the workspace.';
comment on column sequencing.workspace.disk_location is e''
  'The location of the workspace on disk.';
comment on column sequencing.workspace.parcel_id is e''
  'The parcel that files in the workspace use.';
comment on column sequencing.workspace.owner is e''
  'The user responsible for this workspace.';
comment on column sequencing.workspace.created_at is e''
  'Time the workspace was created at.';
comment on column sequencing.workspace.updated_by is e''
  'The user who last updated the workspace.';
comment on column sequencing.workspace.updated_at is e''
  'Time the workspace was last updated.';

create trigger set_timestamp
  before update on sequencing.workspace
  for each row
execute function util_functions.set_updated_at();
