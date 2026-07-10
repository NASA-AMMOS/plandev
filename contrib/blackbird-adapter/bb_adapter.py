#!/usr/bin/env python3
"""Blackbird -> PlanDev adapter (real, via Hasura actions).

Flow (all through the supported GraphQL surface, no raw SQL):
  (a) run Blackbird: CREATE_DICTIONARY + OPEN_FILE + REMODEL + WRITE out.xml
  (b) translate dict.json + out.xml -> PlanDev shapes
  (c) insert_mission_model_one (model_type="external")   -> modelId
  (d) registerModelTypes(modelId, activityTypes, resourceTypes, parameters)
  (e) insert_merlin_plan_one                              -> planId
  (f) ingestExternalSimulationResults(planId, results)   -> simulationDatasetId

Encodings (verified against merlin-server parsers):
  * durations are MICROSECOND INTEGERS (durationP = longP)
  * profile segments are {duration:<length µs>, dynamics:<...>} laid consecutively from offset 0
  * SerializedValue is RAW json (number/string/map), NOT tagged
Only dependency: requests
"""
import json, os, re, subprocess, sys, tempfile
import xml.etree.ElementTree as ET
from datetime import datetime, timedelta, timezone
import requests

GRAPHQL_URL  = os.environ.get("AERIE_GRAPHQL_URL", "http://localhost:8080/v1/graphql")
ADMIN_SECRET = os.environ.get("HASURA_ADMIN_SECRET", "YOURSECRET")
BLACKBIRD_CP = os.environ.get("BLACKBIRD_CP", "")
BLACKBIRD_MAIN = os.environ.get("BLACKBIRD_MAIN", "gov.nasa.jpl.Blackbird")
JPLTIME_LIB  = os.environ.get("JPLTIME_LIB", "jplTime/lib")
JAVA_BIN     = os.environ.get("JAVA_BIN", "java")

# ---------------- translation helpers ----------------
def bb_param_schema(t):
    t = (t or "").lower()
    return {"float": {"type": "real"}, "double": {"type": "real"},
            "int": {"type": "int"}, "integer": {"type": "int"}, "long": {"type": "int"},
            "boolean": {"type": "boolean"}, "bool": {"type": "boolean"},
            "duration": {"type": "duration"}, "string": {"type": "string"},
            "time": {"type": "string"}}.get(t, {"type": "string"})

def offset_us(ts, plan_start):
    m = re.match(r"(\d+)-(\d+)T(\d+):(\d+):(\d+)(?:\.(\d+))?", ts)
    y, doy, hh, mm, ss = int(m[1]), int(m[2]), int(m[3]), int(m[4]), int(m[5])
    micros = int(((m[6] or "0") + "000000")[:6])
    t = datetime(y, 1, 1, tzinfo=timezone.utc) + timedelta(days=doy-1, hours=hh, minutes=mm, seconds=ss, microseconds=micros)
    return round((t - plan_start).total_seconds() * 1_000_000)

def dur_us(d):
    days = 0
    if "T" in d:
        dp, d = d.split("T"); days = int(dp)
    hh, mm, ss = d.split(":")
    return round((days*86400 + int(hh)*3600 + int(mm)*60 + float(ss)) * 1_000_000)

def aerie_timestamp(iso):  # "2020-01-01T00:00:00Z" -> "2020-001T00:00:00.000000"
    t = datetime.fromisoformat(iso.replace("Z", "+00:00")).astimezone(timezone.utc)
    return t.strftime("%Y-%jT%H:%M:%S.%f")

def parse_dictionary(dict_path):
    dct = json.load(open(dict_path))
    out = []
    for name, meta in dct.get("activities", {}).items():
        params = [{"name": p["name"], "schema": bb_param_schema(p["type"])} for p in meta.get("parameters", [])]
        reqd = [p["name"] for p in meta.get("parameters", []) if p.get("default", "") == ""]
        at = {"name": name, "parameters": params, "requiredParameters": reqd,
              "computedAttributesSchema": {"type": "struct", "items": {}}}
        if meta.get("subsystem"): at["subsystem"] = meta["subsystem"]
        if meta.get("description"): at["description"] = meta["description"]
        out.append(at)
    return out

def composite_name(el):
    """Flatten Blackbird arrayed resources: <Name> + <Index level=N>idx</Index>...
    -> dotted name, e.g. PositionVector.x, ExampleBodyState.Earth.x."""
    base = el.findtext("Name")
    idxs = [i.text or "" for i in el.findall("Index")]
    return base + "".join("." + i for i in idxs)

def parse_output(xml_path, plan_start, sim_duration_us):
    root = ET.parse(xml_path).getroot()
    res_specs = {}
    for spec in root.iter("ResourceSpec"):
        name = composite_name(spec)
        dtype = (spec.findtext("DataType") or "").lower()
        interp = (spec.findtext("Interpolation") or "constant").lower()
        poss = [s.text for s in spec.findall("./PossibleStates/StringValue")]
        if dtype in ("float", "double") and interp == "linear":
            vs, is_real = {"type": "real"}, True
        elif dtype in ("float", "double"):
            vs, is_real = {"type": "real"}, False
        elif dtype in ("int", "integer", "long"):
            vs, is_real = {"type": "int"}, False
        elif dtype == "duration":
            vs, is_real = {"type": "duration"}, False
        elif poss:
            vs, is_real = {"type": "variant", "variants": [{"key": p, "label": p} for p in poss]}, False
        else:
            vs, is_real = {"type": "string"}, False
        res_specs[name] = (vs, is_real)

    spans = []
    sid = 0
    for rec in root.iter("TOLrecord"):
        if rec.get("type") != "ACT_START":
            continue
        inst = rec.find("Instance")
        if (inst.findtext("Parent") or "").strip() != "":
            continue
        sid += 1
        typ = inst.findtext("Type")
        start = span = None; args = {}
        for a in inst.findall("./Attributes/Attribute"):
            if a.findtext("Name") == "start": start = a.find("TimeValue").text
            if a.findtext("Name") == "span":  span = a.find("DurationValue").text
        for p in inst.findall("./Parameters/Parameter"):
            pn = p.findtext("Name")
            dv, sv = p.find("DurationValue"), p.find("StringValue")
            iv, fv = p.find("IntegerValue"), p.find("DoubleValue")
            if dv is not None:   args[pn] = dur_us(dv.text)
            elif fv is not None: args[pn] = float(fv.text)
            elif iv is not None: args[pn] = int(iv.text)
            elif sv is not None: args[pn] = sv.text
        spans.append({"spanId": sid, "type": typ,
                      "startOffset": offset_us(start, plan_start),
                      "duration": dur_us(span), "arguments": args})

    # RES_VAL -> consecutive-duration segments
    samples = {}
    for rec in root.iter("TOLrecord"):
        if rec.get("type") != "RES_VAL":
            continue
        r = rec.find("Resource")
        name = composite_name(r)
        if name not in res_specs:
            continue
        val = None
        for tag in ("DoubleValue", "IntegerValue", "IntValue", "StringValue", "DurationValue"):
            e = r.find(tag)
            if e is not None:
                if tag == "DoubleValue":                  val = float(e.text)
                elif tag in ("IntegerValue", "IntValue"): val = int(e.text)
                elif tag == "DurationValue":              val = dur_us(e.text)
                else:                                     val = e.text
                break
        samples.setdefault(name, []).append((offset_us(rec.findtext("TimeStamp"), plan_start), val))

    resource_types = [{"name": n, "schema": vs} for n, (vs, _) in res_specs.items()]
    profiles = {}
    for name, segs in samples.items():
        vs, is_real = res_specs[name]
        segs.sort(key=lambda x: x[0])
        # pad to start at offset 0
        if segs[0][0] > 0:
            segs.insert(0, (0, segs[0][1]))
        out_segs = []
        for i, (off, val) in enumerate(segs):
            end = segs[i+1][0] if i+1 < len(segs) else sim_duration_us
            length = end - off
            if length <= 0:
                continue
            dyn = {"initial": float(val), "rate": 0.0} if is_real else val
            out_segs.append({"duration": length, "dynamics": dyn})
        profiles[name] = {"type": "real" if is_real else "discrete", "schema": vs, "segments": out_segs}
    return resource_types, spans, profiles

# ---------------- blackbird ----------------
def run_blackbird(plan_file, workdir):
    dict_path = os.path.join(workdir, "model.dict.json")
    xml_path = os.path.join(workdir, "out.xml")
    script = os.path.join(workdir, "adapter.script")
    lines = [f"CREATE_DICTIONARY {dict_path}"]
    if plan_file:
        lines.append(f"OPEN_FILE {os.path.abspath(plan_file)} unfrozen decompose")
    lines += ["REMODEL", f"WRITE {xml_path}"]
    open(script, "w").write("\n".join(lines) + "\n")
    cmd = [JAVA_BIN, "-cp", BLACKBIRD_CP, f"-Djava.library.path={JPLTIME_LIB}", BLACKBIRD_MAIN, script]
    print("[blackbird]", " ".join(cmd), file=sys.stderr)
    subprocess.run(cmd, check=True, cwd=workdir)
    return dict_path, xml_path

# ---------------- graphql ----------------
def gql(query, variables):
    r = requests.post(GRAPHQL_URL, headers={"x-hasura-admin-secret": ADMIN_SECRET},
                      json={"query": query, "variables": variables})
    r.raise_for_status()
    body = r.json()
    if body.get("errors"):
        raise RuntimeError(json.dumps(body["errors"], indent=2))
    return body["data"]

def create_model(mission, name, version):
    q = """mutation($o:mission_model_insert_input!){ insert_mission_model_one(object:$o){ id } }"""
    o = {"mission": mission, "name": name, "version": version, "description": "Blackbird adapter", "model_type": "external"}
    return gql(q, {"o": o})["insert_mission_model_one"]["id"]

def register_model_types(model_id, activity_types, resource_types, parameters):
    q = """mutation($m:Int!,$a:[ModelActivityTypeInput!]!,$r:[ModelResourceTypeInput!]!,$p:[ModelParameterInput!]!){
      registerModelTypes(missionModelId:$m, activityTypes:$a, resourceTypes:$r, parameters:$p){
        activityTypeCount resourceTypeCount parameterCount } }"""
    return gql(q, {"m": model_id, "a": activity_types, "r": resource_types, "p": parameters})["registerModelTypes"]

def create_plan(model_id, name, start_iso, duration_hms):
    q = """mutation($o:plan_insert_input!){ insert_plan_one(object:$o){ id } }"""
    o = {"name": name, "model_id": model_id, "start_time": start_iso, "duration": duration_hms}
    return gql(q, {"o": o})["insert_plan_one"]["id"]

def ingest(plan_id, start_ts, duration_us, profiles, spans):
    q = """mutation($p:Int!,$r:ExternalSimulationResults!){
      ingestExternalSimulationResults(planId:$p, results:$r){ simulationDatasetId } }"""
    results = {"startTime": start_ts, "duration": duration_us, "profiles": profiles, "spans": spans}
    return gql(q, {"p": plan_id, "r": results})["ingestExternalSimulationResults"]

def run_adapter(plan_file, mission, model_name, version, plan_start_iso, plan_duration_hms, sim_duration_us):
    plan_start = datetime.fromisoformat(plan_start_iso.replace("Z", "+00:00")).astimezone(timezone.utc)
    with tempfile.TemporaryDirectory() as wd:
        dict_path, xml_path = run_blackbird(plan_file, wd)
        activity_types = parse_dictionary(dict_path)
        resource_types, spans, profiles = parse_output(xml_path, plan_start, sim_duration_us)
        model_id = create_model(mission, model_name, version)
        reg = register_model_types(model_id, activity_types, resource_types, [])  # config params: none for exampleAdaptation
        plan_id = create_plan(model_id, f"{model_name} plan (adapter)", plan_start_iso, plan_duration_hms)
        ing = ingest(plan_id, aerie_timestamp(plan_start_iso), sim_duration_us, profiles, spans)
        print(json.dumps({"modelId": model_id, "planId": plan_id, "register": reg, "ingest": ing,
                          "counts": {"activityTypes": len(activity_types), "resourceTypes": len(resource_types),
                                     "spans": len(spans), "profiles": len(profiles)}}, indent=2))

if __name__ == "__main__":
    run_adapter(
        plan_file        = sys.argv[1] if len(sys.argv) > 1 else None,
        mission          = os.environ.get("BB_MISSION", "BlackbirdDemo"),
        model_name       = os.environ.get("BB_MODEL", "exampleAdaptation"),
        version          = os.environ.get("BB_VERSION", "1.0"),
        plan_start_iso   = os.environ.get("BB_PLAN_START", "2020-01-01T00:00:00Z"),
        plan_duration_hms= os.environ.get("BB_PLAN_DURATION", "06:00:00"),
        sim_duration_us  = int(os.environ.get("BB_SIM_DURATION_US", str(6*3600*1_000_000))),
    )
