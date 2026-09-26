#!/usr/bin/env bash
# snapshot-race.sh [hold_seconds=5] [writer_sql]
# Hypothesis: a REFRESH that has to WAIT for the MV lock computes its new contents from a snapshot taken BEFORE the
# wait, so it can overwrite the refresh of the transaction it waited for. Only a transaction whose only (or last)
# refresh waited is exposed; the Gateway upload runs 3 refreshes and the later ones re-read after the lock is held.
#
#   session A: begin; insert source race-<run> + 3 events (refreshes, takes the MV lock); sleep hold; commit
#   session B: (1s later) one statement that fires exactly one refresh -> blocks on A's lock, then refreshes
#   then verify.sql: are race-<run>'s committed events in derived_events?
# Default writer is an attribute-only UPDATE of an unrelated DG's source, i.e. a no-op for derived_events.
set -euo pipefail
here=$(dirname "$0")
source "$here/env.sh"
hold=${1:-5}
run=$(date +%s)
writer=${2:-"update merlin.external_source set attributes = attributes || '{\"touched\": $run}' where key = (select min(key) from merlin.external_source where derivation_group_name = 'perf-dg-03')"}
dir="$OUT/race-$run"; mkdir -p "$dir"
since=$(date -u +%Y-%m-%dT%H:%M:%SZ)

psqlx >"$dir/session-a.txt" 2>&1 <<SQL &
begin;
select txid_current() as a_tx, pg_backend_pid() as a_pid;
insert into merlin.external_source (key, source_type_name, derivation_group_name, valid_at, start_time, end_time)
  values ('race-$run', 'PerfSource', 'perf-dg-02', now() + interval '20 years', '2025-01-01 00:00', '2025-01-01 01:00');
insert into merlin.external_event (key, event_type_name, source_key, derivation_group_name, start_time, duration)
  select 'race-$run-' || i, 'PerfEvent', 'race-$run', 'perf-dg-02', timestamptz '2025-01-01 00:00' + i * interval '1 minute', '30s'
  from generate_series(1, 3) i;
select clock_timestamp() as a_refreshed_holding_lock;
select pg_sleep($hold);
commit;
select clock_timestamp() as a_committed;
SQL
a=$!

# wait until A holds the MV lock, then fire B
until psqlx -Atc "select 1 from pg_locks where relation = 'merlin.derived_events'::regclass and mode = 'ExclusiveLock' and granted" | grep -q 1; do sleep 0.2; done
sleep 1
psqlx >"$dir/session-b.txt" 2>&1 <<SQL
select clock_timestamp() as b_start;
$writer;
select clock_timestamp() as b_done;
SQL
wait $a

pglogs "$since" | grep -E 'DERIVED_REFRESH|still waiting|acquired' | cut -c1-170 >"$dir/refresh.log" || true
psqlx -v src_like="race-$run" <"$here/verify.sql" >"$dir/verify.txt"
cat "$dir/session-a.txt" "$dir/session-b.txt" "$dir/refresh.log" "$dir/verify.txt" | tee "$dir/summary.txt"
