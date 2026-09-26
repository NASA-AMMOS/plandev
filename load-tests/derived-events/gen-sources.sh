#!/usr/bin/env bash
# gen-sources.sh <count> [events_per_file=3] -> writes $OUT/sources/<run>-<i>.json, prints the paths.
# Each file: unique key, unique valid_at later than every seeded source (so it must show up in derived_events),
# period 2025-001T00:00 to 01:00 (overlapping the start of every seeded perf DG), PerfSource/PerfEvent types
# (created by seed.sql).
set -euo pipefail
source "$(dirname "$0")/env.sh"
count=${1:?count}; per=${2:-3}
run=$(date +%s)
mkdir -p "$OUT/sources"
for i in $(seq 1 "$count"); do
  f="$OUT/sources/$run-$i.json"
  valid_at=$(date -u -r $((run + 315576000 + i)) +%Y-%jT%H:%M:%SZ) # ~10y ahead: newer than seeded 2030 sources
  {
    printf '{"source":{"key":"tiny-%s-%s","source_type_name":"PerfSource","valid_at":"%s",' "$run" "$i" "$valid_at"
    printf '"period":{"start_time":"2025-001T00:00:00Z","end_time":"2025-001T01:00:00Z"},"attributes":{}},"events":['
    for e in $(seq 1 "$per"); do
      [ "$e" -gt 1 ] && printf ','
      printf '{"key":"tiny-%s-%s-%s","event_type_name":"PerfEvent","start_time":"2025-001T00:%02d:00Z","duration":"00:00:30","attributes":{"seq":%s,"note":"tiny"}}' \
        "$run" "$i" "$e" $((e % 60)) "$e"
    done
    printf ']}\n'
  } >"$f"
  echo "$f"
done
