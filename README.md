# PlanDev — Python Modeling Branch (`pymerlin/develop`)

> **This is not the main PlanDev branch.**
> This branch adds Python mission modeling support via [pymerlin](https://github.com/remy-rabideau/pymerlin).
> For the standard PlanDev documentation, see the [main branch README](https://github.com/NASA-AMMOS/aerie/blob/develop/README.md).

## What this branch does

Standard PlanDev models are written in Java. This branch embeds a Python runtime ([GraalPy](https://www.graalvm.org/python/)) into the `merlin-worker` and `merlin-server` images so that mission models can be written entirely in Python using the `pymerlin` library — and uploaded as a JAR just like a Java model.

A model author writes Python, runs `pymerlin package`, uploads the resulting JAR to a deployed PlanDev instance, and simulates it through the normal PlanDev UI. No Java code is written; no separate Python process runs alongside PlanDev.

## Quick start

### 1. Set up a virtual environment and install pymerlin

```shell
python -m venv venv
source venv/bin/activate
pip install "git+https://github.com/remy-rabideau/pymerlin.git@v0.2.1"
```

### 2. Write a model

```python
from pymerlin import MissionModel, MissionModelBase
from pymerlin.model_actions import delay, spawn

@MissionModel
class Mission(MissionModelBase):
    def __init__(self, registrar):
        self.counter = registrar.cell(0)
        registrar.resource("/counter", self.counter)

@Mission.ActivityType
def increment(mission, amount: int = 1):
    mission.counter.emit(lambda x: x + amount)
    delay("00:01:00")
```

### 3. Package and upload

```shell
pymerlin package --model model.py:Mission --out mission-model.jar
```

Upload `mission-model.jar` to your PlanDev instance exactly as you would a Java model.

For full documentation on writing Python mission models — cells, resources, activities, configuration, and more — see the [pymerlin README](https://github.com/remy-rabideau/pymerlin/blob/main/README.md).

## How it works

### SharedPythonEngine

`SharedPythonEngine.java` holds a JVM-wide GraalVM `Engine` singleton that parses and JIT-compiles Python source into machine code. It is shared across simulations to avoid reparsing and recompiling the Python interpreter, the standard library, pymerlin, and model modules on every run.

It lives on both `merlin-worker`'s and `merlin-server`'s classpath:

- **merlin-worker** runs simulations.
- **merlin-server** handles model loading (`refreshActivityTypes`, `loadModelType`, `getModelParameters`) and also runs simulations.

### Java → Python (model loading and setup)

1. `ShimModelType.java` implements PlanDev's `ModelType` interface.
2. PlanDev calls `ModelType.instantiate()`.
3. `GraalBridge.java` creates a GraalPy `Context` and imports `_server.py` functions directly:
   ```java
   describeActivityTypes = ctx.eval("python", "_describe_activity_types");
   ```
4. Java asks Python for model information (activity types, configuration parameters, cells).
5. For every cell in the Python model, `instantiate()` allocates one real PlanDev cell. They are tied together via **cell indices** — Python's `CellRef._cell_index` maps directly to Java's `cellsByIndex` list.

### Simulation (runtime path)

1. PlanDev's simulation engine schedules an activity directive (an instance of an activity type).
2. `ShimModelType.runActivity()` calls `bridge.runActivityDirect()` (through the `PyBridge` interface), which calls `_server.py`'s `run_activity_direct()`.
3. The activity's Python function runs on the calling Java `ThreadedTask` virtual thread.
4. If an activity spawns a child, `ShimModelType.directSpawn()` schedules a fresh child `ThreadedTask`.

### Python → Java (callbacks)

While a Python activity function runs, it calls pymerlin APIs (`delay()`, `emit()`, `spawn()`, `call()`, `wait_until()`). Each call routes back into Java through `PyActions.java`, which delegates to `ShimModelType.java`, which in turn calls static methods in `ModelActions.java` — acting on whichever virtual thread is calling:

| Python API | Java callback | PlanDev engine call |
|---|---|---|
| `delay(duration)` | `PyActions.delay()` | `ModelActions.delay()` — parks the virtual thread |
| `cell.get()` | `PyActions.ask()` | `ModelActions.ask(cellId)` — reads from the real PlanDev cell |
| `cell.emit(event)` | `PyActions.emitCell()` | `ModelActions.emit(topic)` — emits to the cell's topic |
| `spawn(activity)` | `PyActions.spawnActivity()` | `ModelActions.spawnWithSpan()` — creates a new `ThreadedTask` |
| `call(activity)` | `PyActions.callActivity()` | `ModelActions.callWithSpan()` — blocks caller until child completes |
| `wait_until(pred)` | `PyActions.waitUntil()` | `ModelActions.waitUntil()` — Python predicate wrapped as `BooleanSupplier` |

### In-process architecture

**The big idea:** Python code runs inside the JVM via GraalPy. Python is a guest language on PlanDev's GraalVM runtime, executing on the same threads as Java. Method calls between Python and Java happen on the same thread — no subprocess, no sockets, no protocol, no serialization boundary.

When a Python activity calls `delay()`, it parks the Java virtual thread it is running on — with Python frames still live on the stack. The PlanDev simulation engine picks up the next task. When time advances, the same thread resumes and the Python function continues where it left off.

```
PlanDev merlin-worker (JVM, GraalVM 21)
  └── ShimModelType  (implements ModelType, loaded from mission-model.jar)
        ├── PyBridge interface
        │     └── GraalBridge  (sole implementation)
        │           └── GraalPy Context (one per simulation)
        │                 ↕ direct host calls (org.graalvm.polyglot.Value)
        │               _server.py → model.py  (extracted from the JAR to a temp dir)
        └── PyActions  (host object handed to Python for callbacks)
              └── ShimModelType.direct*() → ModelActions.*()
```

## Worker image

The `merlin-worker` and `merlin-server` Docker images are built on `ghcr.io/graalvm/jdk-community:21` and include:

- An embedded GraalPy runtime (version set by `GRAALPY_VERSION` in the Dockerfile)
- A pre-built virtual environment at `/opt/pymerlin/python-resources/` containing `pymerlin`, `numpy`, and `spiceypy`

Both images are provisioned by the shared script [`docker/graalpy/install.sh`](docker/graalpy/install.sh). The model JAR does **not** carry its own Python runtime — the images supply it.

### Python dependencies

Model dependencies are handled automatically. When you `import` a third-party package in your model, `pymerlin package` detects it, generates a `requirements.txt` (pinned to the versions in your local environment), and bundles it inside the JAR. At model load time, the Java shim (`RequirementsInstaller`) reads this file and pip-installs anything missing into the GraalPy venv — no image rebuild required.

The model author never writes a `requirements.txt`. The imports *are* the dependency list:

```
model.py:   import toml    ←  this is all you do
```
```
pymerlin package --model model.py:Mission --out model.jar
# [pymerlin] Requirements:    generated from the model's imports
# [pymerlin]                  toml==0.10.2
```

On first simulation, the worker installs any missing packages before creating the GraalPy Context. Subsequent simulations of the same model skip the install (marker-file caching).

**Caveats:**
- Packages with native extensions must build cleanly under GraalPy. CPython wheels from PyPI are **not** binary-compatible.
- The worker container needs outbound network access for pip installs to succeed.
- Dynamic imports (`importlib.import_module(...)`) are not detected by the static scanner. Work around this by also importing the package normally somewhere in the model.

## Key files

### Java shim (`docker/graalpy/pymerlin-src/java/pymerlin-shim/`)

| File | Role |
|---|---|
| `ShimModelType.java` | PlanDev `ModelType` implementation; allocates cells, builds task factories, hosts `direct*()` callbacks |
| `PyBridge.java` | Interface between `ShimModelType` and the Python runtime (pluggability seam) |
| `GraalBridge.java` | Sole `PyBridge` implementation; creates GraalPy Context, calls `_server.py` functions |
| `PyActions.java` | Stateless host object given to Python for `delay`/`emit`/`spawn`/`call`/`waitUntil` callbacks |
| `ShimMerlinPlugin.java` | `MerlinPlugin` SPI entry point; PlanDev discovers the model through this |

### Python runtime (`pymerlin/pymerlin/_internal/`)

| File | Role |
|---|---|
| `_server.py` | In-process Python runtime: model loading, activity/config introspection, `run_activity_direct()` |
| `_registrar.py` | `Registrar`, `CellRef`, `LinearCellRef`; declares cells, resources, and projections |
| `_globals.py` | Thread-local state: `java_actions`, `cell_values_by_id`, reaction context |

### Shared engine (`graalpy-engine-cache/`)

| File | Role |
|---|---|
| `SharedPythonEngine.java` | JVM-wide GraalVM `Engine` singleton; caches compiled code across simulations |

## Version compatibility

The pymerlin ref pinned in `install.sh` (`PYMERLIN_REF`) must ship a `pymerlin-shim.jar` compiled against the **same** `graalPyVersion` as the image's `GRAALPY_VERSION`. A mismatch compiles fine but fails at simulation time. The CI workflow `graalpy-preflight.yml` partially automates this check.

## Differences from upstream PlanDev

This branch is a **superset** of upstream PlanDev. All standard Java modeling, scheduling, constraints, and sequencing functionality is unchanged. The additions are:

- GraalPy runtime provisioned in the worker and server images
- `graalpy-engine-cache` library (shared `Engine` singleton)
- `pymerlin-shim` JAR (loaded from the uploaded model JAR via `MerlinPlugin` SPI)
- `docker/graalpy/install.sh` and related provisioning scripts

Java mission models continue to work exactly as before. The Python modeling path activates only when an uploaded JAR contains a `pymerlin-shim` plugin.
