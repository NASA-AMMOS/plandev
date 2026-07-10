#!/usr/bin/env python3
"""Blackbird external-model backend SERVICE.

PlanDev's merlin simulation route (for model_type='external') POSTs to /simulate:
  { planStart: <ISO>, duration: <us>, configuration: {..}, directives: [ {id,type,startOffset(us),arguments} ] }
This service converts the directives to a Blackbird .plan.json, runs Blackbird
(OPEN_FILE unfrozen decompose -> REMODEL -> WRITE), translates the XMLTOL output, and returns:
  { realProfiles: {name:{schema,segments:[{duration,dynamics:{initial,rate}}]}},
    discreteProfiles: {name:{schema,segments:[{duration,dynamics}]}},
    spans: [{spanId,type,startOffset,duration,arguments,parentId?}] }

Run:  BLACKBIRD_CP=... JPLTIME_LIB=... python3 bb_service.py [port]
stdlib only.
"""
import json, os, re, subprocess, sys, tempfile, uuid
import xml.etree.ElementTree as ET
from datetime import datetime, timedelta, timezone
from http.server import BaseHTTPRequestHandler, HTTPServer

BLACKBIRD_CP = os.environ["BLACKBIRD_CP"]
BLACKBIRD_MAIN = os.environ.get("BLACKBIRD_MAIN", "gov.nasa.jpl.Blackbird")
JPLTIME_LIB = os.environ.get("JPLTIME_LIB", "jplTime/lib")
JAVA_BIN = os.environ.get("JAVA_BIN", "java")
PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 5001

def iso_to_dt(iso):
    return datetime.fromisoformat(iso.replace("Z", "+00:00")).astimezone(timezone.utc)

def dt_to_bbtime(dt):
    return dt.strftime("%Y-%jT%H:%M:%S.%f")

def us_to_bbdur(us):
    s = us / 1_000_000.0
    d = int(s // 86400); s -= d*86400
    hh = int(s // 3600); s -= hh*3600
    mm = int(s // 60); s -= mm*60
    body = "%02d:%02d:%09.6f" % (hh, mm, s)
    return ("%dT%s" % (d, body)) if d else body

def bb_dur_to_us(txt):
    days = 0
    if "T" in txt:
        dp, txt = txt.split("T"); days = int(dp)
    hh, mm, ss = txt.split(":")
    return round((days*86400 + int(hh)*3600 + int(mm)*60 + float(ss)) * 1_000_000)

def bb_time_to_us_offset(ts, plan_start):
    m = re.match(r"(\d+)-(\d+)T(\d+):(\d+):(\d+)(?:\.(\d+))?", ts)
    y, doy, hh, mm, ss = int(m[1]), int(m[2]), int(m[3]), int(m[4]), int(m[5])
    micros = int(((m[6] or "0") + "000000")[:6])
    t = datetime(y, 1, 1, tzinfo=timezone.utc) + timedelta(days=doy-1, hours=hh, minutes=mm, seconds=ss, microseconds=micros)
    return round((t - plan_start).total_seconds() * 1_000_000)

# activity param types from CREATE_DICTIONARY (name -> [(paramName, bbType)])
PARAM_TYPES = {}
# each resource's declared initial value (composite name -> value), captured from a zero-activity
# REMODEL. Blackbird registers all resources at engine startup, so an empty sim reports every
# resource's default BEFORE any real plan runs -- used to seed the t=0 profile segment correctly.
INITIALS = {}
def load_dictionary(workdir):
    dict_path = os.path.join(workdir, "model.dict.json")
    script = os.path.join(workdir, "dict.script")
    open(script, "w").write("CREATE_DICTIONARY %s\n" % dict_path)
    run_bb(script, workdir)
    d = json.load(open(dict_path))
    for name, meta in d.get("activities", {}).items():
        PARAM_TYPES[name] = [(p["name"], p.get("type", "string")) for p in meta.get("parameters", [])]

def load_initials(workdir):
    """Run a zero-activity REMODEL and record each resource's initial/default value at sim start.
    This is also proof that resource *types* are knowable pre-simulation (see the ResourceSpec dump)."""
    plan = os.path.join(workdir, "empty.plan.json")
    json.dump({"activities": []}, open(plan, "w"))
    xml = os.path.join(workdir, "empty.xml")
    script = os.path.join(workdir, "init.script")
    open(script, "w").write("OPEN_FILE %s unfrozen decompose\nREMODEL\nWRITE %s\n" % (plan, xml))
    run_bb(script, workdir)
    root = ET.parse(xml).getroot()
    seen = {}
    for rec in root.iter("TOLrecord"):
        if rec.get("type") != "RES_VAL":
            continue
        r = rec.find("Resource"); name = composite_name(r)
        if name in seen:  # keep earliest (initial) sample only
            continue
        for tag in ("DoubleValue", "IntegerValue", "IntValue", "StringValue", "DurationValue"):
            e = r.find(tag)
            if e is not None:
                if tag == "DoubleValue":                  seen[name] = float(e.text)
                elif tag in ("IntegerValue", "IntValue"): seen[name] = int(e.text)
                elif tag == "DurationValue":              seen[name] = bb_dur_to_us(e.text)
                else:                                     seen[name] = e.text
                break
    INITIALS.update(seen)

def run_bb(script, workdir):
    cmd = [JAVA_BIN, "-cp", BLACKBIRD_CP, "-Djava.library.path=%s" % JPLTIME_LIB, BLACKBIRD_MAIN, script]
    p = subprocess.run(cmd, cwd=workdir, capture_output=True, text=True)
    if p.returncode != 0:
        # surface BB's stderr + the generated inputs for debugging
        try:
            planj = open(os.path.join(workdir, "in.plan.json")).read()
        except Exception:
            planj = "(no in.plan.json)"
        raise RuntimeError("Blackbird exit %d\nSTDERR:\n%s\nSTDOUT:\n%s\nPLAN:\n%s"
                           % (p.returncode, p.stderr[-1500:], p.stdout[-500:], planj[:1500]))

def fmt_param(bbtype, value):
    if bbtype == "duration" and isinstance(value, (int, float)):
        return us_to_bbdur(int(value))
    if bbtype == "string":
        return '"%s"' % value
    return value  # numbers/bools raw; lists/maps best-effort

def build_plan_json(plan_start, directives, workdir):
    acts = []
    directive_by_uuid = {}  # Blackbird activity UUID -> originating PlanDev directive id
    for d in directives:
        typ = d["type"]
        start = dt_to_bbtime(plan_start + timedelta(microseconds=d["startOffset"]))
        ptypes = dict(PARAM_TYPES.get(typ, []))
        params = []
        for pname, pval in (d.get("arguments") or {}).items():
            bt = ptypes.get(pname, "string")
            v = fmt_param(bt, pval)
            params.append({"name": pname, "type": bt, "value": v if isinstance(v, str) else json.dumps(v)})
        # Blackbird requires a UUID id; derive a stable one from the PlanDev directive id.
        bb_id = str(uuid.uuid5(uuid.NAMESPACE_OID, "plandev-directive-" + str(d["id"])))
        directive_by_uuid[bb_id] = d["id"]
        acts.append({"type": typ, "start": start, "parameters": params, "notes": "", "id": bb_id, "parent": None})
    path = os.path.join(workdir, "in.plan.json")
    json.dump({"activities": acts}, open(path, "w"))
    return path, directive_by_uuid

def composite_name(el):
    """Blackbird arrayed resources emit <Name> + one or more <Index level=N>idx</Index>;
    flatten to a PlanDev-friendly dotted name, e.g. PositionVector.x, ExampleBodyState.Earth.x."""
    base = el.findtext("Name")
    idxs = [i.text or "" for i in el.findall("Index")]
    return base + "".join("." + i for i in idxs)

def parse_output(xml_path, plan_start, sim_duration_us, directive_by_uuid=None):
    directive_by_uuid = directive_by_uuid or {}
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

    # Two passes so decomposition children (which may appear before their parent in the
    # TOL) can resolve their parent's spanId: first assign a sequential spanId per
    # activity instance keyed by Blackbird's own UUID, then link Parent UUID -> spanId.
    act_recs = [r for r in root.iter("TOLrecord") if r.get("type") == "ACT_START"]
    uuid_to_sid = {r.find("Instance").findtext("ID"): i for i, r in enumerate(act_recs, start=1)}
    spans = []
    for sid, rec in enumerate(act_recs, start=1):
        inst = rec.find("Instance")
        parent_uuid = (inst.findtext("Parent") or "").strip()
        parent_sid = uuid_to_sid.get(parent_uuid) if parent_uuid else None
        # a top-level instance carrying our correlation UUID links back to its PlanDev directive;
        # spawned/decomposed instances have no directive (they are pure sim output).
        directive_id = directive_by_uuid.get(inst.findtext("ID"))
        start = span = None; args = {}
        for a in inst.findall("./Attributes/Attribute"):
            if a.findtext("Name") == "start": start = a.find("TimeValue").text
            if a.findtext("Name") == "span":  span = a.find("DurationValue").text
        for p in inst.findall("./Parameters/Parameter"):
            pn = p.findtext("Name")
            dv, sv = p.find("DurationValue"), p.find("StringValue")
            iv, fv = p.find("IntegerValue"), p.find("DoubleValue")
            if dv is not None:   args[pn] = bb_dur_to_us(dv.text)
            elif fv is not None: args[pn] = float(fv.text)
            elif iv is not None: args[pn] = int(iv.text)
            elif sv is not None: args[pn] = sv.text
        spans.append({"spanId": sid, "type": inst.findtext("Type"),
                      "startOffset": bb_time_to_us_offset(start, plan_start),
                      "duration": bb_dur_to_us(span), "arguments": args,
                      "parentId": parent_sid, "directiveId": directive_id})

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
                if tag == "DoubleValue":                val = float(e.text)
                elif tag in ("IntegerValue", "IntValue"): val = int(e.text)
                elif tag == "DurationValue":            val = bb_dur_to_us(e.text)
                else:                                   val = e.text
                break
        samples.setdefault(name, []).append((bb_time_to_us_offset(rec.findtext("TimeStamp"), plan_start), val))

    real_profiles, discrete_profiles = {}, {}
    for name, segs in samples.items():
        vs, is_real = res_specs[name]
        segs.sort(key=lambda x: x[0])
        if segs[0][0] > 0:
            # Blackbird only samples a resource once an activity touches it; before that the
            # resource holds its declared initial value, not its first post-activity value.
            segs.insert(0, (0, INITIALS.get(name, segs[0][1])))
        out_segs = []
        for i, (off, v) in enumerate(segs):
            end = segs[i+1][0] if i+1 < len(segs) else sim_duration_us
            length = end - off
            if length <= 0:
                continue
            dyn = {"initial": float(v), "rate": 0.0} if is_real else v
            out_segs.append({"duration": length, "dynamics": dyn})
        (real_profiles if is_real else discrete_profiles)[name] = {"schema": vs, "segments": out_segs}
    return real_profiles, discrete_profiles, spans

def simulate(req):
    plan_start = iso_to_dt(req["planStart"])
    sim_dur = int(req["duration"])
    with tempfile.TemporaryDirectory() as wd:
        plan_json, directive_by_uuid = build_plan_json(plan_start, req.get("directives", []), wd)
        xml_path = os.path.join(wd, "out.xml")
        script = os.path.join(wd, "sim.script")
        open(script, "w").write(
            "OPEN_FILE %s unfrozen decompose\nREMODEL\nWRITE %s\n" % (plan_json, xml_path))
        run_bb(script, wd)
        rp, dp, spans = parse_output(xml_path, plan_start, sim_dur, directive_by_uuid)
        return {"realProfiles": rp, "discreteProfiles": dp, "spans": spans}

class Handler(BaseHTTPRequestHandler):
    def do_POST(self):
        try:
            n = int(self.headers.get("Content-Length", 0))
            req = json.loads(self.rfile.read(n) or b"{}")
            resp = simulate(req)
            body = json.dumps(resp).encode()
            self.send_response(200); self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body))); self.end_headers()
            self.wfile.write(body)
        except Exception as e:
            import traceback; traceback.print_exc()
            body = json.dumps({"error": str(e)}).encode()
            self.send_response(500); self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body))); self.end_headers()
            self.wfile.write(body)
    def log_message(self, *a): pass

if __name__ == "__main__":
    with tempfile.TemporaryDirectory() as wd:
        load_dictionary(wd)
        load_initials(wd)
    print("Blackbird backend service on :%d  (activity types: %d, resource initials: %d)"
          % (PORT, len(PARAM_TYPES), len(INITIALS)), flush=True)
    HTTPServer(("0.0.0.0", PORT), Handler).serve_forever()
