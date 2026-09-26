# Source me. Defaults target the `pd` eeperf stack; override the variables for any other compose stack.
: "${PG_CONTAINER:=pd-eeperf-postgres}"
: "${GATEWAY:=http://localhost:31081}"
: "${OUT:=${TMPDIR:-/tmp}/derived-events-bench}"
BENCH_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
mkdir -p "$OUT"

# psql inside the postgres container as the superuser, against the PlanDev DB. SQL files go on stdin.
psqlx() {
  docker exec -i "$PG_CONTAINER" sh -c 'psql -X -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$PLANDEV_DB" "$@"' psql "$@"
}

# Postgres log lines since an RFC3339 timestamp or docker duration (e.g. 5m).
pglogs() { docker logs "$PG_CONTAINER" --since "$1" 2>&1; }

# Gateway JWT (works with AUTH_TYPE=none, which is what local stacks run).
token() {
  curl -sf -X POST "$GATEWAY/auth/login" -H 'content-type: application/json' \
    -d '{"username":"bench","password":"bench"}' | sed -E 's/.*"token":"([^"]+)".*/\1/'
}

# Upload one source file. Prints: label http_code start_epoch end_epoch seconds. Extra args go to curl.
upload() {
  local file=$1 dg=$2 label=$3; shift 3
  local t0 t1 code
  t0=$(python3 -c 'import time;print(f"{time.time():.3f}")')
  code=$(curl -sS -o "$OUT/upload-$label.out" -w '%{http_code}' "$@" \
    -H "Authorization: Bearer $TOKEN" \
    -F "derivation_group_name=$dg" -F "external_source_file=@$file" \
    "$GATEWAY/uploadExternalSource" 2>>"$OUT/upload-$label.out") || code="curl_exit_$?"
  t1=$(python3 -c 'import time;print(f"{time.time():.3f}")')
  echo "$label $code $t0 $t1 $(python3 -c "print(f'{$t1-$t0:.3f}')")"
}
