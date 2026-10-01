-- Hasura runs each migration transactionally. These trigger changes lock the external-event tables while the cache is
-- backfilled, so venue services should remain stopped for the migration as recommended by the deployment procedure.
drop trigger refresh_derived_events_on_derivation_group on merlin.derivation_group;
drop trigger refresh_derived_events_on_external_source on merlin.external_source;
drop trigger refresh_derived_events_on_external_event on merlin.external_event;
drop function merlin.refresh_derived_events_on_trigger();

create index external_event_derivation_group_source_key_index
  on merlin.external_event (derivation_group_name, source_key);

create function merlin.compute_derived_events(p_derivation_group_name text)
returns table (
  event_key text,
  source_key text,
  derivation_group_name text,
  event_type_name text,
  duration interval,
  start_time timestamp with time zone,
  source_range tstzmultirange,
  valid_at timestamp with time zone,
  attributes jsonb
)
language sql
stable
as $$
  -- "distinct on (event_key, derivation_group_name)" and "order by valid_at" satisfies rule 4
  -- (only the most recently valid version of an event is included)
  select distinct on (output.event_key, output.derivation_group_name)
      output.event_key,
      output.source_key,
      output.derivation_group_name,
      output.event_type_name,
      output.duration,
      output.start_time,
      output.source_range,
      output.valid_at,
      output.attributes
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
      s.valid_at,
      ee.attributes
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
        where external_source.derivation_group_name = $1
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
      order by r.derivation_group_name desc, r.valid_at
    ) s
      on s.key = ee.source_key
      and s.derivation_group_name = ee.derivation_group_name
    where s.source_range @> ee.start_time
      and ee.derivation_group_name = $1
    order by valid_at desc
  ) output
  order by
    output.event_key,
    output.derivation_group_name,
    output.valid_at desc;
$$;

-- Build the replacement before dropping the old materialized view. Recompute from authoritative source/event data
-- rather than copying a view that may be stale or contain a pre-ordering-fix result.
create table merlin.derived_events_next (
  event_key text not null,
  source_key text not null,
  derivation_group_name text not null,
  event_type_name text not null,
  duration interval not null,
  start_time timestamp with time zone not null,
  source_range tstzmultirange not null,
  valid_at timestamp with time zone not null,
  attributes jsonb not null,

  constraint derived_events_pkey
    primary key (derivation_group_name, event_key),

  constraint derived_events_references_derivation_group
    foreign key (derivation_group_name)
      references merlin.derivation_group(name)
      on update cascade
      on delete cascade,

  constraint derived_events_references_event_type
    foreign key (event_type_name)
      references merlin.external_event_type(name)
      on update cascade
      on delete cascade
);

insert into merlin.derived_events_next (
  event_key,
  source_key,
  derivation_group_name,
  event_type_name,
  duration,
  start_time,
  source_range,
  valid_at,
  attributes
)
select computed.*
from merlin.derivation_group dg
cross join lateral merlin.compute_derived_events(dg.name) computed;

drop materialized view merlin.derived_events;
alter table merlin.derived_events_next rename to derived_events;

create index derived_events_event_type_name_index
  on merlin.derived_events (event_type_name);

comment on table merlin.derived_events is e''
  'Caches the final event set for each derivation group. Do not modify directly.';

comment on function merlin.compute_derived_events(text) is e''
  'Computes the current derived event set for one derivation group.';

create function merlin.refresh_derived_events(p_derivation_group_name text)
returns void
language plpgsql
as $$
begin
  delete from merlin.derived_events
  where derived_events.derivation_group_name = p_derivation_group_name;

  insert into merlin.derived_events (
    event_key,
    source_key,
    derivation_group_name,
    event_type_name,
    duration,
    start_time,
    source_range,
    valid_at,
    attributes
  )
  select *
  from merlin.compute_derived_events(p_derivation_group_name);
end;
$$;

comment on function merlin.refresh_derived_events(text) is e''
  'Atomically replaces the derived event set for one derivation group.';

-- One pending row coalesces all changes to a group in this transaction and serializes concurrent updates to that group.
create table merlin.derived_events_refresh_queue (
  derivation_group_name text not null,

  constraint derived_events_refresh_queue_pkey
    primary key (derivation_group_name)
);

-- Transition tables collect affected groups once per statement instead of running queue logic once per changed row.
create function merlin.queue_derived_events_refresh_on_insert()
returns trigger
language plpgsql
as $$
begin
  insert into merlin.derived_events_refresh_queue (derivation_group_name)
  select distinct new_rows.derivation_group_name
  from new_rows
  order by new_rows.derivation_group_name
  on conflict (derivation_group_name) do nothing;
  return null;
end;
$$;

create function merlin.queue_derived_events_refresh_on_update()
returns trigger
language plpgsql
as $$
begin
  insert into merlin.derived_events_refresh_queue (derivation_group_name)
  select affected_groups.derivation_group_name
  from (
    select old_rows.derivation_group_name from old_rows
    union
    select new_rows.derivation_group_name from new_rows
  ) affected_groups
  order by affected_groups.derivation_group_name
  on conflict (derivation_group_name) do nothing;
  return null;
end;
$$;

create function merlin.queue_derived_events_refresh_on_delete()
returns trigger
language plpgsql
as $$
begin
  insert into merlin.derived_events_refresh_queue (derivation_group_name)
  select distinct old_rows.derivation_group_name
  from old_rows
  order by old_rows.derivation_group_name
  on conflict (derivation_group_name) do nothing;
  return null;
end;
$$;

create function merlin.refresh_derived_events_from_queue()
returns trigger
language plpgsql
as $$
begin
  perform merlin.refresh_derived_events(new.derivation_group_name);
  delete from merlin.derived_events_refresh_queue
  where derivation_group_name = new.derivation_group_name;
  return null;
end;
$$;

-- This fires before commit, keeping refresh synchronous and atomic while allowing all upload statements to coalesce.
create constraint trigger refresh_derived_events_from_queue
after insert on merlin.derived_events_refresh_queue
deferrable initially deferred
for each row execute function merlin.refresh_derived_events_from_queue();

create trigger queue_derived_events_on_external_source_insert
after insert on merlin.external_source
referencing new table as new_rows
for each statement execute function merlin.queue_derived_events_refresh_on_insert();

create trigger queue_derived_events_on_external_source_update
after update on merlin.external_source
referencing old table as old_rows new table as new_rows
for each statement execute function merlin.queue_derived_events_refresh_on_update();

create trigger queue_derived_events_on_external_source_delete
after delete on merlin.external_source
referencing old table as old_rows
for each statement execute function merlin.queue_derived_events_refresh_on_delete();

create trigger queue_derived_events_on_external_event_insert
after insert on merlin.external_event
referencing new table as new_rows
for each statement execute function merlin.queue_derived_events_refresh_on_insert();

create trigger queue_derived_events_on_external_event_update
after update on merlin.external_event
referencing old table as old_rows new table as new_rows
for each statement execute function merlin.queue_derived_events_refresh_on_update();

create trigger queue_derived_events_on_external_event_delete
after delete on merlin.external_event
referencing old table as old_rows
for each statement execute function merlin.queue_derived_events_refresh_on_delete();

call migrations.mark_migration_applied('38');
