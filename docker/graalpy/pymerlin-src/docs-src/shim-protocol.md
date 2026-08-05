# Shim architecture (in-process)

This document describes how the pymerlin shim actually runs a model: **in-process**, inside
the PlanDev worker's JVM, on an embedded [GraalPy](https://www.graalvm.org/python/)
interpreter. There is no subprocess and no wire protocol — Java and Python call each other
directly across the GraalVM polyglot boundary.

> **Superseded document, now rewritten (2026-07-21 → Phase 5).** This file previously
> documented the Phase 0–2 subprocess/JSON protocol (`SubprocessBridge`, `PythonProcess.java`,
> `Protocol.java`, and `_server.py`'s `_send`/`_recv`/`_ActivityRunner`), all of which were
> deleted once the in-process direct-call path was proven byte-identical against them on a
> real GraalPy image (`roadmap.md` §6.3/§6.6). The description below reflects the current
> architecture; `roadmap.md` §6 (execution) and §7 (cells) remain the authoritative,
> low-level source of truth.

## The two seams

Two Java types define the boundary. Both live in `java/pymerlin-shim/`.

- **`PyBridge`** — Java → Python. The queries and the activity-entry call the shim makes
  *into* the model. The only implementation is `GraalBridge`, which holds the GraalPy
  `Context`. (`PyBridge` stays an interface purely as a seam for a possible future
  fallback bridge; there is no runtime bridge switch anymore.)
- **`PyActions`** — Python → Java. A single host object handed to every activity; the model's
  `delay`/`emit`/`spawn`/`call`/`wait_until` call *back out* through it into the PlanDev engine.

Everything the old JSON protocol expressed as messages is now one of these two directions of
ordinary method call, passing `org.graalvm.polyglot.Value` objects (and, where a serialized
form is genuinely needed, `gson` `JsonObject`/`JsonElement`).

## Java → Python: `PyBridge` / `GraalBridge`

At load time, `GraalBridge` builds a GraalPy `Context`, puts the model's source directory on
`sys.path`, and imports the entry points from `pymerlin._internal._server`. Then:

| `PyBridge` method | Python it calls | When |
|---|---|---|
| `getActivityTypes()` | `_describe_activity_types(model_class)` | `getDirectiveTypes()`, model-class only |
| `getConfigParameters()` | `_describe_config(model_class)` | `getConfigurationType()`, model-class only |
| `setConfiguration(json)` | (stored; passed to `_ModelState`) | before the model state is built |
| `getResources()` | `_ModelState.describe_resources()` | after `instantiate()` |
| `getResourceValue(name)` | `_ModelState.get_resource_value(name)` | resource extraction |
| `getCells()` | `_ModelState.describe_cells()` | after `instantiate()` (Phase 4) |
| `getEvolutionFunctions()` | `_ModelState.get_evolution_functions()` | after `getCells()` — returns per-cell evolution callables (0.1.1) |
| `runActivityDirect(...)` | `run_activity_direct(model_state, actions, name, args)` | per activity |

Registration queries (`getActivityTypes`/`getConfigParameters`) only touch the model *class*,
so they never instantiate the model — a metadata-only bridge is cheap. The model's
`_ModelState` (and its cells) is built lazily on first use.

## Python → Java: `PyActions`

`runActivityDirect` runs the Python activity function **to completion on the calling PlanDev
`ThreadedTask` thread**, handing it the shared `PyActions` host object. There is no drive
loop, no queue, and no background Python thread: when the model calls an action, it is a
synchronous host call that returns control to Python when the engine says so.

| Model call (Python) | `PyActions` method | Effect |
|---|---|---|
| `delay(duration)` | `delay(micros)` | `ModelActions.delay(...)` parks this task thread |
| `cell.emit(value)` | `emitCell(cellIndex, value)` | emit to the cell's topic (Phase 4) |
| `cell.set_rate(r)` | `setRate(cellIndex, rate)` | set a linear cell's rate (Phase 4) |
| `cell.get()` | `ask(cellIndex)` | `ModelActions.ask(cellId)`; registers a read dependency during `wait_until` |
| `spawn(child(...))` | `spawnActivity(name, argsJson)` | a fresh child `ThreadedTask` |
| `call(child(...))` | `callActivity(name, argsJson)` | a fresh child; **blocks** the caller until it finishes |
| `wait_until(pred)` | `waitUntil(BooleanSupplier)` | wraps the Python predicate in a PlanDev `Condition` and yields |

`wait_until` is the payoff of running in-process: the Python predicate is passed to Java as a
`BooleanSupplier` (GraalPy auto-wraps the callable), wrapped in a PlanDev `Condition`, and the
engine re-evaluates it on the engine thread whenever a cell the predicate read changes — real
dependency-tracked blocking, not tick polling.

## Why this is legal (threads and the GIL)

An activity blocks its `ThreadedTask` thread inside a host call (`delay`, `call`, `wait_until`)
with Python frames still on the stack. This does not deadlock the engine because GraalPy
releases its interpreter lock across the Python→host call boundary, so another task can enter
the same `Context` concurrently. This is the property Gate B set out to prove before any of
this was built; see `roadmap.md` §1.3.2 and the Gate B result.

## Lifecycle

```
Java                                   Python (GraalPy, same JVM)
 |                                      |
 |-- GraalBridge: build Context ------->|  import _server entry points
 |-- getConfigParameters() ------------>|  _describe_config(model_class)
 |-- getActivityTypes() --------------->|  _describe_activity_types(model_class)
 |                                      |
 |   [ instantiate() ]                  |
 |-- setConfiguration(json) ----------->|  (stored)
 |-- getCells()/getResources() -------->|  _ModelState(model_class, config)
 |  allocate a PlanDev cell per resource|
 |                                      |
 |   [ simulation begins ]              |
 |-- runActivityDirect(act-1, ...) ---->|  run_activity_direct(state, actions, ...)
 |                                      |    body runs on this task thread:
 |<----- actions.emitCell(...) ---------|      cell.emit(...)
 |<----- actions.delay(micros) ---------|      delay(...)  (thread parks)
 |   [ engine advances time ]           |
 |   ...unpark, continue...             |
 |<----- actions.spawnActivity(...) ----|      spawn(child(...))
 |  (returns when the function returns) |
 |                                      |
 |   [ simulation ends ]                |
 |-- close(): Context.close(true) ----->|  (+ delete any extracted model source)
```
