-- Restore the stock refresh function (deployment/postgres-init-db/sql/views/merlin/derived_events.sql) and logging.
alter system reset log_min_messages;
alter system reset log_lock_waits;
alter system reset log_temp_files;
select pg_reload_conf();

create or replace function merlin.refresh_derived_events_on_trigger()
  returns trigger
  language plpgsql as $$
begin
  refresh materialized view concurrently merlin.derived_events;
  return new;
end;
$$;

drop table if exists public.derived_events_bench;
