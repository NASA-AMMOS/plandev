create table merlin.external_source (
    key text not null,
    source_type_name text not null,
    derivation_group_name text not null,
    valid_at timestamp with time zone not null,
    start_time timestamp with time zone not null,
    end_time timestamp with time zone not null,
    CHECK (end_time > start_time),
    created_at timestamp with time zone default now() not null,
    owner text,
    attributes jsonb not null default '{}',

    constraint external_source_pkey
      primary key (key, derivation_group_name),
    -- a given dg cannot have two sources with the same valid_at!
    CONSTRAINT dg_unique_valid_at UNIQUE (derivation_group_name, valid_at),
    constraint external_source_references_external_source_type_name
      foreign key (source_type_name)
      references merlin.external_source_type(name)
      on update cascade
      on delete restrict,
    constraint external_source_type_matches_derivation_group
      foreign key (derivation_group_name)
      references merlin.derivation_group (name)
      on update cascade
      on delete restrict,
    constraint external_source_owner_exists
      foreign key (owner) references permissions.users
      on update cascade
      on delete set null
);

comment on table merlin.external_source is e''
  'Externally imported event sources.';

comment on column merlin.external_source.key is e''
  'The key, or name, of the external_source.\n'
  'Part of the primary key, along with the derivation_group_name';
comment on column merlin.external_source.source_type_name is e''
  'The type of this external_source.';
comment on column merlin.external_source.derivation_group_name is e''
  'The name of the derivation_group that this external_source is included in.';
comment on column merlin.external_source.valid_at is e''
  'The time (in _planner_ time, NOT plan time) at which a source becomes valid.\n'
  'This time helps determine when a source''s events are valid for the span of time it covers.';
comment on column merlin.external_source.start_time is e''
  'The start time (in _plan_ time, NOT planner time), of the range that this source describes.';
comment on column merlin.external_source.end_time is e''
  'The end time (in _plan_ time, NOT planner time), of the range that this source describes.';
comment on column merlin.external_source.created_at is e''
  'The time (in _planner_ time, NOT plan time) that this particular source was created.\n'
  'This column is used primarily for documentation purposes, and has no associated functionality.';
comment on column merlin.external_source.owner is e''
  'The user who uploaded the external source.';
comment on column merlin.external_source.attributes is e''
  'Additional data captured from the original external event, in key/pair form.';

-- make sure new sources' source_type match that of their derivation group!
create function merlin.external_source_type_matches_dg_on_add()
  returns trigger
  language plpgsql as $$
declare
  source_type text;
begin
  select into source_type derivation_group.source_type_name from merlin.derivation_group where name = new.derivation_group_name;
  if source_type is distinct from new.source_type_name then
    raise foreign_key_violation
    using message='External source ' || new.key || ' is being added to a derivation group ' || new.derivation_group_name
                    || ' where its type ' || new.source_type_name || ' does not match the derivation group type '
                    || source_type || '.' ;
  end if;
  return new;
end;
$$;

create trigger external_source_type_matches_dg_on_add
before insert or update on merlin.external_source
  for each row execute function merlin.external_source_type_matches_dg_on_add();

-- if an external source is linked to a plan it cannot be deleted
create function merlin.external_source_pdg_association_delete()
  returns trigger
  language plpgsql as $$
begin
  if exists (select * from merlin.plan_derivation_group pdg where pdg.derivation_group_name = old.derivation_group_name) then
    raise foreign_key_violation
    using message='External source ' || old.key || ' is part of a derivation group that is associated to a plan.';
  end if;
  return old;
end;
$$;

create trigger external_source_pdg_association_delete
before delete on merlin.external_source
  for each row execute function merlin.external_source_pdg_association_delete();

-- set acknowledged on merlin.plan_derivation_group false for this derivation group as there are new changes
create function merlin.external_source_pdg_ack_update()
  returns trigger
  language plpgsql as $$
begin
  update merlin.plan_derivation_group set plan_derivation_group.acknowledged = false
  where plan_derivation_group.derivation_group_name = NEW.derivation_group_name;
  return new;
end;
$$;

create trigger external_source_pdg_ack_update
after insert on merlin.external_source
  for each row execute function merlin.external_source_pdg_ack_update();
