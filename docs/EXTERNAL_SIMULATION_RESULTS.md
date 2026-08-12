# External Simulation Results in Aerie

**One interchange format, two ways to view it.** A non-Aerie simulation engine emits a single JSON bundle; Aerie can either render it with no backend at all, or ingest it into a running database so it behaves like ordinary plan data.

This document summarises both efforts across both repositories: what was built, what was verified against a live instance, what broke, and what remains open.

| | |
|---|---|
| **Repos** | `NASA-AMMOS/aerie` (backend, producers) · `NASA-AMMOS/plandev-ui` (viewer) |
| **Status** | Both paths working and verified end to end against a live Aerie stack |
| **Tests** | 948 UI · 26 backend · 67 `bundle2aerie` · 29 `tol2bundle` — all passing |
| **Not done** | Nothing committed; no upstream issues filed; span ingestion not implemented |

---

## Contents

1. [The two efforts](#1-the-two-efforts)
2. [The bundle format](#2-the-bundle-format)
3. [Effort A — offline mode (plandev-ui)](#3-effort-a--offline-mode-plandev-ui)
4. [Effort B — database import (aerie)](#4-effort-b--database-import-aerie)
5. [Producers and tooling](#5-producers-and-tooling)
6. [What was verified live](#6-what-was-verified-live)
7. [Findings worth reporting upstream](#7-findings-worth-reporting-upstream)
8. [Limitations](#8-limitations)
9. [What's next](#9-whats-next)
10. [File-by-file change inventory](#10-file-by-file-change-inventory)

---

## 1. The two efforts

Aerie's UI is coupled to Aerie's backend: a plan view needs Hasura, Postgres, the gateway for auth, and a compiled mission model. Other simulation engines produce timeline data worth viewing in that UI, and today there is no way in short of running the whole stack and importing through it.

Two complementary answers, sharing one artifact:

**Effort A — offline mode.** Upload a bundle to a `/offline` route; the real timeline renders it with no backend whatsoever. Zero infrastructure. Suits sharing, triage, archival, and inspecting a colleague's run. Read-only by construction.

**Effort B — database import.** Convert a bundle into a live Aerie instance via the existing `addExternalDataset` action plus ordinary plan/directive inserts. Gives full-fidelity Aerie — editing, constraints, scheduling, collaboration — at the cost of running the stack.

They are complements, not alternatives, and deliberately consume the **same bundle**. That constraint is what keeps the format honest: its job is to be easy to *produce* once and useful in more than one place.

---

## 2. The bundle format

One versioned envelope combining Aerie's existing plan JSON, its existing simulation-results JSON, and one new section.

```jsonc
{
  "bundleVersion": "1.0.0",
  "plan": { "name": "...", "startTime": "2024-07-01T00:00:00Z", "duration": "24:00:00" },
  "activityTypes": [ { "name": "...", "parameters": {}, "requiredParameters": [] } ],
  "activityDirectives": [ { "id": 4, "type": "...", "startOffset": "02:27:15.059" } ],
  "simulation": {
    "simulationStartTime": "...", "simulationEndTime": "...",
    "spans":     [ { "id": 4, "type": "...", "parentId": null, "directiveId": 4,
                     "startOffset": "+02:27:15.059000", "duration": "+00:00:02.000000" } ],
    "resources": [ { "name": "/counter", "type": "discrete", "schema": {"type":"int"},
                     "segments": [ { "extent": "+00:16:40.000000", "dynamics": 0 } ] } ]
  }
  // "view" optional
}
```

Normative schema: `plandev-ui/src/schemas/offline-bundle-schema-v1.json`, with checked-in copies at `stateless-aerie/src/test/resources/` and `contrib/tol2bundle/schema/` so neither repo's tests reach into the other.

**Most of this already existed.** `stateless-aerie` already simulated plans with no server, and `SimulationResultsWriter` already emitted a documented, versioned results JSON. The genuinely new section is `activityTypes` — parameter schemas appear in neither pre-existing file, and the nearest proxy (`topics["ActivityType.Input.<Type>"].schema`) lacks required-parameter and computed-attribute information. They are static per type, so a producer emits them once.

### The sharpest edge: `extent` vs `start_offset`

Producers emit `extent` — each segment's own duration, a **delta**. Consumers read cumulative `start_offset` and infer each segment's end from the *next* segment's offset. These are different quantities, not a rename:

```
bundle:   extent     1000s     1000s     1000s      400s
                  ├─────────┼─────────┼─────────┼─────────┤
consumer: offset     0        1000      2000      3000     duration = 3400
```

Get it wrong and you render a plausible staircase that ends at the wrong time, with nothing visibly broken.

Two facts make this safe. First, Aerie's own Java calls the delta **`extent()`** (`PostProfileSegmentsAction.java:38`) — the format independently adopted the same word. Second, merlin-server already performs exactly this prefix-sum when writing external datasets, so **the bundle's encoding matches Aerie's internal contract**, and Effort B needs no conversion at all — just a rename to `duration` and a unit change to microseconds.

### Design principle: the consumer owns every transform

A third-party engine emits only what it naturally has. The loader handles `Span.attributes` nesting, drops `childIds` (hierarchy is rederived from `parent_id`), rebases offsets from simulation start onto plan start, derives millisecond fields, and accepts four duration spellings (Aerie signed, Postgres interval, ISO-8601, integer microseconds) plus two timestamp spellings (ISO-8601, Aerie day-of-year). snake_case aliases are accepted so an unmodified `plan.json` pastes in, with an opaque-key guard so an activity argument genuinely named `start_time` is not rewritten.

---

## 3. Effort A — offline mode (plandev-ui)

A read-only `/offline` route that loads a bundle and renders it on the **real** `TimelinePanel`. 10 modified files (+218 / −23); every substantial addition is a new file.

### Why it is small

Three properties of the existing code:

1. The read path never touches the `simulate` action — results come from plain `span` / `profile` / `profile_segment` tables.
2. `sampleProfiles` (`src/utilities/resources.ts:12`) is already pure client-side math.
3. `generateDefaultView` (`src/utilities/view.ts:26`) builds the row/layer tree client-side, so a bundle need not carry a view.

This is a **data-hydration problem, not a protocol problem**. No GraphQL is reimplemented; no fake server exists.

### The load-bearing change: the subscription gate

`src/stores/subscribable.ts`

The initial design assumed hydrating stores would suffice, since components only *read* stores. **That was wrong.** `gqlSubscribable.subscribe()` calls `getSharedClient()` unconditionally in the browser; `updateValue()` only reaches *already-subscribed* listeners. The moment any component references `$plan`, a live WebSocket is attempted.

A first implementation worked around this by hand-rolling a substitute timeline. That was discarded — the point is the real UI, not a lookalike.

Because `gqlSubscribable` is the single chokepoint for every live query, one guard makes all subscriptions inert and unlocks the genuine `TimelinePanel` with **zero component changes**. Four call sites gated (`subscribe`, `clientSubscribe`, `resubscribe`, `restartSocket`); `loading` settles to `false` so spinners don't hang.

`subscribable.ts` deliberately does not import `stores/offline.ts` — that would close a cycle via `stores/simulation.ts`. A dependency-free leaf module `stores/offlineFlag.ts` (20 lines) holds the flag, which is why it is a plain boolean rather than a store.

### Read-only by reuse

`planReadOnly` already existed as a derived store over snapshot and merge-request flags, already wired through every actionable control. Snapshot-browsing is structurally identical to offline viewing, so a third input was added rather than a new gating concept:

```ts
derived([planReadOnlySnapshot, planReadOnlyMergeRequest, planReadOnlyOffline],
        ([$s, $m, $o]) => $m || $s || $o)
```

One line, no permission audit, no new concept.

### Remaining seams

- **Auth/routing** — `/offline` returns early in `hooks.server.ts` *and* is excluded in `+layout.server.ts`. Both are required; the first alone still redirects to `/login`. Route is `ssr = false`; no adapter change needed.
- **Resources** — `createProfileSubscription` gains an early branch serving an in-memory map, matching the live path's `TimelineResourceState` shape and preserving its refcounting.
- **Synthetic `SimulationDataset`** — `status: 'success'`, `canceled: false`, shared `dataset_id`. Load-bearing: status is what closes the final profile segment at `duration`; a mismatched `dataset_id` silently yields "resource not found" for every resource.

### Two independent fixes for existing external-dataset users

Surfaced by this work but unrelated to the offline path; could be split into their own PR.

- **`Row.svelte`** gated *all* resource fetching — simulation and external — on `simulationDataset !== null`, so a plan with only external data rendered no resource rows. Simulation and external subscriptions now track separate dataset ids. Refcount invariants traced through mount/unmount.
- **Collision detection** — `ResourceLayerFilter` is a bare string, so an external profile sharing a mission-model resource name was silently unreachable. Adds `getCollidingResourceNames()` plus a layer-editor warning. A namespacing *scheme* was deliberately not invented — that is a maintainer decision.

---

## 4. Effort B — database import (aerie)

Landing a bundle in a running instance so it appears as ordinary plan data.

### What already existed

External resource profiles have a complete path today: the `addExternalDataset` Hasura action, `plan_dataset`/`profile`/`profile_segment` tables, a `createExternalResourceSubscription` in the UI, and an upload button. The UI already models `TimelineResourceKind = 'sim' | 'external'`. Profiles require **no mission model** — `PostProfilesAction` inserts `{name, type, schema}` straight from the caller with no validation against `resource_type`.

### What was proven against a live database

| Question | Answer |
|---|---|
| Is `plan.model_id` nullable? | **Yes** — `integer null`. A model-less plan is schema-legal and was created. |
| Is a `simulation` row needed? | Auto-created by trigger on plan insert. |
| Do directives need registered types? | No — `activity_directive.type` is plain `text` with no FK. |
| Is the segment encoding compatible? | Yes — verified byte-for-byte through the live prefix-sum. |
| Are anchors preservable? | Yes — bundle ids remap to server ids; Aerie's own `anchor_validation_status` reports the chain valid. |
| Is permission enforced? | Yes — a non-owner is rejected: *"cannot perform 'insert_ext_dataset' because they are not a 'PLAN_OWNER'"*. |

An earlier draft of the design identified the `mission_model` foreign key as the biggest obstacle. **That was wrong**, and the live test disproved it.

### The real gap: spans

Spans cannot be ingested. The external-dataset path touches only `plan_dataset` and profile tables. Confirmed at the permission layer: `span`, `simulation_dataset`, `profile`, and `profile_segment` have **no `insert_permissions` block at all** — only `select` and `delete`. Everything must go through merlin-server.

Every primitive exists, though. A new `addExternalSimulationResults` action mirroring `addExternalDataset` would sequence `CreateSimulationDatasetAction` → `PostSpansAction` → `SetSimulationStateAction`. Two hazards: spans must be **topologically sorted parent-first**, and inserting a `simulation_dataset` fires `pg_notify` that a live worker will try to claim — which works in a dev stack and misbehaves on a deployed one.

**Not implemented.** It is several days of Java whose riskiest parts need a running worker to exercise properly.

---

## 5. Producers and tooling

### `stateless-aerie --bundle` — Aerie as its own reference producer

`BundleWriter` plus an extracted `ResourceSegmentJsonWriter`, so `extent` semantics are defined once and shared with `SimulationResultsWriter`. Exposed as `-b`/`--bundle` on the existing `simulate` subcommand — it is an alternate output format of the same run, sharing every input and the `-f`/stdout convention.

Confirmed by spike: `MissionModel.getDirectiveTypes()` exposes parameter schemas, ordering, and computed-attribute schemas, so `activityTypes` comes from the model rather than being scraped from topics.

### `contrib/tol2bundle/` — TOL XML → bundle

Streaming converter (stdlib only, `iterparse` + element clearing). Handles a 167 MB NISAR file in **17.7 s at ~309 MB peak RSS**; an 8-hour window in **3.7 s at ~30 MB**.

| TOL construct | Bundle mapping |
|---|---|
| `ResourceSpec` name + `Index level=N` | flattened to one `/`-joined name |
| `Interpolation: constant` / `linear` | `discrete` / `real` (`rate = Δvalue/Δseconds`) |
| `RES_VAL` sequence | segments; `extent` = next timestamp − this timestamp |
| `ACT_START` + `ACT_END` (matched by UUID) | one directive + one span, linked by `directiveId` |
| `<Parent>` UUID | `span.parentId` via a stable UUID→int allocator |
| `ERROR` / `RELEASE` | no equivalent — counted and reported, never silently dropped |

Time subsetting (`--start`/`--end`/`--max-activities`) exists because a full conversion is 84 MB and reports what it drops rather than truncating silently.

### `contrib/bundle2aerie/` — bundle → live Aerie

Imports plan, directives (with anchor remapping), and resources via `addExternalDataset`. Also generates a **view**, because an imported model-less plan has no `resourceTypes` and therefore no default resource rows — without this the data is invisible even when correctly imported.

`build_view_definition` is validated against a vendored `ui-view-schema-v3.json`, with a drift test against the UI's copy, and was verified **field-for-field equal** to output from the UI's own `generateDefaultView`.

---

## 6. What was verified live

A full stack (Postgres, Hasura, merlin, gateway, workers) was built and run.

### The differential test

The same bundle, prefix-summed by **three independent implementations** — this repo's TypeScript loader, merlin-server's Java, and the offline viewer — agrees exactly. For the 87-segment `/counter` profile: last `start_offset` **86,000 s**, total duration **86,400 s**, identical across all three.

This is the cross-validation the design called for, and it is stronger than planned because it spans three implementations rather than two.

### The full circle

```
foo mission model
  → stateless-aerie simulate --bundle       (Java)
  → bundle.json ──┬──→ offline loader        (TypeScript)
                  └──→ bundle2aerie → live Aerie DB   (Java, merlin-server)
```

### Scale

The **entire** NISAR TOL file was imported into a live database:

| | |
|---|---|
| Activity directives | 68,532 |
| Resource profiles | 872 |
| Profile segments | 133,574 |
| Activity types | 56 |
| Plan span | ~547 days (`13127:30:01`) |

Segment counts match the converter's output exactly — nothing lost.

---

## 7. Findings worth reporting upstream

Independent of whether any of this work is adopted.

1. **`addExternalDataset` is capped at 1 MB.** `AerieAppDriver.java:114` creates Javalin with no `maxRequestSize`, inheriting the **1 MB default**. A 6.3 MB profileSet is rejected with `Content Too Large`. Any realistic external dataset must be chunked. A one-line `config.http.maxRequestSize` would remove the need. (Worked around here by chunking through `extendExternalDataset`: 872 profiles → 11 chunks, **4.3 s**.)

2. **Directive insert throughput is very low.** 68,532 directives took **3 h 09 m** — roughly 5.5/second, with the client idle throughout (10.7 s user CPU). Batched 500/request, so that is ~80 s *per batch*, not per-row round trips. Server-side per-row work (anchor validation, validation rows, changelog triggers) dominates. For mission-scale ingest this is the more serious of the two.

3. **`resource_type` cannot be inserted through Hasura**, while `activity_type` can (select/**insert**/update/delete vs select/delete only). The normal population path loads the model JAR, which can never succeed for a foreign source — leaving raw SQL as the only route. Symmetric permissions would remove the only step standard role-based access cannot perform.

4. **`extendExternalDataset` does not call `checkPermissions`**, while `addExternalDataset` does. Looks like an oversight.

5. **The UI has no concept of data provenance.** Staleness is a pure revision comparison; imported results are indistinguishable from a real Aerie run. Acceptable for profiles that users knowingly upload — but for *spans* it means a timeline could show simulated activities Aerie never simulated, with nothing saying so. A design question to settle **before** span ingestion lands, since retrofitting provenance onto existing rows is impossible.

---

## 8. Limitations

**Scope**
- Read-only in offline mode. No editing, re-simulation, or scheduling.
- External events/sources out of scope; hydrated empty.
- No constraint violations in the bundle.
- Span ingestion into a live database is **not implemented**.

**Data fidelity**
- `requiredParameters` is always empty — a pre-existing `merlin-framework-processor` behaviour (`NoneDefinedMethodMaker` never overrides `getParametersWithDefaults()`), not introduced here and not fixable from the bundle side.
- `BundleWriter` omits `plan.id`/`modelId` and directive `name`/`metadata`/`tags` because `PlanJsonParser` drops them. All optional.
- TOL `Units`/`Maximum`/`Minimum` have no schema slot; mission-specific typed values (`YawValue`) degrade to strings.
- TOL `linear` interpolation converts sampled values to `{initial, rate}` — exact at sample points, approximate between.

**Unverified**
- **`linear` and `float` TOL paths have never run against real data.** All 872 NISAR resources are `constant` with boolean/integer/string/duration types. Synthetic coverage only.
- **Rendering at extreme scale is unverified.** The 68,532-activity import proves the *data path*, not the *render path*. A 873-row view may not be usable.
- Component tests tolerate jsdom's missing canvas — they prove the tree mounts and stores hydrate, not that the timeline draws.
- `BundleWriterTest` is one `@Test` with 28 assertions; should be split.

**Operational**
- An **external-only plan renders nothing by default**, because a model-less plan has no `resourceTypes` and `generateDefaultView` emits no resource rows. `bundle2aerie --create-view` works around this; arguably `generateDefaultView` should consider external resource names.
- Nothing is committed; both repos are dirty on their default branches.

---

## 9. What's next

**Highest value first**

1. **Visual verification at scale** — the one gap tests cannot close. Load plan 4 (254 activities) and plan 7 (68,532) in a browser and compare stock vs. modified UI.
2. **Report findings 1–4 upstream.** Cheap, independently useful, and unblocks others.
3. **Split the two external-dataset fixes** into their own PR — they benefit existing users and shouldn't wait on the larger feature.
4. **Settle provenance** before implementing span ingestion.

**Then**

5. `addExternalSimulationResults` for spans, with a worker running to exercise the `pg_notify` race.
6. Exercise `linear`/`float` TOL paths against a file that contains them.
7. Have `tol2bundle` embed a view directly in the bundle — `build_view_definition` is already pure, and the bundle schema already has the optional field, so one artifact would render correctly in both the offline viewer *and* the live UI.
8. Downsampling / level-of-detail for dense profiles.
9. Fix `required_parameters` in `merlin-framework-processor` — benefits all of Aerie.

---

## 10. File-by-file change inventory

### `NASA-AMMOS/aerie`

**Modified** (3 files, +50 / −43)

| File | Change |
|---|---|
| `orchestration-utils/.../SimulationResultsWriter.java` | segment writing extracted to shared helper |
| `stateless-aerie/.../Main.java` | `-b`/`--bundle` flag on `simulate` |
| `stateless-aerie/.../CLIArgumentsTest.java` | updated help-text golden |

**New**

| Path | Purpose |
|---|---|
| `orchestration-utils/.../BundleWriter.java` | bundle serializer |
| `orchestration-utils/.../ResourceSegmentJsonWriter.java` | shared segment/`extent` writer |
| `stateless-aerie/.../BundleWriterTest.java` | end-to-end schema-conformance test |
| `stateless-aerie/src/test/resources/offline-bundle-schema-v1.json` | checked-in contract |
| `contrib/tol2bundle/` | TOL XML → bundle (974 lines, 29 tests) |
| `contrib/bundle2aerie/` | bundle → live Aerie (1,322 lines, 67 tests) |
| `docs/OFFLINE_BUNDLE_VIEWER.md` | design doc + backend research |
| `docs/OFFLINE_BUNDLE_IMPORT_PLAN.md` | staged implementation plan |

### `NASA-AMMOS/plandev-ui`

**Modified** (10 files, +218 / −23)

| File | Change |
|---|---|
| `src/stores/subscribable.ts` | offline gate at the subscription chokepoint |
| `src/stores/profile.ts` | offline branch in `createProfileSubscription` |
| `src/stores/plan.ts` | `planReadOnlyOffline` folded into `planReadOnly` |
| `src/hooks.server.ts` | `/offline` auth bypass |
| `src/routes/+layout.server.ts` | `/offline` login-redirect exclusion |
| `src/components/timeline/Row.svelte` | external resources no longer require a sim dataset |
| `src/components/.../TimelineLayerEditor.svelte` | collision warning |
| `src/utilities/timeline.ts` | `getCollidingResourceNames()` |
| `src/utilities/timeline.test.ts`, `src/stores/externalResource.test.ts` | tests |

**New**

| Path | Purpose |
|---|---|
| `src/utilities/offline-bundle.ts` | parse / validate / transform (42 tests) |
| `src/types/offline-bundle.ts` | bundle + loaded types |
| `src/stores/offline.ts` | offline bundle store, sampled-resource memo (9 tests) |
| `src/stores/offlineFlag.ts` | cycle-free leaf flag |
| `src/routes/offline/` | route, upload UI, data shaping (10 tests) |
| `src/schemas/offline-bundle-schema-v1.json` | normative schema |
| `src/stores/subscribable.test.ts` | gating tests (8) |
| `src/tests/fixtures/foo-bundle.json` | golden fixture |
