#!/usr/bin/env bash
# sweep.sh [runs=3] -> $OUT/sweep.csv
# A: event count scaling (15 DGs x 1 source, 100k..1.5M events)
# B: source-revision scaling at ~constant 1.5M events (15 DGs x 1/5/10/20 revisions, 50% overlap, 30% key reuse)
# Reseeds the perf-% DGs for every point and leaves the last point (B, 20 revisions) seeded.
set -euo pipefail
here=$(dirname "$0")
source "$here/env.sh"
runs=${1:-3}
csv="$OUT/sweep.csv"
echo "dataset,dgs,sources_per_dg,events_per_source,events,derived,concurrent_median_s,concurrent_range_s,plain_median_s,select_only_s" >"$csv"

point() { # dataset sources events overlap reuse
  local ds=$1 s=$2 e=$3 o=$4 r=$5 label="sweep-$1-$2x$3"
  psqlx -v dgs=15 -v sources="$s" -v events="$e" -v overlap="$o" -v reuse="$r" <"$here/seed.sql" >/dev/null
  "$here/refresh-benchmark.sh" "$label" "$runs" >/dev/null
  local d="$OUT/bench-$label"
  python3 - "$ds" "$s" "$e" "$d" >>"$csv" <<'PY'
import statistics, sys, re, pathlib
ds, s, e, d = sys.argv[1:]
runs = [l.split() for l in pathlib.Path(d, "runs.txt").read_text().splitlines()[1:]]
conc = [float(r[1]) / 1000 for r in runs if r[0] == "concurrent"]
plain = [float(r[1]) / 1000 for r in runs if r[0] == "plain"]
sizes = [l for l in pathlib.Path(d, "sizes.txt").read_text().splitlines() if re.match(r"\s*\d", l)][0].split("|")
sel = float(re.search(r"Execution Time: ([\d.]+)", pathlib.Path(d, "plan-timing-off.txt").read_text()).group(1)) / 1000
print(f"{ds},{sizes[0].strip()},{s},{e},{sizes[2].strip()},{sizes[3].strip()},{statistics.median(conc):.2f},"
      f"{min(conc):.2f}-{max(conc):.2f},{statistics.median(plain):.2f},{sel:.2f}")
PY
  tail -1 "$csv"
}

for e in 6667 33333 66667 100000; do point A 1 "$e" 0 0; done
for s in 1 5 10 20; do point B "$s" $((100000 / s)) 0.5 0.3; done
column -s, -t "$csv"
