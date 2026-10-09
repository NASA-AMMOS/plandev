# Workspace versioning scale harness

Measures how workspace history and explicit file revisions behave as a workspace ages: many files, deep save history,
many revisions, large/binary churn, concurrent writers, restarts, rebuilds and remote round trips. It measures and
verifies; it does not optimize anything and has no performance pass/fail thresholds. A run fails only on incorrect
results, exceptions, or operations slower than a generous safety ceiling (`opCeilingSeconds`, default 600 s).

## Layout

All code is in the `workspace-server` test source set:

| File | Role |
| --- | --- |
| `scale/WorkspaceVersioningDriver.java` | The product-level operations (save, read, delete, move, create/list/preview/restore revision, restart, rebuild, publish/clone/sync) plus diagnostics. **The only thing scenarios talk to.** |
| `GitRevisionsPrototypeDriver.java` | Adapter for today's backend (Git commit per save, revision tags + catalog, fileId sidecars, controlled remotes). Calls the same handlers the HTTP layer does, below HTTP. **The only file that knows how the backend works.** |
| `scale/Scenarios.java` | The workloads. |
| `scale/Workload.java` | Deterministic content generation, the expected state, the standard probe of user operations, and verification through the driver. |
| `scale/Run.java` | Timing, percentiles, checkpoints, checks, JSON. |
| `scale/ScaleBench.java` | Entry point, profiles, parameters, environment capture, output. |
| `scale/ScaleBenchSmokeTest.java` | Runs every scenario at toy size in the normal `test` task (~15 s) so the harness doesn't rot. |

To benchmark a different implementation, write another `WorkspaceVersioningDriver` and construct it in
`ScaleBench.run` instead of `GitRevisionsPrototypeDriver`. Scenario code should not need to change. Scenarios never
create commits, tags, fileIds, sidecars or catalog rows, and they never judge correctness from them. Implementation
numbers (`.git` bytes, loose objects, packs, refs, commits) are reported as diagnostics only.

## Running

From the repository root:

```sh
# Quick: every scenario at developer sizes (~10 min on an M-series laptop)
./gradlew :workspace-server:workspaceScale --args='profile=quick'

# Scale: every scenario at full size (hours, tens of GB of disk)
./gradlew :workspace-server:workspaceScale --args='profile=scale'

# The year-equivalent workspace only
./gradlew :workspace-server:workspaceScale --args='profile=scale scenarios=year'

# One scenario with sizes overridden, no source edits needed
./gradlew :workspace-server:workspaceScale --args='profile=quick scenarios=history-depth history.checkpoints=0,1000,5000,20000'
./gradlew :workspace-server:workspaceScale --args='profile=scale scenarios=storage-growth storage.kinds=binary-10MiB:binary:10485760:1000'
```

Arguments are `key=value`. Any key below can be overridden; the profile only supplies defaults.

| Key | Meaning |
| --- | --- |
| `profile` | `smoke`, `quick` (default) or `scale` |
| `scenarios` | comma-separated names, or `all` (default) |
| `jdbc` | Postgres admin JDBC URL for the revision catalog, e.g. `jdbc:postgresql://localhost:31024/postgres?user=postgres_user&password=postgres_user`. A scratch database `wsbench_<pid>` is created from the production DDL and dropped afterwards. Also read from `WSBENCH_JDBC`. Without it the adapter uses the in-memory test catalog, whose `list` scans every row; use Postgres for numbers you intend to compare. |
| `out` | output directory (default `workspace-server/build/scale-results/<timestamp>-<profile>`) |
| `root` | where workspaces are created (default `<out>/data`); deleted at the end unless `keep=true` |
| `seed` | random seed; same seed, same bytes, same operation sequence |
| `reps` | repetitions of each probed operation per checkpoint |
| `budgetMinutes` | soft per-scenario budget: an incremental scenario stops growing when exceeded and records where it got to. Hard kill at 3x. |
| `opCeilingSeconds` | a single operation slower than this counts as a failure |
| `verify.revisionSamples` | how many historical revisions' content verification reads back |

Scenario keys (quick / scale defaults are in `ScaleBench.PROFILES`):

| Scenario | Keys | What it does |
| --- | --- | --- |
| `tree-size` | `tree.files`, `tree.extraMiB` | One workspace per size, populated by a single import, then the probe. Sweeps file count, then content bytes (100 small files plus N untouched 1 MiB files) |
| `history-depth` | `history.files`, `history.checkpoints` | Saves round-robin over a few files; probe at each history depth |
| `revisions-one-file` | `revisions.one.checkpoints` | Save + revision on one file; probe and rebuild at each count |
| `revisions-spread` | `revisions.spread.files`, `revisions.spread.checkpoints` | Same totals round-robin over many files |
| `storage-growth` | `storage.kinds` (`name:text\|binary:bytes:saves,...`), `storage.maintenance` | One file churned per kind; disk at 10 checkpoints; then manual maintenance (JGit gc) and its effect |
| `concurrency` | `concurrency.writers`, `concurrency.ops` | W writers in one workspace: distinct files, then one file via read + If-Match |
| `independent-workspaces` | `independent.workspaces`, `independent.ops` | One writer in each of W workspaces at once |
| `remote` | `remote.files`, `remote.checkpoints`, `remote.revisionEvery` | Grow, publish, fresh clone, incremental sync of a follower, follower publishes back, origin merges |
| `year` | `year.*` | The year-equivalent workspace (below) |

### The probe

At each checkpoint the same user operations run against the current state (`reps` times each), reads first so that
after a restart the first sample is the cold one: `read`, `lastEdit`, `revision.list.matchingLatest` (state equals the
newest revision), `revision.preview.oldest/newest`, `save.existing`, `save.new`, `revision.list.unmatched` (state
matches no revision, the worst case for matching), `revision.create`, `revision.restore.oldest`, then stale-token save
and restore (must be refused and change nothing). Where supported, `rebuild` is timed too. "after restart (cold)" and
"(warm)" checkpoints follow a restart.

### The year-equivalent workspace

Defaults (scale): 3,000 initial files in directories of 50; 50,000 saves; 5,000 revisions; 30 hot files take 50% of
saves and 70% of revisions; files mostly 1-8 KiB text, 2% medium text (64 KiB-1 MiB), 0.3% 1 MiB binary; per
operation 0.3% moves, 0.2% deletes, 1% new files; restart every 10,000 saves; probe, rebuild, publish and follower sync
every 10,000 saves; at the end a probe on the most-revised file, restart, fresh clone, full verification and a manual
maintenance pass. It is a reproducible aged repository, not a model of any mission. Quick profile: 150 files, 1,000
saves, 100 revisions.

## Output

In `out`:

- `results.json`: environment (commit, dirty flag, JVM, OS, CPUs, memory, filesystem), adapter description, all
  parameters, and per scenario: status, ops (count, total, min, median, p95, p99 when n >= 100, max, ops/s, failures,
  and a 20-bucket latency series for population ops with n >= 200), checkpoints (position: files, saves, revisions,
  content bytes; per-op stats; storage diagnostics), results (throughput, storage per save, maintenance), notes and
  failed checks. Rewritten after every scenario, so an interrupted run keeps what finished.
- `raw-timings-us.json.gz`: every sample in microseconds, in order, for plotting.
- `summary.txt`: the console summary.

## Correctness

Checked through the driver only, as a user would observe it: every live file's bytes, deleted files absent, every
revised file's revision list complete and ordered, revision count, sampled historical revision content, restore makes
the revision current, stale saves/restores refused without change, state preserved across restart, rebuild, clone,
sync and maintenance, and for concurrent If-Match writers that every acknowledged save forms one chain ending at the
current state. Clones are compared by revision position, not id.

## Caveats

- "Restart" is in-process: services are rebuilt and JGit's caches cleared, but JIT and the OS page cache stay warm.
- Disk numbers are apparent file sizes, not allocated blocks.
- Postgres in Docker on macOS adds port-forwarding latency to catalog calls.
- Workloads never re-create a deleted path, so results don't depend on whether a future design gives a re-created
  path the old file's revisions.
