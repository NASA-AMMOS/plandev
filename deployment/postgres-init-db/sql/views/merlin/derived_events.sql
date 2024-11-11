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
