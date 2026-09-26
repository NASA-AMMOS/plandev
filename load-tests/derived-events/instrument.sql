-- TEMPORARY benchmark instrumentation for merlin.refresh_derived_events_on_trigger(). Never ship this.
-- Revert with uninstrument.sql.
--
-- Logs every trigger-initiated refresh to the Postgres log (not a table, so a rollback cannot erase the evidence):
--   DERIVED_REFRESH START pid=.. tx=.. table=.. op=.. at=..
--   DERIVED_REFRESH END   pid=.. tx=.. table=.. op=.. at=.. ms=..
--
-- Optional artificial delay (storm/cancel repros ONLY, never for performance numbers), applied after the refresh
-- while the MV lock is still held, i.e. it simulates a slower refresh. Takes effect immediately for all sessions:
--   update public.derived_events_bench set refresh_delay = 2;

alter system set log_min_messages = 'log';
alter system set log_lock_waits = on;
alter system set log_temp_files = 0;
select pg_reload_conf();

-- A table, not a GUC: ALTER SYSTEM rejects custom GUCs and ALTER DATABASE SET misses Hasura's pooled sessions.
create table if not exists public.derived_events_bench (refresh_delay double precision not null);
insert into public.derived_events_bench select 0 where not exists (select from public.derived_events_bench);
grant select on public.derived_events_bench to public;

create or replace function merlin.refresh_derived_events_on_trigger()
  returns trigger
  language plpgsql as $$
declare
  t0 timestamptz := clock_timestamp();
  delay double precision := (select refresh_delay from public.derived_events_bench);
begin
  raise log 'DERIVED_REFRESH START pid=% tx=% table=% op=% at=%',
    pg_backend_pid(), txid_current(), tg_table_name, tg_op, t0;
  refresh materialized view concurrently merlin.derived_events;
  if delay > 0 then
    perform pg_sleep(delay);
  end if;
  raise log 'DERIVED_REFRESH END pid=% tx=% table=% op=% at=% ms=%',
    pg_backend_pid(), txid_current(), tg_table_name, tg_op, clock_timestamp(),
    round((extract(epoch from clock_timestamp() - t0) * 1000)::numeric, 1);
  return new;
end;
$$;
