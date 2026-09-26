# derived_events refresh: baseline, storm, and staleness (#1890)

Measured 2026-09-25/26 on the `develop` schema (v4.4.0, commit 2a57122de). The harness is this directory
(see `README.md`). The raw output behind every number below is in `results/2026-09-25/`, one folder per run, named
as in the tables. No architecture changes were made; the only schema touch was temporary logging on
`merlin.refresh_derived_events_on_trigger()` (`instrument.sql`, since reverted).

**Environment:** local `pd` stack `eeperf`. PostgreSQL 16.14 with stock settings (`work_mem` 4MB,
`shared_buffers` 128MB). Gateway image `plandev-gateway:develop`. Docker VM with 16 CPU / 32 GB on an Apple Silicon
laptop. Absolute times will differ from production; the ratios and mechanisms are what carry over.

## Summary

1. **One upload runs 3 full refreshes in one transaction.** The order is derivation_group → external_source →
   external_event. At 1.5M derived rows each refresh takes about 10s, so an upload takes about 32s (up to 41s cold).
   The DG refresh is pure waste whenever the DG already exists: the Gateway's upsert is
   `ON CONFLICT DO NOTHING` (0 rows), but statement triggers fire anyway.
2. **Uploads serialize completely, regardless of derivation group.** The first refresh takes `ExclusiveLock` on
   the MV and holds it until commit. Upload k of N finishes at about k × 31s. 5 uploads to 5 different DGs queue
   exactly like 5 to one DG.
3. **The timeout that fires is the Gateway's 300s Node `fetch` timeout to Hasura, and it does not roll anything
   back.** With 12 concurrent uploads, 2 got HTTP 500 `fetch failed` at 302s. Both then committed 30–65s later with
   a correct MV. The client is told the upload failed; the data is in.
4. **Stale `derived_events` is reproducible, but not through timeouts or cancellation.** The cause is a snapshot
   race. A `REFRESH … CONCURRENTLY` that waits for the MV lock computes its new contents from a snapshot taken
   before the wait. When the transaction it waited on commits, this refresh overwrites that transaction's result.
   A transaction is exposed when its *only* (last) refresh is the one that waited. A DG create (as the UI does)
   racing an upload leaves the upload's committed events missing from the MV, and both sides report success.
   Uploads are protected only because their 2nd and 3rd refreshes re-read after the lock is held. **Removing the
   "redundant" refreshes would expose every concurrent upload.**
5. **Every timeout and cancellation mode tested gives rollback or a late commit, never a stale MV.** The refresh
   runs inside the ingest transaction, so "base rows committed but refresh failed" (Scenario 1) cannot happen with
   this trigger design.
6. **Rule 4 is broken independently of refresh.** `DISTINCT ON (event_key, derivation_group_name)` has no ORDER BY
   at its own level, so the version it keeps is arbitrary. On Dataset B, 38,564 of 45,000 reused keys kept an
   older revision. A new DB test fails deterministically, and the one-line fix makes it pass.
7. **Refresh cost is linear in derived rows and barely depends on the number of revisions.** CONCURRENTLY costs
   about 3.5× the SELECT itself. Most of that goes to Postgres's duplicate check and diff over the whole new result,
   with 1.9 GB of temp spill per refresh at 1.5M rows.

## A. Reproduction

```sh
# start a stack (any compose stack works; defaults target pd stack "eeperf": PG_CONTAINER/GATEWAY override)
cd deployment && docker compose up -d
cd ../load-tests/derived-events && source env.sh
psqlx < instrument.sql                                                                  # temp logging + knobs
psqlx -v dgs=15 -v sources=1  -v events=100000 -v overlap=0   -v reuse=0   < seed.sql   # Dataset A (~40s)
bash refresh-benchmark.sh A-1.5M 5 --cold                                               # baseline refresh
bash storm.sh 1 same single                                                             # one Gateway upload
SEQUENTIAL=1 bash storm.sh 5 same seq5; bash storm.sh 5 same same5; bash storm.sh 5 multi multi5
bash storm.sh 12 same same12                                                            # crosses the 300s fetch timeout
bash snapshot-race.sh 5                                                                 # stale MV repro
bash snapshot-race.sh 3 "insert into merlin.derivation_group (name, source_type_name) values ('x', 'PerfSource')"
for c in A B1 B2 C1 C2 D; do bash cancel-repro.sh $c; done                              # timeouts / cancels
psqlx < verify.sql                                                                      # correctness check
psqlx -v dgs=15 -v sources=10 -v events=10000 -v overlap=0.5 -v reuse=0.3 < seed.sql   # Dataset B
bash sweep.sh 3                                                                         # scaling
psqlx < uninstrument.sql
```

`verify.sql` compares the MV against a fresh run of its own definition, taken from `pg_get_viewdef`, using a two-way
`EXCEPT ALL`. It reports full-row diffs, key-only diffs, and Rule 4 violations. Every scenario below ends with it.
The README in the harness has details.

## B. Baseline refresh

| Dataset | Events | Sources | DGs | Derived rows | CONCURRENTLY: cold-ish / median (range), n=5 | Plain REFRESH median | SELECT only |
|---|---:|---:|---:|---:|---:|---:|---:|
| A (1 src/DG, no overlap) | 1,500,000 | 15 | 15 | 1,500,000 | 17.8s / **9.3s** (8.4–17.8) | 4.4s (3.6–6.0) | 2.6s |
| B (10 rev/DG, 50% overlap, 30% key reuse) | 1,500,000 | 150 | 15 | 420,000 | 3.9s / **3.8s** (3.5–3.9)* | 2.6s | 1.9s |

\* Measured before `seed.sql` vacuumed away Dataset A's dead tuples. The events table was bloated to 751 MB. After
vacuum, the sweep measured B at 3.2s concurrent / 2.0s plain.

"Cold-ish" means after a Postgres restart, so `shared_buffers` is empty but the VM page cache is still warm.
"SELECT only" is `EXPLAIN (ANALYZE, TIMING OFF)` of the view definition.

- **Sizes, Dataset A:** MV 273 MB heap / 370 MB total, `external_event` 377 MB, `external_source` 48 kB.
- **Per concurrent refresh, Dataset A:** 1.88 GB temp written and 53 kB WAL (no changes). A plain refresh writes
  613 MB temp and 417 MB WAL, since it rewrites everything.
- **Resources:** the container peaked at about 124% CPU, meaning one backend is mostly single-core bound, and
  136 MB RSS. The backend spends much of its time in `IO:BufFileRead/Write` (temp files).

### Scaling (`sweep.sh`, 3 runs per point, medians)

| Shape | Events | Derived | CONCURRENTLY | Plain | SELECT only |
|---|---:|---:|---:|---:|---:|
| A 15×1×6,667 | 100k | 100k | 0.62s | 0.35s | 0.16s |
| A 15×1×33,333 | 500k | 500k | 2.78s | 1.34s | 0.83s |
| A 15×1×66,667 | 1.0M | 1.0M | 5.30s | 2.46s | 1.67s |
| A 15×1×100,000 | 1.5M | 1.5M | 8.13s | 4.00s | 2.54s |
| B 15×1×100,000 | 1.5M | 1.5M | 8.06s | 3.51s | 2.38s |
| B 15×5×20,000 | 1.5M | 540k | 3.77s | 2.28s | 1.66s |
| B 15×10×10,000 | 1.5M | 420k | 3.22s | 2.01s | 1.92s |
| B 15×20×5,000 | 1.5M | 360k | 3.79s | 2.34s | 1.95s |

- **Event count:** cost scales linearly, about 5.4 µs per derived row for CONCURRENTLY.
- **Revision count, at a fixed 1.5M events:** it doesn't drive the cost. The source self-join and
  `subtract_later_ranges` take 13 ms for 150 sources. What remains is the event scan plus sorting the candidate
  rows, which is about 2s regardless. CONCURRENTLY then adds diff cost proportional to the derived rows.
- **What this does not measure:** hundreds of revisions per DG. The source self-join is O(sources² per DG), and
  it is not visible at 20 per DG.

## C. Query plan: where the time goes

**The view's SELECT** (Dataset A, 3.4s with timing on; `plan.txt` in the bench folder):

| Step | Rows | Cost |
|---|---:|---|
| Seq scan of `external_event` | 1.5M | ~260 ms |
| Hash join to the 15-row per-source range CTE (`@>` join filter) | 1.5M | ~550 ms cumulative |
| Sort by `valid_at DESC` (the subquery's ORDER BY) | 1.5M | external merge, 261 MB spill, ~750 ms |
| Sort by `(event_key, derivation_group_name)` for DISTINCT ON | 1.5M | external merge, 249 MB spill, ~1.8 s |
| Unique | 1.5M | ~130 ms |

- The planner estimates the join at **100 rows versus 1.5M actual**, because the `@>` on a function result is
  unestimable. So it plans small in-memory sorts that then spill.
- The first sort is wasted work: the DISTINCT ON sort above it re-sorts everything and discards the order. This is
  also the Rule 4 bug (section G).
- The source side is negligible: 15 sources in under 1 ms (A), 150 sources in 13 ms (B).
- **Dataset B:** 1.5M events → 825k candidates (675k removed by the range filter) → 420k derived. The same two
  external sorts, at about 135 MB each.

**What CONCURRENTLY adds.** `auto_explain` on its internal queries (Dataset A, one 10.5s refresh):

| Phase | Time | Temp |
|---|---:|---:|
| Run the view and fill a temp table | 3.5s | ~1 GB r/w |
| Duplicate-row check on the new data (self-join of newdata on whole-row `*=`, two 293 MB sorts) | **4.3s** | ~1.2 GB |
| Full hash join of the old MV against newdata to build the diff | 2.5s | ~0.7 GB |
| Apply the diff (0 rows for a no-op refresh) | ~0 | |

So about 2/3 of every trigger refresh is spent proving that nothing, or almost nothing, changed.

## D. Refresh amplification (Dataset A, real Gateway `/uploadExternalSource` of a 3-event source)

One upload into an existing DG, from `storm-single-A`. Everything is the same pid (40) and the same tx (878):

| Trigger | START | END | Duration |
|---|---|---|---:|
| derivation_group INSERT (0 rows, ON CONFLICT DO NOTHING) | 23:41:25.604 | 23:41:37.346 | 11.7s |
| external_source INSERT | 23:41:37.347 | 23:41:49.750 | 12.4s |
| external_event INSERT (3 rows) | 23:41:49.752 | 23:42:06.037 | 16.3s |

HTTP was 40.7s (first run after re-seed); a warm repeat was 31.9s (3 × ~10.5s). On Dataset B, one upload took
9.75s (3 refreshes, 9.66s total). A brand-new DG gives the same 3 refreshes; the DG refresh just isn't a no-op
then. The Gateway's reply confirms the DG upsert changed nothing: `"upsertDerivationGroup": null`.

| Scenario (Dataset A) | Files | Refresh calls | Refresh work* | Time in refresh fn incl. lock wait | Request wall time |
|---|---:|---:|---:|---:|---:|
| Single source | 1 | 3 | 31.7–40.4s | same | 31.9–40.7s |
| 5 sequential | 5 | 15 | 152s | 152s | 153s (28.6–33.3s each) |
| 5 concurrent, same DG | 5 | 15 | ~157s | 475s | 32 → 157s (last) |
| 5 concurrent, different DGs | 5 | 15 | ~173s | 482s | 30 → 173s (last) |
| 10 concurrent, same DG | 10 | 30 | ~290s | 1,618s | 32 → 290s |
| 12 concurrent, same DG | 12 | 36 | ~365s | — | 31 → 300s, then 2 × HTTP 500 at 302s |

\* Refresh work equals wall time here because the refreshes run strictly one after another.

## E. Storm behaviour

| | 5 same DG | 5 different DGs | 10 same DG | 12 same DG |
|---|---|---|---|---|
| Requests started / HTTP 200 / failed | 5 / 5 / 0 | 5 / 5 / 0 | 10 / 10 / 0 | 12 / 10 / 2 (500 `fetch failed`) |
| Refreshes started / completed | 15 / 15 | 15 / 15 | 30 / 30 | 36 / 36 (the 2 "failed" still committed) |
| Max backends waiting on the MV lock | 4 | 4 | 10 | 11 |
| Total duration | 157s | 173s | 290s | 302s client, ~365s DB |
| MV matches recompute afterwards | yes | yes | yes | yes |

**Lock behaviour**, from 250ms samples of `pg_stat_activity`/`pg_locks` and `log_lock_waits`:
- All N transactions start together. Each runs its DG upsert, and its first refresh requests `ExclusiveLock` on
  `merlin.derived_events`.
- One holder proceeds. Every other backend sits in `wait_event = Lock:relation`,
  `ExclusiveLock (WAITING)`, and `pg_blocking_pids` points at the holder. Postgres logs the queue, e.g.
  `Process holding the lock: 624. Wait queue: 488, 625, 566, …`.
- The holder keeps the lock through all 3 of its refreshes and releases it only at commit. The next queued
  transaction then runs its 3 refreshes.
- Completion order is lock-queue order, not request order.
- **Different DGs make no difference:** there is one lock and one global refresh. Per-DG isolation would remove
  this queue entirely.
- Waiting transactions hold open Hasura/Postgres connections the whole time: 12 concurrent uploads used 12
  connections for 5+ minutes.

## F. Correctness: can base data be committed while `derived_events` disagrees with a fresh recompute?

**Yes.** It is reproduced deterministically, but by a lock/snapshot race, not by the timeout/cancellation path the
issue describes.

### The mechanism (`snapshot-race.sh`)

In READ COMMITTED, the `REFRESH` command inside the trigger function takes its snapshot when the statement starts.
That is *before* it blocks on the MV's `ExclusiveLock`. `CONCURRENTLY` computes the new contents with that old
snapshot, then diffs them against the *current* MV contents, which a newer committed refresh has written. The
result is that the waiting refresh "corrects" the MV back to the state before the transaction it waited on.

Run on Dataset A, with no timeouts, errors, or artificial delay:

| | |
|---|---|
| Session A (tx 904) | Inserts source `race-…` (3 events, perf-dg-02, newest valid_at). 2 refreshes, holds the MV lock, sleeps 5s, commits at 23:48:29. |
| Session B (tx 905) | At 23:48:04, one unrelated statement: an attribute-only UPDATE of a perf-dg-03 source, so exactly 1 refresh. It waits 24.5s for the lock (`acquired ExclusiveLock … after 24509 ms`), refreshes, commits. |
| Both sessions | Report success. |
| Afterwards | `race-…` has 3 events committed, 3 expected in the MV, **0 actually in the MV**. The 60 seeded perf-dg-02 events the race source should have superseded are back. `verify.sql`: key_missing 3, key_extra 60; row diffs about 100k, because `source_range` of the superseded source is stale too. |

The same happens when session B is **`insert into merlin.derivation_group …`**, which is exactly the UI's
CREATE_DERIVATION_GROUP mutation. It is 1 refresh, and it wiped the concurrent upload's 3 events.

**Not exposed:**
- **A Gateway upload:** its 2nd and 3rd refreshes run after the lock is held, with fresh snapshots, so they repair
  the stale 1st. Every storm above ended consistent.
- **A source delete (UI DELETE_EXTERNAL_SOURCES):** 2 refreshes (external_source, then the cascaded
  external_event). Tested: consistent.

**Exposed:** any write to the 3 tables that fires exactly one refresh:
- UI create DG
- UI delete DG (DGs must be empty to delete)
- deleting events directly
- a single-statement update of a source, event, or DG

The stale state lasts until the next refresh from any write in any DG, which quietly repairs it. That makes it
intermittent and hard to catch in production.

**Mitigation tested, not implemented.** Adding `perform pg_advisory_xact_lock(<const>);` right before the
`REFRESH` in the trigger function makes the wait happen in a separate statement, so REFRESH snapshots afterwards.
Both race writers (attribute update, DG create) stayed consistent with it. `LOCK TABLE` does not work on
materialized views in PG16.

### Timeout and cancellation matrix (Dataset A unless noted; `cancel-repro.sh`, `storm.sh 12`)

| Case | What was cancelled / timed out | Client saw | Base source/events | MV matches recompute | Scenario |
|---|---|---|---|---|---|
| A | curl `--max-time 5` on an upload | curl exit 28 at 5s | **committed** ~27s later | yes | 3 |
| Gateway timeout | 12 concurrent; the Gateway's Node fetch to Hasura hit its 300s headers timeout | HTTP 500 `fetch failed` at 302s (2 requests) | **committed** at ~334s and ~365s | yes | 3 |
| B1 | `statement_timeout 2s` on a session whose refresh runs >2s | ERROR canceling statement (in the REFRESH) | rolled back | yes | 2 |
| B2 | `statement_timeout 5s` while waiting for another tx's MV lock | ERROR, rolled back | rolled back | yes | 2 |
| C1 | `pg_cancel_backend` on the upload holding the lock, mid-refresh | **HTTP 200** with a GraphQL `errors` body | rolled back | yes | 2 |
| C2 | `pg_cancel_backend` on an upload waiting for the lock | HTTP 200 + `errors`; the other upload succeeded | cancelled one rolled back, the other committed | yes | 2 |
| D | 5-upload storm: cancelled the 2nd tx's lock holder, then one waiter | 3 × 200 OK, 2 × 200 + `errors` | 2 rolled back, 3 committed | yes | 2 |

**Nothing in the stack cancels the SQL when the client goes away.** When curl gives up, or the Gateway's own fetch
times out, Gateway, Hasura, and Postgres carry on and commit.

**What the issue likely saw.** Its "timeout → stale" report fits a combination of three things observed here:
1. Uploads that "failed" at the 300s Gateway timeout but committed later, so the data appears late.
2. MV contents lagging the upload queue by k × refresh time during a storm.
3. The snapshot race whenever a single-refresh write overlaps an upload.

Production Gateway logs (`fetch failed` on `/uploadExternalSource`) and whether anyone created or deleted DGs
during bulk ingests would show which of these happened. **We never observed Scenario 1** (refresh fails after base
rows commit): with in-transaction triggers it can't occur.

### Error-reporting gaps found along the way

- A Hasura/Postgres error, including cancellation, comes back as **HTTP 200** with an `errors` body
  (`res.json(HasuraError)` in the Gateway).
- The Gateway's 500 on fetch timeout says nothing about whether the upload committed. A client retry of the same
  file then fails on the duplicate key.

## Rule 4 / DISTINCT ON (independent bug)

The view relies on a subquery's `ORDER BY valid_at DESC` surviving into the outer `DISTINCT ON`. The plan re-sorts
on `(event_key, derivation_group_name)` only, and Postgres sorts are not stable. Which duplicate survives is
therefore deterministic for a given input and plan, but arbitrary.

- **Dataset B:** 38,564 of 45,000 reused key/DG pairs kept an older revision. Example: `k0` in perf-dg-06 kept
  revision 003 of 10.
- **Empty DB, `distinct-on.sql`:** 2 revisions × 10 keys gives 4 wrong; 2 × 3 gives 0 wrong. Postgres's qsort
  switches to insertion sort (stable) below 7 elements, which is likely why the existing single-key `rule4` test
  passes.
- **New test:** `ExternalEventTests.DerivedEventRuleTests.rule4_manyDuplicateKeys` (3 non-overlapping sources ×
  200 shared keys). It fails 5/5 runs identically (145/200 wrong).
- **Fix, verified but not applied:** append `order by event_key, derivation_group_name, valid_at desc` to the outer
  SELECT. With it, all 22 ExternalEventTests pass. It also removes the wasted inner sort, so it should be a small
  performance win too.

## One-line improvements noticed (documented, not implemented)

1. Outer `ORDER BY` for Rule 4 (above). Correctness bug.
2. `pg_advisory_xact_lock` before `REFRESH` in the trigger function. Closes the stale-snapshot race; tested.
   Anyone removing redundant refreshes needs this first.
3. Skip the DG refresh when the upsert changed nothing: a statement trigger with `REFERENCING NEW TABLE` that
   returns early when it is empty, or no DG upsert in the Gateway when the DG exists. Saves 1/3 of upload refresh
   time. Only safe together with #2.
4. The Gateway should return non-200 for GraphQL `errors` bodies, and its 500 on fetch timeout should say
   "may still commit".

## G. Harness

This directory:

| File | Purpose |
|---|---|
| `env.sh` | Connection, token, and upload helpers |
| `instrument.sql`, `uninstrument.sql` | Temporary logging and knobs, and their removal |
| `seed.sql` | Parameterized dataset |
| `verify.sql` | Staleness check |
| `distinct-on.sql` | Rule 4 repro |
| `refresh-benchmark.sh`, `sweep.sh` | Refresh timing and scaling |
| `gen-sources.sh`, `storm.sh` | Tiny source files and Gateway storms |
| `snapshot-race.sh` | Stale-MV repro |
| `cancel-repro.sh` | Timeout and cancellation cases |
| `README.md` | Commands |

**Caveats:**
- `results/2026-09-25/storm-same-A-12/verify.txt` was captured before the 2 timed-out transactions finished; the
  harness's idle check has since been fixed. The final state described in section F was checked by hand afterwards:
  both sources committed, and the MV matched a fresh recompute.
- During the early storm runs, a harness bug leaked idle 250ms sampler sessions (up to 11), since fixed. Per-upload
  refresh time stayed around 31s throughout, and the standalone benchmarks ran before any leak.
- Production-shaped Dataset C was not built; there are no LRO numbers yet. It is one `seed.sql` invocation once
  someone supplies sources/DG, events/source, and overlap.
