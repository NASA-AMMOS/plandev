#!/usr/bin/env python3
"""
bundle2aerie.py — Import an Aerie offline bundle (bundleVersion 1.0.0) into a
running Aerie/plandev instance via Hasura GraphQL.

This implements Stage 1 of docs/OFFLINE_BUNDLE_IMPORT_PLAN.md: profiles and
activity directives only, zero backend changes. See that document and
docs/OFFLINE_BUNDLE_VIEWER.md §10 for the research this acts on, and this
directory's README.md for what has and has not been verified against a live
instance (short answer: nothing has — there is no live instance available in
this environment, so this tool has only ever been exercised in --dry-run).

Usage:
    python3 bundle2aerie.py BUNDLE.json --hasura-url URL [--auth-token TOK]
        [--role ROLE] (--plan-id N | --create-plan --model-id N)
        [--dry-run] [-v]

Design constraint (see README "Testing without a server"): every piece of
logic that decides *what* to send is a pure function of plain Python data —
no network, no argparse Namespace, nothing that requires a live Hasura. Only
the thin `HasuraClient` class in the "HTTP adapter" section below touches
`urllib`. This is what makes the whole mapping unit-testable with no server.
"""
from __future__ import annotations

import argparse
import datetime as dt
import json
import os
import re
import sys
import urllib.error
import urllib.request
from typing import Any, Dict, List, Optional, Tuple

# --------------------------------------------------------------------------
# Reuse tol2bundle's helpers rather than reimplementing them.
#
# tol2bundle.py already contains correct, tested parsing/formatting logic for
# the Aerie day-of-year / ISO-8601 timestamp spellings the bundle schema
# allows (parse_timestamp), and a JSON-Schema-validating validate_bundle()
# that already knows how to fall back to a hand-rolled minimal validator when
# the `jsonschema` package isn't installed. We import tol2bundle as a module
# (rather than copy-pasting) so tol2bundle's own 29 tests remain the single
# source of truth for that behavior, and so a fix there automatically
# benefits this tool too.
# --------------------------------------------------------------------------
_TOL2BUNDLE_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "tol2bundle")
if _TOL2BUNDLE_DIR not in sys.path:
    sys.path.insert(0, _TOL2BUNDLE_DIR)

import tol2bundle as t2b  # noqa: E402  (path insertion above must run first)

# The task instructions note a vendored schema copy already exists at
# contrib/tol2bundle/schema/offline-bundle-schema-v1.json and asks us to
# reuse it rather than vendor a third copy. bundle2aerie has no schema copy
# of its own; it reaches into tol2bundle's.
SCHEMA_PATH = os.path.join(_TOL2BUNDLE_DIR, "schema", "offline-bundle-schema-v1.json")

# Vendored copy of plandev-ui's src/schemas/ui-view-schema-v3.json, used to
# validate generated view definitions (see "View generation" section below
# and build_view_definition's docstring). Vendored -- not imported from a
# path inside plandev-ui -- so this tool does not depend on the UI repo
# being checked out beside this one. test_bundle2aerie.py's
# TestViewSchemaVendoring compares this file byte-for-byte against
# plandev-ui's copy (when that repo is available) so drift is caught rather
# than silently tolerated.
VIEW_SCHEMA_PATH = os.path.join(
    os.path.dirname(os.path.abspath(__file__)), "schema", "ui-view-schema-v3.json"
)


# --------------------------------------------------------------------------
# Duration parsing: bundle -> microseconds
#
# This is new logic, not a reimplementation of anything in tol2bundle.
# tol2bundle only ever *produces* Aerie signed duration strings (it formats
# TOWARD that one spelling); it never has to parse a duration string of
# unknown spelling back into a number, because its input (TOL XML) has its
# own separate, already-typed duration representation
# (parse_tol_duration_value_elem). bundle2aerie is different: a bundle may
# come from tol2bundle, from stateless-aerie's BundleWriter, or from any
# third-party producer, so per the schema (contrib/tol2bundle/schema/
# offline-bundle-schema-v1.json, "duration" definition) it must accept ALL
# FOUR spellings:
#   - an Aerie signed duration:      "+11:39:55.219000" / "-00:00:05"
#   - a Postgres interval:           "02:27:15.059"
#   - an ISO-8601 duration:          "PT2H27M15.059S"
#   - a bare integer number of microseconds (JSON number, not string)
# --------------------------------------------------------------------------

# Matches both the Aerie signed spelling and the Postgres interval spelling:
# they differ only in whether a sign is present. Hours are deliberately
# \d+ (not \d{2}) because Aerie never rolls hours into days ("+30:00:00" is
# valid), and per the schema/README convention this tool mirrors that.
_CLOCK_DURATION_RE = re.compile(
    r"^(?P<sign>[+-])?(?P<h>\d+):(?P<m>\d{2}):(?P<s>\d{2})(?:\.(?P<frac>\d+))?$"
)

# ISO-8601 duration, e.g. "PT2H27M15.059S", "P1DT2H", "-PT30M". We only
# support the subset the bundle schema's example uses (days/hours/minutes/
# seconds); weeks, years, and months are not meaningful durations for a
# simulation timeline and are intentionally unsupported.
_ISO8601_DURATION_RE = re.compile(
    r"^(?P<sign>-)?P"
    r"(?:(?P<days>\d+)D)?"
    r"(?:T"
    r"(?:(?P<hours>\d+)H)?"
    r"(?:(?P<minutes>\d+)M)?"
    r"(?:(?P<seconds>\d+(?:\.\d+)?)S)?"
    r")?$"
)


def _frac_to_microseconds(frac: Optional[str]) -> int:
    """'5' -> 500000, '219000' -> 219000, '1' -> 100000, None -> 0."""
    if not frac:
        return 0
    return int((frac + "000000")[:6])


def parse_duration_to_microseconds(value: Any) -> int:
    """Convert any bundle-schema-accepted duration spelling into an integer
    number of microseconds (positive or negative). Raises ValueError for
    anything unrecognized."""
    if isinstance(value, bool):
        raise ValueError(f"invalid duration: booleans are not durations ({value!r})")
    if isinstance(value, (int, float)):
        return round(value)

    if not isinstance(value, str):
        raise ValueError(f"invalid duration: expected string or number, got {type(value).__name__}")

    text = value.strip()
    if not text:
        raise ValueError("invalid duration: empty string")

    m = _ISO8601_DURATION_RE.match(text)
    if m and text.upper().lstrip("-").startswith("P"):
        sign = -1 if m.group("sign") else 1
        days = int(m.group("days") or 0)
        hours = int(m.group("hours") or 0)
        minutes = int(m.group("minutes") or 0)
        seconds_text = m.group("seconds")
        if seconds_text is not None:
            whole, _, frac = seconds_text.partition(".")
            seconds = int(whole)
            micros_from_frac = _frac_to_microseconds(frac)
        else:
            seconds = 0
            micros_from_frac = 0
        total_micros = (
            ((days * 24 + hours) * 3600 + minutes * 60 + seconds) * 1_000_000 + micros_from_frac
        )
        return sign * total_micros

    m = _CLOCK_DURATION_RE.match(text)
    if m:
        sign = -1 if m.group("sign") == "-" else 1
        hours = int(m.group("h"))
        minutes = int(m.group("m"))
        seconds = int(m.group("s"))
        micros_from_frac = _frac_to_microseconds(m.group("frac"))
        total_micros = (hours * 3600 + minutes * 60 + seconds) * 1_000_000 + micros_from_frac
        return sign * total_micros

    raise ValueError(f"invalid duration: {value!r} matches none of the accepted spellings")


def microseconds_to_pg_interval(total_micros: int) -> str:
    """Format an integer microsecond count as a Postgres interval literal
    ('HH:MM:SS.ffffff', sign-prefixed if negative). Used for plan.duration
    and activity_directive.start_offset, which are Postgres `interval`
    columns (see plan_insert_input / activity_directive_insert_input in
    plandev-ui's generated GraphQL types, mirrored below)."""
    sign = "-" if total_micros < 0 else ""
    total_micros = abs(total_micros)
    micros = total_micros % 1_000_000
    total_secs = total_micros // 1_000_000
    s = total_secs % 60
    total_mins = total_secs // 60
    m = total_mins % 60
    h = total_mins // 60
    return f"{sign}{h:02d}:{m:02d}:{s:02d}.{micros:06d}"


def duration_str_to_pg_interval(value: Any) -> str:
    return microseconds_to_pg_interval(parse_duration_to_microseconds(value))


# --------------------------------------------------------------------------
# Pure mapping: bundle -> addExternalDataset profileSet
#
# Per docs/OFFLINE_BUNDLE_IMPORT_PLAN.md Stage 1 and OFFLINE_BUNDLE_VIEWER.md
# §10.1 (verified against PostProfileSegmentsAction.java:36-54): both the
# bundle's `extent` and addExternalDataset's `duration` are per-segment
# DELTAS, not cumulative offsets. No prefix-sum happens here or on the
# server; that part of the earlier design (the offline *viewer's* loader)
# does not apply to this path at all.
# --------------------------------------------------------------------------


def build_profile_set(resources: List[Dict[str, Any]]) -> Dict[str, Any]:
    """Convert bundle `simulation.resources` into addExternalDataset's
    `profileSet` input. Pure function: no I/O, no validation beyond what's
    needed to build correct output (schema validation happens earlier, via
    tol2bundle.validate_bundle)."""
    profile_set: Dict[str, Any] = {}
    for resource in resources:
        name = resource["name"]
        segments_out = []
        for segment in resource.get("segments", []):
            duration_micros = parse_duration_to_microseconds(segment["extent"])
            is_gap = bool(segment.get("isGap")) or segment.get("dynamics") is None
            dynamics = None if is_gap else segment.get("dynamics")
            segments_out.append({"duration": duration_micros, "dynamics": dynamics})
        profile_set[name] = {
            "type": resource["type"],
            "schema": resource["schema"],
            "segments": segments_out,
        }
    return profile_set


def format_aerie_doy(when: dt.datetime) -> str:
    """Renders an instant as an Aerie day-of-year timestamp,
    e.g. '2024-183T00:00:00.000'."""
    return (
        f"{when.year:04d}-{when.timetuple().tm_yday:03d}"
        f"T{when.hour:02d}:{when.minute:02d}:{when.second:02d}"
        f".{when.microsecond // 1000:03d}"
    )


def derive_dataset_start(bundle: Dict[str, Any]) -> str:
    """datasetStart for addExternalDataset, as an Aerie day-of-year timestamp.

    merlin-server parses this field itself rather than handing it to Postgres,
    and its parser accepts ONLY the day-of-year spelling. Passing an ISO-8601
    timestamp (which the bundle schema permits, and which stateless-aerie's
    plan.json uses) is rejected at the action boundary with:

        JSON Parsing Exception ... {"breadcrumbs":["input","datasetStart"],
                                    "message":"invalid timestamp format"}

    Verified against a live Aerie instance: ISO-8601 fails, day-of-year
    succeeds. So the bundle's timestamp is always normalized here, whichever
    spelling it arrived in.
    """
    return format_aerie_doy(t2b.parse_timestamp(bundle["simulation"]["simulationStartTime"]))


# --------------------------------------------------------------------------
# View generation
#
# Motivation (docs/OFFLINE_BUNDLE_IMPORT_PLAN.md "Problem"): an imported plan
# has model_id = null, so plandev-ui's $resourceTypes store is empty and
# generateDefaultView (src/utilities/view.ts:26) emits zero resource rows —
# there is no mission model to enumerate resource *types* from. The plan's
# external profiles exist in the database and would render fine, but nothing
# in a fresh view references them by name, so the user sees a blank
# timeline unless they hand-build a view.
#
# build_view_definition() below is a pure, from-scratch reimplementation of
# generateDefaultView, driven by resource *names* (which a bundle always
# has) instead of ResourceType rows (which a model-less plan never has). It
# is checked field-for-field against a definition plandev-ui's own
# generateDefaultView produced for a live 18-resource plan (captured to
# scratch as "p6view.json" during development) and against the constants and
# helpers it calls:
#   - src/utilities/view.ts:generateDefaultView (structure, non-timeline
#     sections, activity row)
#   - src/utilities/timeline.ts:createTimeline / createRow /
#     createTimelineActivityLayer / createTimelineResourceLayer /
#     createTimelineLineLayer / createTimelineXRangeLayer / createYAxis /
#     getNextRowID / getNextLayerID / getNextYAxisID (id allocation, per-field
#     defaults)
#   - src/constants/view.ts:ViewDefaultDiscreteOptions /
#     ViewDiscreteLayerColorPresets / ViewLineLayerColorPresets (colors,
#     discrete-row defaults)
#
# One deliberate deviation from generateDefaultView: the UI decides
# line-vs-x-range from a ResourceType's ValueSchema (schema.type in
# {boolean,string,variant} => x-range, {int,real,duration,struct-with-
# rate/initial} => line). A bundle/profile row has no ValueSchema decision
# to make -- addExternalDataset's own `type` field is already exactly
# 'real' or 'discrete' (see build_profile_set above), which is the more
# direct and less error-prone signal, and is what the task spec calls for.
# The resulting chartType choice is identical for every schema type Aerie's
# own profile format actually produces (discrete profiles are always
# boolean/string/variant/int-enum-like; real profiles are always numeric),
# so this is not a behavior change, just a shorter path to the same answer.
# --------------------------------------------------------------------------

VIEW_SCHEMA_VERSION = 3

# plandev-ui src/constants/view.ts:24-30 (ViewDefaultDiscreteOptions).
VIEW_DEFAULT_DISCRETE_OPTIONS: Dict[str, Any] = {
    "activityOptions": {"composition": "both", "hierarchyMode": "flat"},
    "displayMode": "compact",
    "externalEventOptions": {"groupBy": "event_type_name"},
    "height": 16,
    "labelVisibility": "auto",
}

# plandev-ui src/constants/view.ts:32-44 / 46-58. Only index [0] of each is
# ever used here, because build_view_definition (like generateDefaultView)
# calls the create* helpers exactly once per row/layer with no persistent
# "next color" counter -- every generated row gets the same first-preset
# color, which is what a from-scratch (never edited) default view looks
# like in the UI too (see p6view.json: every line layer is '#283593').
VIEW_DISCRETE_LAYER_COLOR_PRESET = "#fcdd8f"
VIEW_LINE_LAYER_COLOR_PRESET = "#283593"

# The non-timeline sections of the definition plandev-ui's generateDefaultView
# emits are static constants (src/utilities/view.ts:96-260ish) -- they don't
# depend on resourceTypes/externalEventTypes at all. Copied verbatim (field
# order does not matter for JSON equality/schema validation) from that
# function's literal object, cross-checked against p6view.json.
_ACTIVITY_DIRECTIVES_TABLE: Dict[str, Any] = {
    "autoSizeColumns": "fill",
    "columnDefs": [
        {
            "aggFunc": None, "colId": "arguments", "pinned": None, "pivot": False,
            "pivotIndex": None, "rowGroup": False, "rowGroupIndex": None, "sort": None,
            "sortIndex": None, "width": 200,
        },
        {"field": "id", "filter": "text", "headerName": "ID", "resizable": True, "sortable": True, "width": 80},
        {
            "aggFunc": None, "colId": "last_modified_at", "pinned": None, "pivot": False,
            "pivotIndex": None, "rowGroup": False, "rowGroupIndex": None, "sort": None,
            "sortIndex": None, "width": 200,
        },
        {
            "aggFunc": None, "colId": "metadata", "pinned": None, "pivot": False, "pivotIndex": None,
            "rowGroup": False, "rowGroupIndex": None, "sort": None, "sortIndex": None, "width": 200,
        },
        {"field": "name", "filter": "text", "headerName": "Name", "resizable": True, "sortable": True, "width": 200},
        {"field": "type", "filter": "text", "headerName": "Type", "resizable": True, "sortable": True},
        {
            "aggFunc": None, "colId": "source_scheduling_goal_id", "pinned": None, "pivot": False,
            "pivotIndex": None, "rowGroup": False, "rowGroupIndex": None, "sort": None,
            "sortIndex": None, "width": 200,
        },
        {"field": "start_offset", "filter": "text", "headerName": "Start Offset", "resizable": True, "sortable": True},
        {
            "aggFunc": None, "colId": "tags", "pinned": None, "pivot": False, "pivotIndex": None,
            "rowGroup": False, "rowGroupIndex": None, "sort": None, "sortIndex": None, "width": 200,
        },
        {
            "aggFunc": None, "colId": "type", "pinned": None, "pivot": False, "pivotIndex": None,
            "rowGroup": False, "rowGroupIndex": None, "sort": None, "sortIndex": None, "width": 280,
        },
        {
            "aggFunc": None, "colId": "anchor_id", "pinned": None, "pivot": False, "pivotIndex": None,
            "rowGroup": False, "rowGroupIndex": None, "sort": None, "sortIndex": None, "width": 200,
        },
        {
            "aggFunc": None, "colId": "applied_preset", "pinned": None, "pivot": False, "pivotIndex": None,
            "rowGroup": False, "rowGroupIndex": None, "sort": None, "sortIndex": None, "width": 200,
        },
        {
            "aggFunc": None, "colId": "anchored_to_start", "pinned": None, "pivot": False, "pivotIndex": None,
            "rowGroup": False, "rowGroupIndex": None, "sort": None, "sortIndex": None, "width": 200,
        },
        {
            "field": "derived_start_time", "filter": "text", "headerName": "Absolute Start Time (UTC)",
            "resizable": True, "sortable": True, "width": 200,
        },
        {
            "aggFunc": None, "colId": "start_offset", "pinned": None, "pivot": False, "pivotIndex": None,
            "rowGroup": False, "rowGroupIndex": None, "sort": None, "sortIndex": None, "width": 200,
        },
        {
            "field": "created_at", "filter": "text", "headerName": "Created At (UTC)", "hide": True,
            "resizable": True, "sortable": True, "width": 200,
        },
    ],
    "columnStates": [],
}

_ACTIVITY_SPANS_TABLE: Dict[str, Any] = {
    "autoSizeColumns": "fill",
    "columnDefs": [
        {"field": "id", "filter": "text", "headerName": "ID", "resizable": True, "sortable": True},
        {"field": "dataset_id", "filter": "text", "headerName": "Dataset ID", "resizable": True, "sortable": True},
        {"field": "parent_id", "filter": "text", "headerName": "Parent ID", "resizable": True, "sortable": True},
        {"field": "type", "filter": "text", "headerName": "Type", "resizable": True, "sortable": True},
        {"field": "start_offset", "filter": "text", "headerName": "Start Offset", "resizable": True, "sortable": True},
        {"field": "duration", "filter": "text", "headerName": "Duration", "resizable": True, "sortable": True},
    ],
    "columnStates": [],
}

_GRID: Dict[str, Any] = {
    "columnSizes": "1fr 3px 3fr 3px 1fr",
    "leftComponentBottom": "SimulationPanel",
    "leftComponentTop": "TimelineItemsPanel",
    "leftHidden": False,
    "leftRowSizes": "1fr",
    "leftSplit": False,
    "middleComponentBottom": "ActivityDirectivesTablePanel",
    "middleRowSizes": "2fr 3px 1fr",
    "middleSplit": True,
    "rightComponentBottom": "TimelineEditorPanel",
    "rightComponentTop": "ActivityFormPanel",
    "rightHidden": False,
    "rightRowSizes": "1fr",
    "rightSplit": False,
}

_IFRAMES: List[Dict[str, Any]] = [
    {"id": 0, "src": "https://eyes.nasa.gov/apps/mars2020/#/home", "title": "Mars-2020-EDL"},
]

_SIMULATION_EVENTS_TABLE: Dict[str, Any] = {
    "autoSizeColumns": "fit",
    "columnDefs": [
        {"field": "id", "filter": "text", "headerName": "ID", "resizable": True, "sortable": True},
        {"field": "dataset_id", "filter": "text", "headerName": "Dataset ID", "resizable": True, "sortable": True},
        {"field": "start_offset", "filter": "text", "headerName": "Start Offset", "hide": True, "resizable": True, "sortable": True},
        {"field": "dense_time", "filter": "text", "headerName": "Dense Time", "hide": True, "resizable": True, "sortable": True},
        {"field": "topic", "filter": "text", "headerName": "Topic", "resizable": True, "sortable": True},
        {"field": "value", "filter": "text", "headerName": "Value", "resizable": True, "sortable": True},
    ],
    "columnStates": [],
}


def build_view_definition(
    resources: List[Dict[str, Any]],
    activity_types: Optional[List[Dict[str, Any]]] = None,
    name: Optional[str] = None,
) -> Dict[str, Any]:
    """Builds an Aerie UI view *definition* (schema version 3) containing one
    timeline with one activity row plus one row per resource, mirroring
    plandev-ui's generateDefaultView field-for-field (see the module comment
    above for exactly which functions were used as ground truth).

    `resources` — bundle/profile-shaped dicts, each needing only `name` and
    `type` ('real' or 'discrete'); the same shape build_profile_set already
    consumes from bundle["simulation"]["resources"], so a caller can pass
    that list directly, or a list of {'name', 'type'} pairs derived from a
    live plan's `profile` table.

    `activity_types` is accepted for API symmetry with a plan's activity
    palette, but -- exactly like generateDefaultView's own activity row --
    is not used to build the activity layer's filter: a fresh default view's
    activity row always uses an empty filter (`{"activity": {}}`), which
    matches every activity type rather than enumerating them. Passing
    activity_types has no effect on the output; it exists so a future
    per-type activity row is a additive change to this function's callers
    rather than a signature change.

    `name` is accepted for the same forward-compatibility reason but is
    *not* embedded in the returned dict: the ui-view-schema-v3.json
    `plan.timelines[]`/definition object has no `name` field at all
    (`additionalProperties: false` on the definition and every nested
    object, per schema/ui-view-schema-v3.json) -- the human-readable name
    belongs to the outer `ui.view` row (`{name, definition, owner}`), not
    the definition payload itself. Callers that want a named view pass
    `name` to the `insert_view_one` mutation directly (see
    build_insert_view_variables below), not to this function's output.
    """
    del activity_types, name  # accepted for API symmetry; see docstring

    next_row_id = 0
    next_layer_id = 0
    next_yaxis_id = 0

    def alloc_row_id() -> int:
        nonlocal next_row_id
        i = next_row_id
        next_row_id += 1
        return i

    def alloc_layer_id() -> int:
        nonlocal next_layer_id
        i = next_layer_id
        next_layer_id += 1
        return i

    def alloc_yaxis_id() -> int:
        nonlocal next_yaxis_id
        i = next_yaxis_id
        next_yaxis_id += 1
        return i

    rows: List[Dict[str, Any]] = []

    # Activity row -- src/utilities/view.ts:36-45.
    activity_layer = {
        "activityColor": VIEW_DISCRETE_LAYER_COLOR_PRESET,
        "chartType": "activity",
        "filter": {"activity": {}},
        "id": alloc_layer_id(),
        "name": "Activity Layer",
        "yAxisId": None,
    }
    activity_row = {
        "autoAdjustHeight": True,
        "discreteOptions": {**VIEW_DEFAULT_DISCRETE_OPTIONS, "displayMode": "grouped"},
        "expanded": True,
        "height": 160,
        "horizontalGuides": [],
        "id": alloc_row_id(),
        "layers": [activity_layer],
        "name": "Activities by Type",
        "yAxes": [],
    }
    rows.append(activity_row)

    # Resource rows -- src/utilities/view.ts:73-86 + timeline.ts
    # createTimelineResourceLayer/createTimelineLineLayer/
    # createTimelineXRangeLayer/createYAxis.
    for resource in resources:
        resource_name = resource["name"]
        resource_type = resource["type"]  # 'real' | 'discrete'

        is_line = resource_type == "real"
        yaxis_id = alloc_yaxis_id()
        y_axis = {
            "color": "#1b1d1e",
            "domainFitMode": "fitTimeWindow",
            "id": yaxis_id,
            "label": {"text": resource_name},
            "renderTickLines": True,
            "tickCount": 5 if is_line else 0,
        }

        layer_id = alloc_layer_id()
        if is_line:
            layer = {
                "chartType": "line",
                "filter": {"resource": resource_name},
                "id": layer_id,
                "lineColor": VIEW_LINE_LAYER_COLOR_PRESET,
                "lineWidth": 1,
                "name": "",
                "pointRadius": 2,
                "yAxisId": yaxis_id,
            }
        else:
            layer = {
                "chartType": "x-range",
                "colorScheme": "schemeTableau10",
                "filter": {"resource": resource_name},
                "id": layer_id,
                "name": "",
                "opacity": 0.8,
                "showAsLinePlot": False,
                "yAxisId": yaxis_id,
            }

        row = {
            "autoAdjustHeight": False,
            "discreteOptions": dict(VIEW_DEFAULT_DISCRETE_OPTIONS),
            "expanded": True,
            "height": 100,
            "horizontalGuides": [],
            "id": alloc_row_id(),
            "layers": [layer],
            "name": resource_name,
            "yAxes": [y_axis],
        }
        rows.append(row)

    timeline = {
        "id": 0,
        "marginLeft": 250,
        "marginRight": 30,
        "rows": rows,
        "verticalGuides": [],
    }

    return {
        "plan": {
            "activityDirectivesTable": _ACTIVITY_DIRECTIVES_TABLE,
            "activitySpansTable": _ACTIVITY_SPANS_TABLE,
            "grid": _GRID,
            "iFrames": _IFRAMES,
            "simulationEventsTable": _SIMULATION_EVENTS_TABLE,
            "timelines": [timeline],
        },
        "version": VIEW_SCHEMA_VERSION,
    }


def validate_view_definition(definition: Dict[str, Any], schema_path: str, verbose: bool = False) -> None:
    """Validates a view definition against the vendored
    schema/ui-view-schema-v3.json, following the exact same
    jsonschema-else-fallback pattern as tol2bundle.validate_bundle (see that
    function's docstring in tol2bundle.py for why: `jsonschema` when
    importable, a minimal hand-rolled required/type/enum walker otherwise,
    so this tool never hard-depends on a package that may not be installed).
    Raises SystemExit on failure, exactly like validate_bundle."""
    with open(schema_path) as f:
        schema = json.load(f)

    try:
        import jsonschema  # type: ignore

        validator_cls = jsonschema.validators.validator_for(schema)
        validator_cls.check_schema(schema)
        validator = validator_cls(schema)
        errors = sorted(validator.iter_errors(definition), key=lambda e: list(e.path))
        if errors:
            for e in errors[:20]:
                path = "/".join(str(p) for p in e.path)
                eprint(f"[schema error] at '{path}': {e.message}")
            raise SystemExit(f"View definition failed JSON Schema validation ({len(errors)} error(s)). Aborting.")
        if verbose:
            eprint("[ok] validated view definition against JSON Schema via jsonschema library.")
        return
    except ImportError:
        pass

    # Minimal fallback: check the handful of invariants build_view_definition
    # itself guarantees, mirroring tol2bundle's fallback validator's scope
    # (required fields / top-level shape, not a full JSON Schema engine).
    def fail(msg: str):
        raise SystemExit(f"View definition failed minimal validation: {msg}")

    if definition.get("version") != 3:
        fail("top-level 'version' must be 3")
    plan = definition.get("plan")
    if not isinstance(plan, dict):
        fail("top-level 'plan' must be an object")
    for key in ("activityDirectivesTable", "activitySpansTable", "grid", "iFrames", "timelines"):
        if key not in plan:
            fail(f"'plan.{key}' is required")
    timelines = plan.get("timelines")
    if not isinstance(timelines, list) or not timelines:
        fail("'plan.timelines' must be a non-empty array")
    for timeline in timelines:
        for key in ("id", "marginLeft", "marginRight", "rows", "verticalGuides"):
            if key not in timeline:
                fail(f"timeline missing required field '{key}'")
        seen_row_ids = set()
        for row in timeline.get("rows", []):
            for key in ("autoAdjustHeight", "expanded", "height", "horizontalGuides", "id", "layers", "name", "yAxes"):
                if key not in row:
                    fail(f"row missing required field '{key}'")
            if row["id"] in seen_row_ids:
                fail(f"duplicate row id {row['id']}")
            seen_row_ids.add(row["id"])
            for layer in row.get("layers", []):
                chart_type = layer.get("chartType")
                if chart_type not in ("activity", "line", "x-range", "externalEvent"):
                    fail(f"unrecognized layer chartType {chart_type!r}")
                if chart_type == "line":
                    for key in ("chartType", "filter", "id", "lineColor", "lineWidth", "pointRadius", "yAxisId"):
                        if key not in layer:
                            fail(f"line layer missing required field '{key}'")
                elif chart_type == "x-range":
                    for key in ("chartType", "colorScheme", "filter", "id", "opacity", "yAxisId"):
                        if key not in layer:
                            fail(f"x-range layer missing required field '{key}'")
                elif chart_type == "activity":
                    for key in ("activityColor", "chartType", "filter", "id", "yAxisId"):
                        if key not in layer:
                            fail(f"activity layer missing required field '{key}'")
    if verbose:
        eprint("[ok] validated view definition via minimal fallback validator (jsonschema not installed).")


def build_insert_view_variables(name: str, definition: Dict[str, Any]) -> Dict[str, Any]:
    """Variables for INSERT_VIEW_MUTATION. `owner` is deliberately omitted:
    the `view` Hasura object-relationship's insert permissions preset
    `owner` from the `x-hasura-user-id` session variable server-side
    (confirmed empirically against a live instance -- an insert_view_one
    call with no `owner` in the object still returns a populated `owner`
    equal to the calling user), and `view_insert_input`'s own field list
    (introspected against a live instance) only exposes `definition` and
    `name` -- there is no `owner` input field to set even if we wanted to."""
    return {"object": {"name": name, "definition": definition}}


INSERT_VIEW_MUTATION = """
mutation InsertView($object: view_insert_input!) {
  insert_view_one(object: $object) {
    id
    name
    owner
  }
}
""".strip()


def view_url(ui_base_url: str, plan_id: int, view_id: int) -> str:
    return f"{ui_base_url.rstrip('/')}/plans/{plan_id}?viewId={view_id}"


# --------------------------------------------------------------------------
# Pure mapping: bundle -> plan / activity_directive mutation variables
#
# There is no live Aerie instance available to spike this against (see
# README "What is verified vs unverified"), so these are derived from
# plandev-ui's own GraphQL and the effects that call it — the UI is the
# authoritative, working client for these mutations. Every field below cites
# the exact plandev-ui source line so a reviewer can check it independently.
# --------------------------------------------------------------------------


def build_plan_insert_input(bundle: Dict[str, Any], model_id: int) -> Dict[str, Any]:
    """Mirrors the `planInsertInput` object built in plandev-ui's
    effects.ts:createPlan (src/utilities/effects.ts:1749-1754):

        const planInsertInput: PlanInsertInput = {
          duration: getIntervalFromDoyRange(startTimeDoy, endTimeDoy),
          model_id: modelId,
          name,
          start_time: startTimeDoy,
        };

    The UI computes `duration` as the delta between two DOY-formatted
    timestamps (start/end); we instead take the bundle's own `plan.duration`
    directly, since the bundle already expresses it as a duration rather
    than a pair of timestamps -- same Postgres `interval` shape, cheaper
    derivation, and it means an explicit bundle duration is respected even
    if it doesn't exactly equal simulationEndTime - simulationStartTime."""
    plan = bundle["plan"]
    return {
        "duration": duration_str_to_pg_interval(plan["duration"]),
        "model_id": model_id,
        "name": plan["name"],
        "start_time": plan["startTime"],
    }


def build_activity_directive_insert_input(
    directive: Dict[str, Any],
    plan_id: int,
    anchor_id_map: Optional[Dict[int, int]] = None,
) -> Dict[str, Any]:
    """Mirrors the `activityDirectiveInsertInput` object built in
    plandev-ui's effects.ts:createActivityDirective
    (src/utilities/effects.ts:1072-1081):

        const activityDirectiveInsertInput: ActivityDirectiveInsertInput = {
          anchor_id: null,
          anchored_to_start: true,
          arguments: argumentsMap,
          metadata,
          name,
          plan_id: plan.id,
          start_offset: startOffset,
          type,
        };

    One deliberate difference: the UI always inserts with `anchor_id: null`
    because a user creates one directive at a time and anchors are set by a
    later edit. A bundle's directives can reference each other's `anchorId`
    up front, but Aerie assigns directive ids at insert time, so the bundle
    id in `anchorId` cannot be resolved until the anchor target has already
    been inserted and its real id is known. bundle2aerie therefore always
    inserts with anchor_id null first (this function), then issues a second
    pass of UPDATE_ACTIVITY_DIRECTIVE mutations (see
    build_activity_directive_anchor_update) once every directive's bundle-id
    -> real-id mapping is known. `anchor_id_map` is accepted here for
    symmetry / testability but is unused when it's the first pass (None)."""
    anchor_bundle_id = directive.get("anchorId")
    anchor_id = None
    if anchor_bundle_id is not None and anchor_id_map is not None:
        anchor_id = anchor_id_map.get(anchor_bundle_id)

    return {
        "anchor_id": anchor_id,
        "anchored_to_start": directive.get("anchoredToStart", True),
        "arguments": directive.get("arguments", {}),
        "metadata": directive.get("metadata", {}),
        "name": directive.get("name") or directive["type"],
        "plan_id": plan_id,
        "start_offset": duration_str_to_pg_interval(directive["startOffset"]),
        "type": directive["type"],
    }


def build_activity_directive_anchor_update(
    real_id: int, plan_id: int, real_anchor_id: int
) -> Dict[str, Any]:
    """Variables for the second-pass UPDATE_ACTIVITY_DIRECTIVE mutation
    (mirrors plandev-ui's gql.ts:3819-3824 / enums/gql.ts:229
    'update_activity_directive_by_pk')."""
    return {
        "id": real_id,
        "plan_id": plan_id,
        "activityDirectiveSetInput": {"anchor_id": real_anchor_id},
    }


def plan_directives_needing_anchor_update(
    directives: List[Dict[str, Any]], bundle_id_to_real_id: Dict[int, int]
) -> List[Tuple[int, int]]:
    """Pure helper: given the bundle directives and the bundle-id -> real-id
    map produced by the first insert pass, return [(real_id, real_anchor_id), ...]
    for every directive whose anchorId resolves to a directive that was
    actually inserted (an anchorId with no match in the map -- e.g. it
    pointed outside the bundle, or --max-activities style filtering dropped
    it upstream -- is skipped rather than crashing, mirroring tol2bundle's
    "leave parentId null rather than fabricate a dangling reference" policy
    for spans)."""
    updates = []
    for d in directives:
        anchor_bundle_id = d.get("anchorId")
        if anchor_bundle_id is None:
            continue
        real_id = bundle_id_to_real_id.get(d["id"])
        real_anchor_id = bundle_id_to_real_id.get(anchor_bundle_id)
        if real_id is not None and real_anchor_id is not None:
            updates.append((real_id, real_anchor_id))
    return updates


# --------------------------------------------------------------------------
# GraphQL mutation text
#
# Cited line numbers refer to /Users/jhaug/Developer/plandev-ui at the time
# this was written. CREATE_PLAN and CREATE_ACTIVITY_DIRECTIVE's *selection
# sets* (the fields requested back) are trimmed here to only what
# bundle2aerie consumes (id, plus a couple of fields useful for --dry-run
# / -v output); the *input* shapes (plan_insert_input /
# activity_directive_insert_input) mirror the UI exactly, per the builder
# functions above. addExternalDataset's mutation text comes directly from
# deployment/hasura/metadata/actions.graphql:16-21, which is this repo's own
# source of truth, not something we had to derive from the UI.
# --------------------------------------------------------------------------

# plandev-ui src/utilities/gql.ts:386-402 (CREATE_PLAN), whose
# `${Queries.INSERT_PLAN}` resolves to 'insert_plan_one'
# (plandev-ui src/enums/gql.ts:150).
CREATE_PLAN_MUTATION = """
mutation CreatePlan($plan: plan_insert_input!) {
  insert_plan_one(object: $plan) {
    id
    revision
    start_time
    duration
  }
}
""".strip()

# plandev-ui src/utilities/gql.ts:143-169 (CREATE_ACTIVITY_DIRECTIVE), whose
# `${Queries.INSERT_ACTIVITY_DIRECTIVE}` resolves to
# 'insert_activity_directive_one' (plandev-ui src/enums/gql.ts:124).
# Batched sibling of the single-row insert below. Hasura returns `returning`
# in the same order as the supplied `objects`, which is what lets us zip the
# server-assigned ids back onto the bundle ids for anchor resolution.
CREATE_ACTIVITY_DIRECTIVES_BATCH_MUTATION = """
mutation CreateActivityDirectives($objects: [activity_directive_insert_input!]!) {
  insert_activity_directive(objects: $objects) {
    returning {
      id
    }
  }
}
"""

CREATE_ACTIVITY_DIRECTIVE_MUTATION = """
mutation CreateActivityDirective($activityDirectiveInsertInput: activity_directive_insert_input!) {
  insert_activity_directive_one(object: $activityDirectiveInsertInput) {
    id
    type
    name
    start_offset
    anchor_id
  }
}
""".strip()

# plandev-ui src/utilities/gql.ts:3819-3824 (UPDATE_ACTIVITY_DIRECTIVE),
# whose `${Queries.UPDATE_ACTIVITY_DIRECTIVE}` resolves to
# 'update_activity_directive_by_pk' (plandev-ui src/enums/gql.ts:229). Used
# for the anchor-id second pass described in
# build_activity_directive_anchor_update above.
UPDATE_ACTIVITY_DIRECTIVE_ANCHOR_MUTATION = """
mutation UpdateActivityDirectiveAnchor($id: Int!, $plan_id: Int!, $activityDirectiveSetInput: activity_directive_set_input!) {
  update_activity_directive_by_pk(pk_columns: { id: $id, plan_id: $plan_id }, _set: $activityDirectiveSetInput) {
    id
    anchor_id
  }
}
""".strip()

# aerie deployment/hasura/metadata/actions.graphql:16-21 and :373
# (`scalar ProfileSet`). This is the action itself, not a UI-derived guess.
# merlin-server runs Javalin with no explicit maxRequestSize, so it inherits
# Javalin's 1 MB default request-body cap (AerieAppDriver.java:114). A real
# external dataset blows straight past that -- a full NISAR conversion is a
# 6.3 MB profileSet -- and the server rejects it with "Content Too Large".
# So profiles are uploaded in chunks: the first chunk creates the dataset via
# addExternalDataset, and the rest are appended with extendExternalDataset,
# which exists for exactly this and continues each profile's accumulated
# offset server-side (AppendProfileSegmentsAction).
EXTEND_EXTERNAL_DATASET_MUTATION = """
mutation ExtendExternalDataset($datasetId: Int!, $profileSet: ProfileSet!) {
  extendExternalDataset(datasetId: $datasetId, profileSet: $profileSet) {
    datasetId
  }
}
"""

ADD_EXTERNAL_DATASET_MUTATION = """
mutation AddExternalDataset($planId: Int!, $simulationDatasetId: Int, $datasetStart: String!, $profileSet: ProfileSet!) {
  addExternalDataset(
    planId: $planId,
    simulationDatasetId: $simulationDatasetId,
    datasetStart: $datasetStart,
    profileSet: $profileSet
  ) {
    datasetId
  }
}
""".strip()


# --------------------------------------------------------------------------
# HTTP adapter — the only part of this file that touches the network.
#
# Deliberately minimal: this has never been run against a live server (see
# README), so keeping it a thin, obviously-correct wrapper over urllib
# matters more than making it featureful. Mirrors requests.ts:reqHasura
# (plandev-ui src/utilities/requests.ts:166-186) for header shape: Bearer
# auth token, x-hasura-role, JSON content type.
# --------------------------------------------------------------------------


class GraphQLError(RuntimeError):
    def __init__(self, message: str, errors: List[Dict[str, Any]]):
        super().__init__(message)
        self.errors = errors


class HasuraClient:
    def __init__(
        self,
        url: str,
        auth_token: Optional[str] = None,
        role: str = "user",
        timeout: float = 60.0,
        admin_secret: Optional[str] = None,
        user_id: Optional[str] = None,
    ):
        self.url = url
        self.auth_token = auth_token
        self.role = role
        self.timeout = timeout
        self.admin_secret = admin_secret
        self.user_id = user_id

    def execute(self, query: str, variables: Dict[str, Any]) -> Dict[str, Any]:
        headers = {
            "Content-Type": "application/json",
            "x-hasura-role": self.role,
        }
        if self.auth_token:
            headers["Authorization"] = f"Bearer {self.auth_token}"
        # A local dev stack authenticates with Hasura's admin secret rather
        # than a gateway-issued JWT. When it is used, x-hasura-user-id must be
        # supplied explicitly too, because there is no token for Hasura to
        # derive the session user from.
        if self.admin_secret:
            headers["x-hasura-admin-secret"] = self.admin_secret
        if self.user_id:
            headers["x-hasura-user-id"] = self.user_id

        body = json.dumps({"query": query, "variables": variables}).encode("utf-8")
        request = urllib.request.Request(self.url, data=body, headers=headers, method="POST")
        try:
            with urllib.request.urlopen(request, timeout=self.timeout) as response:
                payload = json.loads(response.read().decode("utf-8"))
        except urllib.error.HTTPError as e:
            raise GraphQLError(f"HTTP {e.code} from {self.url}: {e.read().decode('utf-8', 'replace')}", [])

        if payload.get("errors"):
            raise GraphQLError(
                f"GraphQL errors from {self.url}: {json.dumps(payload['errors'])}", payload["errors"]
            )
        return payload["data"]


# --------------------------------------------------------------------------
# Orchestration
# --------------------------------------------------------------------------


def eprint(*a, **kw):
    print(*a, file=sys.stderr, **kw)


class DryRunClient:
    """Stand-in for HasuraClient in --dry-run mode. Prints every mutation +
    variables and returns synthetic-but-plausible data so the rest of the
    import logic (which needs e.g. a newly "created" plan id or directive
    id to proceed) can run unmodified. Never touches the network -- this is
    what makes --dry-run a first-class, exercised-by-tests code path rather
    than a separate branch that skips half the logic."""

    def __init__(self):
        self._next_fake_id = 1_000_000
        self.calls: List[Tuple[str, Dict[str, Any]]] = []

    def _fake_id(self) -> int:
        fake = self._next_fake_id
        self._next_fake_id += 1
        return fake

    def execute(self, query: str, variables: Dict[str, Any]) -> Dict[str, Any]:
        self.calls.append((query, variables))
        mutation_name = query.split("(", 1)[0].split()[-1]
        eprint(f"--- dry-run: {mutation_name} ---")
        eprint(json.dumps(variables, indent=2, sort_keys=True, default=str))

        if "extendExternalDataset" in query:
            return {"extendExternalDataset": {"datasetId": self._fake_id()}}
        if "insert_plan_one" in query:
            return {"insert_plan_one": {"id": self._fake_id()}}
        if "insert_activity_directive(" in query:
            count = len(variables.get("objects", []))
            return {"insert_activity_directive": {"returning": [{"id": self._fake_id()} for _ in range(count)]}}
        if "insert_activity_directive_one" in query:
            return {"insert_activity_directive_one": {"id": self._fake_id()}}
        if "update_activity_directive_by_pk" in query:
            return {"update_activity_directive_by_pk": {"id": variables["id"], "anchor_id": variables["activityDirectiveSetInput"]["anchor_id"]}}
        if "addExternalDataset" in query:
            return {"addExternalDataset": {"datasetId": self._fake_id()}}
        if "insert_view_one" in query:
            fake_id = self._fake_id()
            return {
                "insert_view_one": {
                    "id": fake_id,
                    "name": variables["object"]["name"],
                    "owner": "dry-run",
                }
            }
        raise AssertionError(f"DryRunClient doesn't know how to fake a response for: {mutation_name}")


def load_bundle(path: str) -> Dict[str, Any]:
    with open(path) as f:
        return json.load(f)


# Budget per request, well under merlin's 1 MB cap to leave room for the
# GraphQL envelope, variable names, and header overhead.
DEFAULT_PROFILE_CHUNK_BYTES = 700_000


def chunk_profile_set(profile_set: Dict[str, Any], budget_bytes: int = DEFAULT_PROFILE_CHUNK_BYTES):
    """Splits a profileSet into request-sized pieces.

    Yields dicts small enough to POST individually. Whole profiles are kept
    together where possible; a single profile too large on its own has its
    segments split across successive pieces under the same name, which
    extendExternalDataset appends in order.
    """
    current: Dict[str, Any] = {}
    current_size = 0

    def profile_bytes(name: str, profile: Dict[str, Any]) -> int:
        return len(json.dumps({name: profile}))

    for name, profile in profile_set.items():
        size = profile_bytes(name, profile)
        if size <= budget_bytes:
            if current and current_size + size > budget_bytes:
                yield current
                current, current_size = {}, 0
            current[name] = profile
            current_size += size
            continue

        # Oversized single profile: emit its segments in slices.
        if current:
            yield current
            current, current_size = {}, 0
        header = {k: v for k, v in profile.items() if k != "segments"}
        segments = profile["segments"]
        # Size one segment to estimate how many fit per request.
        per_segment = max(1, len(json.dumps(segments[0])) if segments else 1)
        per_chunk = max(1, (budget_bytes - len(json.dumps({name: header}))) // per_segment)
        for start in range(0, len(segments), per_chunk):
            yield {name: {**header, "segments": segments[start : start + per_chunk]}}

    if current:
        yield current


def import_bundle(
    bundle: Dict[str, Any],
    client: Any,
    plan_id: Optional[int],
    model_id: Optional[int],
    verbose: bool = False,
    batch_size: int = 500,
    profile_chunk_bytes: int = DEFAULT_PROFILE_CHUNK_BYTES,
) -> int:
    """Runs the full Stage-1 import. `client` is anything with an
    `execute(query, variables) -> dict` method -- either a real HasuraClient
    or a DryRunClient. Returns the plan id used/created."""

    if plan_id is None:
        if model_id is None:
            raise ValueError("--create-plan requires --model-id")
        plan_input = build_plan_insert_input(bundle, model_id)
        data = client.execute(CREATE_PLAN_MUTATION, {"plan": plan_input})
        plan_id = data["insert_plan_one"]["id"]
        eprint(f"created plan id={plan_id}")

    directives = bundle.get("activityDirectives", [])
    bundle_id_to_real_id: Dict[int, int] = {}
    # Real plans reach tens of thousands of directives (a full NISAR TOL
    # conversion is ~68k), where one mutation per directive costs minutes of
    # round trips. Batch by default; batch_size=1 restores the per-directive
    # path, which gives better error messages when a single row is rejected.
    for start in range(0, len(directives), max(1, batch_size)):
        chunk = directives[start : start + max(1, batch_size)]
        objects = [build_activity_directive_insert_input(d, plan_id) for d in chunk]
        if len(objects) == 1:
            data = client.execute(
                CREATE_ACTIVITY_DIRECTIVE_MUTATION, {"activityDirectiveInsertInput": objects[0]}
            )
            returned_ids = [data["insert_activity_directive_one"]["id"]]
        else:
            data = client.execute(CREATE_ACTIVITY_DIRECTIVES_BATCH_MUTATION, {"objects": objects})
            returned_ids = [row["id"] for row in data["insert_activity_directive"]["returning"]]
        if len(returned_ids) != len(chunk):
            raise SystemExit(
                f"error: inserted {len(chunk)} directives but got {len(returned_ids)} ids back; "
                "cannot map bundle ids to plan ids safely"
            )
        for directive, real_id in zip(chunk, returned_ids):
            bundle_id_to_real_id[directive["id"]] = real_id
        if verbose:
            eprint(f"inserted directives {start + 1}-{start + len(chunk)} of {len(directives)}")

    anchor_updates = plan_directives_needing_anchor_update(directives, bundle_id_to_real_id)
    for real_id, real_anchor_id in anchor_updates:
        client.execute(
            UPDATE_ACTIVITY_DIRECTIVE_ANCHOR_MUTATION,
            build_activity_directive_anchor_update(real_id, plan_id, real_anchor_id),
        )
        if verbose:
            eprint(f"anchored directive id={real_id} to id={real_anchor_id}")

    resources = bundle.get("simulation", {}).get("resources", [])
    if resources:
        profile_set = build_profile_set(resources)
        dataset_start = derive_dataset_start(bundle)
        dataset_id = None
        for index, chunk in enumerate(chunk_profile_set(profile_set, profile_chunk_bytes)):
            if dataset_id is None:
                data = client.execute(
                    ADD_EXTERNAL_DATASET_MUTATION,
                    {
                        "planId": plan_id,
                        # Attach at plan level, not to a simulation run: a
                        # bundle has no Aerie simulation to bind to, and
                        # Stage 1 creates no simulation_dataset. See
                        # OFFLINE_BUNDLE_IMPORT_PLAN.md Stage 1,
                        # "Decision -- attach at plan level".
                        "simulationDatasetId": None,
                        "datasetStart": dataset_start,
                        "profileSet": chunk,
                    },
                )
                dataset_id = data["addExternalDataset"]["datasetId"]
                eprint(f"created external dataset id={dataset_id} ({len(chunk)} profiles)")
            else:
                client.execute(
                    EXTEND_EXTERNAL_DATASET_MUTATION,
                    {"datasetId": dataset_id, "profileSet": chunk},
                )
                if verbose:
                    eprint(f"extended dataset {dataset_id} with chunk {index + 1} ({len(chunk)} profiles)")

    total_segments = sum(len(r.get("segments", [])) for r in resources)
    eprint("=== bundle2aerie summary ===")
    eprint(f"plan id: {plan_id}")
    eprint(f"activity directives inserted: {len(directives)}")
    eprint(f"  anchors set: {len(anchor_updates)}")
    eprint(f"resources (profiles) inserted: {len(resources)}")
    eprint(f"  total segments: {total_segments}")

    return plan_id


def default_view_name(bundle: Dict[str, Any], plan_id: int) -> str:
    plan_name = bundle.get("plan", {}).get("name")
    if plan_name:
        return f"{plan_name} (generated by bundle2aerie)"
    return f"Plan {plan_id} (generated by bundle2aerie)"


def create_view(
    bundle: Dict[str, Any],
    client: Any,
    plan_id: int,
    ui_base_url: str,
    view_name: Optional[str] = None,
    schema_path: str = VIEW_SCHEMA_PATH,
    verbose: bool = False,
) -> Tuple[int, str]:
    """Builds a view definition for every resource in the bundle, validates
    it against the vendored ui-view-schema-v3.json, inserts it via
    insert_view_one, and returns (view_id, url). See "View generation"
    above and docs/OFFLINE_BUNDLE_IMPORT_PLAN.md 's "Problem" section for
    why this exists: without it, an imported plan's resources are invisible
    in the UI because there's no mission model to derive a default view
    from."""
    resources = bundle.get("simulation", {}).get("resources", [])
    definition = build_view_definition(resources)
    validate_view_definition(definition, schema_path, verbose)

    name = view_name or default_view_name(bundle, plan_id)
    data = client.execute(INSERT_VIEW_MUTATION, build_insert_view_variables(name, definition))
    view_id = data["insert_view_one"]["id"]
    url = view_url(ui_base_url, plan_id, view_id)
    eprint(f"created view id={view_id} name={name!r}")
    eprint(f"view url: {url}")
    return view_id, url


# --------------------------------------------------------------------------
# CLI
# --------------------------------------------------------------------------


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(
        description="Import an Aerie offline bundle into a running Aerie instance via Hasura GraphQL."
    )
    parser.add_argument("bundle", help="Path to the bundle JSON file.")
    parser.add_argument("--hasura-url", required=True, help="Hasura GraphQL endpoint URL.")
    parser.add_argument("--auth-token", default=None, help="Bearer token for Authorization header.")
    parser.add_argument("--role", default="user", help="x-hasura-role header value (default: user).")
    parser.add_argument(
        "--admin-secret",
        default=os.environ.get("HASURA_GRAPHQL_ADMIN_SECRET"),
        help="Hasura admin secret (local dev stacks). Defaults to $HASURA_GRAPHQL_ADMIN_SECRET.",
    )
    parser.add_argument(
        "--user-id",
        help="x-hasura-user-id header value. Required alongside --admin-secret.",
    )
    parser.add_argument("--plan-id", type=int, default=None, help="Import into this existing plan.")
    parser.add_argument("--create-plan", action="store_true", help="Create a new plan from the bundle's plan section.")
    parser.add_argument("--model-id", type=int, default=None, help="Mission model id for --create-plan.")
    parser.add_argument("--dry-run", action="store_true", help="Print every mutation + variables without sending them.")
    parser.add_argument("--schema", default=None, help="Override path to the offline-bundle JSON Schema.")
    parser.add_argument("--no-validate", action="store_true", help="Skip schema validation (not recommended).")
    parser.add_argument(
        "--create-view",
        action="store_true",
        help=(
            "After importing, generate a UI view definition (one row per bundle resource, "
            "'line' for real / 'x-range' for discrete) and insert it via insert_view_one, so "
            "the imported resources are actually visible in the UI. Without this, an imported "
            "plan's model_id is null, $resourceTypes is empty, and generateDefaultView emits no "
            "resource rows -- see docs/OFFLINE_BUNDLE_IMPORT_PLAN.md 'Problem'."
        ),
    )
    parser.add_argument(
        "--ui-base-url",
        default="http://localhost:3000",
        help="Base URL of the plandev-ui instance, used to build the printed view URL (default: http://localhost:3000).",
    )
    parser.add_argument("--view-name", default=None, help="Name for the generated view (default: derived from the bundle's plan name).")
    parser.add_argument("--view-schema", default=None, help="Override path to the vendored ui-view-schema-v3.json.")
    parser.add_argument("-v", "--verbose", action="store_true", help="Verbose logging to stderr.")
    args = parser.parse_args(argv)

    if bool(args.plan_id is not None) == bool(args.create_plan):
        parser.error("specify exactly one of --plan-id or --create-plan")
    if args.create_plan and args.model_id is None:
        parser.error("--create-plan requires --model-id")

    bundle = load_bundle(args.bundle)

    if not args.no_validate:
        schema_path = args.schema or SCHEMA_PATH
        # Reuses tol2bundle's validate_bundle, which validates via the
        # `jsonschema` package when available and falls back to a minimal
        # hand-rolled required/type/enum checker otherwise -- see
        # tol2bundle.py's own "Validation" section.
        t2b.validate_bundle(bundle, schema_path, args.verbose)

    if args.dry_run:
        client: Any = DryRunClient()
    else:
        client = HasuraClient(
            args.hasura_url,
            auth_token=args.auth_token,
            role=args.role,
            admin_secret=args.admin_secret,
            user_id=args.user_id,
        )

    try:
        used_plan_id = import_bundle(bundle, client, plan_id=args.plan_id, model_id=args.model_id, verbose=args.verbose)
        if args.create_view:
            create_view(
                bundle,
                client,
                plan_id=used_plan_id,
                ui_base_url=args.ui_base_url,
                view_name=args.view_name,
                schema_path=args.view_schema or VIEW_SCHEMA_PATH,
                verbose=args.verbose,
            )
    except GraphQLError as e:
        eprint(f"error: {e}")
        return 1

    if args.dry_run:
        eprint(f"(dry run: {len(client.calls)} mutation(s) would have been sent; nothing was written)")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
