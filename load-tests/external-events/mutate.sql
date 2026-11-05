\timing on
\echo 'Timing one gateway-shaped external-source upload...'

begin;

-- The Gateway upserts the derivation group before inserting the nested source and events.
insert into merlin.derivation_group (name, source_type_name)
values ('benchmark-group-' || :group_number, 'benchmark-source-type')
on conflict (name) do nothing;

insert into merlin.external_source (
  key,
  source_type_name,
  derivation_group_name,
  valid_at,
  start_time,
  end_time,
  created_at,
  attributes
)
values (
  'benchmark-update-' || :run_id,
  'benchmark-source-type',
  'benchmark-group-' || :group_number,
  timestamptz '2030-01-01 00:00:00+00' + :run_id * interval '1 second',
  timestamptz '2025-01-01 00:00:00+00',
  timestamptz '2025-02-01 00:00:00+00',
  now(),
  jsonb_build_object('benchmark_run', :run_id)
);

insert into merlin.external_event (
  key,
  event_type_name,
  source_key,
  derivation_group_name,
  start_time,
  duration,
  attributes
)
select
  'benchmark-event-' || event_number,
  'benchmark-event-type',
  'benchmark-update-' || :run_id,
  'benchmark-group-' || :group_number,
  timestamptz '2025-01-01 01:00:00+00' + event_number * interval '1 microsecond',
  interval '1 microsecond',
  jsonb_build_object('benchmark_run', :run_id)
from generate_series(0, :event_count - 1) as event_number;

commit;

select
  count(*) as derived_event_count,
  count(*) filter (where source_key = 'benchmark-update-' || :run_id) as newest_revision_count
from merlin.derived_events
where derivation_group_name = 'benchmark-group-' || :group_number;
