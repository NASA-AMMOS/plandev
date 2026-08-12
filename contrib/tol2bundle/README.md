# tol2bundle

Converts a JPL "TOL XML" (Timeline Object List) file into an Aerie
offline-bundle JSON file (`bundleVersion 1.0.0`) that the Aerie UI can load
without a backend.

Pure standard library (`xml.etree.ElementTree`, `json`, `argparse`,
`dataclasses`). If the `jsonschema` package is installed it is used to
validate the output against the bundled copy of the schema; otherwise a
minimal hand-rolled required/type/enum validator is used instead (see
`validate_bundle()` in `tol2bundle.py`).

## Usage

```
python3 tol2bundle.py INPUT.tol.xml -o OUTPUT.json \
    [--start T] [--end T] [--max-activities N] [--plan-name NAME] [-v]
```

- `--start` / `--end`: only include TOL records whose `<TimeStamp>` falls in
  `[start, end]`. Accepts either TOL's native day-of-year form
  (`2024-183T00:00:00[.ffffff]`) or ISO-8601 (`2024-07-01T00:00:00Z`).
- `--max-activities N`: stop accepting new `ACT_START` records once N have
  been accepted (does **not** cap resource segment counts — see "Sizing a
  slice" below).
- `--plan-name NAME`: name for `plan.name` (default: input filename with
  `.tol.xml`/`.xml` stripped).
- `--schema PATH`: override the schema used for validation (default: the
  copy vendored at `schema/offline-bundle-schema-v1.json`).
- `--no-validate`: skip validation (not recommended).
- `-v` / `--verbose`: print warnings (fallback-to-discrete, missing
  ResourceSpec, etc.) as they occur, not just the summary count.

A run summary — record counts per type, how many were dropped by the time
window vs. by `--max-activities`, matched/unmatched activity counts, and
resource/segment counts — is always printed to stderr. Nothing is ever
silently truncated; every drop is counted and reported.

### Producing a browser-viewable slice of `nisarbb.tol.xml`

The full file spans `2023-029` (earliest `ACT_START`) through `2024-211`
(latest record) — about 20 months, 68,532 activities, and 872 resources.
Converting that whole range in one bundle would be enormous. An 8-hour
window in the middle of the file produces a reasonably sized, fully
representative bundle in a few seconds:

```
python3 tol2bundle.py /path/to/nisarbb.tol.xml \
    -o nisar_slice.json \
    --start 2024-060T08:00:00 --end 2024-060T16:00:00 -v
```

This is the exact command used for the smoke run below.

## The mapping

### `plan` / `simulation` time bounds

`plan.startTime` and `simulation.simulationStartTime` are the earliest
`<TimeStamp>` among records that **survive the `--start`/`--end` filter**
(not the whole file); `simulation.simulationEndTime` is the latest.
`plan.duration` is the difference, formatted as an Aerie signed duration.
TOL day-of-year timestamps are passed through verbatim (the target schema
accepts DOY natively); only ISO-8601 `--start`/`--end` arguments get
parsed, and only for comparison purposes.

### Resources (`<ResourceMetadata>/<ResourceSpec>`, `RES_VAL`/`RES_FINAL_VAL`)

- **Identity**: a resource's bundle name is its `<Name>` plus its
  `<Index level="N">` values, joined with `/`
  (`RESOURCE_NAME_JOINER` in `tol2bundle.py`), e.g.
  `downlinkCompleted/leftDBFAntennaPatternDM1_ch5`, or
  `isLsarYawTargetInView/slew/NASA_26_CR_BEAM7_ASC` for a two-level index.
- **Segments**: `RES_VAL` records for a given resource are sorted by
  timestamp (records are believed to already be chronological within the
  file, but we don't rely on that) and turned into segments where each
  segment's `extent` is `next_sample_time - this_sample_time` — a delta,
  never a running total. The final segment closes at the matching
  `RES_FINAL_VAL` timestamp if one was captured in the window, otherwise at
  the window's overall `simulationEndTime`.
- **Interpolation → resource type**: `<Interpolation>constant</Interpolation>`
  (or an empty/missing `<Interpolation>` — seen in `nisarbb.tol.xml` for a
  few specs) maps to `type: "discrete"` with `dynamics` set to the raw
  value. `linear` maps to `type: "real"` with
  `dynamics: {initial, rate}`, `rate` computed as
  `(nextValue - thisValue) / extentSeconds` (0 on the final segment). If a
  `linear` resource turns out to have non-numeric values, the converter
  falls back to discrete and emits a warning (exercised in
  `test_tol2bundle.py`'s synthetic fixture is *not* needed for this case in
  practice, since it never occurs in `nisarbb.tol.xml` — see "Lossy"
  below).
- **`DataType` → `ValueSchema`**: `boolean→boolean`, `integer→int`,
  `float→real`, `string→string`, `duration→duration` (an extra mapping not
  explicitly requested but present in the source data — `nisarbb.tol.xml`
  has resources typed `duration`, e.g. `totalAnalysisRemainingForCalibration`).
- A `RES_VAL`/`RES_FINAL_VAL` for a resource with **no** matching
  `ResourceSpec` is still converted (schema inferred from the value
  element's own tag) but counted/warned as "without spec" in the summary —
  did not occur in `nisarbb.tol.xml`, but the fixture doesn't need to cover
  it either since it can't happen without a malformed file.

### Activities (`ACT_START` / `ACT_END`)

- TOL has no "directive vs. simulated span" distinction, so every activity
  becomes **both** an `activityDirective` and a `span`, linked by
  `span.directiveId == activityDirective.id`.
- `ACT_START` and `ACT_END` are matched by `<Instance>/<ID>` /
  `<ActivityID>` (a UUID). Aerie ids must be integers, so a stable,
  insertion-ordered UUID→int map is built as `ACT_START` records are
  accepted (`IdAllocator` in `tol2bundle.py`).
- `<Instance>/<Parent>` becomes `span.parentId` (resolved through the same
  UUID→int map). It is **not** used for `activityDirective.anchorId` — TOL
  has no anchor concept, only a display hierarchy — so every directive gets
  `anchorId: null, anchoredToStart: true`, and `startOffset` is computed
  directly from `ACT_START`'s `<TimeStamp>` relative to
  `simulationStartTime`. If a parent's own `ACT_START` fell outside the
  time window (or was excluded by `--max-activities`), `parentId` is left
  `null` rather than pointing at an id that doesn't exist in the bundle.
- `span.duration`: if the `ACT_END` was matched, `ACT_END.<TimeStamp> -
  ACT_START.<TimeStamp>`. If unmatched (see below), falls back to the TOL
  `span` `<Attribute>` (a `<DurationValue>`) if present, else
  `+00:00:00.000000`.
- **Unmatched `ACT_START`**: an `ACT_START` with no matching `ACT_END`
  (either genuinely absent from the file, or its `ACT_END` fell outside
  `[--start, --end]`) is still emitted, with `metadata.unmatchedActEnd:
  true` / `attributes.unmatchedActEnd: true` so downstream consumers can
  tell duration is a fallback, not an observed value. Counted and reported
  as `unmatched ACT_START` in the summary — never silently dropped.
- `<Attributes>/subsystem` and `<Attributes>/legend` become
  `metadata.subsystem`/`metadata.legend` on the directive and
  `attributes.subsystem`/`attributes.legend` on the span.
  `<Visibility>` becomes `metadata.visibility`. The source UUID is kept as
  `metadata.uuid` on every directive for traceability back to the TOL file.
- `<Instance>/<Parameters>/<Parameter>*` become `activityDirective.arguments`
  (name → typed value).

### `activityTypes`

One entry per distinct `<Type>` seen among accepted `ACT_START` records.
`parameters` is inferred from the union of `<Parameter>` names/types
observed across all instances of that type (first-observed-type wins;
`order` reflects first-seen order). `requiredParameters` is always `[]`
(TOL has no such concept). `computedAttributesValueSchema` is always
`{"type": "struct", "items": {}}` (TOL has no computed-attributes concept
either). `subsystem` is set to the most frequently observed
`<Attributes>/subsystem` value for that type, when any instance had one.

## Lossy / unmodeled TOL constructs

Being rigorously honest about what does **not** round-trip:

- **`ERROR` and `RELEASE` records** (rule-violation events — 28 of each in
  `nisarbb.tol.xml`) have no representation in the offline-bundle schema
  (no constraint/violation concept exists there). They are parsed, counted,
  and reported in the summary ("unsupported record types dropped") but do
  not appear anywhere in the output. This is the single largest structural
  gap between TOL and the bundle format.
- **`TimeValue` parameters/attributes** (e.g. an activity `Parameter`
  holding an absolute time, like the `end` parameter seen on
  `GenericActivity` instances) are converted to plain `string` values —
  there is no `"timestamp"` `ValueSchema` type in the target schema. The
  DOY/ISO text is preserved verbatim, but the "this is a time" semantic is
  lost; a consumer would need to know out-of-band which string parameters
  are actually timestamps.
- **Mission-specific typed values** (`<YawValue>`, `<YawTargetValue>`,
  `<CommissioningPhaseValue>`, and any other `<FooValue>` tag not in
  `VALUE_TAG_TO_SCHEMA_TYPE`) fall back to `string`, using the element's
  text verbatim (e.g. `YawTargetValue` text
  `"YawTarget(yaw=left, target=AMAZON_1)"` is kept as one opaque string,
  not decomposed into a struct).
- **`<Instance>/<Parameters>`, distinct from `<Attributes>`**: TOL
  instances carry two separate name/value bags. We treat `<Parameters>` as
  the activity's arguments and hand-pick only `subsystem`/`legend`/`span`
  out of `<Attributes>` (the rest of `<Attributes>` — anything besides
  `start`/`span`/`subsystem`/`legend` — is currently dropped without
  individual reporting, since it wasn't observed in `nisarbb.tol.xml`
  beyond those four keys; if a future TOL file has more, they will
  silently not appear in the bundle).
- **`<PossibleStates>`** on string-typed `ResourceSpec`s (an enum of legal
  values) has no equivalent slot in the target `ValueSchema` for strings —
  it is not carried into the output at all.
- **`Units`/`Maximum`/`Minimum`** on `ResourceSpec` are read but not
  emitted; the target `ValueSchema` for `real`/`int` has no unit or
  range field to put them in (an activity *parameter* schema does support
  an optional `unit` field per the top-level schema, but resource schemas
  do not, and we did not fabricate a place to put resource units).
- **`--max-activities` does not cap resources.** It only limits how many
  `ACT_START` records are accepted; every `RES_VAL`/`RES_FINAL_VAL` in the
  time window is still converted. A wide `--start`/`--end` window with a
  small `--max-activities` can still produce a very large, resource-heavy
  bundle — see the smoke-run numbers below for how quickly that adds up
  (a 70-day window produced a 5.4MB bundle from 299 resources even with
  `--max-activities 50`).
- **Untested against real `linear`-interpolation / `float` resources.**
  `nisarbb.tol.xml`'s 872 `ResourceSpec`s use only `<Interpolation>` values
  `constant` and empty (`""`), and only `<DataType>` values `boolean`,
  `integer`, `string`, `duration` — **no `linear` or `float` resources
  exist in this file.** The `linear`→real/rate-of-change code path is
  exercised only by the synthetic fixture in `test_tol2bundle.py`, not
  against real TOL data. Treat that path as unit-tested but not
  field-verified.
- **Resource ordering assumption**: we sort each resource's samples by
  timestamp before computing extents, so out-of-order `RES_VAL` records
  (if any exist) are handled correctly; we did not exhaustively verify the
  real file is monotonic per-resource, only that overall record order
  appeared chronological in spot checks.

## Tests

```
python3 -m unittest test_tol2bundle -v
```

29 tests, all passing as of this writing. They build a small synthetic TOL
fixture (`fixtures/synthetic.tol.xml`, ~200 lines) covering:

- DOY timestamp parsing (including a leap-year day-of-year rollover) and
  ISO-8601 parsing.
- Duration formatting, including hours *not* rolling into days (30h stays
  `30:00:00`) and negative durations.
- Indexed resource naming (0, 1, and 2 `<Index>` levels).
- A constant-interpolation (discrete) resource and a linear-interpolation
  (real, with computed rate) resource, both closed by a `RES_FINAL_VAL`.
- **Extent-is-a-delta correctness**: an explicit assertion that segment
  extents are per-segment deltas, not cumulative offsets (the third
  segment of a 3-segment resource must read `+00:00:00.000000`, not
  `+01:00:00.000000`).
- A parent/child activity pair, verifying `span.parentId` links correctly
  and that `span.duration` comes from `ACT_END - ACT_START`, not blindly
  from the TOL `span` attribute (which happens to agree in the fixture, so
  the test picks values where a bug copying the attribute instead of
  computing the real delta would still be caught).
- An `ACT_START` with no `ACT_END`, verifying the `span`-attribute
  duration fallback and the `unmatchedActEnd` marker.
- `--start`/`--end` time-window filtering, including a regression test
  that `plan.startTime`/`simulationEndTime` reflect only the *filtered*
  records, not the whole input file (this was in fact a bug caught during
  development — the first implementation computed observed min/max
  timestamps before applying the window filter).
- `--max-activities` capping.
- Full JSON Schema validation of the converted synthetic bundle, plus a
  check that a deliberately broken bundle (missing `plan.startTime`) is
  correctly rejected.
- An end-to-end CLI subprocess smoke test.

Tests do **not** depend on the 167MB `nisarbb.tol.xml` file — it isn't
checked into version control and isn't needed to exercise any of the
mapping logic.

## Smoke run against the real file

Machine: this session's sandbox (macOS, Python 3.9.6). File:
`/Users/jhaug/Downloads/nisarbb.tol.xml`, 167,672,730 bytes (~167.7 MB),
covering `2023-029` through `2024-211`, containing 68,532 `ACT_START`,
68,532 `ACT_END`, 132,703 `RES_VAL`, 872 `RES_FINAL_VAL`, 28 `ERROR`, 28
`RELEASE` records across 872 `ResourceSpec`s.

```
python3 tol2bundle.py /Users/jhaug/Downloads/nisarbb.tol.xml \
    -o nisar_slice.json --start 2024-060T08:00:00 --end 2024-060T16:00:00 -v
```

Result:

| metric | value |
|---|---|
| wall time | 3.67s |
| peak RSS (`time -l`) | ~29.6 MB (`maximum resident set size`), ~23.9 MB `peak memory footprint` |
| output size | 314,364 bytes (~307 KB) |
| activities emitted | 254 (250 matched `ACT_START`/`ACT_END` pairs, 4 unmatched) |
| resources emitted | 42, 326 total segments |
| activity types | 12 |
| schema validation | passed (both the bundled copy and the canonical `plandev-ui` copy) |

Peak RSS stayed essentially flat (~30 MB) regardless of the 167 MB input
size — confirming the `iterparse()` + `elem.clear()` + `root.remove(elem)`
streaming approach is actually bounding memory by output size, not input
size. As a second data point, scanning the *entire* file with a
deliberately tiny window (`--start 2024-030T00:00:00 --end
2024-030T06:00:00`, which matches only 4 activities and 26 resource
segments) still took the same ~3.6s wall time and ~30 MB RSS — the whole
167 MB is always read linearly once, regardless of window size, so
narrowing the window saves output size and downstream JSON-parsing cost in
the browser, not conversion time.

A wider stress test — `--start 2024-030T00:00:00 --end 2024-100T00:00:00
--max-activities 50` (a 70-day window) — produced a 5,441,156-byte
(~5.4 MB) bundle in a comparable ~3.6s, with 50 activities but 299
resources / 53,909 segments (since `--max-activities` doesn't limit
resources — see "Lossy" above). This size is likely at or past the edge of
"comfortably viewable in a browser"; the 8-hour window above is a safer
default for a demo slice.

Full-file (unwindowed) conversion was **not attempted**: at ~68,532
activities and ~132,703 resource samples across the full ~20-month span,
the output would be on the order of hundreds of MB to low GB of JSON,
which this tool makes no attempt to size-cap beyond `--max-activities`
(which, again, doesn't touch resources) — use a real time window instead.
