#!/usr/bin/env bash
# storm.sh <n> <same|multi> [label] [extra curl args...]
#   n=1 same  -> one ordinary upload (refresh amplification)
#   same      -> n concurrent uploads to perf-dg-01
#   multi     -> n concurrent uploads to perf-dg-01..n (one per DG)
# Env knobs: DELAY=<s> sets the artificial post-refresh delay for this run (repro only, not for timings);
#            SEQUENTIAL=1 fires the uploads one after another instead of concurrently;
#            extra args go to curl, e.g. --max-time 5.
# Needs instrument.sql applied. Output: $OUT/storm-<label>/{summary.txt,uploads.txt,activity.txt,refresh.log,verify.txt}
set -euo pipefail
here=$(dirname "$0")
source "$here/env.sh"
n=${1:?n}; mode=${2:?same|multi}; label=${3:-$mode-$n}; shift 3 || shift $#
dir="$OUT/storm-$label"; rm -rf "$dir"; mkdir -p "$dir"
TOKEN=$(token)

psqlx -qc "update public.derived_events_bench set refresh_delay = ${DELAY:-0}"
files=($("$here/gen-sources.sh" "$n"))
run=$(basename "${files[0]}" | cut -d- -f1)

# 250ms sampler: every non-idle client backend, what it waits on, who blocks it, its lock on the MV
psqlx -At >"$dir/activity.txt" 2>/dev/null <<'SQL' &
set application_name = 'derived-events-sampler';
select clock_timestamp()::time(3), a.pid, a.state, coalesce(a.wait_event_type || ':' || a.wait_event, '-'),
  pg_blocking_pids(a.pid), round(extract(epoch from clock_timestamp() - a.xact_start)::numeric, 1) as xact_s,
  (select string_agg(l.mode || case when l.granted then '' else '(WAITING)' end, ',') from pg_locks l
   where l.pid = a.pid and l.relation = 'merlin.derived_events'::regclass) as mv_lock,
  left(regexp_replace(a.query, '\s+', ' ', 'g'), 70)
from pg_stat_activity a
where a.datname = current_database() and a.backend_type = 'client backend' and a.pid <> pg_backend_pid()
  and a.state <> 'idle'
\watch 0.25
SQL
# killing the local docker exec does not end the in-container psql, so stop it by application_name
stop_sampler() {
  psqlx -Atc "select pg_terminate_backend(pid) from pg_stat_activity where application_name = 'derived-events-sampler'" >/dev/null || true
}
trap stop_sampler EXIT
sleep 0.5

since=$(date -u +%Y-%m-%dT%H:%M:%SZ)
t0=$(python3 -c 'import time;print(time.time())')
pids=()
for i in $(seq 1 "$n"); do
  dg=perf-dg-01; [ "$mode" = multi ] && dg=$(printf 'perf-dg-%02d' "$i")
  if [ -n "${SEQUENTIAL:-}" ]; then upload "${files[$((i-1))]}" "$dg" "$label-$i" "$@"
  else upload "${files[$((i-1))]}" "$dg" "$label-$i" "$@" & pids+=($!); fi
done >"$dir/uploads.txt"
[ ${#pids[@]} -gt 0 ] && wait "${pids[@]}" || true
t1=$(python3 -c 'import time;print(time.time())')

# a client timeout does not stop the server: wait until no mutation is still running before judging the DB
while [ "$(psqlx -Atc "select count(*) from pg_stat_activity where datname = current_database() and state <> 'idle'
  and backend_type = 'client backend' and pid <> pg_backend_pid()
  and (query ilike '%external_%' or query ilike '%derivation_group%')")" != 0 ]; do sleep 0.5; done
t2=$(python3 -c 'import time;print(time.time())')
sleep 1
stop_sampler
psqlx -qc "update public.derived_events_bench set refresh_delay = 0"

pglogs "$since" | grep -E 'DERIVED_REFRESH|ERROR|canceling|terminating|still waiting|acquired|deadlock' \
  | grep -v 'STATEMENT' >"$dir/refresh.log" || true
psqlx -v src_like="tiny-$run-%" <"$here/verify.sql" >"$dir/verify.txt"

{
  echo "== storm $label: n=$n mode=$mode delay=${DELAY:-0} sequential=${SEQUENTIAL:-0} curl_args=[$*] run=$run"
  printf 'client wall: %.2fs   until DB idle: %.2fs\n' "$(echo "$t1 - $t0" | bc)" "$(echo "$t2 - $t0" | bc)"
  echo "-- requests (label http seconds; body snippet)"
  sort -k3 -n "$dir/uploads.txt" | while read -r l code a b s; do
    printf '%-14s %-12s %7ss  %.110s\n' "$l" "$code" "$s" "$(tr -d '\n' <"$OUT/upload-$l.out")"
  done
  awk '{c[$2~/^200$/?"http_200":$2]++} END{for(k in c) printf "%s=%d ", k, c[k]; print ""}' "$dir/uploads.txt"
  { grep -l '"errors"' $(awk '{print "'"$OUT"'/upload-"$1".out"}' "$dir/uploads.txt") 2>/dev/null || true; } | wc -l | sed 's/^ */graphql error bodies: /'
  echo "-- refreshes per transaction (pid tx: count, first START -> last END, sum ms, tables)"
  awk '/DERIVED_REFRESH/{
      for(i=1;i<=NF;i++){split($i,kv,"="); v[kv[1]]=kv[2]}
      k=v["pid"]" tx="v["tx"]; t=$2
      if($0~/START/){s[k]++; if(!(k in first)) first[k]=t; tabs[k]=tabs[k] substr(v["table"],1,6) ","}
      else {e[k]++; last[k]=t; ms[k]+=v["ms"]; tot+=v["ms"]}
    }
    END{for(k in s) printf "pid=%s: started=%d ended=%d %s -> %s  sum=%.0fms  [%s]\n", k, s[k], e[k], first[k], last[k], ms[k], tabs[k]
        for(k in s){S+=s[k]; E+=e[k]}
        printf "refreshes started=%d completed=%d total_refresh_ms=%.0f\n", S, E, tot}' "$dir/refresh.log" | sort -t'>' -k2
  grep -E 'ERROR|canceling|terminating' "$dir/refresh.log" | cut -c1-200 | head -10 || true
  echo "-- lock waits (log_lock_waits, >1s):"
  { grep 'still waiting' "$dir/refresh.log" || true; } | wc -l
  echo "-- sampler: max concurrent backends waiting on a lock / holding the MV lock"
  awk -F'|' '{t=$1; if($4~/^Lock:/) w[t]++; if($7!="") h[t]++} END{for(t in w) if(w[t]>mw) mw=w[t]; print "max waiting on a lock:", mw+0}' "$dir/activity.txt"
  echo "-- verify"
  cat "$dir/verify.txt"
} | tee "$dir/summary.txt"
