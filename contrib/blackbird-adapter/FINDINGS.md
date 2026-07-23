# Foreign Model Backend — Findings (Blackbird pilot)

_PlanDev architecture spike · 2026-07-10 · branch `feat/foreign-model-backend` · commit `b4cfb9238`_
_Shareable report: https://claude.ai/code/artifact/6c126665-91c6-46b7-8a58-a647d0bc96af_

## TL;DR

PlanDev can now register, validate, edit, **simulate**, and visualize a model it never compiled —
the **Simulate button drives Blackbird** over HTTP and the results come back as a first-class PlanDev
simulation. Directives in PlanDev → simulate → sent to Blackbird → Blackbird simulates → results
(resource profiles + activity spans) render natively. This is a working prototype, validated live
end-to-end and containerized; it is **not** production-hardened.

### Build-out progress since initial findings

Two hardening items from the list below have since shipped on this branch (both validated live):

- **Anchor resolution in the external simulate path** (`0dfa04547`) — offsets are now resolved via
  `StartOffsetReducer` and keyed to simulation start (previously raw offsets were sent, so any anchored
  directive simulated at the wrong time silently). Directives anchored to the *end* of another activity
  are rejected with a clear error rather than misplaced.
- **Wire-delegated validation** (`639d81303`) — the model is now the validation authority: argument
  validation + effective-args delegate to the backend's `/validate` endpoint, with graceful fallback to
  the shallow stored-schema check when the backend is unreachable. This catches, at *validation* time,
  bad arguments a model rejects (e.g. `InitialConditionActivity {initialValues:"111"}`) that the stored
  `ValueSchema` would have passed.

## Why

The only way to run a Blackbird model in Aerie today is to **rewrite it as a Merlin model** (the
incumbent `aerie-multimission-models-bb` is exactly that ongoing hand-port). But PlanDev is already
largely model-agnostic *above* the simulator: the UI reads four Postgres tables, and procedural
scheduling + both constraint engines operate purely on simulation output (string-keyed resource
profiles + activity spans as `SerializedValue`). The bet: let a foreign framework **bring its own
simulator** and integrate at the results + type-metadata level, rather than re-implementing it as
Merlin cells.

## What we built (35 files, +1,662)

**Core (`merlin-server`)**
- Model-type discriminator: `mission_model.model_type` (`jar`|`external`) + `external_backend_url`;
  `jar_id` nullable; model queries INNER→LEFT JOIN. Migrations 37, 38.
- External branches in `LocalMissionModelService` for validate / effective-args / refresh / resource
  schemas / model params.
- Native simulate path: `runSimulation` routes external models to `ExternalSimulationBackend`, which
  POSTs the plan's directives to the backend and ingests profiles + spans into a normal
  `simulation_dataset` (profiles via the resource manager, spans via `SimulationResults` incl.
  decomposition parent + directive→span linkage).
- Push ingestion path: single-transaction insert that defeats the `notify_simulation_workers` claim
  race; topo-sorted span insertion.
- Hasura actions `registerModelTypes`, `ingestExternalSimulationResults` + parsers.

**Adapter (`contrib/blackbird-adapter`)**
- `bb_service.py` — the backend the Simulate button calls: directives → Blackbird `.plan.json` →
  `REMODEL` → translate XMLTOL → profiles + spans.
- `bb_adapter.py` — push variant (registers types, ingests a run).
- Translation: real vs discrete profiles, arrayed-resource flattening (`PositionVector.x`),
  int/duration/variant handling, decomposition parent + directive↔span correlation.
- Multi-stage Dockerfile (clones + builds Blackbird + jplTime) + README.

## How it works

```
PlanDev UI --Simulate--> merlin (model_type=external) --HTTP POST--> bb_service /simulate
   ^                                                                        |
   |                                                                     REMODEL
   |                                                                        v
   +---- resource plots / spans / constraints <-- native simulation_dataset <-- Blackbird (JVM)
```

Key decision: the foreign sim lands as **one first-class `simulation_dataset`**, not on the
second-class external-dataset rails, so it renders exactly like a Merlin sim. PlanDev's directive set
stays the source of truth; everything Blackbird produces internally (decomposition, forward-dispatch)
comes back as **spans**, preserving "sim = pure function of the directive set."

## Integration archetypes: two ways a foreign model plugs in

The variable that matters most is **who places the activities**. Foreign frameworks fall into two
archetypes, and they use PlanDev very differently. The same `external` backend + wire contract serves
both — only the scheduling integration differs.

### Archetype B — scheduler + simulator (Blackbird)

The model does its own scheduling *during* the sim (forward-dispatch: conditions fire; `decompose`/`spawn`
and dispatch place activities as time advances). Running the sim yields a **fully-placed plan** — the
schedule is an *output*, not an input. Consequences:

- You do **not** run PlanDev's scheduler against it — that would pit two schedulers against each other
  (your goal places A, the model's forward-dispatch then places B/C/D on top).
- PlanDev is the **plan editor / resource visualizer / constraint checker**. The user edits top-level
  directives; the adapter round-trips the edited directive set to the framework for a fresh run and
  re-ingests the result.
- Everything the framework creates internally (decomposition, dispatched activities) comes back as
  **spans**, not directives — preserving "sim = pure function of the directive set."
- A whole-plan re-sim per edit is the *native* workflow (Blackbird has no checkpoint/fork), so "no
  incremental re-sim" is not a regression here — it's how the framework already works.
- **This is the archetype we built and validated.**

### Archetype A — pure simulator (mirrors PlanDev's own layering)

The model is a pure function `directives → profiles + spans` with **no internal scheduling** — exactly
the contract Merlin itself honors (simulation is deterministic in the directive set; *placement* is a
separate layer above it). Consequences:

- PlanDev's **own scheduler places activities**, calling the foreign `simulate()` as an oracle in a loop
  (procedural goals, via a `PlanEditAdapter` over the `SimulationBackend`).
- PlanDev is the full stack: authoring + scheduling + simulation + constraints + viz.
- Cost is a **full foreign re-sim per scheduler iteration** — a performance question (fine if a single
  sim is cheap), not a correctness one. A checkpoint/incremental-resim capability in the foreign engine
  would remove this cost.
- A Python effect-model (SimPy-style, or the `aerie-python-prototype` engine) is the canonical
  Archetype A.

| | Archetype B — Blackbird | Archetype A — pure simulator |
|---|---|---|
| Who places activities | the model, during the sim | PlanDev's scheduler |
| PlanDev's role | editor / visualizer / constraint-checker | full stack incl. scheduling |
| Scheduling UI + goals | hidden / N/A | active (procedural goals) |
| Re-sim cost | whole-plan, native | one full re-sim per scheduler iteration |
| Extra adapter work | round-trip edited directives → framework | `PlanEditAdapter` over `SimulationBackend` |
| Simulation seam | `simulate(directives) → profiles+spans` + native ingestion | same |

### Bringing other models in (beyond Blackbird)

Core ships the **mechanism**, not per-framework knowledge: a small fixed set of backend kinds (`jar`,
`external`) plus a language-neutral wire contract that mirrors the already-serializable protocol types
(`SerializedValue`, `ValueSchema`, `Duration`, `RealDynamics`). A specific framework is then an **adapter
that conforms to a backend kind**, shipped alongside the model — *not* a core change per framework. End
users pick a backend kind and point at an adapter; they never modify PlanDev.

To onboard a new model, a framework answers four things over the wire:

1. **Introspect types** → activity types, resource types, config params as `ValueSchema`.
2. **Validate / effective-args** (the wire-delegated-validation item) — needed for any model with
   defaults or validation logic.
3. **Simulate** `(planStart, config, directives) → {realProfiles, discreteProfiles, spans}`.
4. *(Archetype A only)* tolerate being called repeatedly as a scheduling oracle.

Candidate stacks:

- **Python** (Archetype A) — a `python-model-server` sibling service reusing the `aerie-python-prototype`
  engine blueprint (generator-tasks + event-sourced cells) with domain physics from pure-calculation libs
  (SpiceyPy / Skyfield / Orekit / hapsira / NumPy) called inside activities, plus a small authoring SDK
  (decorators → `ValueSchema`).
- **Other JVM DES frameworks** — like Blackbird, run as an external process if they target a different
  Java version.
- **Domain-specific / analytic models** — anything that can emit resource time-histories + activity spans
  (a power / thermal / link-budget model) fits Archetype A at the "bring-your-own-simulator" level.

Every new adapter must reckon with the same two constraints: the **determinism contract**
(`(config, directives) → results`, required for the edit→re-sim→re-ingest loop) and the **type-fidelity
limits** of `ValueSchema` (e.g. Blackbird's dynamic `map<string,comparable>` params don't map cleanly).

## Validated live

- **Simulate button drives Blackbird end-to-end** — results persist as a native simulation and render.
- **Rich coverage** — 27 resource profiles (real, int, duration, variant, multi-dimensional arrayed);
  decomposition span trees; values match the model's math (e.g. `ResourceA = π × amount`).
- **Directive↔span linkage** via `attributes.directiveId`; decomposition children correctly carry none.
- **Resource types known pre-sim** — a zero-activity `REMODEL` emits all resource specs, so the type
  catalog is populated at registration, as PlanDev expects.
- **No JAR-model regression; notify-race defeated** (adversarially confirmed).
- **Runs containerized** — committed Dockerfile builds + boots; merlin drives the container unchanged.
- **Permissions unchanged** — Simulate is not admin-gated (`user` role + plan ownership).

## Activity-behavior fidelity (audited)

All 6 activity types in the test plan verified **CORRECT** — audited by 6 independent readers, each
adversarially re-checked, plus a cross-activity resource reconciliation (13 agents). **Zero genuine
discrepancies.**

| Activity | Verdict | Note |
|---|---|---|
| ActivityOne | CORRECT | 3 instances (2 decompose children + 1 directive); ResourceA/B + PositionVector.y effects land at start & start+1min. |
| ActivityTwo | CORRECT | Spawns 2 ActivityOne; deferred `ResourceA = π·3` + `PositionVector.x += 3` at start+2min. |
| SignalSendingActivity | CORRECT | ×2; one `ResourceA −1` each; both signals sent (confirmed via TestState). |
| WaitingOnSignalActivity | CORRECT | Signal loop: NoSignal → "SignalSent: after" → NoSignal after 5s, twice. |
| ExampleScheduler | CORRECT | Correct no-op: gate `PositionVector.y > 5.0` never met (max 0.03). |
| GetWindowsActivity | CORRECT | Correct no-op: year-2000 window search over 2020 plan → empty → zero children. |

Subtle-but-correct behaviors the reconciliation confirmed: cross-activity interference (child B's
`+20.5` on the `π·3` value → 29.9248, not isolated 35.5), same-instant write collapse, span-length vs
effect-time decoupling, and the signal `result="after"` branch (confirmed via the coupled TestState flip).

## What's left to build

| Item | Severity |
|---|---|
| ~~Wire-delegated validation~~ — **✅ shipped** (`639d81303`, `9134897ad`): model is the authority; presence-first + effective-args from dictionary defaults | done |
| `SimulationBackend` as a real serialization boundary; make `registerModelTypes` atomic | MAJOR |
| ~~End-anchor resolution~~ — **✅ shipped** (`0dfa04547`): anchors resolved via `StartOffsetReducer`; end-anchors rejected with a clear error | done |
| Registration UI ("register external model" flow; currently a manual action call) | MODERATE |
| UI type-support surface — **Monaco typings ✅ already work** for external models (verified live: `getActivityTypeScript`, `constraintsDslTypescript`, `schedulingDslTypescript` all succeed off stored metadata). Remaining: `CHECK_MODEL_COMPATIBILITY_FOR_PLAN` + confirm `anchor_validation_status` for external | MINOR |
| **UI: surface validation notices with empty `subjects`** — external (Blackbird) validation errors are *whole-activity* (no per-parameter attribution), so their notices carry `subjects:[]`; `plandev-ui` drops these at every render surface (`errors.ts:167-173` rollup + `effects.ts:8982-8990` inline map both key on parameter name), so a real `success:false` failure shows no badge/inline error and even prints "has 0 validation errors". Fix in `plandev-ui`: attribute subject-less notices at the directive level (fallback in `errors.ts`), optionally + an adapter heuristic to recover the param from the message. **Deferred.** | MODERATE |
| Computed-attributes synthesis (expansion + finished/unfinished span classification) | MODERATE |
| Sim cache key must include external model + config version | MODERATE |
| Pure-simulator scheduling (`PlanEditAdapter` over backend; Archetype A only, N/A for Blackbird) | MODERATE |
| Round-trip polish (incremental edits, 400 vs 500 error mapping) — anchor→absolute now done | MINOR |
| Type fidelity (`map<string,comparable>` → ValueSchema), concurrency, SPICE cold-start | MINOR |
| Generalize to Python backend over the same wire contract | MINOR |

## Open questions & decisions

- **Adapt vs. keep porting?** Adapter vs continuing to hand-port models to Merlin — driven by model size / rate of change.
- **Whose scheduler / sequencer?** Blackbird ships its own forward-dispatch scheduler + SASF/SATF; for a scheduler+simulator model PlanDev is editor/visualizer/constraint-checker, not scheduler.
- **Constraints — re-author in PlanDev (portable/editable) or ingest Blackbird TOL (read-only, full fidelity)?** They can coexist.
- **Dynamic types** — how to represent BB `map<string,comparable>` in `ValueSchema`.
- **Deployment topology** — backend per model vs shared; scaling + cold-start for process-per-request JVM sims (+ SPICE kernels).
- **Determinism contract** — round-trip assumes deterministic `(config, directives) → results`; flag nondeterministic models as unsupported.

## Bottom line

The read side is genuinely model-agnostic, and the spike proves a foreign simulator can plug in at the
results + type-metadata seam and behave like a first-class PlanDev model — live, containerized,
end-to-end. Remaining cost is concentrated in validation delegation, a clean backend abstraction, and
the UI/registration surface — real but bounded. Recommended next step: a thin production slice
(wire-delegated validation + registration UI) behind the adapt-vs-port decision.
