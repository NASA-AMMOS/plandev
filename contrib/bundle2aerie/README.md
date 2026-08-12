# bundle2aerie

Imports an [Aerie offline bundle](../../docs/OFFLINE_BUNDLE_VIEWER.md) (`bundleVersion 1.0.0`)
into a running Aerie/plandev instance via Hasura GraphQL. This is **Stage 1**
of [`docs/OFFLINE_BUNDLE_IMPORT_PLAN.md`](../../docs/OFFLINE_BUNDLE_IMPORT_PLAN.md):
profiles and activity directives only, zero backend changes. Read that
document (and §10 of `OFFLINE_BUNDLE_VIEWER.md`) for the full research this
tool implements.

Pure Python 3 standard library. No dependencies beyond the interpreter (an
optional `jsonschema` install gives full JSON-Schema validation instead of
the bundled minimal fallback — see "Schema validation" below).

## The headline caveat

**There is no live Aerie instance available in the environment this tool was
originally built in, and most of it has never been run against one.** Every
piece of logic that decides *what* GraphQL to send is unit-tested as a pure
function with no network involved (`test_bundle2aerie.py`, 67 tests, all
passing). The parts that are genuinely unverified are called out explicitly
below. Treat this as
"believed correct by inspection and derivation from the UI's own GraphQL,"
not "proven against a server."

**Update — `--create-view` (see below) has since been exercised against a
live Hasura instance.** `build_view_definition` was verified there to
produce a definition that (a) matches, field-for-field, an actual
`generateDefaultView` output captured from plandev-ui for a live
18-resource plan, (b) validates against the vendored
`schema/ui-view-schema-v3.json` both before insertion and after a round
trip through `insert_view_one` / `view_by_pk`, and (c) when generated for
all 42 resources of a live 254-directive NISAR import (plan 4 in that
instance), the inserted view's 42 resource-layer `filter.resource` values
exactly matched the plan's 42 `profile.name` values queried independently
from the database. Everything else in this document's caveats (plan /
directive creation, `addExternalDataset`) is unaffected and remains as
originally written.

## Usage

```
python3 bundle2aerie.py BUNDLE.json --hasura-url URL [--auth-token TOK] [--role ROLE] \
    (--plan-id N | --create-plan --model-id N) \
    [--create-view [--ui-base-url URL] [--view-name NAME] [--view-schema PATH]] \
    [--dry-run] [-v]
```

- `--plan-id N`: import into an existing plan.
- `--create-plan --model-id N`: create a new plan from the bundle's `plan`
  section, attached to mission model `N` (must already exist in Aerie —
  this tool does not create mission models or `activity_type` rows; that is
  Stage 0.3 / Stage 3 territory per the import plan, out of scope here).
- `--create-view`: after importing, generate a UI view definition (one
  "Activities by Type" row plus one row per bundle resource — `line` for
  `real` resources, `x-range` for `discrete` resources, exactly matching
  what plandev-ui's own `generateDefaultView` would produce *if* the plan
  had a mission model to enumerate resource types from) and insert it via
  `insert_view_one`. Prints the new view id and a ready-to-open
  `<ui-base-url>/plans/<planId>?viewId=<viewId>` URL. This exists because an
  imported plan has `model_id: null`, so `$resourceTypes` is empty and a
  freshly-opened plan shows *no* resource rows at all — see
  `docs/OFFLINE_BUNDLE_IMPORT_PLAN.md` "Problem" and `build_view_definition`'s
  docstring in `bundle2aerie.py` for the full derivation. Always validated
  against `schema/ui-view-schema-v3.json` before insertion (see "View
  definition validation" below); under `--dry-run`, the definition is
  printed and nothing is inserted, same as every other mutation.
- `--ui-base-url URL`: base URL used to build the printed view URL with
  `--create-view` (default: `http://localhost:3000`).
- `--view-name NAME`: name for the generated view (default: derived from
  the bundle's plan name).
- `--view-schema PATH`: override the vendored view-schema location (default:
  `schema/ui-view-schema-v3.json` beside this script).
- `--dry-run`: **print every mutation and its variables to stderr without
  sending anything.** This writes to a shared database, so dry-run is a
  first-class feature, not a nicety — it is the default recommendation for
  a first run against any instance. `--dry-run` never imports or calls
  `urllib.request.urlopen` (see `TestDryRunProducesNoHttp` in the test
  file, which asserts this by monkeypatching `urlopen` to raise if called).
- `-v` / `--verbose`: log each directive's bundle-id → real-id mapping and
  anchor resolution as it happens.
- `--no-validate`: skip JSON Schema validation of the bundle (not
  recommended).
- `--schema PATH`: override the schema location (default: see below).

On success, a summary (directive count, resource/profile count, total
segment count, and the target plan id) is printed to stderr.

### View definition validation

Every generated view definition is validated before it is sent, against a
vendored copy of plandev-ui's `src/schemas/ui-view-schema-v3.json`, at
`schema/ui-view-schema-v3.json` in this directory. It is vendored (not
imported from a path inside plandev-ui) so this tool does not depend on the
UI repo being checked out beside this one. `TestViewSchemaVendoring` in the
test file compares the two copies byte-for-byte whenever plandev-ui *is*
available beside this repo, and skips (not fails) otherwise — so drift is
caught in any environment that has both repos checked out, without making
that a hard requirement for the rest of the test suite. Validation uses the
same `jsonschema`-else-fallback pattern as bundle validation (see
`validate_view_definition` in `bundle2aerie.py`).

### Schema validation

Every bundle is validated before anything is sent, against the same schema
the offline viewer and `tol2bundle` use:
[`contrib/tol2bundle/schema/offline-bundle-schema-v1.json`](../tol2bundle/schema/offline-bundle-schema-v1.json).
There is no separate copy in this directory — `bundle2aerie.py` imports
`tol2bundle` as a module and calls its `validate_bundle()` directly, so a
fix to validation in one place benefits both tools and there is exactly one
schema file to keep in sync with `plandev-ui/src/schemas/offline-bundle-schema-v1.json`.

## The mapping

### Resources → `addExternalDataset`'s `profileSet`

Per `OFFLINE_BUNDLE_IMPORT_PLAN.md` Stage 1, verified (in that document,
against source, not against a live server — see caveat above) against
`PostProfileSegmentsAction.java:36-54`:

| Bundle | `addExternalDataset` |
|---|---|
| `resources[].name` | `profileSet` object key |
| `resources[].type` | `type` (`"discrete"` \| `"real"`) |
| `resources[].schema` | `schema` |
| `resources[].segments[].extent` | `segments[].duration`, **as an integer number of microseconds** |
| `resources[].segments[].dynamics` (or `isGap: true`) | `segments[].dynamics` — `null` marks a gap |

Both sides are per-segment **deltas** — there is no prefix-sum in either
direction, unlike the offline *viewer's* loader (which does prefix-sum
`extent` into cumulative `start_offset` for client-side sampling — a
completely different, unrelated transform that does not apply here).

`datasetStart` is the bundle's `simulation.simulationStartTime`, passed
through unchanged (`derive_dataset_start()`); the server computes
`offset_from_plan_start` itself. `simulationDatasetId` is always `null` —
Stage 1's explicit decision to attach profiles at the plan level, since a
bundle has no Aerie simulation run to bind to.

**Verified**: the mapping logic itself, and its direction (deltas, not
cumulative), by inspection of `PostProfileSegmentsAction.java` and the
`ExternalDatasetsTest.java` e2e fixture (five 1-hour segments →
`3600000000` microseconds each — `test_extent_converted_to_integer_microseconds_matching_e2e_fixture`
in the test file mirrors that fixture's numbers exactly).
**Unverified**: whether the server actually accepts these mutations —
never run against a live Hasura.

### Plan and activity-directive mutations

There was no live server to spike this against, so the plan-creation and
directive-insert mutations are derived from **plandev-ui's own GraphQL and
the effects that call it** — the UI is the authoritative, working client
for these mutations, even though we could not confirm the round trip
ourselves. Every builder function in `bundle2aerie.py` cites the exact
`plandev-ui` source line it mirrors:

| bundle2aerie | Mirrors (plandev-ui, at the commit this was written against) |
|---|---|
| `CREATE_PLAN_MUTATION` | `src/utilities/gql.ts:386-402` (`CREATE_PLAN`), resolving to `insert_plan_one` (`src/enums/gql.ts:150`) |
| `build_plan_insert_input()` | `src/utilities/effects.ts:1749-1754` (the `planInsertInput` object in `createPlan`) |
| `CREATE_ACTIVITY_DIRECTIVE_MUTATION` | `src/utilities/gql.ts:143-169` (`CREATE_ACTIVITY_DIRECTIVE`), resolving to `insert_activity_directive_one` (`src/enums/gql.ts:124`) |
| `build_activity_directive_insert_input()` | `src/utilities/effects.ts:1072-1081` (the `activityDirectiveInsertInput` object in `createActivityDirective`) |
| `UPDATE_ACTIVITY_DIRECTIVE_ANCHOR_MUTATION` | `src/utilities/gql.ts:3819-3824` (`UPDATE_ACTIVITY_DIRECTIVE`), resolving to `update_activity_directive_by_pk` (`src/enums/gql.ts:229`) |
| `HasuraClient` header shape | `src/utilities/requests.ts:166-186` (`reqHasura`) — `Authorization: Bearer <token>`, `x-hasura-role`, `Content-Type: application/json` |

`ADD_EXTERNAL_DATASET_MUTATION` is the one exception: it comes directly from
this repo's own `deployment/hasura/metadata/actions.graphql:16-21`, not from
the UI, since it's a first-class Hasura action rather than a table mutation.

**Role**: `--role` defaults to `user`. Per `OFFLINE_BUNDLE_VIEWER.md` §10.2,
`addExternalDataset` permits `aerie_admin` or `user` plus a fine-grained
`insert_ext_dataset` check seeded as `PLAN_OWNER`; plan/directive inserts
require ordinary owner/collaborator permissions on the target plan
(`plandev-ui/src/utilities/permissions.ts:411-416,480-482`). Use
`--role aerie_admin` if the account doesn't already own the target plan.

#### Anchors: a deliberate departure from the UI

The UI always inserts a directive with `anchor_id: null` — a user creates
one directive at a time, then anchors it in a later edit. A bundle can have
directives that reference each other's `anchorId` from the start, but Aerie
assigns real directive ids at insert time, so a bundle's own ids cannot be
used directly. `bundle2aerie` handles this with two passes:

1. Insert every directive with `anchor_id: null`, recording
   `bundle id → real id`.
2. For every directive whose bundle `anchorId` resolves to another inserted
   directive, send a second `UPDATE_ACTIVITY_DIRECTIVE_ANCHOR_MUTATION`
   setting the real `anchor_id`.

An `anchorId` that doesn't resolve (e.g. it pointed outside the bundle) is
skipped, not fabricated — the same policy `tol2bundle` uses for spans whose
parent fell outside a `--start`/`--end` window.

This two-pass behavior is unit-tested
(`TestAnchorUpdatePass`, `TestActivityDirectiveInsertInput`) but, like
everything else here, never run against a live server.

#### What the UI does that this tool intentionally skips

`effects.ts:createPlan` also calls `initialSimulationUpdate` after inserting
the plan, to set the simulation template/arguments/bounds on the
auto-created `simulation` row. `bundle2aerie` does not call it: the
`simulation` row is created automatically by a Postgres trigger
(`merlin.create_simulation_row_for_new_plan`, `deployment/postgres-init-db/sql/tables/merlin/plan.sql:80-93`)
with sensible defaults, and Stage 1 never runs an Aerie simulation against
the imported plan (`simulationDatasetId` is always `null`), so there is
nothing to configure.

### Duration and timestamp spellings

Per the bundle schema's `duration` definition, four spellings are accepted
and all are handled by `parse_duration_to_microseconds()`:

- Aerie signed duration: `+11:39:55.219000` / `-00:00:05`
- Postgres interval: `02:27:15.059`
- ISO-8601 duration: `PT2H27M15.059S`
- A bare integer (or float, rounded) number of microseconds

This parsing is new code, not a copy of anything in `tol2bundle` —
`tol2bundle` only ever *produces* the Aerie signed spelling (see its
`format_duration_seconds`); it never has to parse an unknown spelling back
into a number, since its input (TOL XML) carries its own separately-typed
duration attribute. `bundle2aerie` genuinely needs this because a bundle
may come from `tol2bundle`, from `stateless-aerie --bundle`, or from any
third-party producer using any of the four spellings.

Timestamps (`plan.startTime`, `simulation.simulationStartTime`, used for
`start_time` and `datasetStart`) are passed through **unchanged** — no
reformatting. Both the Aerie day-of-year and ISO-8601 spellings the schema
allows are accepted directly by Postgres's `timestamptz` type, per the
comment at `plandev-ui/src/utilities/effects.ts:1753`
(`// Postgres accepts DOY dates for its 'timestamptz' type.`) — the same
fact `effects.ts:createPlan` relies on for `plan.start_time`.

### What is reused vs. new

- **Reused, unmodified**: `contrib/tol2bundle/tol2bundle.py`'s
  `parse_timestamp` (via `import tol2bundle as t2b`) and its
  `validate_bundle()` (JSON-Schema validation with a minimal fallback), and
  the single vendored schema copy at
  `contrib/tol2bundle/schema/offline-bundle-schema-v1.json`. `tol2bundle.py`
  was **not modified** by this work — its 29 tests are unaffected and still
  pass (`python3 -m unittest` in `contrib/tol2bundle/`).
- **New**: duration→microseconds parsing for all four spellings
  (`parse_duration_to_microseconds`, `microseconds_to_pg_interval`), the
  `profileSet` builder, the plan/directive mutation builders, the anchor
  two-pass, and the thin `HasuraClient` / `DryRunClient` HTTP layer.

## Testing without a server

Every mapping/mutation-building function is pure: it takes plain dicts and
returns plain dicts, with no `urllib` import in sight. `HasuraClient` is the
only thing in `bundle2aerie.py` that touches the network, and it's a thin
wrapper — construct a `urllib.request.Request` with JSON body and the three
headers above, POST it, raise `GraphQLError` on an HTTP error or a
`{"errors": [...]}` GraphQL response, otherwise return `data`. Nothing about
it could plausibly be wrong in a way unit tests would need a real server to
catch; what unit tests *can't* catch is whether the server's actual schema
and permissions match what's assumed here, which is exactly the "unverified"
list below.

`test_bundle2aerie.py` runs entirely offline:

```
python3 -m unittest -v
```

47 tests, all passing, 0 skipped except one deliberately-gated integration
smoke test (`TestLiveIntegrationSmoke`) that only runs if `AERIE_URL` (and
`AERIE_MODEL_ID`, optionally `AERIE_AUTH_TOKEN`) is set in the environment —
`@unittest.skipUnless(os.environ.get('AERIE_URL'), ...)`. That test was
never run during development of this tool, for the reason stated at the top
of this document.

## What remains unverified pending a live instance

Everything that requires an actual Hasura/Postgres round trip:

1. That `CREATE_PLAN_MUTATION` / `CREATE_ACTIVITY_DIRECTIVE_MUTATION` /
   `UPDATE_ACTIVITY_DIRECTIVE_ANCHOR_MUTATION`, mirrored from the UI's
   GraphQL, actually match the live schema (field names, nullability,
   permission checks) rather than a snapshot of `plandev-ui` source that
   may have drifted.
2. That `addExternalDataset` accepts a `profileSet` built this way — the
   *shape* is confirmed against `PostProfileSegmentsAction.java` and the
   e2e fixture's numbers, but the actual mutation has never been sent.
3. That the auto-created `simulation` row (via the Postgres trigger) really
   does need no further configuration for Stage 1's `simulationDatasetId:
   null` external-dataset attachment to render correctly in the UI. Per
   `OFFLINE_BUNDLE_IMPORT_PLAN.md` Stage 1's "Expected caveat": resources
   will only render if a simulation dataset is *selected* in the UI
   (`Row.svelte:224`) — this is a known, documented UI limitation fixed by
   Stage 2, not a bug in this tool, but it means **a freshly-imported plan
   with no prior simulation run will show no resource rows until Stage 2
   lands or a simulation is run**, and that has not been visually confirmed.
4. Whether `--role user` is sufficient in practice or `aerie_admin` is
   needed, depending on plan ownership on the target instance.
5. Whether the anchor two-pass update actually succeeds against
   `update_activity_directive_by_pk`'s real permission checks.
6. Any error-message shape from the server — `GraphQLError` surfaces
   whatever Hasura returns verbatim, untested against real error payloads.

None of this blocks Stage 1 from being useful in `--dry-run` today (it
requires no server) or from being pointed at a real instance by someone who
has one — it's simply the boundary of what could be checked in this
environment.
