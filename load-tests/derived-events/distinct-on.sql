-- Does the view's DISTINCT ON (event_key, derivation_group_name) really keep the latest valid_at duplicate (Rule 4)?
--   psqlx -v revisions=10 -v keys=1000 < distinct-on.sql
-- The ORDER BY valid_at DESC sits in a subquery; the DISTINCT ON sort above it is on (event_key, dg) only and
-- Postgres sorts are not stable, so the surviving duplicate is whatever the sort left first.
-- Builds one throwaway DG of back-to-back (non-overlapping) revisions that all carry the same keys, so every
-- duplicate survives the range filter and only DISTINCT ON decides. Runs the view definition on it, then rolls back.

begin;
alter table merlin.derivation_group disable trigger refresh_derived_events_on_derivation_group;
alter table merlin.external_source disable trigger refresh_derived_events_on_external_source;
alter table merlin.external_event disable trigger refresh_derived_events_on_external_event;

insert into merlin.external_source_type (name) values ('PerfSource') on conflict do nothing;
insert into merlin.external_event_type (name) values ('PerfEvent') on conflict do nothing;
insert into merlin.derivation_group (name, source_type_name) values ('distinct-on-repro', 'PerfSource');
insert into merlin.external_source (key, source_type_name, derivation_group_name, valid_at, start_time, end_time)
select format('rev-%s', lpad(r::text, 3, '0')), 'PerfSource', 'distinct-on-repro',
       timestamptz '2030-01-01' + r * interval '1 hour',
       timestamptz '2025-01-01' + r * :keys * interval '1 minute',
       timestamptz '2025-01-01' + (r + 1) * :keys * interval '1 minute'
from generate_series(1, :revisions) r;
insert into merlin.external_event (key, event_type_name, source_key, derivation_group_name, start_time, duration)
select format('k%s', k), 'PerfEvent', s.key, s.derivation_group_name, s.start_time + k * interval '1 minute', '30s'
from merlin.external_source s, generate_series(0, :keys - 1) k
where s.derivation_group_name = 'distinct-on-repro';

select pg_get_viewdef('merlin.derived_events'::regclass, true) as def \gset
create temp table picked on commit drop as :def

\echo 'expected: every key picks the latest revision'
select format('rev-%s', lpad(:revisions::text, 3, '0')) as latest,
       count(*) as keys,
       count(*) filter (where source_key <> format('rev-%s', lpad(:revisions::text, 3, '0'))) as wrong_pick
from picked where derivation_group_name = 'distinct-on-repro';
select source_key, count(*) from picked where derivation_group_name = 'distinct-on-repro' group by 1 order by 1;
rollback;
