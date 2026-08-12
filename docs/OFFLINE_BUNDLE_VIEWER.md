# Aerie Offline Bundle Viewer

**Read-only plan and simulation viewing with no backend.** A user uploads one JSON file and the real Aerie timeline renders it — no Hasura, no gateway, no Postgres, no mission model, no login.

Status: working end to end. Verified against the `foo` mission model and against a real NISAR TOL file. Not committed; no upstream issue filed.

- **Repos touched:** `plandev-ui` (the viewer), `aerie` (a reference producer + a format converter)
- **Verification:** 940 UI tests, 26 backend tests, 29 converter tests, 0 TypeScript errors, timeline confirmed rendering in-browser

---

## Contents

1. [The problem](#1-the-problem)
2. [What already existed](#2-what-already-existed)
3. [The governing insight](#3-the-governing-insight)
4. [The complication](#4-the-complication-that-changed-the-design)
5. [The seams](#5-the-seams)
6. [The bundle format](#6-the-bundle-format)
7. [Producers](#7-producers)
8. [Verification](#8-verification)
9. [Limitations](#9-limitations)
10. [Path to a live database backend](#10-path-to-a-live-database-backend)
11. [Future work](#11-future-work)
12. [Footprint](#12-footprint)

---

## 1. The problem

Aerie's UI is coupled to Aerie's backend: a plan view requires Hasura, Postgres, the gateway for auth, and a compiled mission model. Other simulation engines produce activity timelines and resource profiles that would be valuable to inspect in that UI, but there is no way in — short of standing up the entire stack and importing data through it.

The goal is a second, looser mode alongside the existing tightly-coupled one: **upload a file, see activities and simulation results.** Read-only. Single file. Intended to merge upstream, so the change must be minimal and idiomatic rather than a fork.

---

## 2. What already existed

Most of this was already in the repo, which shaped everything that follows.

**`stateless-aerie`** is a CLI that simulates a plan with no server, no Postgres, and no Hasura — just a plan JSON plus a mission-model JAR, driving the same `merlin-driver` core as `merlin-server`. It is undocumented outside its own source, but actively maintained and published to Maven.

**Two documented JSON formats** already ship:

| Artifact | Producer | Fixture |
|---|---|---|
| Plan + activity directives | Gateway export; parsed by `PlanJsonParser.java:36` | `stateless-aerie/src/test/resources/simpleFooPlan.json` |
| Spans + resource profiles + events | `SimulationResultsWriter.java` (schema documented in-file at lines 349-433) | `stateless-aerie/src/test/resources/simpleFooPlanResults.json` |

**`addExternalDataset`** is an existing first-class Hasura action for ingesting non-Aerie resource profiles into a live plan — evidence that "view someone else's data" is already a sanctioned use case, just one that currently requires the full stack.

So the format question was largely settled before any code was written. The open question was how to make the UI *read* these files.

---

## 3. The governing insight

Three properties of the existing UI make this tractable:

1. **The read path never touches the `simulate` action.** `simulate` only triggers a run and returns status; results are read back from the plain `span` / `profile` / `profile_segment` tables. Viewing results has no dependency on the action layer at all.
2. **Resource sampling is already pure client-side math.** `sampleProfiles` (`src/utilities/resources.ts:12`) turns `Profile[]` into plottable `Resource[]` with zero network calls.
3. **The default timeline view is generated client-side.** `generateDefaultView(resourceTypes, externalEventTypes)` (`src/utilities/view.ts:26`) builds the entire row/layer tree from in-memory arrays, so a bundle need not carry a view at all.

Taken together: **this is a data-hydration problem, not a protocol problem.** No GraphQL needs to be reimplemented; the stores just need to be filled from a different source.

That conclusion held — but only after one significant correction.

---

## 4. The complication that changed the design

The initial plan assumed that because components only ever *read* Svelte stores, hydrating those stores would be sufficient. That was wrong.

`gqlSubscribable.subscribe()` (`src/stores/subscribable.ts:233`) unconditionally calls `debouncedClientSubscribe()` → `getSharedClient()` whenever `browser` is true. `updateValue()` pushes a value to *already-subscribed* listeners, but the moment any component references `$plan`, `$simulationDataset`, or `$planModelActivityTypes`, Svelte auto-subscribes and a live WebSocket connection is attempted against a backend that isn't there.

A first implementation pass hit this and worked around it by hand-rolling a substitute timeline — a plan summary, a CSS bar list, and a resource table. That is not the deliverable: the point is seeing data in the *real* Aerie UI.

The correct fix turned out smaller than the workaround. `gqlSubscribable` is the **single chokepoint** for every live query in the application. Guarding it once makes every subscription inert offline, which unlocks the genuine `TimelinePanel` with no changes to any component.

This is the load-bearing architectural fact of the whole change, and the one most worth reviewing carefully.

---

## 5. The seams

Five touch points in existing files. Each gains a guard; none is rewritten.

### 5.1 Auth and routing — 3 files, 8 lines

| File | Change |
|---|---|
| `src/hooks.server.ts:19` | `/offline` returns early, before the JWT/SSO branch |
| `src/routes/+layout.server.ts:7` | `/offline` excluded from `shouldRedirectToLogin` |
| `src/routes/offline/+page.ts` | `export const ssr = false` |

Both bypasses follow precedent already in those files: the Chrome-devtools short-circuit and the existing `login` substring check.

Two notes for reviewers:

- **`+layout.server.ts` is easy to miss.** Bypassing `hooks.server.ts` alone still leaves the route redirecting to `/login`.
- **No adapter change is required.** `adapter-node` serves a client-only route alongside SSR routes; `adapter-static` is not needed.

### 5.2 Read-only mode — 1 file, ~6 lines

`src/stores/plan.ts:21` adds `planReadOnlyOffline` and ORs it into the existing derived store at lines 24-26:

```ts
export const planReadOnly: Readable<boolean> = derived(
  [planReadOnlySnapshot, planReadOnlyMergeRequest, planReadOnlyOffline],
  ([$snapshot, $merge, $offline]) => $merge || $snapshot || $offline,
);
```

This is the most important reuse in the change. `planReadOnly` already exists for snapshot-browsing and merge-review, and is already wired throughout the plan page — every actionable control ANDs against `!$planReadOnly`. Offline viewing is structurally identical, so no new gating concept was invented and no permission audit was needed beyond confirming the existing one applies.

A secondary effect reinforces it: `hasNoAuthorization` treats an empty-but-non-null `permissibleQueries` as "no authorization", so a minimal synthetic user causes every `featurePermissions.*.can*()` check to return false naturally.

### 5.3 The subscription gate — 1 file, 4 guards

| Location | Behavior when offline |
|---|---|
| `subscribable.ts:91` | `clientSubscribe()` returns early |
| `subscribable.ts:174` | `resubscribe()` returns early |
| `subscribable.ts:185` | `restartSocket()` settles loading, returns |
| `subscribable.ts:233` | `subscribe()` skips activation, still serves the current value |

`subscribe()` still adds the subscriber and calls `next(value)`, and settles `loading` to `false` so spinners do not hang forever.

**Import-cycle avoidance.** `subscribable.ts` must not import `stores/offline.ts`, because `offline.ts` → `stores/simulation.ts` → `subscribable.ts` closes a loop. A dependency-free leaf module, `src/stores/offlineFlag.ts` (20 lines), holds a plain module-level boolean set by `setOfflineBundle` / `clearOfflineBundle`.

### 5.4 Resource delivery — 1 file

`createProfileSubscription` (`src/stores/profile.ts:37`) is not a WebSocket subscription; it is a windowed pull driven by `simulationDataset` ticks. An early branch serves from an in-memory map instead, matching the live path's `TimelineResourceState` shape (`{error, loading, resource}`) and preserving the `acquireTimelineResource` / `releaseTimelineResource` refcounting that the file's own comments warn about.

### 5.5 Store hydration — new code only

The rest is ordinary hydration in `src/routes/offline/+page.svelte`: `gqlSubscribable`s via `updateValue()`, plain writables via `.set()`, then `initializeView()`. The timeline components (`TimelinePanel` → `Timeline` → `Row` → layer components) are untouched and cannot distinguish the source.

---

## 6. The bundle format

One versioned envelope: the existing plan JSON, plus the existing results JSON, plus one new section.

```jsonc
{
  "bundleVersion": "1.0.0",
  "plan": { "name": "...", "startTime": "2024-07-01T00:00:00Z", "duration": "24:00:00" },
  "activityTypes": [ { "name": "...", "parameters": {}, "requiredParameters": [] } ],
  "activityDirectives": [
    { "id": 4, "type": "...", "startOffset": "02:27:15.059", "arguments": {} }
  ],
  "simulation": {
    "simulationStartTime": "...",
    "simulationEndTime": "...",
    "spans": [
      { "id": 4, "type": "...", "parentId": null, "directiveId": 4,
        "startOffset": "+02:27:15.059000", "duration": "+00:00:02.000000" }
    ],
    "resources": [
      { "name": "/counter", "type": "discrete", "schema": { "type": "int" },
        "segments": [ { "extent": "+00:16:40.000000", "dynamics": 0 } ] }
    ]
  }
  // "view" is optional
}
```

Normative schema: `plandev-ui/src/schemas/offline-bundle-schema-v1.json` (148 lines). A checked-in copy lives at `stateless-aerie/src/test/resources/offline-bundle-schema-v1.json` so the backend test does not reach into the UI repo.

### 6.1 `activityTypes` is the only genuinely new data

Activity-type parameter schemas exist in **neither** pre-existing file. The nearest proxy — `topics["ActivityType.Input.<Type>"].schema` — supplies parameter shape but not `required_parameters` or computed-attribute schemas. Because these are static per type rather than per run, a producer emits them once and reuses them across runs.

### 6.2 Design principle: the loader owns every transform

A foreign engine should only have to emit what it naturally has. All reshaping happens in `src/utilities/offline-bundle.ts` (459 lines).

**Segment encoding is the sharpest edge in the format.** Producers emit `extent` — each segment's *own* duration, a delta. The UI's `sampleProfiles` reads cumulative `start_offset` values and infers each segment's end from the *next* segment's offset. These are different quantities, not a renaming:

```
bundle:   extent       1000s     1000s     1000s      400s
                    ├─────────┼─────────┼─────────┼─────────┤
UI:  start_offset      0        1000      2000      3000      duration = 3400
```

Getting this wrong yields a plausible-looking staircase that terminates at the wrong time, with nothing visibly broken. The prefix sum therefore lives in exactly one function, and three tests pin it — including the sampled end-point, not just the intermediate offsets.

**Remaining transforms:**

| Concern | Handling |
|---|---|
| `Span.attributes` | Writer emits `directiveId` / `arguments` / `attributes` flat; the UI expects them nested under `attributes` |
| `childIds` | Dropped — `createSpanUtilityMaps` (`activities.ts:62`) rederives hierarchy from `parent_id` alone |
| Time rebasing | Spans and profiles are simulation-start-relative; rebased onto plan start when the two differ |
| `startMs` / `endMs` / `durationMs` | Derived by the loader |
| Durations accepted | Aerie signed `+HH:MM:SS.ffffff`, Postgres `HH:MM:SS.fff`, ISO-8601 `PT2H27M15.059S`, or integer microseconds |
| Timestamps accepted | ISO-8601 and Aerie day-of-year (`2024-183T00:00:00`) |
| Hours | Never rolled into days — 30 hours is `30:00:00` |
| snake_case aliases | Accepted, so an unmodified `plan.json` pastes in — with an opaque-key guard so an activity argument genuinely named `start_time` is not rewritten |

### 6.3 The synthetic `SimulationDataset`

Fabricated with `status: 'success'`, `canceled: false`, and `dataset_id === OFFLINE_DATASET_ID (0)`, shared by every span and profile.

These values are load-bearing. `profile.ts:154-166` uses the status to decide the simulation has settled, which is what closes the final profile segment at `duration` rather than leaving it open-ended. A mismatched `dataset_id` silently yields "resource not found" for every resource on the timeline.

---

## 7. Producers

### 7.1 `stateless-aerie --bundle` — Aerie as its own reference producer

`BundleWriter`, plus an extracted `ResourceSegmentJsonWriter` so `extent` semantics are defined once and shared with `SimulationResultsWriter`. Exposed as `-b` / `--bundle` on the existing `simulate` subcommand rather than a new subcommand: it is an alternate output format of the same simulation, sharing every input and the same `-f`/stdout convention.

**Spike result:** `MissionModel.getDirectiveTypes()` does expose parameter schemas, ordering, and computed-attribute schemas, so `activityTypes` is sourced from the model rather than scraped from topics.

### 7.2 `contrib/tol2bundle/` — TOL XML → bundle

Python 3, standard library only, streaming via `iterparse` with element clearing.

| TOL construct | Bundle mapping |
|---|---|
| `ResourceSpec` `Name` + `Index level=N` | Flattened into one `/`-joined resource name |
| `DataType` boolean / integer / float / string | ValueSchema boolean / int / real / string |
| `Interpolation: constant` | `discrete`; dynamics is the raw value |
| `Interpolation: linear` | `real`; `rate = Δvalue / Δseconds` |
| `RES_VAL` sequence | Segments; `extent` = next timestamp − this timestamp |
| `RES_FINAL_VAL` | Closes the final segment |
| `ACT_START` + `ACT_END`, matched by UUID | One `activityDirective` and one `span`, linked by `directiveId` |
| `<Parent>` UUID | `span.parentId`, via a stable UUID→int allocator |
| `ERROR` / `RELEASE` | No equivalent — counted and reported, never silently dropped |

Note that `ACT_END` records carry only `<TimeStamp>` and `<ActivityID>`, not an `<Instance>`.

Measured against `nisarbb.tol.xml` — 167 MB, 68,532 activity pairs, 132,703 `RES_VAL`, 872 resources, spanning 2023-029 to 2024-211:

```bash
python3 tol2bundle.py nisarbb.tol.xml -o slice.json \
  --start 2024-060T08:00:00 --end 2024-060T16:00:00 -v
```

3.7 s wall time, **~30 MB peak RSS** (flat regardless of input size, confirming the streaming works), 314 KB output, 254 activities across 12 types, 42 resources, 326 segments.

---

## 8. Verification

| Check | Result |
|---|---|
| UI unit tests | **940 passing**, 72 files (69 new; baseline 911) |
| UI typecheck | 0 errors project-wide |
| UI lint | clean |
| Backend tests | 26 passing (forced `--rerun-tasks`, not cached) |
| Converter tests | 29 passing |
| Real NISAR slice through the real TS loader | passes — 254 directives, 42 profiles, all samples finite and within plan bounds |
| Browser | timeline renders |

Test distribution: 42 loader, 9 offline store, 8 subscription gating, 7 route data, 3 component mount.

The most valuable tests run converter output through the UI's *own* consumers — `sampleProfiles` and `createSpanUtilityMaps` — rather than through assumptions about them. The end-to-end check that mattered most was real NISAR data, converted by a tool that knows nothing about the loader, parsed and sampled by the actual production functions.

---

## 9. Limitations

### Scope

- Read-only. No editing, no re-simulation, no scheduling.
- External events and sources are out of scope; hydrated as empty so those rows render blank rather than erroring.
- No constraint violations in the bundle.

### Data fidelity

- **`requiredParameters` is always empty.** Pre-existing behavior: `merlin-framework-processor`'s `NoneDefinedMethodMaker` never overrides `getParametersWithDefaults()`, so every parameter appears to have a default. Not introduced here, and not fixable from the bundle side — topics carry no required-parameter information either.
- `BundleWriter` omits `plan.id`, `plan.modelId`, and directive `name` / `metadata` / `tags`, because `PlanJsonParser` drops them. All are optional in the schema.
- TOL `Units` / `Maximum` / `Minimum` have no schema slot; mission-specific typed values such as `YawValue` degrade to opaque strings.
- TOL `linear` interpolation converts sampled values into `{initial, rate}` — exact at sample points, an approximation between them.

### Unverified

- **The `linear` and `float` TOL paths have never run against real data.** All 872 resources in `nisarbb.tol.xml` use `constant` interpolation with boolean/integer/string/duration types. Coverage is synthetic-fixture only.
- **There is no differential test against a live backend.** Rendering the same plan both ways and comparing is the single most valuable missing check.
- The component test tolerates jsdom's missing canvas, so it proves the component tree mounts and stores hydrate — not that the timeline draws correctly.
- `BundleWriterTest` is a single `@Test` with 28 assertions; it should be split before upstreaming.

### Scale

- `--max-activities` caps activities but not resources, so wide windows stay large — a 70-day span produced 5.4 MB. Full conversion of the NISAR file was not attempted and would run to hundreds of MB.
- No downsampling or virtualization for dense profiles.

### A bug worth recording

`view` is an optional free-form object, so a producer emitting `view: {}` is schema-valid. That produced a `View` whose `definition.plan` was undefined and crashed `TimelinePanel.svelte:74` at `$view?.definition.plan.timelines` — the optional chain guards a *null* view but not an *empty* one. Fixed by degrading any unusable view to `generateDefaultView`, with three regression cases.

The schema was deliberately left permissive rather than requiring `plan` inside `view`. For a format aimed at third-party producers, graceful degradation beats a hard parse failure.

---

## 10. Path to a live database backend

Everything in this section was researched directly against the backend and UI source and verified against the code, not inferred. Where an earlier draft of this document guessed wrong, the correction is called out explicitly.

The headline result: **far more of this already exists than expected.** External resource profiles have a complete, working, end-to-end ingestion path today — server action, database tables, UI subscription, and an upload button. The gaps are narrower and more specific than "build an import feature".

### 10.1 The format already matches Aerie's internal contract

The critical question was whether the external-dataset wire format's segment `duration` is a per-segment *delta* or a *cumulative* offset, because the bundle's `extent` is a delta while `profile_segment.start_offset` on the read side is cumulative.

**Answer: it is a delta, and merlin-server performs the prefix-sum on write.** `PostProfileSegmentsAction.java:36-54`:

```java
// Each profile segment's duration part is the duration for which the dynamics hold
// before the next one begins. Since order in the database is not guaranteed
// we need to convert to offsets from the simulation start so order can be preserved
var accumulatedOffset = Duration.ZERO;
for (final var pair : segments) {
  final var duration = pair.extent();
  ...
  PreparedStatements.setDuration(this.statement, 3, accumulatedOffset);
  this.statement.addBatch();
  accumulatedOffset = Duration.add(accumulatedOffset, duration);
}
```

Note that Aerie's own code calls the delta **`extent()`** — the same term the bundle format independently adopted. `AppendProfileSegmentsAction.java` runs the identical loop for `extendExternalDataset`, seeded with the profile's existing accumulated duration.

Confirmed empirically in `e2e-tests/.../ExternalDatasetsTest.java`: five segments of `duration = 3600000000` (1 hour, microseconds) read back as `start_offset` values of `00:00:00, 01:00:00, 02:00:00, 03:00:00, 04:00:00`.

**Consequence: the bundle's `resources` section maps onto `addExternalDataset` almost verbatim** — rename `extent` → `duration`, express it in microseconds, and re-key the array into an object keyed by profile name. No re-derivation, no encoding conversion, no risk of the silently-wrong-timeline failure mode.

```json
{
  "/my_boolean": {
    "type": "discrete",
    "schema": { "type": "boolean" },
    "segments": [
      { "duration": 3600000000, "dynamics": false },
      { "duration": 3600000000, "dynamics": null }
    ]
  }
}
```

`dynamics: null` marks a gap (`is_gap = true`), matching the bundle's own convention.

### 10.2 What already works end to end

| Stage | Mechanism |
|---|---|
| Ingest | `addExternalDataset` action → `MerlinBindings.java:503` → `PostgresPlanRepository.addExternalDataset` (`:203-248`) |
| Store | `plan_dataset` + `profile` + `profile_segment` |
| Associate | `plan_dataset.simulation_dataset_id` — if null, the dataset shows for *every* simulation run of the plan; if set, only for that run |
| Align | `offset_from_plan_start = datasetStart − planStart`, recomputed by trigger when a dataset is linked to another plan |
| Read | `SUB_PLAN_DATASET` (`gql.ts:3001`) → `createExternalResourceSubscription` (`stores/externalResource.ts:39`) → `GET_EXTERNAL_PROFILE_SEGMENTS_SINCE` |
| Render | `Row.svelte:224-288` routes each layer by name: not in `$resourceTypes` ⇒ external |
| Upload UI | `ResourceList.svelte:41-62` → `effects.uploadExternalDataset` → gateway `/uploadDataset` |

The UI has a first-class notion of external resources already: `TimelineResourceKind = 'sim' | 'external'` (`stores/timelineResourceStatus.ts:12`), with two parallel subscription factories writing into one shared status registry. External profiles are keyed by profile *id* rather than name in the delta query, precisely because names can collide across `plan_dataset` rows.

Notably, profiles carry **no requirement to correspond to a mission model**. `PostProfilesAction` inserts `{name, type, schema}` straight from the caller; there is no join or validation against `resource_type`. Profile names are free-form.

Permissions: Hasura role `aerie_admin` or `user`, plus a fine-grained check on `addExternalDataset` — `insert_ext_dataset`, seeded as `PLAN_OWNER` (`default_user_roles.sql:20`). `extendExternalDataset` does **not** perform the fine-grained check, which looks like an oversight worth reporting upstream independent of this work.

### 10.3 The real gaps

**Spans cannot be ingested.** This is the substantive gap. The external-dataset write path touches only `plan_dataset` and the profile tables — no reference to `merlin.span` anywhere. Spans are written exclusively by `PostSpansAction`, called from the worker-side `PostgresResultsCellRepository.postSimulationResults` (`:361-431`), which is private and unreachable externally.

Confirmed at the permission layer: `span.yaml`, `simulation_dataset.yaml`, `profile.yaml`, and `profile_segment.yaml` have **no `insert_permissions` block at all** — only `select` and `delete`. There is no raw-Hasura write path for any results data; everything must go through merlin-server.

**But every primitive already exists.** A new `addExternalSimulationResults` action, mirroring `addExternalDataset`, would sequence three existing pieces:

1. `CreateSimulationDatasetAction` — creates `simulation_dataset`; a `before insert` trigger auto-creates the `dataset` row and fills the revision columns.
2. `PostSpansAction` — unmodified, but requires spans **topologically sorted parent-first** (see `PostgresResultsCellRepository:420-427`) so the partition's self-referencing FK doesn't fail.
3. `SetSimulationStateAction` — flip status to `success` so the UI doesn't see a perpetually pending row.

Two implementation hazards:

- **Partitioning is automatic, not an obstacle.** An `after insert` trigger on `dataset` calls `allocate_dataset_partitions(id)`, dynamically creating `merlin.span_<id>` and attaching it. No manual DDL. (An earlier draft listed this as a risk; it is not.)
- **Inserting a `simulation_dataset` notifies live workers.** The insert fires `pg_notify('simulation_notification', ...)`, and a running worker will attempt to claim and execute it. A synthetic-results path must either set `status` in the same transaction or bypass the notify-driven flow. This works fine in dev and misbehaves on a deployed instance, so it needs deliberate handling.

**`resource_type` cannot be inserted through Hasura.** A sharp and probably unintentional asymmetry:

| Table | Hasura permissions |
|---|---|
| `activity_type` | select, **insert**, update, delete (`aerie_admin`) |
| `resource_type` | select, delete only |

`resource_type` is normally populated by an event trigger that loads the model JAR — a path that can never succeed for a foreign data source. So registering external resource types today requires raw SQL. Worth raising upstream: adding insert permissions symmetric with `activity_type` would remove the only step in this whole flow that standard role-based access cannot perform.

In practice this may not block anything, since external profiles do not require `resource_type` rows to render — but their absence is what makes `Row.svelte:256` classify a resource as external, so the two interact.

**Resource layers require a selected simulation dataset.** `Row.svelte:224` gates the entire resource-fetch block — both sim *and* external — on `simulationDataset !== null`. A plan holding only external data with no simulation history renders no resource rows at all. This is the single most likely thing to surprise someone importing profiles into a fresh plan, and the smallest UI fix on the list.

**No resource-name namespacing.** `ResourceLayerFilter` is a bare string. If an external profile shares a name with a mission-model resource type, `Row.svelte:256` silently resolves to the simulation resource and the external one becomes unreachable, with no UI indication. The layer picker concatenates both name lists without de-duplication.

**No management UI.** Upload exists; listing, inspecting, and deleting uploaded external datasets do not. The upload control also offers only a binary "attach to current simulation" checkbox, so an arbitrary simulation dataset cannot be targeted.

### 10.4 Correcting an earlier assumption

An earlier draft of this document identified the `mission_model` foreign key as the biggest obstacle. **That was wrong.** `merlin.plan.model_id` is declared `integer null` (`plan.sql:6`) with `on delete set null` — a model-less plan is schema-legal.

The accurate picture:

- Nothing eagerly loads the JAR when a plan is opened or a directive is inserted. `activity_directive.type` is plain `text` with **no foreign key** to `activity_type`.
- Argument validation is asynchronous; on a model-load failure it logs and leaves validations `pending` rather than erroring (`LocalMissionModelService.java:143-146`).
- A synthetic `mission_model` row inserts cleanly — `uploaded_file.path` is opaque `bytea` with no validity check — and the three refresh event triggers fail harmlessly with `num_retries: 0`.
- The UI is reasonably defensive: the plan page guards `if (data.initialPlan.model)` before dereferencing, and `SimulationPanel` guards with `if ($simulation && $plan && $plan.model)`.

So the realistic split is that the **timeline read path tolerates a model-less plan**, while model assumptions concentrate in the **interactive panels** (simulation, expansion, presets) — acceptable for read-only external results, and avoidable by hiding those panels for such plans.

### 10.5 A staged proposal

**Stage 1 — profiles only, zero backend changes.** Convert a bundle's `resources` into a `profileSet` and call `addExternalDataset`. Works today. Deliverable: a `bundle2aerie` script beside `tol2bundle`. Immediately useful, and validates the encoding mapping against a live instance.

**Stage 2 — fix the two small UI papercuts.** Relax `Row.svelte:224` so external resources render without a selected simulation dataset, and add namespacing or at least a collision warning. Both are small, independently valuable, and benefit existing external-dataset users who have nothing to do with this feature.

**Stage 3 — `addExternalSimulationResults`.** The new action for spans, following the `addExternalDataset` pattern exactly. This is what makes an imported bundle a *complete* plan rather than resources-only.

**Stage 4 — provenance.** See below; ideally settled before Stage 3 ships.

### 10.6 A question for maintainers

The UI has **no concept of data provenance.** Simulation staleness is a pure revision comparison (`stores/simulation.ts:128-153`): if `plan_revision`, `model_revision`, and `simulation_revision` match current counters and `status = 'success'`, imported results are *indistinguishable* from a real Aerie simulation run. No flag is stored, and none is displayed.

For resource profiles this is arguably fine — external datasets are an established feature and users upload them knowingly. For *spans*, it means a timeline could show simulated activities that Aerie never simulated, with nothing in the UI saying so.

This is a design question rather than a technical obstacle, and it should be answered before span ingestion lands. Options range from a `simulation_dataset.source` column with a UI badge, to a distinct dataset kind, to an explicit decision that provenance is out of scope. In a mission-planning tool the answer seems likely to be "mark it", but that is the maintainers' call, not ours.

### 10.7 Relationship between the two modes

These are complements, not alternatives. Offline mode needs no infrastructure and suits sharing, triage, archival, and inspecting a colleague's run. Database import gives full-fidelity Aerie — editing, constraints, scheduling, collaboration — at the cost of running the stack.

The same bundle is the artifact in both directions. That is why keeping the loader permissive — several duration and timestamp spellings, graceful degradation on an unusable view — matters more than format purity: the format's job is to be easy to *produce*, and Aerie's job is to meet it where it lands.

## 11. Future work

**Correctness**

1. Differential harness: render the same plan via live backend and via bundle; compare span counts, resource sample points, and timeline bounds.
2. Fix `required_parameters` in `merlin-framework-processor` — benefits all of Aerie, not just this feature.
3. Split `BundleWriterTest`; add a golden-file round-trip test.
4. Exercise the `linear` / `float` TOL paths against a file that actually contains them.

**Capability**

5. External events and sources — the schema has room reserved.
6. Constraint violations in the bundle, so violation overlays render offline.
7. A gateway export endpoint producing a bundle from a live plan.
8. Bundle import into a live instance — see the staged proposal in §10.5.

**Scale**

9. Downsampling or level-of-detail for dense profiles.
10. Chunked or lazy loading — a manifest plus per-resource files.

**Ergonomics**

11. Drag-and-drop, and a `?bundle=<url>` deep link.
12. A version-negotiation policy as the format evolves.
13. Publish the JSON Schema as a stable artifact for third-party producers.

---

## 12. Footprint

**plandev-ui** — 5 files modified (**58 insertions, 7 deletions**):

```
 src/hooks.server.ts            |  5 +++++
 src/routes/+layout.server.ts   |  2 +-
 src/stores/plan.ts             |  8 ++++++--
 src/stores/profile.ts          | 23 +++++++++++++++++++++-
 src/stores/subscribable.ts     | 27 +++++++++++++++++++++++---
```

New files: `utilities/offline-bundle.ts` (459), `types/offline-bundle.ts` (119), `stores/offline.ts` (65), `stores/offlineFlag.ts` (20), `routes/offline/` (`+page.svelte` 217, `offline-data.ts` 130, `+page.ts`), `schemas/offline-bundle-schema-v1.json` (148), fixtures, and 5 test files.

**aerie** — 3 files modified (50 insertions, 43 deletions). New: `BundleWriter.java`, `ResourceSegmentJsonWriter.java`, `BundleWriterTest.java`, the schema copy, and `contrib/tol2bundle/`.

The modified-file diff is deliberately small. Every substantial addition is a new file, and each of the five touched files gains a guard rather than a rewrite — which is the property that makes this reviewable as an upstream contribution.
