# derived_events refresh benchmark and repro harness (#1890)

Measures the current `merlin.derived_events` materialized-view refresh (global
`REFRESH MATERIALIZED VIEW CONCURRENTLY` from 3 statement-level triggers), reproduces the refresh storm under
concurrent ingests, and checks the view for staleness after each scenario. Plain bash + psql + curl, no deps.

Everything talks to a running PlanDev stack: the Postgres container through `docker exec`, and the Gateway over
HTTP (`AUTH_TYPE=none`). Defaults target the `pd` stack `eeperf`. Override them for any other compose stack:

```sh
export PG_CONTAINER=postgres GATEWAY=http://localhost:9000   # e.g. deployment/docker-compose.yml
export OUT=/tmp/derived-events-bench                           # where results go (default $TMPDIR/derived-events-bench)
```

All scripts are bash. Run them as `bash <script>`, or directly from bash, since zsh arrays differ.

## 0. Start a stack

```sh
cd deployment && docker compose up -d      # or: pd stack new <name> --plandev <this checkout>
```

## 1. Instrument (temporary, never ship)

```sh
cd load-tests/derived-events
source env.sh
psqlx < instrument.sql     # RAISE LOG START/END per refresh, log_lock_waits, log_temp_files, delay knob
docker logs -f "$PG_CONTAINER" 2>&1 | grep DERIVED_REFRESH   # watch
```

`psqlx < uninstrument.sql` restores the stock function and logging.

## 2. Seed ~1.5M events (triggers off during the bulk insert, one refresh at the end)

```sh
psqlx -v dgs=15 -v sources=1  -v events=100000 -v overlap=0   -v reuse=0   < seed.sql   # Dataset A
psqlx -v dgs=15 -v sources=10 -v events=10000  -v overlap=0.5 -v reuse=0.3 < seed.sql   # Dataset B
```

This replaces the `perf-%` DGs only. Parameters are documented in `seed.sql`. A production-shaped dataset is the same
command with other numbers.

## 3. Baseline refresh timing and plans

```sh
bash refresh-benchmark.sh A-1.5M 5 --cold    # 5 concurrent + 5 plain refreshes, EXPLAIN, auto_explain phases
cat "$OUT/bench-A-1.5M/summary.txt"          # plan.txt, auto-explain.txt, docker-stats.txt alongside
bash sweep.sh 3                              # event-count and revision-count scaling -> $OUT/sweep.csv
```

## 4. Correctness check (run after anything)

```sh
psqlx < verify.sql                        # MV vs a fresh run of its own definition (two-way EXCEPT ALL)
psqlx -v src_like='tiny-%' < verify.sql   # + per-source committed / expected / materialized counts
psqlx -v revisions=2 -v keys=10 < distinct-on.sql   # Rule 4 DISTINCT ON tie-break repro (rolled back)
```

`key_*` columns ignore which duplicate DISTINCT ON kept. `rule4_violations` counts rows where a later revision of
the same key survived range filtering but lost anyway.

## 5. Single upload and storms (real Gateway uploads of tiny sources)

```sh
bash storm.sh 1 same single            # one upload: refresh count per table, durations, tx/pid
SEQUENTIAL=1 bash storm.sh 5 same seq5 # 5 one after another
bash storm.sh 5 same same5             # 5 concurrent, one DG
bash storm.sh 5 multi multi5           # 5 concurrent, 5 DGs
DELAY=2 bash storm.sh 5 same slow5     # artificial +2s per refresh while holding the lock (repro only)
cat "$OUT/storm-same5/summary.txt"     # activity.txt = 250ms pg_stat_activity/pg_locks samples
```

## 6. Staleness and cancellation

```sh
bash snapshot-race.sh 5                # single-refresh tx waits on the MV lock -> MV reverts the other tx
bash snapshot-race.sh 3 "insert into merlin.derivation_group (name, source_type_name) values ('perf-x', 'PerfSource')"
bash cancel-repro.sh A                 # curl --max-time 5 on an upload
bash cancel-repro.sh B1                # statement_timeout while refreshing
bash cancel-repro.sh B2                # statement_timeout while waiting for the MV lock
bash cancel-repro.sh C1                # pg_cancel_backend on the running refresh
bash cancel-repro.sh C2                # pg_cancel_backend on a waiting refresh
bash cancel-repro.sh D                 # storm with one holder and one waiter cancelled
```

## 7. DB tests stay green with the instrumentation

```sh
./gradlew db-tests:e2eTest --tests gov.nasa.ammos.plandev.database.ExternalEventTests   # needs postgres on :5432
```
