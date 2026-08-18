create table sequencing.user_sequence (
  id integer generated always as identity,
  created_at timestamptz not null default now(),
  definition text not null,
  is_locked boolean not null default false,
  seq_json jsonb,
  name text not null,
  owner text,
  parcel_id integer not null,
  updated_at timestamptz not null default now(),
  workspace_id integer not null,

  constraint user_sequence_primary_key primary key (id),

  foreign key (parcel_id)
    references sequencing.parcel (id)
    on delete cascade,
  foreign key (owner)
    references permissions.users
    on update cascade
    on delete cascade,
  foreign key (workspace_id)
    references sequencing.workspace (id)
    on delete cascade
);

comment on column sequencing.user_sequence.created_at is e''
  'Time the user sequence was created.';
comment on column sequencing.user_sequence.definition is e''
  'The user sequence definition string.';
comment on column sequencing.user_sequence.is_locked is e''
  'A boolean representing whether this user sequence is editable.';
comment on column sequencing.user_sequence.seq_json is e''
  'The SeqJson representation of the user sequence.';
comment on column sequencing.user_sequence.id is e''
  'ID of the user sequence.';
comment on column sequencing.user_sequence.name is e''
  'Human-readable name of the user sequence.';
comment on column sequencing.user_sequence.owner is e''
  'The user responsible for this sequence.';
comment on column sequencing.user_sequence.parcel_id is e''
  'Parcel the user sequence was created with.';
comment on column sequencing.user_sequence.updated_at is e''
  'Time the user sequence was last updated.';
comment on column sequencing.user_sequence.workspace_id is e''
  'The workspace the sequence is associated with.';

create function sequencing.check_is_locked_update()
returns trigger
language plpgsql as $$
begin
  if old.is_locked and new.is_locked then
    raise exception 'Cannot update locked user sequence.';
  end if;

  -- Update the updated_at timestamp
  new.updated_at = now();
  return new;
end
$$;

create trigger check_locked_update
before update on sequencing.user_sequence
for each row
execute function sequencing.check_is_locked_update();

create function sequencing.check_is_locked_delete()
returns trigger
language plpgsql as $$
begin
  if old.is_locked then
    raise exception 'Cannot delete locked user sequence.';
  end if;

  return old;
end
$$;

create trigger check_locked_delete
before delete on sequencing.user_sequence
for each row
execute function sequencing.check_is_locked_delete();

call migrations.mark_migration_rolled_back(26);
