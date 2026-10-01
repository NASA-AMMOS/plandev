-- Hasura runs this rollback transactionally. Rebuilding the global materialized view takes blocking locks, so venue
-- services should remain stopped as recommended by the deployment procedure.
drop trigger queue_derived_events_on_external_source_insert on merlin.external_source;
drop trigger queue_derived_events_on_external_source_update on merlin.external_source;
drop trigger queue_derived_events_on_external_source_delete on merlin.external_source;
drop trigger queue_derived_events_on_external_event_insert on merlin.external_event;
drop trigger queue_derived_events_on_external_event_update on merlin.external_event;
drop trigger queue_derived_events_on_external_event_delete on merlin.external_event;
drop trigger refresh_derived_events_from_queue on merlin.derived_events_refresh_queue;

drop function merlin.queue_derived_events_refresh_on_insert();
drop function merlin.queue_derived_events_refresh_on_update();
drop function merlin.queue_derived_events_refresh_on_delete();
drop function merlin.refresh_derived_events_from_queue();
drop function merlin.refresh_derived_events(text);

drop table merlin.derived_events_refresh_queue;
drop table merlin.derived_events;
drop function merlin.compute_derived_events(text);
drop index merlin.external_event_derivation_group_source_key_index;

create materialized view merlin.derived_events as
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
      select
        external_source.key,
        external_source.derivation_group_name,
        tstzmultirange(tstzrange(external_source.start_time, external_source.end_time)) as range,
        external_source.valid_at
      from merlin.external_source
      order by external_source.valid_at
    ), base_and_sub_ranges as (
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
  order by valid_at desc
) output
order by
  output.event_key,
  output.derivation_group_name,
  output.valid_at desc;

create unique index on merlin.derived_events (
  event_key,
  source_key,
  derivation_group_name,
  event_type_name
);

create function merlin.refresh_derived_events_on_trigger()
returns trigger
language plpgsql as $$
begin
  refresh materialized view concurrently merlin.derived_events;
  return new;
end;
$$;

create trigger refresh_derived_events_on_external_event
after insert or update or delete on merlin.external_event
for each statement execute function merlin.refresh_derived_events_on_trigger();

create trigger refresh_derived_events_on_external_source
after insert or update or delete on merlin.external_source
for each statement execute function merlin.refresh_derived_events_on_trigger();

create trigger refresh_derived_events_on_derivation_group
after insert or update or delete on merlin.derivation_group
for each statement execute function merlin.refresh_derived_events_on_trigger();

comment on materialized view merlin.derived_events is e''
  'Derives the final event set for each derivation group.';

call migrations.mark_migration_rolled_back('38');
