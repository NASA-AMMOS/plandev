#!/usr/bin/env bash
# refresh-benchmark.sh <label> [runs=5] [--cold]
# Times REFRESH MATERIALIZED VIEW CONCURRENTLY (what the triggers run) and the plain REFRESH, then EXPLAINs the
# view definition alone (compute cost without the MV write/diff). --cold restarts postgres first (empties
# shared_buffers; the Docker VM page cache stays warm, hence "cold-ish").
# Output: $OUT/bench-<label>/{summary.txt,plan.txt,plan-timing-off.txt,docker-stats.txt,sizes.txt}
set -euo pipefail
source "$(dirname "$0")/env.sh"
label=${1:?label}; runs=${2:-5}; cold=${3:-}
dir="$OUT/bench-$label"; mkdir -p "$dir"

if [ "$cold" = --cold ]; then
  docker restart "$PG_CONTAINER" >/dev/null
  until psqlx -Atc 'select 1' >/dev/null 2>&1; do sleep 1; done
fi

(while :; do
  docker stats --no-stream --format '{{.CPUPerc}} {{.MemUsage}} {{.BlockIO}}' "$PG_CONTAINER" | sed "s/^/$(date +%T) /"
done) >"$dir/docker-stats.txt" 2>/dev/null &
sampler=$!
trap 'kill $sampler 2>/dev/null' EXIT

dbstats="select temp_files, temp_bytes, blks_read, blks_hit, tup_inserted, tup_deleted
         from pg_stat_database where datname = current_database()"
psqlx -Atc "$dbstats" >"$dir/dbstats-before.txt"

# one run: prints "<kind> <ms> <wal_bytes> <temp_bytes>"
run() {
  local kind=$1 sql=$2 since
  sleep 1  # log timestamps have 1s resolution here; keep runs from sharing a second
  since=$(date -u +%Y-%m-%dT%H:%M:%S.000000000Z)
  psqlx -At <<SQL | awk -v k="$kind" '/^Time:/{ms=$2} /^WAL /{wal=$2} END{printf "%s %s %s", k, ms, wal}'
select pg_current_wal_lsn() as lsn0 \gset
\timing on
$sql;
\timing off
select 'WAL ' || pg_wal_lsn_diff(pg_current_wal_lsn(), :'lsn0');
SQL
  sleep 0.2
  pglogs "$since" | awk '/temporary file/{for(i=1;i<=NF;i++) if($i=="size") s+=$(i+1)} END{printf " %d\n", s}'
}

{
  echo "kind ms wal_bytes temp_bytes"
  for i in $(seq 1 "$runs"); do run concurrent 'refresh materialized view concurrently merlin.derived_events'; done
  for i in $(seq 1 "$runs"); do run plain 'refresh materialized view merlin.derived_events'; done
} | tee "$dir/runs.txt"

sleep 1.5  # let the backends flush pg_stat_database
psqlx -Atc "$dbstats" >"$dir/dbstats-after.txt"

def=$(psqlx -Atc "select pg_get_viewdef('merlin.derived_events'::regclass, true)")
psqlx -c "explain (analyze, buffers, wal, settings, summary) ${def%;}" >"$dir/plan.txt"
psqlx -c "explain (analyze, timing off, buffers, summary) ${def%;}" >"$dir/plan-timing-off.txt"

# One more concurrent refresh with auto_explain on its internal SPI queries: splits the refresh into
# compute view -> duplicate check on new data -> full-join diff against the old MV -> apply.
since=$(date -u +%Y-%m-%dT%H:%M:%SZ)
psqlx >/dev/null <<'SQL'
load 'auto_explain';
set auto_explain.log_min_duration = 100;
set auto_explain.log_nested_statements = on;
set auto_explain.log_analyze = on;
set auto_explain.log_buffers = on;
set auto_explain.log_timing = off;
refresh materialized view concurrently merlin.derived_events;
SQL
sleep 1
pglogs "$since" >"$dir/auto-explain.txt"

psqlx -c "select
  (select count(*) from merlin.derivation_group) dgs, (select count(*) from merlin.external_source) sources,
  (select count(*) from merlin.external_event) events, (select count(*) from merlin.derived_events) derived,
  pg_size_pretty(pg_relation_size('merlin.derived_events')) mv_heap,
  pg_size_pretty(pg_total_relation_size('merlin.derived_events')) mv_total,
  pg_size_pretty(pg_total_relation_size('merlin.external_event')) events_total,
  pg_size_pretty(pg_total_relation_size('merlin.external_source')) sources_total" >"$dir/sizes.txt"

{
  cat "$dir/sizes.txt"
  awk 'NR>1{ms[$1]=ms[$1]" "$2} END{for(k in ms) print k, ms[k]}' "$dir/runs.txt" | while read -r kind vals; do
    python3 -c "import statistics,sys; v=[float(x) for x in sys.argv[2:]]; print(f'{sys.argv[1]:>10}: first {v[0]/1000:.1f}s  median {statistics.median(v)/1000:.1f}s  range {min(v)/1000:.1f}-{max(v)/1000:.1f}s  (n={len(v)})')" "$kind" $vals
  done
  echo "pg_stat_database (temp_files temp_bytes blks_read blks_hit tup_inserted tup_deleted) before/after:"
  cat "$dir/dbstats-before.txt" "$dir/dbstats-after.txt"
  grep -E "Execution Time|Planning Time" "$dir/plan-timing-off.txt" | sed 's/^/explain timing off: /'
  echo "concurrent refresh phases (auto_explain):"
  awk '/duration: .* plan:/{d=$0; sub(/.*duration: /,"",d); sub(/ plan:.*/,"",d); getline; q=$0; sub(/.*Query Text: /,"",q); printf "  %10s  %.90s\n", d, q}' "$dir/auto-explain.txt"
  echo "peak docker stats: $(sort -k2 -t' ' -rn "$dir/docker-stats.txt" | head -1)"
} | tee "$dir/summary.txt"
