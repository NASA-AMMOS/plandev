# Implementation Plan: Importing External Results into a Live Aerie Database

Companion to [`OFFLINE_BUNDLE_VIEWER.md`](./OFFLINE_BUNDLE_VIEWER.md). That document establishes the bundle format and the read-only offline viewer, and §10 of it contains the research this plan acts on.

**Goal:** take a bundle produced by a non-Aerie engine and land it in a running Aerie/plandev instance so it displays in the stock UI, with editing, constraints, and collaboration available.

**Guiding constraint:** each stage must be independently valuable and independently mergeable. Nothing here should require the whole sequence to land before anyone benefits.

---

## Stage 0 — Prerequisites and spikes

Must complete before Stage 1 begins. Expect a day; most of it is standing up infrastructure.

### 0.1 A running Aerie instance (blocking)

Everything downstream needs one. `docker-compose.yml` at the repo root pulls prebuilt images (`ghcr.io/nasa-ammos/aerie-*`).

Verify: Hasura reachable, gateway auth working, a mission model uploadable, a plan creatable, and a simulation runnable end to end. **Do not proceed until a real simulation completes** — otherwise later failures are ambiguous between "our code is wrong" and "the stack is misconfigured".

Risks: image pull may need registry auth; the gateway owns its own Postgres DB and auth config; local ports may collide.

### 0.2 Spike: can a plan and its directives be created purely through Hasura?

The research covered profiles, spans, and models, but **not** the ordinary plan/directive write path. The UI clearly does this as a normal `user`, so it should be straightforward — confirm rather than assume.

Answer concretely: which role, which mutations, whether `plan.revision` and the `simulation` row are auto-created by trigger, and whether `activity_directive` inserts need anything model-derived.

Deliverable: a shell/Python transcript creating a plan with two directives from scratch.

### 0.3 Spike: does a synthetic model produce a usable activity palette?

Per §10.4 a synthetic `mission_model` row inserts cleanly, `activity_type` rows can be inserted through Hasura, and `resource_type` rows cannot.

Determine empirically: with a synthetic model plus directly-inserted `activity_type` rows, does the timeline colour, group, and label activities correctly? Do the three failing refresh event triggers cause visible noise? Does `resource_type` being empty actually matter for rendering external profiles?

This decides whether Stage 3 needs a model at all, or whether `model_id = null` is cleaner.

---

## Stage 1 — Profiles only, zero backend changes

**The highest-value stage.** Proves the encoding mapping against a live instance and delivers something useful immediately. Requires no changes to Aerie or plandev-ui.

### Scope

A converter `contrib/bundle2aerie/bundle2aerie.py` that reads a bundle and:

1. Creates (or targets an existing) plan.
2. Inserts `activityDirectives` as `activity_directive` rows.
3. Converts `resources` → `profileSet` and calls `addExternalDataset`.

### The mapping

Per §10.1, verified against `PostProfileSegmentsAction.java:36-54`:

| Bundle | `addExternalDataset` |
|---|---|
| `resources[].name` | object key |
| `resources[].type` | `type` (`discrete` \| `real`) |
| `resources[].schema` | `schema` |
| `resources[].segments[].extent` | `segments[].duration`, **as integer microseconds** |
| `resources[].segments[].dynamics` | `segments[].dynamics` (`null` ⇒ gap) |

Both sides are per-segment deltas — no prefix-sum, no re-derivation.

`datasetStart` = the bundle's `simulationStartTime`; the server computes `offset_from_plan_start` itself.

**Decision — attach at plan level.** Pass `simulationDatasetId = null` so profiles appear for every simulation run of the plan rather than being bound to one. A bundle has no Aerie simulation to bind to, and Stage 1 creates no `simulation_dataset`.

### CLI

```
bundle2aerie.py BUNDLE.json --hasura-url URL --auth-token TOK
                [--plan-id N | --create-plan] [--model-id N]
                [--dry-run] [-v]
```

`--dry-run` prints the mutations without executing. Given this writes to a shared database, dry-run is a first-class feature, not a nicety.

### Tests

- Unit tests for the mapping (extent → microsecond duration, gaps, real vs discrete, name keying) against the existing `foo-bundle.json` fixture — no live instance needed, so they run in CI.
- One integration test in `e2e-tests/`, reusing `HasuraRequests.insertExternalDataset` and following `ExternalDatasetsTest.java`. Assert the round trip: import a bundle, read back `profile_segment.start_offset`, confirm the cumulative offsets match the bundle's prefix-summed extents.

That last assertion is the real proof, and it is exactly the differential test §9 of the design doc admits is missing.

### Acceptance

A NISAR slice imports into a live plan and its resources render on the stock timeline.

**Expected caveat:** they will only render if a simulation dataset is selected (`Row.svelte:224`). Stage 2 fixes that. Verify the behaviour and record it rather than working around it.

---

## Stage 2 — Two UI papercuts

Independent of everything else, and beneficial to existing external-dataset users who have nothing to do with bundles. Good candidates for a first upstream PR, since they are small and self-justifying.

### 2.1 Render external resources without a selected simulation dataset

`Row.svelte:224` gates the entire resource-fetch block — sim *and* external — on `simulationDataset !== null`. A plan with only external data and no simulation history shows no resource rows at all.

Change: gate the *simulation* branch on a non-null dataset, and let the external branch proceed independently. Careful review needed around `timelineResourceStatus` bookkeeping, which is shared by both kinds and refcounted.

Test: a plan with external profiles and no simulation renders resource layers.

### 2.2 Surface resource-name collisions

`ResourceLayerFilter` is a bare string, and `Row.svelte:256` classifies by "not in `$resourceTypes` ⇒ external". A colliding name silently resolves to the simulation resource, making the external one unreachable with no indication.

Minimum viable: detect the collision and warn in the layer editor. Full namespacing is a larger design change and should be proposed separately — flag it, don't unilaterally introduce a naming scheme into a shared format.

---

## Stage 3 — `addExternalSimulationResults`

The substantive backend work: making an imported bundle a *complete* plan rather than resources-only.

### Design

A new Hasura action mirroring `addExternalDataset` exactly, so it inherits an established review-approved pattern.

- **Metadata:** `actions.yaml` + `actions.graphql`, handler `{{AERIE_MERLIN_URL}}/addExternalSimulationResults`, roles `aerie_admin` + `user`.
- **Route:** new case in `MerlinBindings.java` beside `addExternalDataset` (`:120`, handler `:503`), with a `checkPermissions` call — and note that `extendExternalDataset` omits this check, which looks like an existing bug worth a separate issue.
- **Parser:** a span-tree input shape in the `ProfileParsers` style.
- **Repository:** one new public method sequencing three existing actions:
  1. `CreateSimulationDatasetAction` — trigger auto-creates the `dataset` row, allocates the span partition, and fills revision columns.
  2. `PostSpansAction` — **unmodified**, but spans must be topologically sorted parent-first (see `PostgresResultsCellRepository:420-427`).
  3. `SetSimulationStateAction` — set `status = 'success'`.

All three exist; only the sequencing entry point is new (`postSimulationResults` is private and worker-only).

### Two hazards to handle explicitly

**Worker notification.** Inserting a `simulation_dataset` fires `pg_notify('simulation_notification', ...)`, and a live worker will try to claim and run it. Must be resolved deliberately — set status within the same transaction, or bypass the notify-driven path. **This works in a dev stack with no worker and fails on a deployed instance**, so it needs a test with a worker actually running.

**Revision matching.** The UI computes staleness by comparing `plan_revision` / `model_revision` / `simulation_revision` to current counters (`stores/simulation.ts:128-153`). Set them at insert time or the import shows as permanently stale.

### Tests

- Java unit/integration tests in `e2e-tests/`, mirroring `ExternalDatasetsTest`.
- A test with a simulation worker running, asserting the imported dataset is not claimed or overwritten.
- A round trip: import a bundle's spans, read them back, verify parent/child hierarchy survives.

---

## Stage 4 — Provenance (needs a decision first)

**Blocked on maintainers, not on engineering.** See §10.6 of the design doc.

Today the UI cannot distinguish imported results from a real Aerie run — staleness is a pure revision comparison and no provenance is stored or displayed. Tolerable for resource profiles, which users upload knowingly. Questionable for spans: a timeline could show simulated activities Aerie never simulated, with nothing saying so.

Options to put to the maintainers, cheapest first:

1. A `simulation_dataset.source` column (`'aerie' | 'external'`) plus a UI badge.
2. A distinct dataset kind, modelled on `external_source`.
3. An explicit decision that provenance is out of scope.

**Recommendation: raise this before Stage 3 merges.** Adding the column later is a migration on a table that accumulates rows quickly, and retrofitting provenance onto already-imported data is not possible.

---

## Sequencing

```
Stage 0  ──▶  Stage 1  ──────────────▶  Stage 3  ──▶  Stage 4
                 │                         ▲
                 └──▶  Stage 2  ───────────┘
                    (independent)
```

Stage 2 is parallelizable and independently mergeable. Stage 3 depends on Stage 1 only for the surrounding tooling and confidence, not technically. Stage 4's *decision* should precede Stage 3's merge even though its implementation follows.

Rough effort, to be treated as order-of-magnitude: Stage 0 one day (mostly infrastructure), Stage 1 two to three days, Stage 2 one to two days, Stage 3 three to five days, Stage 4 unknown pending the decision.

---

## Risk register

| Risk | Severity | Mitigation |
|---|---|---|
| Local Aerie stack won't come up | **Blocking** | Stage 0 gate; nothing downstream is verifiable without it |
| Worker claims a synthetic `simulation_dataset` | High | Explicit handling in Stage 3; test with a worker running |
| Writing to a shared/staging database | High | `--dry-run` default posture; never target production; prefer a disposable instance |
| Provenance decided late | Medium | Raise before Stage 3 merges — retrofitting is impossible |
| `resource_type` needs raw SQL | Medium | May not matter (Stage 0.3 answers this); propose symmetric insert permissions upstream |
| Upstream declines the new action | Medium | Stages 1 and 2 stand alone and remain useful |
| Bundle size on import | Low | Reuse `tol2bundle`'s windowing; import a slice |

---

## What needs maintainer input

1. **Provenance** — the one genuine design question (§10.6).
2. **`resource_type` insert permissions** — is the asymmetry with `activity_type` deliberate?
3. **`extendExternalDataset` missing `checkPermissions`** — appears to be a bug; report separately from this work.
4. **Resource-name namespacing** — a format-level decision that shouldn't be made unilaterally.

Items 2 and 3 are worth filing regardless of whether any of this work proceeds.

---

## Definition of done

- A bundle from a non-Aerie engine imports into a live Aerie instance and displays in the stock UI, with activities and resources on one timeline.
- Round-trip tests pass in `e2e-tests/` against a real database.
- Imported results are distinguishable from native runs, or an explicit decision records why they need not be.
- No regression in existing external-dataset behaviour.
- The offline viewer and the import path consume the **same bundle artifact**, with no format divergence.

That last item is the one to guard hardest. The moment import needs a different shape than the viewer, the format has failed at its actual job — being easy to produce once and useful in more than one place.
