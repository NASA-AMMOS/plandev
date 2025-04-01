create table merlin.external_source_type (
    name text not null,

    constraint external_source_type_pkey
      primary key (name)
);

comment on table merlin.external_source_type is e''
  'Externally imported event source types (each external source has to be of a certain type).\n'
  'They are also helpful to classify external sources.\n'
  'Derivation groups are a subclass of external source type.';

comment on column merlin.external_source_type.name is e''
  'The identifier for this external_source_type, as well as its name.';

create table merlin.external_event_type (
    name text not null,

    constraint external_event_type_pkey
      primary key (name)
);

comment on table merlin.external_event_type is e''
  'Externally imported event types.';

comment on column merlin.external_event_type.name is e''
  'The identifier for this external_event_type, as well as its name.';

create table merlin.derivation_group (
    name text not null,
    source_type_name text not null,
    owner text,

    constraint derivation_group_pkey
      primary key (name),
    constraint derivation_group_references_external_source_type
      foreign key (source_type_name)
      references merlin.external_source_type(name)
      on update cascade
      on delete restrict,
    constraint derivation_group_owner_exists
      foreign key (owner) references permissions.users
      on update cascade
      on delete set null
);

comment on table merlin.derivation_group is e''
  'A collection of external sources of the same type that the derivation operation is run against.';

comment on column merlin.derivation_group.name is e''
  'The name and primary key of the derivation group.';
comment on column merlin.derivation_group.source_type_name is e''
  'The name of the external_source_type of sources in this derivation group.';
comment on column merlin.derivation_group.owner is e''
  'The name of the user that created this derivation_group.';

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

create table merlin.external_event (
    key text not null,
    event_type_name text not null,
    source_key text not null,
    derivation_group_name text not null,
    start_time timestamp with time zone not null,
    duration interval not null,

    constraint external_event_pkey
      primary key (key, source_key, derivation_group_name, event_type_name),
    constraint external_event_references_source_key_derivation_group
      foreign key (source_key, derivation_group_name)
      references merlin.external_source (key, derivation_group_name)
      on update cascade
      on delete cascade,
    constraint external_event_references_event_type_name
      foreign key (event_type_name)
      references merlin.external_event_type(name)
      on update cascade
      on delete restrict
);

comment on table merlin.external_event is e''
  'Externally imported events.';

comment on column merlin.external_event.key is e''
  'The key, or name, of the external_event.\n'
  'Part of the primary key, along with the source_key, derivation_group_name, and event_type_name.';
comment on column merlin.external_event.event_type_name is e''
  'The type of this external_event.';
comment on column merlin.external_event.source_key is e''
  'The key of the external_source that this external_event is included in.\n'
  'Used as a foreign key along with the derivation_group_name to directly identify said source.\n'
  'Part of the primary key along with the key, derivation_group_name, and event_type_name.';
comment on column merlin.external_event.derivation_group_name is e''
  'The derivation_group that the external_source bearing this external_event is a part of.';
comment on column merlin.external_event.start_time is e''
  'The start time (in _plan_ time, NOT planner time), of the range that this source describes.';
comment on column merlin.external_event.duration is e''
  'The span of time of this external event.';

create trigger check_external_event_duration_is_nonnegative_trigger
before insert or update on merlin.external_event
for each row
when (new.duration < '0')
execute function util_functions.raise_duration_is_negative();

create function merlin.check_external_event_boundaries()
returns trigger
language plpgsql as $$
declare
  source_start timestamp with time zone;
  source_end timestamp with time zone;
  event_start timestamp with time zone;
  event_end timestamp with time zone;
begin
  select start_time, end_time into source_start, source_end
  from merlin.external_source
  where new.source_key = external_source.key
    and new.derivation_group_name = external_source.derivation_group_name;

  event_start := new.start_time;
  event_end := new.start_time + new.duration;
  if event_start < source_start or event_end > source_end then
    raise exception 'Event % out of bounds of source %.', new.key, new.source_key;
  end if;
  return new;
end;
$$;

comment on function merlin.check_external_event_boundaries() is e''
  'Checks that an external_event added to the database has a start time and duration that fall in bounds of the associated external_source.';

create trigger check_external_event_boundaries
before insert on merlin.external_event
	for each row execute function merlin.check_external_event_boundaries();

comment on trigger check_external_event_boundaries on merlin.external_event is e''
  'Fires any time a new external event is added that checks that the span of the event fits in its referenced source.';

create table merlin.plan_derivation_group (
    plan_id integer not null,
    derivation_group_name text not null,
    last_acknowledged_at timestamp with time zone default now() not null,
    acknowledged boolean not null default true,

    constraint plan_derivation_group_pkey
      primary key (plan_id, derivation_group_name),
    constraint pdg_plan_exists
      foreign key (plan_id)
      references merlin.plan(id)
      on delete cascade,
    constraint pdg_derivation_group_exists
      foreign key (derivation_group_name)
      references merlin.derivation_group(name)
      on update cascade
      on delete restrict
);

comment on table merlin.plan_derivation_group is e''
  'Links externally imported event sources & plans.\n'
  'Additionally, tracks the last time a plan owner/contributor(s) have acknowledged additions to the derivation group.\n';

comment on column merlin.plan_derivation_group.plan_id is e''
  'The plan with which the derivation group is associated.';
comment on column merlin.plan_derivation_group.derivation_group_name is e''
  'The derivation group being associated with the plan.';
comment on column merlin.plan_derivation_group.last_acknowledged_at is e''
  'The time at which changes to the derivation group were last acknowledged.';

-- update last_acknowledged whenever acknowledged is set to true
create function merlin.pdg_update_ack_at()
  returns trigger
  language plpgsql as $$
begin
  if new.acknowledged = true then
    new.last_acknowledged_at = now();
  end if;
  return new;
end;
$$;

create trigger pdg_update_ack_at
before update on merlin.plan_derivation_group
  for each row execute function merlin.pdg_update_ack_at();

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
  update merlin.plan_derivation_group set "acknowledged" = false
  where plan_derivation_group.derivation_group_name = NEW.derivation_group_name;
  return new;
end;
$$;

create trigger external_source_pdg_ack_update
after insert on merlin.external_source
  for each row execute function merlin.external_source_pdg_ack_update();

create function merlin.subtract_later_ranges(curr_date tstzmultirange, later_dates tstzmultirange[])
returns tstzmultirange
immutable
language plpgsql as $$
  declare
	  ret tstzmultirange := curr_date;
	  later_date tstzmultirange;
begin
	foreach later_date in array later_dates loop
		ret := ret - later_date;
	end loop;
	return ret;
end
$$;

comment on function merlin.subtract_later_ranges(curr_date tstzmultirange, later_dates tstzmultirange[]) is e''
  'Used by the derived_events view that produces from the singular interval of time that a source covers a set of disjoint intervals.\n'
  'The disjointedness arises from where future sources'' spans are subtracted from this one.\n'
  'For example, if a source is valid at t=0, and covers span s=1 to s=5, and there is a source valid at t=1 with a span s=2 to s=3\n'
  'and another valid at t=2 with a span 3 to 4, then this source should have those spans subtracted and should only be valid over [1,2] and [4,5].';

-- Rule 1. An External Event superseded by nothing will be present in the final, derived result.
-- Rule 2. An External Event partially superseded by a later External Source, but whose start time occurs before the start of said External Source(s), will be present in the final, derived result.
-- Rule 3. An External Event whose start is superseded by another External Source, even if its end occurs after the end of said External Source, will be replaced by the contents of that External Source (whether they are blank spaces, or other events).
-- Rule 4. An External Event who shares an ID with an External Event in a later External Source will always be replaced.

create materialized view merlin.derived_events as
-- "distinct on (event_key, derivation_group_name)" and "order by valid_at" satisfies rule 4
-- (only the most recently valid version of an event is included)
select distinct on (event_key, derivation_group_name)
    output.event_key,
    output.source_key,
    output.derivation_group_name,
    output.event_type_name,
    output.duration,
    output.start_time,
    output.source_range,
    output.valid_at
from (
  -- select the events from the sources and include them as they fit into the ranges determined by sub
  select
    s.key as source_key,
    ee.key as event_key,
    ee.event_type_name,
    ee.duration,
    s.derivation_group_name,
    ee.start_time,
    s.source_range,
    s.valid_at
  from merlin.external_event ee
  join (
    with base_ranges as (
      -- base_ranges orders sources by their valid time
      -- and extracts the multirange that they are stated to be valid over
      select
        external_source.key,
        external_source.derivation_group_name,
        tstzmultirange(tstzrange(external_source.start_time, external_source.end_time)) as range,
        external_source.valid_at
      from merlin.external_source
      order by external_source.valid_at
    ), base_and_sub_ranges as (
      -- base_and_sub_ranges takes each of the sources above and compiles a list of all the sources that follow it
      -- and their multiranges that they are stated to be valid over
      select
        base.key,
        base.derivation_group_name,
        base.range as original_range,
        array_remove(array_agg(subsequent.range order by subsequent.valid_at), NULL) as subsequent_ranges,
        base.valid_at
      from base_ranges base
      left join base_ranges subsequent
        on base.derivation_group_name = subsequent.derivation_group_name
        and base.valid_at < subsequent.valid_at
      group by base.key, base.derivation_group_name, base.valid_at, base.range
    )
    -- this final selection (s) utilizes the first, as well as merlin.subtract_later_ranges,
    -- to produce a sparse multirange that a given source is valid over.
    -- See merlin.subtract_later_ranges for further details on subtracted ranges.
    select
      r.key,
      r.derivation_group_name,
      merlin.subtract_later_ranges(r.original_range, r.subsequent_ranges) as source_range,
      r.valid_at
    from base_and_sub_ranges r
    order by r.derivation_group_name desc, r.valid_at) s
  on s.key = ee.source_key
  and s.derivation_group_name = ee.derivation_group_name
  where s.source_range @> ee.start_time
  order by valid_at desc
) output;

-- create a unique index, which allows concurrent refreshes
create unique index on merlin.derived_events (
  event_key,
  source_key,
  derivation_group_name,
  event_type_name
);

-- refresh the materialized view after insertion/update/deletion to external_source and external_event and derivation_group
create function merlin.refresh_derived_events_on_trigger()
  returns trigger
  language plpgsql as $$
begin
  refresh materialized view concurrently merlin.derived_events;
  return new;
end;
$$;

-- events are the most basic source of information, so update when the set of events changes.
create trigger refresh_derived_events_on_external_event
after insert or update or delete on merlin.external_event
  for each statement execute function merlin.refresh_derived_events_on_trigger();

-- also trigger on external sources, especially in the case of an empty source, which could still overlap and erase some
--    events in time (see "rule3_empty" test in ExternalEventTests.java).
create trigger refresh_derived_events_on_external_source
after insert or update or delete on merlin.external_source
  for each statement execute function merlin.refresh_derived_events_on_trigger();

create trigger refresh_derived_events_on_derivation_group
after insert or update or delete on merlin.derivation_group
  for each statement execute function merlin.refresh_derived_events_on_trigger();

comment on materialized view merlin.derived_events is e''
  'Derives the final event set for each derivation group.';

call migrations.mark_migration_applied('11');
