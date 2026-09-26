-- Is merlin.derived_events what its own definition computes from the base tables right now?
--   psqlx < verify.sql
-- The expected set comes from pg_get_viewdef, so it can never drift from the deployed view definition.
-- Run it in a quiet moment (no ingest in flight): it compares one snapshot of base tables to the MV.
--
-- row_*: full-row two-way EXCEPT ALL (missing_from_mv / extra_in_mv).
-- key_*: same diff on (event_key, derivation_group_name) only, i.e. which events are present, independent of which
--        duplicate DISTINCT ON picked. key diff = 0 but row diff > 0 means a tie-break difference, not staleness.
-- rule4_violations: MV rows for which a candidate with the same key/DG from a LATER valid_at source exists
--        (the view's DISTINCT ON has no ORDER BY at its own level; see distinct-on.sql).

\set QUIET on
begin isolation level repeatable read;

select pg_get_viewdef('merlin.derived_events'::regclass, true) as def \gset
-- candidates = the view without its outer DISTINCT ON
select regexp_replace(:'def', '^\s*SELECT DISTINCT ON \(event_key, derivation_group_name\)', 'SELECT') as cand \gset
select :'cand' <> :'def' as stripped \gset
\if :stripped
\else
  \echo 'verify.sql: could not strip DISTINCT ON from the view definition; update the regexp'
  \quit
\endif

create temp table v_expected on commit drop as :def
create temp table v_candidates on commit drop as :cand
create temp table v_actual on commit drop as select * from merlin.derived_events;

\set QUIET off
select
  (select count(*) from v_actual) as mv_rows,
  (select count(*) from v_expected) as expected_rows,
  (select count(*) from (table v_expected except all table v_actual) x) as row_missing_from_mv,
  (select count(*) from (table v_actual except all table v_expected) x) as row_extra_in_mv,
  (select count(*) from (select event_key, derivation_group_name from v_expected
                         except all select event_key, derivation_group_name from v_actual) x) as key_missing_from_mv,
  (select count(*) from (select event_key, derivation_group_name from v_actual
                         except all select event_key, derivation_group_name from v_expected) x) as key_extra_in_mv,
  (select count(*) from v_actual a where exists (
     select from v_candidates c
     where c.event_key = a.event_key and c.derivation_group_name = a.derivation_group_name
       and c.valid_at > a.valid_at)) as rule4_violations;

\echo 'sample missing_from_mv (source_key, count):'
select source_key, count(*) from (table v_expected except all table v_actual) x group by 1 order by 2 desc limit 5;
\echo 'sample extra_in_mv (source_key, count):'
select source_key, count(*) from (table v_actual except all table v_expected) x group by 1 order by 2 desc limit 5;

-- optional: psqlx -v src_like='tiny-123-%' < verify.sql  -> per-source committed / expected / materialized
\if :{?src_like}
\echo 'per source matching' :'src_like'
select s.key as source_key, s.derivation_group_name as dg, s.valid_at, s.created_at,
  (select count(*) from merlin.external_event e where e.source_key = s.key and e.derivation_group_name = s.derivation_group_name) as events_committed,
  (select count(*) from v_expected x where x.source_key = s.key and x.derivation_group_name = s.derivation_group_name) as expected_in_mv,
  (select count(*) from v_actual x where x.source_key = s.key and x.derivation_group_name = s.derivation_group_name) as actual_in_mv
from merlin.external_source s where s.key like :'src_like' order by s.derivation_group_name, s.valid_at;
\endif
commit;
