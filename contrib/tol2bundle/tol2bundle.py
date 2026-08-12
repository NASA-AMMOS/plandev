#!/usr/bin/env python3
"""
tol2bundle.py — Convert a JPL "TOL XML" (Timeline Object List) file into an
Aerie offline-bundle JSON file (bundleVersion 1.0.0).

Usage:
    python3 tol2bundle.py INPUT.tol.xml -o OUTPUT.json \
        [--start T] [--end T] [--max-activities N] [--plan-name NAME] [-v]

See README.md in this directory for the full field-by-field mapping, the
parts of TOL that cannot be represented losslessly, and worked examples.

Design notes (see README for the full rationale):
  * The 167MB+ TOL files this tool targets are far too large to load into
    memory with ET.parse(). We use ET.iterparse() and aggressively clear()
    finished elements (and detach them from their parent) so memory stays
    bounded by the *output* (post time-window / --max-activities filter),
    never by the size of the input file.
  * TOL's <TimeStamp> values are fixed-width day-of-year strings
    ("YYYY-DDDTHH:MM:SS.ffffff"), so they sort lexicographically in
    chronological order. We still parse them to datetimes wherever duration
    arithmetic is required (offsets, extents, --start/--end comparison
    against possibly-ISO input), but pass the original DOY string through
    untouched for the bundle's plan.startTime / simulation start & end,
    since the target schema accepts DOY natively.
"""
from __future__ import annotations

import argparse
import datetime as dt
import json
import re
import sys
from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional, Tuple

# --------------------------------------------------------------------------
# Constants
# --------------------------------------------------------------------------

BUNDLE_VERSION = "1.0.0"

# Joiner used to combine a ResourceSpec's <Name> with its <Index> values
# (and to combine a resource record's Name+Index at TOLrecord time) into
# the single resource name used in the bundle. Kept as one constant so it
# is easy to change if a downstream tool expects a different convention.
RESOURCE_NAME_JOINER = "/"

# TOL <DataType> -> Aerie ValueSchema "type"
DATATYPE_TO_SCHEMA_TYPE = {
    "boolean": "boolean",
    "integer": "int",
    "float": "real",
    "double": "real",
    "string": "string",
    "duration": "duration",
}

# TOL value-element tag -> Aerie ValueSchema "type", for values we can type
# confidently. Anything not in this map (YawValue, YawTargetValue,
# CommissioningPhaseValue, TimeValue, and any other JPL-mission-specific
# <FooValue> tag we haven't special-cased) falls back to "string", using
# the element's text verbatim. This is a documented lossy fallback -- see
# README "Lossy / unmodeled constructs".
VALUE_TAG_TO_SCHEMA_TYPE = {
    "BooleanValue": "boolean",
    "IntegerValue": "int",
    "FloatValue": "real",
    "DoubleValue": "real",
    "RealValue": "real",
    "StringValue": "string",
    "DurationValue": "duration",
}

DEFAULT_SCHEMA_PATH = None  # resolved at runtime relative to this file


# --------------------------------------------------------------------------
# Time / duration helpers
# --------------------------------------------------------------------------

_DOY_RE = re.compile(
    r"^(?P<year>\d{4})-(?P<doy>\d{3})T(?P<h>\d{2}):(?P<m>\d{2}):(?P<s>\d{2})"
    r"(?:\.(?P<frac>\d+))?$"
)

_TOL_DURATION_TEXT_RE = re.compile(
    r"^(?:(?P<days>\d+)T)?(?P<h>\d{2}):(?P<m>\d{2}):(?P<s>\d{2})"
    r"(?:\.(?P<frac>\d+))?$"
)


def parse_timestamp(s: str) -> dt.datetime:
    """Parse either a TOL day-of-year timestamp or an ISO-8601 timestamp
    into a naive (UTC-assumed) datetime, for arithmetic/comparison only.
    The original string form is preserved wherever it flows into the
    output bundle."""
    s = s.strip()
    m = _DOY_RE.match(s)
    if m:
        year = int(m.group("year"))
        doy = int(m.group("doy"))
        base = dt.datetime(year, 1, 1) + dt.timedelta(
            days=doy - 1,
            hours=int(m.group("h")),
            minutes=int(m.group("m")),
            seconds=int(m.group("s")),
        )
        frac = m.group("frac")
        if frac:
            micros = int((frac + "000000")[:6])
            base += dt.timedelta(microseconds=micros)
        return base
    # Fall back to ISO-8601.
    iso = s.replace("Z", "+00:00")
    d = dt.datetime.fromisoformat(iso)
    if d.tzinfo is not None:
        d = d.astimezone(dt.timezone.utc).replace(tzinfo=None)
    return d


def format_duration_seconds(total_seconds: float) -> str:
    """Format a (possibly negative, possibly >24h) number of seconds as an
    Aerie signed duration string '+HH:MM:SS.ffffff'. Hours are NOT rolled
    into days."""
    sign = "-" if total_seconds < 0 else "+"
    total_micros = round(abs(total_seconds) * 1_000_000)
    micros = total_micros % 1_000_000
    total_secs = total_micros // 1_000_000
    s = total_secs % 60
    total_mins = total_secs // 60
    m = total_mins % 60
    h = total_mins // 60
    return f"{sign}{h:02d}:{m:02d}:{s:02d}.{micros:06d}"


def duration_between(start: dt.datetime, end: dt.datetime) -> str:
    return format_duration_seconds((end - start).total_seconds())


def parse_tol_duration_value_elem(elem) -> str:
    """Convert a <DurationValue milliseconds="..">text</DurationValue>
    element into an Aerie duration string. Prefers the milliseconds
    attribute (always present in observed data); falls back to parsing the
    TOL '[<days>T]HH:MM:SS[.ffffff]' text form."""
    ms = elem.get("milliseconds")
    if ms is not None:
        return format_duration_seconds(int(ms) / 1000.0)
    text = (elem.text or "").strip()
    m = _TOL_DURATION_TEXT_RE.match(text)
    if not m:
        # Unrecognized text; best-effort passthrough as 0 with a note.
        return "+00:00:00.000000"
    days = int(m.group("days") or 0)
    total = (
        days * 86400
        + int(m.group("h")) * 3600
        + int(m.group("m")) * 60
        + int(m.group("s"))
    )
    frac = m.group("frac")
    seconds = total + (int((frac + "000000")[:6]) / 1_000_000 if frac else 0.0)
    return format_duration_seconds(seconds)


# --------------------------------------------------------------------------
# Value extraction
# --------------------------------------------------------------------------


def find_value_child(container) -> Optional[Any]:
    """Given a <Resource>, <Attribute>, or <Parameter> element, return its
    single typed value child (i.e. the child that isn't <Name> or
    <Index>)."""
    for child in container:
        if child.tag not in ("Name", "Index"):
            return child
    return None


def convert_value(value_elem) -> Tuple[Any, str]:
    """Convert a typed TOL value element into (python_value, schema_type)."""
    tag = value_elem.tag
    text = (value_elem.text or "").strip()
    if tag == "BooleanValue":
        return text.strip().lower() == "true", "boolean"
    if tag == "IntegerValue":
        try:
            return int(text), "int"
        except ValueError:
            return text, "string"
    if tag in ("FloatValue", "DoubleValue", "RealValue"):
        try:
            return float(text), "real"
        except ValueError:
            return text, "string"
    if tag == "StringValue":
        return text, "string"
    if tag == "DurationValue":
        return parse_tol_duration_value_elem(value_elem), "duration"
    if tag == "TimeValue":
        # No dedicated "timestamp" ValueSchema type exists in the target
        # schema; pass the DOY/ISO text through as a string. Documented as
        # lossy (loses the "this is a time" semantic) in the README.
        return text, "string"
    # Unknown/mission-specific *Value tag (YawValue, YawTargetValue,
    # CommissioningPhaseValue, etc): fall back to string passthrough.
    return text, "string"


def schema_for(schema_type: str) -> Dict[str, Any]:
    return {"type": schema_type}


# --------------------------------------------------------------------------
# Resource spec (from <ResourceMetadata>)
# --------------------------------------------------------------------------


@dataclass
class ResourceSpec:
    name: str
    indices: Tuple[str, ...]
    data_type: str
    interpolation: str
    subsystem: str
    units: str

    @property
    def joined_name(self) -> str:
        parts = [self.name] + list(self.indices)
        return RESOURCE_NAME_JOINER.join(parts)


def build_resource_name(name: str, indices: List[str]) -> str:
    parts = [name] + list(indices)
    return RESOURCE_NAME_JOINER.join(parts)


def parse_resource_spec_elem(elem) -> ResourceSpec:
    name = (elem.findtext("Name") or "").strip()
    indices: List[Tuple[int, str]] = []
    for idx_elem in elem.findall("Index"):
        level = int(idx_elem.get("level", len(indices)))
        indices.append((level, (idx_elem.text or "").strip()))
    indices.sort(key=lambda t: t[0])
    data_type = (elem.findtext("DataType") or "").strip().lower()
    interpolation = (elem.findtext("Interpolation") or "").strip().lower()
    subsystem = (elem.findtext("Subsystem") or "").strip()
    units = (elem.findtext("Units") or "").strip()
    return ResourceSpec(
        name=name,
        indices=tuple(v for _, v in indices),
        data_type=data_type,
        interpolation=interpolation,
        subsystem=subsystem,
        units=units,
    )


# --------------------------------------------------------------------------
# Activity accumulation
# --------------------------------------------------------------------------


@dataclass
class PendingActivity:
    uuid: str
    int_id: int
    name: str
    type: str
    parent_uuid: Optional[str]
    start_dt: dt.datetime
    start_text: str
    span_duration: Optional[str]  # Aerie duration string, from the 'span' Attribute
    subsystem: Optional[str]
    legend: Optional[str]
    visibility: Optional[str]
    arguments: Dict[str, Any]
    end_dt: Optional[dt.datetime] = None
    matched: bool = False


class IdAllocator:
    """Stable UUID(str) -> sequential-int mapping, insertion ordered."""

    def __init__(self, start: int = 1):
        self._next = start
        self._map: Dict[str, int] = {}

    def get(self, uuid: str) -> int:
        if uuid not in self._map:
            self._map[uuid] = self._next
            self._next += 1
        return self._map[uuid]

    def peek(self, uuid: str) -> Optional[int]:
        return self._map.get(uuid)


# --------------------------------------------------------------------------
# Activity type schema inference
# --------------------------------------------------------------------------


class ActivityTypeInfo:
    def __init__(self, name: str):
        self.name = name
        self.param_order: Dict[str, int] = {}
        self.param_schema: Dict[str, Dict[str, Any]] = {}
        self.subsystem_counts: Dict[str, int] = {}

    def observe_param(self, pname: str, schema_type: str):
        if pname not in self.param_order:
            self.param_order[pname] = len(self.param_order)
            self.param_schema[pname] = schema_for(schema_type)

    def observe_subsystem(self, subsystem: Optional[str]):
        if subsystem:
            self.subsystem_counts[subsystem] = self.subsystem_counts.get(subsystem, 0) + 1

    def most_common_subsystem(self) -> Optional[str]:
        if not self.subsystem_counts:
            return None
        return max(self.subsystem_counts.items(), key=lambda kv: kv[1])[0]

    def to_bundle_entry(self) -> Dict[str, Any]:
        params = {}
        for pname, order in self.param_order.items():
            params[pname] = {"order": order, "schema": self.param_schema[pname]}
        entry: Dict[str, Any] = {
            "name": self.name,
            "parameters": params,
            "requiredParameters": [],
            "computedAttributesValueSchema": {"type": "struct", "items": {}},
        }
        subsystem = self.most_common_subsystem()
        if subsystem:
            entry["subsystem"] = subsystem
        return entry


# --------------------------------------------------------------------------
# Resource segment accumulation
# --------------------------------------------------------------------------


class ResourceAccumulator:
    def __init__(self, name: str, spec: Optional[ResourceSpec]):
        self.name = name
        self.spec = spec
        self.samples: List[Tuple[dt.datetime, Any, str]] = []  # (ts, value, schema_type)
        self.final_sample: Optional[Tuple[dt.datetime, Any, str]] = None

    def add(self, ts: dt.datetime, value: Any, schema_type: str, is_final: bool):
        if is_final:
            self.final_sample = (ts, value, schema_type)
        else:
            self.samples.append((ts, value, schema_type))

    def is_discrete(self) -> bool:
        if self.spec is None:
            return True
        return self.spec.interpolation != "linear"

    def build(self, sim_end_dt: dt.datetime, sim_start_dt: dt.datetime, warn) -> Optional[Dict[str, Any]]:
        samples = sorted(self.samples, key=lambda t: t[0])
        if self.final_sample is not None:
            samples = samples + [self.final_sample]
        # de-dup exact-duplicate timestamps introduced by --start/--end edge
        # overlap; keep the last value observed at a given timestamp.
        dedup: Dict[dt.datetime, Tuple[dt.datetime, Any, str]] = {}
        for s in samples:
            dedup[s[0]] = s
        samples = sorted(dedup.values(), key=lambda t: t[0])
        if not samples:
            return None

        discrete = self.is_discrete()
        # If declared linear but values aren't numeric, fall back to discrete.
        if not discrete:
            if any(not isinstance(v, (int, float)) or isinstance(v, bool) for _, v, _ in samples):
                warn(
                    f"resource '{self.name}' declared linear interpolation but has "
                    "non-numeric values; falling back to discrete."
                )
                discrete = True

        schema_type = samples[0][2]
        if self.spec is not None and self.spec.data_type in DATATYPE_TO_SCHEMA_TYPE:
            schema_type = DATATYPE_TO_SCHEMA_TYPE[self.spec.data_type]

        segments = []
        n = len(samples)
        for i, (ts, value, _) in enumerate(samples):
            if i + 1 < n:
                next_ts = samples[i + 1][0]
            else:
                next_ts = sim_end_dt if sim_end_dt > ts else ts
            extent_s = (next_ts - ts).total_seconds()
            if extent_s < 0:
                extent_s = 0.0
            if discrete:
                dynamics: Any = value
                segments.append({"extent": format_duration_seconds(extent_s), "dynamics": dynamics})
            else:
                if i + 1 < n:
                    next_val = samples[i + 1][1]
                    rate = (next_val - value) / extent_s if extent_s > 0 else 0.0
                else:
                    rate = 0.0
                segments.append(
                    {
                        "extent": format_duration_seconds(extent_s),
                        "dynamics": {"initial": value, "rate": rate},
                    }
                )

        if discrete:
            schema = schema_for(schema_type)
            rtype = "discrete"
        else:
            schema = {
                "type": "struct",
                "items": {"initial": {"type": "real"}, "rate": {"type": "real"}},
            }
            rtype = "real"

        return {"name": self.name, "type": rtype, "schema": schema, "segments": segments}


# --------------------------------------------------------------------------
# Main conversion
# --------------------------------------------------------------------------


class Stats:
    def __init__(self):
        self.counts_seen: Dict[str, int] = {}
        self.counts_dropped_window: Dict[str, int] = {}
        self.counts_dropped_maxact: int = 0
        self.unmatched_act_starts: int = 0
        self.matched_activities: int = 0
        self.resources_seen: int = 0
        self.resources_with_spec: int = 0
        self.resources_without_spec: int = 0
        self.unsupported_records: Dict[str, int] = {}
        self.warnings: List[str] = []

    def bump(self, d: Dict[str, int], k: str):
        d[k] = d.get(k, 0) + 1


def eprint(*a, **kw):
    print(*a, file=sys.stderr, **kw)


def convert(
    input_path: str,
    start_arg: Optional[str],
    end_arg: Optional[str],
    max_activities: Optional[int],
    plan_name: str,
    verbose: bool,
) -> Dict[str, Any]:
    import xml.etree.ElementTree as ET

    stats = Stats()

    def warn(msg: str):
        stats.warnings.append(msg)
        if verbose:
            eprint(f"[warn] {msg}")

    window_start = parse_timestamp(start_arg) if start_arg else None
    window_end = parse_timestamp(end_arg) if end_arg else None

    def in_window(ts: dt.datetime) -> bool:
        if window_start is not None and ts < window_start:
            return False
        if window_end is not None and ts > window_end:
            return False
        return True

    resource_specs: Dict[str, ResourceSpec] = {}
    resource_accs: Dict[str, ResourceAccumulator] = {}
    activity_types: Dict[str, ActivityTypeInfo] = {}
    ids = IdAllocator(start=1)
    pending: Dict[str, PendingActivity] = {}
    finished: List[PendingActivity] = []

    observed_min_ts: Optional[dt.datetime] = None
    observed_max_ts: Optional[dt.datetime] = None
    observed_min_text: Optional[str] = None
    observed_max_text: Optional[str] = None

    def observe_ts(ts: dt.datetime, text: str):
        nonlocal observed_min_ts, observed_max_ts, observed_min_text, observed_max_text
        if observed_min_ts is None or ts < observed_min_ts:
            observed_min_ts, observed_min_text = ts, text
        if observed_max_ts is None or ts > observed_max_ts:
            observed_max_ts, observed_max_text = ts, text

    n_activities_accepted = 0
    max_reached = False

    def get_resource_acc(joined_name: str) -> ResourceAccumulator:
        if joined_name not in resource_accs:
            spec = resource_specs.get(joined_name)
            if spec is None:
                stats.resources_without_spec += 1
                warn(f"resource '{joined_name}' has RES_VAL data but no ResourceSpec metadata; assuming discrete/string.")
            else:
                stats.resources_with_spec += 1
            resource_accs[joined_name] = ResourceAccumulator(joined_name, spec)
        return resource_accs[joined_name]

    def handle_resource_record(elem, is_final: bool, ts: dt.datetime):
        res = elem.find("Resource")
        if res is None:
            return
        name = (res.findtext("Name") or "").strip()
        indices = [
            (int(ix.get("level", i)), (ix.text or "").strip())
            for i, ix in enumerate(res.findall("Index"))
        ]
        indices.sort(key=lambda t: t[0])
        idx_values = [v for _, v in indices]
        joined = build_resource_name(name, idx_values)
        value_elem = find_value_child(res)
        if value_elem is None:
            return
        value, schema_type = convert_value(value_elem)
        acc = get_resource_acc(joined)
        acc.add(ts, value, schema_type, is_final)

    def handle_act_start(elem, ts: dt.datetime):
        nonlocal n_activities_accepted, max_reached
        inst = elem.find("Instance")
        if inst is None:
            return
        uuid = (inst.findtext("ID") or "").strip()
        if not uuid:
            return
        if max_activities is not None and n_activities_accepted >= max_activities:
            max_reached = True
            stats.counts_dropped_maxact += 1
            return
        name = (inst.findtext("Name") or "").strip()
        atype = (inst.findtext("Type") or "").strip()
        parent = (inst.findtext("Parent") or "").strip() or None

        subsystem = legend = visibility = None
        span_duration = None
        attrs = inst.find("Attributes")
        if attrs is not None:
            for attr in attrs.findall("Attribute"):
                aname = (attr.findtext("Name") or "").strip()
                vchild = find_value_child(attr)
                if vchild is None:
                    continue
                if aname == "span" and vchild.tag == "DurationValue":
                    span_duration = parse_tol_duration_value_elem(vchild)
                elif aname == "subsystem":
                    subsystem, _ = convert_value(vchild)
                elif aname == "legend":
                    legend, _ = convert_value(vchild)
                elif aname == "start":
                    pass  # redundant with TimeStamp; not otherwise used
                # other attributes are not currently surfaced individually

        vis = inst.findtext("Visibility")
        if vis:
            visibility = vis.strip()

        arguments: Dict[str, Any] = {}
        ti = activity_types.setdefault(atype, ActivityTypeInfo(atype))
        params = inst.find("Parameters")
        if params is not None:
            for p in params.findall("Parameter"):
                pname = (p.findtext("Name") or "").strip()
                vchild = find_value_child(p)
                if vchild is None or not pname:
                    continue
                value, schema_type = convert_value(vchild)
                arguments[pname] = value
                ti.observe_param(pname, schema_type)
        ti.observe_subsystem(subsystem)

        int_id = ids.get(uuid)
        pa = PendingActivity(
            uuid=uuid,
            int_id=int_id,
            name=name or atype,
            type=atype,
            parent_uuid=parent,
            start_dt=ts,
            start_text=elem.findtext("TimeStamp") or "",
            span_duration=span_duration,
            subsystem=subsystem,
            legend=legend,
            visibility=visibility,
            arguments=arguments,
        )
        pending[uuid] = pa
        n_activities_accepted += 1

    def handle_act_end(elem, ts: dt.datetime):
        uuid = (elem.findtext("ActivityID") or "").strip()
        if not uuid:
            return
        pa = pending.pop(uuid, None)
        if pa is None:
            # ACT_END for an activity whose ACT_START we never saw (e.g. it
            # started before --start). Nothing to close; drop silently but
            # count it as a dropped-by-window record downstream via the
            # normal counts_dropped_window bookkeeping (already done by the
            # caller when TimeStamp filtering applies). Here it's simply
            # an orphan END with no matching pending START in our set.
            return
        pa.end_dt = ts
        pa.matched = True
        finished.append(pa)

    with open(input_path, "rb") as fh:
        context = ET.iterparse(fh, events=("start", "end"))
        root = None
        in_resource_metadata = False
        for event, elem in context:
            if event == "start":
                if root is None:
                    root = elem
                continue
            # event == 'end'
            tag = elem.tag
            if tag == "ResourceSpec":
                spec = parse_resource_spec_elem(elem)
                resource_specs[spec.joined_name] = spec
                elem.clear()
                continue
            if tag == "ResourceMetadata":
                elem.clear()
                if root is not None:
                    try:
                        root.remove(elem)
                    except ValueError:
                        pass
                continue
            if tag == "TOLrecord":
                rtype = elem.get("type", "")
                stats.bump(stats.counts_seen, rtype)
                ts_text = elem.findtext("TimeStamp") or ""
                try:
                    ts = parse_timestamp(ts_text)
                except Exception:
                    ts = None

                if ts is None or not in_window(ts):
                    if ts is not None:
                        stats.bump(stats.counts_dropped_window, rtype)
                else:
                    # Only records that actually pass the time-window filter
                    # contribute to the observed plan/simulation bounds --
                    # otherwise --start/--end would filter *content* but
                    # plan.startTime/simulationEndTime would still reflect
                    # the whole input file's range.
                    observe_ts(ts, ts_text)
                    if rtype == "ACT_START":
                        handle_act_start(elem, ts)
                    elif rtype == "ACT_END":
                        handle_act_end(elem, ts)
                    elif rtype == "RES_VAL":
                        handle_resource_record(elem, is_final=False, ts=ts)
                    elif rtype == "RES_FINAL_VAL":
                        handle_resource_record(elem, is_final=True, ts=ts)
                    else:
                        # ERROR / RELEASE / anything else: TOL "rule" events
                        # have no representation in the offline-bundle
                        # schema (no constraint/rule-violation concept).
                        # Counted and reported, never silently dropped.
                        stats.bump(stats.unsupported_records, rtype)

                elem.clear()
                if root is not None:
                    try:
                        root.remove(elem)
                    except ValueError:
                        pass
                continue

    # Anything left in `pending` never got an ACT_END (either truly absent
    # from the file, or its ACT_END fell outside the time window).
    for uuid, pa in pending.items():
        stats.unmatched_act_starts += 1
        finished.append(pa)

    stats.matched_activities = sum(1 for pa in finished if pa.matched)

    if observed_min_ts is None or observed_max_ts is None:
        raise ValueError("No TOLrecord timestamps found in the requested window; nothing to convert.")

    sim_start_dt, sim_start_text = observed_min_ts, observed_min_text
    sim_end_dt, sim_end_text = observed_max_ts, observed_max_text

    # ---- Assemble activityDirectives + spans ----
    finished.sort(key=lambda pa: pa.int_id)
    directives = []
    spans = []
    for pa in finished:
        start_offset = duration_between(sim_start_dt, pa.start_dt)
        if pa.matched and pa.end_dt is not None:
            duration = duration_between(pa.start_dt, pa.end_dt)
        elif pa.span_duration is not None:
            duration = pa.span_duration
        else:
            duration = "+00:00:00.000000"

        metadata: Dict[str, Any] = {"uuid": pa.uuid}
        if pa.subsystem:
            metadata["subsystem"] = pa.subsystem
        if pa.legend:
            metadata["legend"] = pa.legend
        if pa.visibility:
            metadata["visibility"] = pa.visibility
        if not pa.matched:
            metadata["unmatchedActEnd"] = True

        directives.append(
            {
                "id": pa.int_id,
                "type": pa.type,
                "name": pa.name,
                "startOffset": start_offset,
                "anchorId": None,
                "anchoredToStart": True,
                "arguments": pa.arguments,
                "metadata": metadata,
            }
        )

        parent_int_id = None
        if pa.parent_uuid:
            parent_int_id = ids.peek(pa.parent_uuid)
            # If the parent's ACT_START fell outside the window/cap, we
            # cannot resolve it to an int id; leave parentId null rather
            # than fabricate a dangling reference.

        attributes: Dict[str, Any] = {}
        if pa.subsystem:
            attributes["subsystem"] = pa.subsystem
        if pa.legend:
            attributes["legend"] = pa.legend
        if not pa.matched:
            attributes["unmatchedActEnd"] = True

        spans.append(
            {
                "id": pa.int_id,
                "type": pa.type,
                "parentId": parent_int_id,
                "directiveId": pa.int_id,
                "startOffset": start_offset,
                "duration": duration,
                "arguments": pa.arguments,
                "attributes": attributes,
            }
        )

    # ---- Assemble resources ----
    resources = []
    for name in sorted(resource_accs.keys()):
        built = resource_accs[name].build(sim_end_dt, sim_start_dt, warn)
        if built is not None:
            resources.append(built)

    activity_type_entries = [
        activity_types[t].to_bundle_entry() for t in sorted(activity_types.keys())
    ]

    bundle = {
        "bundleVersion": BUNDLE_VERSION,
        "plan": {
            "name": plan_name,
            "startTime": sim_start_text,
            "duration": duration_between(sim_start_dt, sim_end_dt),
        },
        "activityTypes": activity_type_entries,
        "activityDirectives": directives,
        "simulation": {
            "simulationStartTime": sim_start_text,
            "simulationEndTime": sim_end_text,
            "canceled": False,
            "spans": spans,
            "resources": resources,
        },
        # No "view" key: TOL carries no layout information, so the UI generates
        # a default view from the resources. Emitting an empty `view: {}` is
        # schema-valid but yields a view with no `plan.timelines`, which older
        # loaders read as a real view and fail on.
    }

    # ---- Reporting ----
    eprint("=== tol2bundle summary ===")
    eprint(f"input: {input_path}")
    eprint(f"window: start={start_arg or '(none)'} end={end_arg or '(none)'} max-activities={max_activities or '(none)'}")
    eprint(f"records seen by type: {stats.counts_seen}")
    eprint(f"records dropped by time window: {stats.counts_dropped_window}")
    eprint(f"ACT_START records dropped by --max-activities: {stats.counts_dropped_maxact}")
    eprint(f"unsupported record types dropped (no bundle representation): {stats.unsupported_records}")
    eprint(f"activities emitted: {len(finished)} (matched ACT_START/ACT_END pairs: {stats.matched_activities}, unmatched ACT_START: {stats.unmatched_act_starts})")
    eprint(f"resources emitted: {len(resources)} (with ResourceSpec: {stats.resources_with_spec}, without: {stats.resources_without_spec})")
    total_segments = sum(len(r["segments"]) for r in resources)
    eprint(f"total resource segments: {total_segments}")
    eprint(f"activity types: {len(activity_type_entries)}")
    eprint(f"plan window: {sim_start_text} .. {sim_end_text} (duration {bundle['plan']['duration']})")
    if stats.warnings:
        eprint(f"warnings: {len(stats.warnings)} (rerun with -v to print them)")

    return bundle


# --------------------------------------------------------------------------
# Validation
# --------------------------------------------------------------------------


def validate_bundle(bundle: Dict[str, Any], schema_path: str, verbose: bool) -> None:
    with open(schema_path) as f:
        schema = json.load(f)

    try:
        import jsonschema  # type: ignore

        validator_cls = jsonschema.validators.validator_for(schema)
        validator_cls.check_schema(schema)
        validator = validator_cls(schema)
        errors = sorted(validator.iter_errors(bundle), key=lambda e: list(e.path))
        if errors:
            for e in errors[:20]:
                path = "/".join(str(p) for p in e.path)
                eprint(f"[schema error] at '{path}': {e.message}")
            raise SystemExit(f"Output failed JSON Schema validation ({len(errors)} error(s)). Aborting.")
        if verbose:
            eprint("[ok] validated against JSON Schema via jsonschema library.")
        return
    except ImportError:
        pass

    # Minimal fallback validator: required/type/enum checks only, following
    # the schema's structure by hand. Not a full JSON Schema implementation,
    # but enough to catch the common regressions (missing required fields,
    # wrong top-level types, bad enum values).
    def fail(msg: str):
        raise SystemExit(f"Output failed minimal validation: {msg}")

    def check_type(value, types, where):
        types = types if isinstance(types, list) else [types]
        pytypes = {
            "object": dict,
            "array": list,
            "string": str,
            "integer": int,
            "number": (int, float),
            "boolean": bool,
            "null": type(None),
        }
        ok = any(
            isinstance(value, pytypes[t]) and not (t == "integer" and isinstance(value, bool))
            for t in types
        )
        if not ok:
            fail(f"{where}: expected type in {types}, got {type(value).__name__}")

    check_type(bundle, "object", "$")
    for req in ("bundleVersion", "plan", "activityDirectives", "simulation"):
        if req not in bundle:
            fail(f"$: missing required property '{req}'")
    if not re.match(r"^\d+\.\d+\.\d+$", bundle["bundleVersion"]):
        fail("$.bundleVersion: does not match semver pattern")

    plan = bundle["plan"]
    check_type(plan, "object", "$.plan")
    for req in ("name", "startTime", "duration"):
        if req not in plan:
            fail(f"$.plan: missing required property '{req}'")

    sim = bundle["simulation"]
    check_type(sim, "object", "$.simulation")
    for req in ("simulationStartTime", "simulationEndTime"):
        if req not in sim:
            fail(f"$.simulation: missing required property '{req}'")

    for i, d in enumerate(bundle.get("activityDirectives", [])):
        for req in ("id", "type", "startOffset"):
            if req not in d:
                fail(f"$.activityDirectives[{i}]: missing required property '{req}'")
        check_type(d["id"], "integer", f"$.activityDirectives[{i}].id")

    for i, sp in enumerate(sim.get("spans", [])):
        for req in ("id", "type", "startOffset"):
            if req not in sp:
                fail(f"$.simulation.spans[{i}]: missing required property '{req}'")

    for i, r in enumerate(sim.get("resources", [])):
        for req in ("name", "type", "schema", "segments"):
            if req not in r:
                fail(f"$.simulation.resources[{i}]: missing required property '{req}'")
        if r["type"] not in ("discrete", "real"):
            fail(f"$.simulation.resources[{i}].type: must be 'discrete' or 'real', got {r['type']!r}")
        for j, seg in enumerate(r["segments"]):
            if "extent" not in seg:
                fail(f"$.simulation.resources[{i}].segments[{j}]: missing required property 'extent'")

    if verbose:
        eprint("[ok] validated with minimal fallback validator (install 'jsonschema' for full coverage).")


# --------------------------------------------------------------------------
# CLI
# --------------------------------------------------------------------------


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(
        description="Convert a JPL TOL XML file into an Aerie offline-bundle JSON file."
    )
    parser.add_argument("input", help="Path to the .tol.xml input file.")
    parser.add_argument("-o", "--output", required=True, help="Path to write the bundle JSON.")
    parser.add_argument("--start", default=None, help="Only include records at/after this time (DOY or ISO-8601).")
    parser.add_argument("--end", default=None, help="Only include records at/before this time (DOY or ISO-8601).")
    parser.add_argument("--max-activities", type=int, default=None, help="Cap the number of ACT_START records accepted.")
    parser.add_argument("--plan-name", default=None, help="Name for the emitted plan (default: derived from input filename).")
    parser.add_argument("--schema", default=None, help="Path to the offline-bundle JSON Schema (default: bundled copy).")
    parser.add_argument("--no-validate", action="store_true", help="Skip schema validation (not recommended).")
    parser.add_argument("-v", "--verbose", action="store_true", help="Verbose logging to stderr.")
    args = parser.parse_args(argv)

    plan_name = args.plan_name
    if not plan_name:
        import os

        base = os.path.basename(args.input)
        plan_name = re.sub(r"\.tol\.xml$|\.xml$", "", base, flags=re.IGNORECASE)

    bundle = convert(
        input_path=args.input,
        start_arg=args.start,
        end_arg=args.end,
        max_activities=args.max_activities,
        plan_name=plan_name,
        verbose=args.verbose,
    )

    if not args.no_validate:
        import os

        schema_path = args.schema
        if schema_path is None:
            schema_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "schema", "offline-bundle-schema-v1.json")
        validate_bundle(bundle, schema_path, args.verbose)

    with open(args.output, "w") as f:
        json.dump(bundle, f, indent=2, sort_keys=True)
        f.write("\n")

    import os

    size = os.path.getsize(args.output)
    eprint(f"wrote {args.output} ({size:,} bytes)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
