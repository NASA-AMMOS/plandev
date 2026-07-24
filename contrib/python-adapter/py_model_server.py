#!/usr/bin/env python3
"""Minimal PYTHON external-model backend for PlanDev — proof that the `external` wire contract is
language-neutral (same contract the Blackbird adapter speaks; Merlin needs zero new code).

This is an Archetype-A "pure simulator": simulate(directives) -> profiles + spans, with no internal
scheduling (PlanDev's own scheduler could drive it as an oracle).

Model: a toy spacecraft BATTERY.
  Resources: SoC (real, LINEAR — a genuine rate-based profile, which Blackbird's constant reals can't show),
             Mode (variant: Idle | Charging | Discharging), Cycles (int).
  Activities: Charge(duration, rate=1.0/s), Discharge(duration, load=2.0/s).

Endpoints (same shape as the Blackbird adapter):
  POST /simulate   {planStart, duration(us), configuration, directives:[{id,type,startOffset(us),arguments}]}
                -> {realProfiles, discreteProfiles, spans}
  POST /validate   {activities:[{type,arguments}], effectiveOnly}
                -> {results:[{valid, notices:[{subjects,message}], effectiveArguments}]}
  GET  /introspect -> {activityTypes, resourceTypes, parameters}  (for a future registration flow)

Run:  python3 py_model_server.py [port]   (stdlib only)
"""
import hashlib, json, sys
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, HTTPServer
from urllib.parse import urlparse

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 5002
MODEL_KEY, MODEL_NAME, MODEL_VERSION = "battery", "battery", "1.0.0"
US_PER_S = 1_000_000
SOC_INIT, CYCLES_INIT = 50.0, 0

# --- model definition (types + defaults + per-parameter validation) --------------------------------
# Each activity: params as (name, valueSchema, default-or-None). None default => required.
MODEL = {
    "Charge":    [("duration", {"type": "duration"}, None),
                  ("rate",     {"type": "real"},     1.0)],
    "Discharge": [("duration", {"type": "duration"}, None),
                  ("load",     {"type": "real"},     2.0)],
}
RESOURCE_TYPES = {
    "SoC":    {"type": "real"},
    "Mode":   {"type": "variant", "variants": [{"key": k, "label": k} for k in ("Idle", "Charging", "Discharging")]},
    "Cycles": {"type": "int"},
}

def iso_to_dt(iso):
    return datetime.fromisoformat(iso.replace("Z", "+00:00")).astimezone(timezone.utc)

# --- validation / effective args -------------------------------------------------------------------
def effective_args(typ, args):
    eff = dict(args or {})
    for name, _schema, dflt in MODEL.get(typ, []):
        if dflt is not None and name not in eff:
            eff[name] = dflt
    return eff

def validate_one(typ, args, effective_only):
    if typ not in MODEL:
        return {"valid": False, "notices": [{"subjects": [], "message": "unknown activity type '%s'" % typ}],
                "effectiveArguments": None}
    eff = effective_args(typ, args)
    if effective_only:
        return {"valid": True, "notices": [], "effectiveArguments": eff}
    notices = []
    args = args or {}
    for name, _schema, dflt in MODEL[typ]:
        if dflt is None and name not in args:
            notices.append({"subjects": [name], "message": "missing required parameter '%s'" % name})
    # A real per-parameter semantic check — unlike Blackbird, a Python model CAN attribute an error to a
    # specific parameter (subjects), so it renders inline on that field in the UI.
    for pname in ("rate", "load"):
        if pname in args and isinstance(args[pname], (int, float)) and args[pname] <= 0:
            notices.append({"subjects": [pname], "message": "'%s' must be > 0 (got %s)" % (pname, args[pname])})
    return {"valid": len(notices) == 0, "notices": notices, "effectiveArguments": eff}

# --- simulation ------------------------------------------------------------------------------------
def simulate(req):
    sim_dur = int(req["duration"])
    directives = sorted(req.get("directives", []), key=lambda d: d["startOffset"])

    # Build real SoC as continuous piecewise-linear segments {duration, dynamics:{initial, rate}} and
    # discrete Mode / Cycles as {duration, dynamics:<value>} segments. rate is per second.
    real_segs, mode_segs, cyc_segs, spans = [], [], [], []
    t = 0            # cursor (us)
    soc = SOC_INIT   # current SoC value at cursor
    cycles = CYCLES_INIT

    def push_flat(end_us):
        """Advance from cursor t to end_us with the battery idle (rate 0)."""
        nonlocal t, soc
        if end_us <= t:
            return
        dur = end_us - t
        real_segs.append({"duration": dur, "dynamics": {"initial": soc, "rate": 0.0}})
        mode_segs.append({"duration": dur, "dynamics": "Idle"})
        cyc_segs.append({"duration": dur, "dynamics": cycles})
        t = end_us

    for i, d in enumerate(directives, start=1):
        eff = effective_args(d["type"], d.get("arguments") or {})
        start = int(d["startOffset"])
        dur = int(eff.get("duration", 0))
        push_flat(start)                       # idle gap before this activity
        rate_per_s = 0.0
        mode = "Idle"
        if d["type"] == "Charge":
            rate_per_s = float(eff.get("rate", 1.0)); mode = "Charging"; cycles += 1
        elif d["type"] == "Discharge":
            rate_per_s = -float(eff.get("load", 2.0)); mode = "Discharging"
        # linear SoC over the activity's span
        real_segs.append({"duration": dur, "dynamics": {"initial": soc, "rate": rate_per_s}})
        mode_segs.append({"duration": dur, "dynamics": mode})
        cyc_segs.append({"duration": dur, "dynamics": cycles})
        soc = soc + rate_per_s * (dur / US_PER_S)   # value at end of span (continuous)
        t = start + dur
        spans.append({"spanId": i, "type": d["type"], "startOffset": start, "duration": dur,
                      "arguments": eff, "parentId": None, "directiveId": d["id"]})
    push_flat(sim_dur)                          # idle tail to sim end

    return {
        "realProfiles":     {"SoC": {"schema": RESOURCE_TYPES["SoC"], "segments": real_segs}},
        "discreteProfiles": {"Mode":   {"schema": RESOURCE_TYPES["Mode"],   "segments": mode_segs},
                             "Cycles": {"schema": RESOURCE_TYPES["Cycles"], "segments": cyc_segs}},
        "spans": spans,
    }

def identity_hash():
    return hashlib.sha256(json.dumps({
        "acts": {t: [(n, s) for n, s, _ in ps] for t, ps in MODEL.items()},
        "res": RESOURCE_TYPES,
    }, sort_keys=True).encode()).hexdigest()[:16]

def introspect():
    return {
        "activityTypes": [{"name": t, "parameters": [{"name": n, "schema": s} for n, s, _ in ps],
                           "requiredParameters": [n for n, _, d in ps if d is None]}
                          for t, ps in MODEL.items()],
        "resourceTypes": [{"name": n, "schema": s} for n, s in RESOURCE_TYPES.items()],
        "parameters": [],
        "identityHash": identity_hash(),
    }

def models_list():
    return {"models": [{"key": MODEL_KEY, "name": MODEL_NAME, "version": MODEL_VERSION,
                        "identityHash": identity_hash()}]}

class Handler(BaseHTTPRequestHandler):
    def _send(self, code, obj):
        body = json.dumps(obj).encode()
        self.send_response(code); self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body))); self.end_headers(); self.wfile.write(body)

    def do_GET(self):
        path = urlparse(self.path).path.rstrip("/")
        if path.endswith("/models"):
            self._send(200, models_list())          # discovery
        elif path.endswith("/introspect"):
            self._send(200, introspect())            # model key optional (single model)
        else:
            self._send(404, {"error": "not found"})

    def do_POST(self):
        try:
            n = int(self.headers.get("Content-Length", 0))
            req = json.loads(self.rfile.read(n) or b"{}")
            if urlparse(self.path).path.rstrip("/").endswith("/validate"):
                self._send(200, {"results": [validate_one(a.get("type"), a.get("arguments") or {},
                                                           bool(req.get("effectiveOnly"))) for a in req.get("activities", [])]})
            else:
                self._send(200, simulate(req))
        except Exception as e:
            import traceback; traceback.print_exc()
            self._send(500, {"error": str(e)})

    def log_message(self, *a): pass

if __name__ == "__main__":
    print("Python battery model backend on :%d  (activity types: %d, resources: %d)"
          % (PORT, len(MODEL), len(RESOURCE_TYPES)), flush=True)
    HTTPServer(("0.0.0.0", PORT), Handler).serve_forever()
