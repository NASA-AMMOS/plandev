\timing on
\echo 'Seeding external-event benchmark data...'

begin;

do $$
begin
  if exists (
    select 1
    from merlin.derivation_group
    where name like 'benchmark-group-%'
  ) then
    raise exception 'Benchmark data already exists. Run benchmark.sh cleanup first.';
  end if;
end;
$$;

insert into merlin.external_source_type (name, attribute_schema)
values ('benchmark-source-type', '{}')
on conflict (name) do nothing;

insert into merlin.external_event_type (name, attribute_schema)
values ('benchmark-event-type', '{}')
on conflict (name) do nothing;

insert into merlin.derivation_group (name, source_type_name)
select
  'benchmark-group-' || group_number,
  'benchmark-source-type'
from generate_series(0, :group_count - 1) as group_number;

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
select
  'benchmark-source-' || source_number,
  'benchmark-source-type',
  'benchmark-group-' || group_number,
  timestamptz '2024-01-01 00:00:00+00' + source_number * interval '1 second',
  timestamptz '2025-01-01 00:00:00+00',
  timestamptz '2025-02-01 00:00:00+00',
  timestamptz '2024-01-01 00:00:00+00',
  jsonb_build_object('revision', source_number)
from generate_series(0, :group_count - 1) as group_number
cross join generate_series(0, :sources_per_group - 1) as source_number;

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
  'benchmark-source-' || source_number,
  'benchmark-group-' || group_number,
  timestamptz '2025-01-01 01:00:00+00' + event_number * interval '1 microsecond',
  interval '1 microsecond',
  jsonb_build_object('revision', source_number)
from generate_series(0, :group_count - 1) as group_number
cross join generate_series(0, :sources_per_group - 1) as source_number
cross join generate_series(0, :events_per_source - 1) as event_number;

commit;

\ir stats.sql
