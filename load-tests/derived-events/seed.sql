-- Synthetic derived_events dataset. Replaces every 'perf-%' derivation group, leaves other data alone.
--
--   psqlx -v dgs=15 -v sources=1  -v events=100000 -v overlap=0   -v reuse=0   < seed.sql   # Dataset A
--   psqlx -v dgs=15 -v sources=10 -v events=10000  -v overlap=0.5 -v reuse=0.3 < seed.sql   # Dataset B
--
-- dgs      derivation groups (perf-dg-01 ..)
-- sources  source revisions per DG; revision s has valid_at = base + s hours, so later revisions supersede earlier
-- events   events per source, evenly spaced 1 minute apart (30s duration), always inside the source window
-- overlap  fraction of each source window overlapped by the next revision (0 = back to back, 0.9 = heavy)
-- reuse    fraction of event keys shared by every revision in a DG (exercises Rule 4); the rest are unique
--
-- Refresh triggers and the per-row boundary check are disabled for the bulk insert only (all in one transaction,
-- so a failure restores them), followed by one explicit refresh and ANALYZE.

\timing on

begin;

insert into merlin.external_source_type (name) values ('PerfSource') on conflict do nothing;
insert into merlin.external_event_type (name, attribute_schema) values ('PerfEvent',
  '{"type":"object","required":[],"properties":{"seq":{"type":"integer"},"note":{"type":"string"}}}')
  on conflict do nothing;

alter table merlin.derivation_group disable trigger refresh_derived_events_on_derivation_group;
alter table merlin.external_source disable trigger refresh_derived_events_on_external_source;
alter table merlin.external_event disable trigger refresh_derived_events_on_external_event;
alter table merlin.external_event disable trigger check_external_event_boundaries;

delete from merlin.external_source where derivation_group_name like 'perf-%';  -- cascades to events
delete from merlin.derivation_group where name like 'perf-%';

insert into merlin.derivation_group (name, source_type_name)
select format('perf-dg-%s', lpad(d::text, 2, '0')), 'PerfSource' from generate_series(1, :dgs) d;

-- source s of every DG: [base + s*W*(1-overlap), +W), W = events minutes
insert into merlin.external_source (key, source_type_name, derivation_group_name, valid_at, start_time, end_time)
select
  format('perf-src-%s-%s', lpad(d::text, 2, '0'), lpad(s::text, 3, '0')),
  'PerfSource',
  format('perf-dg-%s', lpad(d::text, 2, '0')),
  timestamptz '2030-01-01' + s * interval '1 hour',
  timestamptz '2025-01-01' + s * (:events * (1 - :overlap)) * interval '1 minute',
  timestamptz '2025-01-01' + (s * (:events * (1 - :overlap)) + :events) * interval '1 minute'
from generate_series(1, :dgs) d, generate_series(0, :sources - 1) s;

insert into merlin.external_event (key, event_type_name, source_key, derivation_group_name, start_time, duration, attributes)
select
  case when i < :events * :reuse then format('k%s', i) else format('s%s-e%s', es.valid_at_rank, i) end,
  'PerfEvent',
  es.key,
  es.derivation_group_name,
  es.start_time + i * interval '1 minute',
  interval '30 seconds',
  jsonb_build_object('seq', i, 'note', 'synthetic')
from (
  select key, derivation_group_name, start_time,
         rank() over (partition by derivation_group_name order by valid_at) as valid_at_rank
  from merlin.external_source where derivation_group_name like 'perf-%'
) es, generate_series(0, :events - 1) i;

alter table merlin.derivation_group enable trigger refresh_derived_events_on_derivation_group;
alter table merlin.external_source enable trigger refresh_derived_events_on_external_source;
alter table merlin.external_event enable trigger refresh_derived_events_on_external_event;
alter table merlin.external_event enable trigger check_external_event_boundaries;

commit;

refresh materialized view merlin.derived_events;
analyze merlin.derivation_group;
analyze merlin.external_source;
vacuum analyze merlin.external_event;  -- also clears dead tuples left by the delete above
analyze merlin.derived_events;

\timing off
select
  (select count(*) from merlin.derivation_group) as derivation_groups,
  (select count(*) from merlin.external_source) as sources,
  (select count(*) from merlin.external_event) as events,
  (select count(*) from merlin.derived_events) as derived;
