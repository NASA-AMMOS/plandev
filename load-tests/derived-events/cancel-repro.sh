#!/usr/bin/env bash
# cancel-repro.sh <A|B1|B2|C1|C2|D>  - timeout / cancellation scenarios; each ends with verify.sql.
#   A   client (curl) timeout 5s on a Gateway upload             -> does the source still commit?
#   B1  statement_timeout 2s in a session whose refresh runs >2s   (direct SQL, no Gateway)
#   B2  statement_timeout 5s in a session whose refresh WAITS on another tx's MV lock
#   C1  pg_cancel_backend on an upload's backend while its refresh runs (holds the MV lock)
#   C2  pg_cancel_backend on an upload's backend while its refresh waits for the MV lock
#   D   5 concurrent same-DG uploads; cancel the lock holder of the 2nd tx and one waiter, the rest continue
# Needs instrument.sql applied and a refresh that takes several seconds: Dataset A (~10s), or any dataset plus
# DELAY=<s> (artificial post-refresh delay while holding the MV lock; repro only).
set -euo pipefail
here=$(dirname "$0")
source "$here/env.sh"
case_=${1:?A|B1|B2|C1|C2|D}
dir="$OUT/cancel-$case_"; rm -rf "$dir"; mkdir -p "$dir"
psqlx -qc "update public.derived_events_bench set refresh_delay = ${DELAY:-0}"
trap 'psqlx -qc "update public.derived_events_bench set refresh_delay = 0"' EXIT

# pid of the backend holding (holder) or waiting for (waiter) the MV's ExclusiveLock
mvpid() {
  local granted=$([ "$1" = holder ] && echo true || echo false)
  until p=$(psqlx -Atc "select pid from pg_locks where relation = 'merlin.derived_events'::regclass
                        and mode = 'ExclusiveLock' and granted = $granted order by pid limit 1") && [ -n "$p" ]; do sleep 0.2; done
  echo "$p"
}
cancel() {
  psqlx -Atc "select clock_timestamp()::time(3), $1, pg_cancel_backend($1),
    (select left(regexp_replace(query, '\s+', ' ', 'g'), 80) from pg_stat_activity where pid = $1)" | tee -a "$dir/cancelled.txt"
}
# session that inserts one source + 3 events directly, optionally under a statement_timeout
direct_ingest() {
  local key=$1 timeout=$2
  psqlx -v ON_ERROR_STOP=0 <<SQL
set statement_timeout = '$timeout';
begin;
insert into merlin.external_source (key, source_type_name, derivation_group_name, valid_at, start_time, end_time)
  values ('$key', 'PerfSource', 'perf-dg-04', now() + interval '20 years', '2025-01-01 00:00', '2025-01-01 01:00');
insert into merlin.external_event (key, event_type_name, source_key, derivation_group_name, start_time, duration)
  select '$key-' || i, 'PerfEvent', '$key', 'perf-dg-04', timestamptz '2025-01-01 00:00' + i * interval '1 minute', '30s'
  from generate_series(1, 3) i;
commit;
SQL
}
report_direct() {
  psqlx -v src_like="$1" <"$here/verify.sql" | tee "$dir/verify.txt"
}

case $case_ in
  A) "$here/storm.sh" 1 same "cancel-A" --max-time 5 ;;
  B1)
    key="cancel-b1-$(date +%s)"
    direct_ingest "$key" 2s 2>&1 | tee "$dir/session.txt"
    report_direct "$key" ;;
  B2)
    key="cancel-b2-$(date +%s)"
    psqlx >"$dir/holder.txt" 2>&1 <<'SQL' &
begin;
update merlin.external_source set attributes = attributes || '{"hold": true}'
  where key = (select min(key) from merlin.external_source where derivation_group_name = 'perf-dg-05');
select pg_sleep(15);
commit;
SQL
    mvpid holder >/dev/null
    direct_ingest "$key" 5s 2>&1 | tee "$dir/session.txt"
    wait
    report_direct "$key" ;;
  C1)
    "$here/storm.sh" 1 same "cancel-C1" &
    sleep 3; cancel "$(mvpid holder)"
    wait ;;
  C2)
    "$here/storm.sh" 2 same "cancel-C2" &
    sleep 3; mvpid holder >/dev/null; cancel "$(mvpid waiter)"
    wait ;;
  D)
    "$here/storm.sh" 5 same "cancel-D" &
    first=$(mvpid holder)
    while [ "$(mvpid holder)" = "$first" ]; do sleep 0.5; done  # first upload committed, 2nd tx now holds the lock
    sleep 5; cancel "$(mvpid holder)"
    sleep 2; cancel "$(mvpid waiter)"
    wait ;;
esac
echo "cancelled backends:"; cat "$dir/cancelled.txt" 2>/dev/null || echo "(none)"
