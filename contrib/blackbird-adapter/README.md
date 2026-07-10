# Blackbird external-model backend (spike)

This is a **proof-of-concept adapter** that lets PlanDev run a [Blackbird](https://github.com/nasa-jpl/Blackbird)
mission model as a *foreign* (`model_type = 'external'`) backend — no Merlin JAR, no hand-port to
`merlin-framework`. PlanDev owns the plan/directives and UI; Blackbird does the simulation.

> Status: spike / demonstration. It exercises the real `external` backend seam end-to-end
> (registration → validation → the native Simulate route → resource plots, spans, constraints),
> but is not production-hardened. See **Known gaps** below.

## How it fits together

```
   PlanDev UI ──Simulate──▶ merlin-server/worker ──HTTP POST /simulate──▶ bb_service.py ──▶ Blackbird (JVM)
        ▲                   (model_type='external',                         (this adapter)        │
        │                    external_backend_url)                                                │
        └──────── resource plots / spans / constraints ◀── native simulation_dataset ◀────────────┘
```

* **`bb_service.py`** — the backend service PlanDev's *native* simulation route calls. It receives
  `{planStart, duration, configuration, directives[]}`, builds a Blackbird `.plan.json`, runs
  `OPEN_FILE … unfrozen decompose → REMODEL → WRITE`, translates the XMLTOL output, and returns
  `{realProfiles, discreteProfiles, spans}`. This is the path the **Simulate button** drives.
* **`bb_adapter.py`** — the *push* variant: runs Blackbird and pushes type metadata + one run's
  results into PlanDev via the `registerModelTypes` / `ingestExternalSimulationResults` Hasura
  actions. Useful for one-off ingestion and for **registering activity/resource types**.

Both share the same XMLTOL translation logic (real-vs-discrete profiles, arrayed-resource
flattening to dotted names, `IntegerValue`/duration handling, decomposition parent linking,
directive↔span correlation).

## Build & run (container)

Blackbird and jplTime are cloned at build time. JNISpice is a compile-time dependency of jpl_time
and is **not** on Maven Central — obtain `JNISpice-v2022-05.jar` (from the NAIF/JPL SPICE toolkit,
matching jplTime `2025-10a`) and drop it in `./vendor/`:

```bash
mkdir -p vendor && cp /path/to/JNISpice-v2022-05.jar vendor/
docker build -t plandev/blackbird-adapter .
docker run --rm -p 5001:5001 plandev/blackbird-adapter
```

The service listens on `:5001` and logs `activity types: N, resource initials: M` on startup.

### Run without Docker (dev)

```bash
export BLACKBIRD_CP="<blackbird target/classes>:<blackbird deps>/*"
export JPLTIME_LIB="<dir with libJNISpice.so|.jnilib>"   # only needed if the model uses SPICE
python3 bb_service.py 5001
```

## Register a Blackbird model in PlanDev

1. Insert a `merlin.mission_model` row with `model_type='external'` and
   `external_backend_url='http://<host>:5001/simulate'` (Docker-on-host: `http://host.docker.internal:5001/simulate`).
2. Push type metadata (activity types, resource types, config params) via the `registerModelTypes`
   action — `bb_adapter.py` derives these from Blackbird (`CREATE_DICTIONARY` for activities; a
   zero-activity `REMODEL` `ResourceSpec` dump for resources — resource types are knowable
   **before** any real simulation).
3. Create a plan against that model. From here the UI is identical to a JAR model: build/validate
   directives, hit **Simulate**, and results land as a first-class `simulation_dataset`.

## Wire contract (`POST /simulate`)

```jsonc
// request
{ "planStart": "2020-01-01T00:00:00Z", "duration": 7200000000,  // µs
  "configuration": { },
  "directives": [ { "id": 6, "type": "ActivityOne", "startOffset": 3589806000, "arguments": {"d": 300000000} } ] }

// response
{ "realProfiles":     { "<name>": { "schema": {..}, "segments": [ {"duration": µs, "dynamics": {"initial": n, "rate": n}} ] } },
  "discreteProfiles": { "<name>": { "schema": {..}, "segments": [ {"duration": µs, "dynamics": <SerializedValue>} ] } },
  "spans": [ { "spanId": 1, "type": "..", "startOffset": µs, "duration": µs,
               "arguments": {..}, "parentId": <spanId|null>, "directiveId": <id|null> } ] }
```

* **Arrayed resources** are flattened to dotted names (`PositionVector.x`, `ExampleBodyState.Earth.x`).
* **Decomposition** children carry `parentId` (their parent span); Blackbird spawns them during the run.
* **`directiveId`** links a top-level span back to the PlanDev directive that produced it (via a stable
  `uuid5` correlation id); spawned/dispatched spans have `directiveId: null`.

## Known gaps

* **`map<string,comparable>` params** (e.g. `InitialConditionActivity.initialValues`) don't map cleanly
  onto `ValueSchema`; they register as `string`, so the UI can't offer the right editor and bad input
  isn't caught until Blackbird rejects it.
* One resource with no declared initial value falls back to its first observed sample at t=0.
* Determinism is assumed (`(config, directives) → results`); nondeterministic models are unsupported
  for the edit→re-sim→re-ingest round-trip.
* Registration is currently a manual action call — there is no "register external model" UI yet.
* SPICE-based models need the native lib mounted and kernels (`LOAD_KERNELS`) loaded.
