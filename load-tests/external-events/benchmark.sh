#!/usr/bin/env bash

set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "${script_dir}/../.." && pwd)"

if [[ -f "${repo_root}/.env" ]]; then
  set -a
  # shellcheck disable=SC1091
  source "${repo_root}/.env"
  set +a
fi

command_name="${1:-help}"
database_host="${EXTERNAL_EVENTS_BENCHMARK_HOST:-localhost}"
database_port="${EXTERNAL_EVENTS_BENCHMARK_PORT:-5432}"
database_name="${EXTERNAL_EVENTS_BENCHMARK_DATABASE:-${PLANDEV_DATABASE_NAME:-}}"
database_user="${EXTERNAL_EVENTS_BENCHMARK_USER:-${PLANDEV_USERNAME:-}}"
database_password="${EXTERNAL_EVENTS_BENCHMARK_PASSWORD:-${PLANDEV_PASSWORD:-}}"

if [[ "${command_name}" != "help" ]]; then
  : "${database_name:?Set PLANDEV_DATABASE_NAME or EXTERNAL_EVENTS_BENCHMARK_DATABASE}"
  : "${database_user:?Set PLANDEV_USERNAME or EXTERNAL_EVENTS_BENCHMARK_USER}"
  export PGPASSWORD="${database_password}"
fi

psql_command=(
  psql
  -v ON_ERROR_STOP=1
  --host "${database_host}"
  --port "${database_port}"
  --username "${database_user}"
  --dbname "${database_name}"
)

require_integer() {
  local name="$1"
  local value="$2"
  if [[ ! "${value}" =~ ^[0-9]+$ ]]; then
    echo "${name} must be a nonnegative integer, got '${value}'" >&2
    exit 2
  fi
}

case "${command_name}" in
  seed)
    group_count="${2:-10}"
    sources_per_group="${3:-8}"
    events_per_source="${4:-1000}"
    require_integer group_count "${group_count}"
    require_integer sources_per_group "${sources_per_group}"
    require_integer events_per_source "${events_per_source}"
    "${psql_command[@]}" \
      -v group_count="${group_count}" \
      -v sources_per_group="${sources_per_group}" \
      -v events_per_source="${events_per_source}" \
      -f "${script_dir}/seed.sql"
    ;;
  mutate)
    group_number="${2:-0}"
    run_id="${3:-1}"
    event_count="${4:-1000}"
    require_integer group_number "${group_number}"
    require_integer run_id "${run_id}"
    require_integer event_count "${event_count}"
    "${psql_command[@]}" \
      -v group_number="${group_number}" \
      -v run_id="${run_id}" \
      -v event_count="${event_count}" \
      -f "${script_dir}/mutate.sql"
    ;;
  stats)
    "${psql_command[@]}" -f "${script_dir}/stats.sql"
    ;;
  cleanup)
    "${psql_command[@]}" -f "${script_dir}/cleanup.sql"
    ;;
  help|*)
    cat <<'EOF'
Usage:
  ./load-tests/external-events/benchmark.sh seed [groups] [sources-per-group] [events-per-source]
  ./load-tests/external-events/benchmark.sh mutate [group-number] [run-id] [event-count]
  ./load-tests/external-events/benchmark.sh stats
  ./load-tests/external-events/benchmark.sh cleanup

Defaults seed 80,000 external events across 10 derivation groups. For the
1.3-million-event multi-group case discussed in issue #1890, use:

  ./load-tests/external-events/benchmark.sh seed 13 10 10000

The script reads the repository .env. EXTERNAL_EVENTS_BENCHMARK_HOST,
PORT, DATABASE, USER, and PASSWORD override its connection settings.
EOF
    ;;
esac
