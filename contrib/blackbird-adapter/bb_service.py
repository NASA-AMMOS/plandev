#!/usr/bin/env python3
"""Blackbird external-model backend SERVICE (multi-model).

One generic, model-agnostic adapter that can serve one OR many Blackbird adaptations. It speaks the
PlanDev external-model wire contract; PlanDev only ever talks to a backend at an operator-configured URL.

Endpoints (each addresses a model by key; if only one model is configured the key is optional):
  GET  /models                         -> { models: [{key, name, version, identityHash}] }   (discovery)
  GET  /introspect?model=<key>         -> { activityTypes, resourceTypes, parameters }
  POST /simulate?model=<key>           { planStart, duration(us), configuration, directives[] }
                                       -> { realProfiles, discreteProfiles, spans }
  POST /validate?model=<key>           { activities[], effectiveOnly }
                                       -> { results: [{valid, notices[], effectiveArguments}] }

Config: BB_MODELS = JSON map of {modelKey: classpath}. (Back-compat: if unset, a single model "default"
uses BLACKBIRD_CP.) Each classpath is Blackbird core + jpl_time + exactly one adaptation.

Run:  BB_MODELS='{"orbiter":"/cp/a","lander":"/cp/b"}' python3 bb_service.py [port]
stdlib only.
"""
import hashlib, json, os, re, subprocess, sys, tempfile, uuid
import xml.etree.ElementTree as ET
from datetime import datetime, timedelta, timezone
from http.server import BaseHTTPRequestHandler, HTTPServer
from urllib.parse import urlparse, parse_qs

BLACKBIRD_MAIN = os.environ.get("BLACKBIRD_MAIN", "gov.nasa.jpl.Blackbird")
JPLTIME_LIB = os.environ.get("JPLTIME_LIB", "jplTime/lib")
JAVA_BIN = os.environ.get("JAVA_BIN", "java")
PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 5001

# modelKey -> {cp, name, version, param_types, param_defaults, res_specs, initials, identity}
MODELS = {}

# ---------- time / value helpers (pure) ----------
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

def composite_name(el):
    """Flatten Blackbird arrayed resources: <Name> + <Index level=N>idx</Index>... -> dotted name."""
    base = el.findtext("Name")
    idxs = [i.text or "" for i in el.findall("Index")]
    return base + "".join("." + i for i in idxs)

def bbtype_to_schema(bt):
    bt = (bt or "").lower()
    if bt in ("double", "float", "real"): return {"type": "real"}
    if bt in ("int", "integer", "long"): return {"type": "int"}
    if bt == "duration": return {"type": "duration"}
    if bt in ("boolean", "bool"): return {"type": "boolean"}
    return {"type": "string"}  # map<>/list<>/custom -> string (best-effort)

def fmt_param(bbtype, value):
    if bbtype == "duration" and isinstance(value, (int, float)):
        return us_to_bbdur(int(value))
    if bbtype == "string":
        return '"%s"' % value
    return value

# ---------- Blackbird invocation (classpath per model) ----------
def run_bb(script, workdir, cp):
    cmd = [JAVA_BIN, "-cp", cp, "-Djava.library.path=%s" % JPLTIME_LIB, BLACKBIRD_MAIN, script]
    p = subprocess.run(cmd, cwd=workdir, capture_output=True, text=True)
    if p.returncode != 0:
        try: planj = open(os.path.join(workdir, "in.plan.json")).read()
        except Exception: planj = "(no in.plan.json)"
        raise RuntimeError("Blackbird exit %d\nSTDERR:\n%s\nSTDOUT:\n%s\nPLAN:\n%s"
                           % (p.returncode, p.stderr[-1500:], p.stdout[-500:], planj[:1500]))

def run_bb_ok(script, workdir, cp):
    cmd = [JAVA_BIN, "-cp", cp, "-Djava.library.path=%s" % JPLTIME_LIB, BLACKBIRD_MAIN, script]
    p = subprocess.run(cmd, cwd=workdir, capture_output=True, text=True)
    return (p.returncode == 0, p.stderr or "")

def parse_res_specs(root):
    """resource composite-name -> (ValueSchema, is_real)."""
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
    return res_specs

def parse_initials(root):
    """resource composite-name -> earliest (initial) value."""
    seen = {}
    for rec in root.iter("TOLrecord"):
        if rec.get("type") != "RES_VAL":
            continue
        r = rec.find("Resource"); name = composite_name(r)
        if name in seen:
            continue
        for tag in ("DoubleValue", "IntegerValue", "IntValue", "StringValue", "DurationValue"):
            e = r.find(tag)
            if e is not None:
                if tag == "DoubleValue":                  seen[name] = float(e.text)
                elif tag in ("IntegerValue", "IntValue"): seen[name] = int(e.text)
                elif tag == "DurationValue":              seen[name] = bb_dur_to_us(e.text)
                else:                                     seen[name] = e.text
                break
    return seen

def load_model(key, cp):
    """Introspect one adaptation: activity param types/defaults (CREATE_DICTIONARY) + resource
    schemas/initials (zero-activity REMODEL). Identity = hash of the introspected types."""
    with tempfile.TemporaryDirectory() as wd:
        dpath = os.path.join(wd, "model.dict.json"); s = os.path.join(wd, "d.script")
        open(s, "w").write("CREATE_DICTIONARY %s\n" % dpath); run_bb(s, wd, cp)
        d = json.load(open(dpath))
        param_types, param_defaults = {}, {}
        for name, meta in d.get("activities", {}).items():
            ps = meta.get("parameters", [])
            param_types[name] = [(p["name"], p.get("type", "string")) for p in ps]
            param_defaults[name] = {p["name"]: p.get("default") for p in ps if p.get("default") not in (None, "")}
        plan = os.path.join(wd, "empty.plan.json"); json.dump({"activities": []}, open(plan, "w"))
        xml = os.path.join(wd, "empty.xml"); s2 = os.path.join(wd, "i.script")
        open(s2, "w").write("OPEN_FILE %s unfrozen decompose\nREMODEL\nWRITE %s\n" % (plan, xml)); run_bb(s2, wd, cp)
        root = ET.parse(xml).getroot()
        res_specs = parse_res_specs(root)
        initials = parse_initials(root)
    identity = hashlib.sha256(json.dumps({
        "acts": {n: sorted(param_types[n]) for n in param_types},
        "res": {n: vs for n, (vs, _) in res_specs.items()},
    }, sort_keys=True).encode()).hexdigest()[:16]
    return {"cp": cp, "name": key, "version": "1.0.0", "param_types": param_types,
            "param_defaults": param_defaults, "res_specs": res_specs, "initials": initials, "identity": identity}

# ---------- plan build / output parse (per-model) ----------
def build_plan_json(plan_start, directives, workdir, param_types):
    acts = []
    directive_by_uuid = {}
    for d in directives:
        typ = d["type"]
        start = dt_to_bbtime(plan_start + timedelta(microseconds=d["startOffset"]))
        ptypes = dict(param_types.get(typ, []))
        params = []
        for pname, pval in (d.get("arguments") or {}).items():
            bt = ptypes.get(pname, "string")
            v = fmt_param(bt, pval)
            params.append({"name": pname, "type": bt, "value": v if isinstance(v, str) else json.dumps(v)})
        bb_id = str(uuid.uuid5(uuid.NAMESPACE_OID, "plandev-directive-" + str(d["id"])))
        directive_by_uuid[bb_id] = d["id"]
        acts.append({"type": typ, "start": start, "parameters": params, "notes": "", "id": bb_id, "parent": None})
    path = os.path.join(workdir, "in.plan.json")
    json.dump({"activities": acts}, open(path, "w"))
    return path, directive_by_uuid

def parse_output(xml_path, plan_start, sim_duration_us, initials, directive_by_uuid=None):
    directive_by_uuid = directive_by_uuid or {}
    root = ET.parse(xml_path).getroot()
    res_specs = parse_res_specs(root)

    act_recs = [r for r in root.iter("TOLrecord") if r.get("type") == "ACT_START"]
    uuid_to_sid = {r.find("Instance").findtext("ID"): i for i, r in enumerate(act_recs, start=1)}
    spans = []
    for sid, rec in enumerate(act_recs, start=1):
        inst = rec.find("Instance")
        parent_uuid = (inst.findtext("Parent") or "").strip()
        parent_sid = uuid_to_sid.get(parent_uuid) if parent_uuid else None
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
            segs.insert(0, (0, initials.get(name, segs[0][1])))
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

# ---------- validation / effective args (per-model) ----------
def clean_bb_error(stderr):
    lines = [l.strip() for l in stderr.splitlines() if l.strip()]
    meaningful = [l for l in lines if not l.startswith("at ")]
    joined = " ".join(meaningful)
    m = re.search(r"Root cause:\s*(.+?)(?:\s+at\s|$)", joined)
    if m:
        return m.group(1).strip()[:400]
    for i, l in enumerate(meaningful):
        if "Error while creating activity" in l:
            return " ".join(meaningful[i:i + 3])[:400]
    for l in meaningful:
        if any(k in l for k in ("Not a JSON", "Index", "cannot", "Cannot", "NumberFormat",
                                "IllegalArgument", "Invalid", "required")):
            return l[:400]
    return (meaningful[-1] if meaningful else "invalid arguments")[:400]

def coerce_default(bbtype, dflt):
    if bbtype in ("int", "integer", "long"):
        try: return int(dflt)
        except (ValueError, TypeError): return dflt
    if bbtype in ("float", "double"):
        try: return float(dflt)
        except (ValueError, TypeError): return dflt
    if bbtype in ("boolean", "bool"):
        return str(dflt).lower() == "true"
    if bbtype == "duration":
        try: return bb_dur_to_us(dflt)
        except Exception: return dflt
    return dflt

def effective_args(typ, provided, param_types, param_defaults):
    eff = dict(provided or {})
    ptypes = dict(param_types.get(typ, []))
    for pname, dflt in param_defaults.get(typ, {}).items():
        if pname not in eff:
            eff[pname] = coerce_default(ptypes.get(pname, "string"), dflt)
    return eff

# ---------- endpoint impls ----------
def introspect(model):
    acts = []
    for name, params in model["param_types"].items():
        defaults = model["param_defaults"].get(name, {})
        acts.append({"name": name,
                     "parameters": [{"name": pn, "schema": bbtype_to_schema(bt)} for pn, bt in params],
                     "requiredParameters": [pn for pn, _ in params if pn not in defaults]})
    res = [{"name": n, "schema": vs} for n, (vs, _) in model["res_specs"].items()]
    return {"activityTypes": acts, "resourceTypes": res, "parameters": [],
            "identityHash": model["identity"]}

def simulate(req, model):
    plan_start = iso_to_dt(req["planStart"])
    sim_dur = int(req["duration"])
    with tempfile.TemporaryDirectory() as wd:
        plan_json, directive_by_uuid = build_plan_json(plan_start, req.get("directives", []), wd, model["param_types"])
        xml_path = os.path.join(wd, "out.xml")
        script = os.path.join(wd, "sim.script")
        open(script, "w").write("OPEN_FILE %s unfrozen decompose\nREMODEL\nWRITE %s\n" % (plan_json, xml_path))
        run_bb(script, wd, model["cp"])
        rp, dp, spans = parse_output(xml_path, plan_start, sim_dur, model["initials"], directive_by_uuid)
        return {"realProfiles": rp, "discreteProfiles": dp, "spans": spans}

def validate(req, model):
    effective_only = bool(req.get("effectiveOnly", False))
    plan_start = datetime(2020, 1, 1, tzinfo=timezone.utc)
    pt, pd = model["param_types"], model["param_defaults"]
    results = []
    with tempfile.TemporaryDirectory() as wd:
        for a in req.get("activities", []):
            typ = a.get("type"); args = a.get("arguments") or {}
            if typ not in pt:
                results.append({"valid": False,
                                "notices": [{"subjects": [], "message": "unknown activity type '%s'" % typ}],
                                "effectiveArguments": None})
                continue
            eff = effective_args(typ, args, pt, pd)
            if effective_only:
                results.append({"valid": True, "notices": [], "effectiveArguments": eff}); continue
            plan_json, _ = build_plan_json(plan_start, [{"id": 0, "type": typ, "startOffset": 0, "arguments": args}], wd, pt)
            script = os.path.join(wd, "validate.script")
            open(script, "w").write("OPEN_FILE %s unfrozen decompose\n" % plan_json)
            ok, stderr = run_bb_ok(script, wd, model["cp"])
            results.append({"valid": ok,
                            "notices": [] if ok else [{"subjects": [], "message": clean_bb_error(stderr)}],
                            "effectiveArguments": eff})
    return {"results": results}

def models_list():
    return {"models": [{"key": k, "name": m["name"], "version": m["version"], "identityHash": m["identity"]}
                       for k, m in MODELS.items()]}

# ---------- HTTP ----------
class Handler(BaseHTTPRequestHandler):
    def _send(self, code, obj):
        body = json.dumps(obj).encode()
        self.send_response(code); self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body))); self.end_headers(); self.wfile.write(body)

    def _resolve(self, key):
        """Model by key; if none given and exactly one is configured, use it. Raises KeyError otherwise."""
        if key and key in MODELS:
            return MODELS[key]
        if not key and len(MODELS) == 1:
            return next(iter(MODELS.values()))
        raise KeyError("unknown or unspecified model '%s'; available: %s" % (key, list(MODELS)))

    def _key(self, body=None):
        q = parse_qs(urlparse(self.path).query).get("model")
        return (q[0] if q else None) or (body or {}).get("model")

    def do_GET(self):
        try:
            path = urlparse(self.path).path.rstrip("/")
            if path.endswith("/models"):
                self._send(200, models_list())
            elif path.endswith("/introspect"):
                self._send(200, introspect(self._resolve(self._key())))
            else:
                self._send(404, {"error": "not found"})
        except Exception as e:
            self._send(400 if isinstance(e, KeyError) else 500, {"error": str(e)})

    def do_POST(self):
        try:
            n = int(self.headers.get("Content-Length", 0))
            req = json.loads(self.rfile.read(n) or b"{}")
            path = urlparse(self.path).path.rstrip("/")
            model = self._resolve(self._key(req))
            if path.endswith("/validate"):
                self._send(200, validate(req, model))
            elif path.endswith("/introspect"):
                self._send(200, introspect(model))
            else:
                self._send(200, simulate(req, model))
        except Exception as e:
            import traceback; traceback.print_exc()
            self._send(400 if isinstance(e, KeyError) else 500, {"error": str(e)})

    def log_message(self, *a): pass

if __name__ == "__main__":
    cfg = os.environ.get("BB_MODELS")
    cp_map = json.loads(cfg) if cfg else {"default": os.environ["BLACKBIRD_CP"]}
    for key, cp in cp_map.items():
        MODELS[key] = load_model(key, cp)
    summary = ", ".join("%s(%d acts/%d res, id=%s)" % (k, len(m["param_types"]), len(m["res_specs"]), m["identity"])
                        for k, m in MODELS.items())
    print("Blackbird multi-model backend on :%d  models: %s" % (PORT, summary), flush=True)
    HTTPServer(("0.0.0.0", PORT), Handler).serve_forever()
