create table sequencing.user_sequence_metadata (
  id integer generated always as identity,

  name text not null,
  description text not null default '',
  public boolean not null default false,

  owner text,
  updated_by text,

  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),

  constraint sequencing_user_sequence_metadata_pkey
    primary key (id),
  constraint user_sequence_owner_exists
    foreign key (owner)
    references permissions.users
    on update cascade
    on delete set null,
  constraint user_sequence_updated_by_exists
    foreign key (updated_by)
    references permissions.users
    on update cascade
    on delete set null
);

-- A partial index is used to enforce name uniqueness only on templates visible to other users
create unique index template_name_unique_if_published on sequencing.user_sequence_metadata (name) where public;

comment on table sequencing.user_sequence_metadata is e''
  'A template used to sequence the activities of a plan.';
comment on column sequencing.user_sequence_metadata.id is e''
  'The unique identifier for this sequencing template.';
comment on column sequencing.user_sequence_metadata.name is e''
  'A short human readable name for this template';
comment on column sequencing.user_sequence_metadata.description is e''
  'A longer text description of this sequencing template.';
comment on column sequencing.user_sequence_metadata.public is e''
  'Whether this goal is visible to all users.';
comment on column sequencing.user_sequence_metadata.owner is e''
  'The user responsible for this template.';
comment on column sequencing.user_sequence_metadata.updated_by is e''
  'The user who last modified this template''s metadata.';
comment on column sequencing.user_sequence_metadata.created_at is e''
  'The time at which this template was created.';
comment on column sequencing.user_sequence_metadata.updated_at is e''
  'The time at which this template''s metadata was last modified.';

create trigger set_timestamp
before update on sequencing.user_sequence_metadata
for each row
execute function util_functions.set_updated_at();
