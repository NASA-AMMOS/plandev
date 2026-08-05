# pymerlin

<!-- start elevator-pitch -->
pymerlin is a Python mission modeling framework for the [PlanDev](https://github.com/NASA-AMMOS/aerie) discrete-event simulation ecosystem. It lets you write PlanDev mission models in Python and either simulate them locally or package them as an uploadable PlanDev mission model JAR.

To learn more about PlanDev, read the [PlanDev Docs](https://nasa-ammos.github.io/aerie-docs).
<!-- end elevator-pitch -->

## Prerequisites

- Python >= 3.10 — to author models and run `pymerlin package`.
- Java >= 21 — only for building the shim JAR from source (`./gradlew`); **not** needed to
  author a model or to run `pymerlin package`, which ships a prebuilt shim JAR.

At simulation time the model runs on the GraalPy interpreter that the PlanDev worker image
provides (see [Worker-image contract](#worker-image-contract)) — not on your local CPython.

## Installation

```shell
python -m venv venv
source ./venv/bin/activate
pip install -r requirements.txt
```

Or install from the package directly:

```shell
pip install pymerlin
```

## Packaging a model for PlanDev

To produce a PlanDev-uploadable mission model JAR from a Python model:

```shell
pymerlin package --model path/to/model.py:MissionClassName --out mission-model.jar
```

This copies the prebuilt shim JAR (`pymerlin-shim.jar`), bundles your Python model source
into it under `pymerlin_models/`, and stamps the model reference into the JAR manifest as
`Pymerlin-Model-Ref`. If the model file sits next to an `__init__.py`, the whole package
directory is bundled so intra-package imports keep working. The resulting JAR uploads to a
deployed PlanDev instance like any Java mission model.

The JAR is deliberately thin: it contains only the shim classes, the bundled `gson`
dependency (used for Java↔Python argument/description marshalling), and your model source.
It does **not** contain the GraalPy runtime, the Python standard library, or any Python
packages — those are supplied by the worker image at simulation time.

## Worker-image contract

A packaged model does not carry its own Python runtime or dependencies. Instead, the PlanDev
`merlin-worker` and `merlin-server` images ship an embedded GraalPy interpreter plus a
pre-built virtual environment ("`python-resources`"), and the shim runs the bundled model
against that. Both images are provisioned by the shared script
[`plandev/docker/graalpy/install.sh`](../plandev/docker/graalpy/install.sh); the layout it
produces (external-directory mode) is:

```
/opt/pymerlin/python-resources/
  venv/   <- GraalPy virtualenv: pymerlin + numpy + spiceypy
  src/    <- model .py, extracted from the uploaded JAR at simulation time
```

**Packages available to a model:** the pre-built venv contains exactly **`pymerlin`,
`numpy`, and `spiceypy`** (a fixed set — see `roadmap.md` §11.2). `pymerlin` is installed
from a pinned git ref (`PYMERLIN_GIT_URL`/`PYMERLIN_REF` in `install.sh`) — the image build
no longer needs a local pymerlin checkout alongside `plandev/`, so a model author working
from just `pip install pymerlin` and a standalone model file never needs this repo either.
`numpy`/`spiceypy` are installed with GraalPy's own patched `pip` against its wheel
repository — CPython wheels from PyPI are **not** binary-compatible with GraalPy and cannot
be used.

**Version compatibility.** The pymerlin ref a worker image is built against must ship a
`pymerlin-shim.jar` compiled against the *same* `graalPyVersion` as that image's own GraalPy
runtime (`GRAALPY_VERSION` in the Dockerfile / `graalPyVersion` in `gradle.properties`) —
mismatched, the shim compiles fine and fails confusingly at simulation time against an API
the worker doesn't provide (see `pymerlin-shim/build.gradle`'s `graalPyVersion` comment).
Decoupling the image build from a local checkout removes the accidental guarantee that these
two always moved together, so this needs to be checked explicitly now: bumping
`install.sh`'s `PYMERLIN_REF` means confirming the pymerlin-shim `build.gradle` at that ref
still matches `GRAALPY_VERSION`, and vice versa. `graalpy-preflight.yml`'s "Check GraalPy
versions agree" step automates half of this (a given pymerlin ref's shim version against
`GRAALPY_VERSION`); there is no equivalent automated check yet that `install.sh`'s *currently
pinned* `PYMERLIN_REF` specifically satisfies it.

**If your model needs a package that isn't in the venv:** a missing dependency is an
**image rebuild**, not a JAR change. Add the package to the install step in
`plandev/docker/graalpy/install.sh` (pin it in
[`constraints.txt`](../plandev/docker/graalpy/constraints.txt) so isolated build
environments resolve GraalPy-compatible versions), rebuild the `merlin-worker` and
`merlin-server` images, and redeploy. Packages with native extensions must build cleanly
under GraalPy — most pure-Python and the vetted native packages (numpy, spiceypy/CSPICE)
do, but this is the thing to validate before relying on it. A per-model
`requirements.txt` layered into an ephemeral venv at startup is a deliberate non-goal for
now; add it only if a real need appears (`roadmap.md` §11.2).

## Architecture

A packaged pymerlin model runs **in-process** in the PlanDev worker JVM. The shim JAR
implements PlanDev's `MerlinPlugin` SPI; at load time it creates an embedded GraalPy
`Context` and imports the model's Python source into it. Activity-type registration,
resource description, and every activity body all execute by Java calling Python functions
directly and Python calling back into Java host objects — there is no subprocess, no
stdin/stdout protocol, and no JSON drive loop.

```
PlanDev merlin-worker (JVM)
  └── ShimModelType  (loaded from mission-model.jar)
        └── GraalBridge → embedded GraalPy Context
              ↕ direct host calls (org.graalvm.polyglot.Value)
            pymerlin model .py  (from python-resources/src)
```

- **Activity registration.** `ModelType.getDirectiveTypes()` /
  `getConfigurationType()` call `_describe_activity_types` / `_describe_config` on the model
  class directly and return the schema to PlanDev's activity palette — a model-class-only
  query that never instantiates the model.
- **Simulation.** `ModelType.instantiate()` builds the model's `_ModelState`, allocates a
  real PlanDev cell for each declared resource, and runs each activity body on the calling
  PlanDev task thread. `delay()`, `emit()`, `spawn()`, `call()`, and `wait_until()` route
  straight through a Java host object (`PyActions`) into the PlanDev engine — `wait_until`
  hands the Python predicate to Java as a `BooleanSupplier` wrapped in a PlanDev `Condition`,
  and `call()` genuinely blocks the parent until the child completes.

For the design rationale (why in-process GraalPy over py4j or a subprocess, the GIL/thread
model, abort semantics, native-extension support) and the phase-by-phase record, see
[`roadmap.md`](../roadmap.md). A focused description of the Java↔Python in-process interface is
in [`docs-src/shim-protocol.md`](docs-src/shim-protocol.md).

### Approachability over performance

The main tenet of pymerlin is approachability for rapid model prototyping. Running
in-process removes the old subprocess/serialization overhead, but a model author who needs
production simulation performance should still port the model to Java for a single,
fully instrumented JVM process.

## Building the shim JAR

If any changes are made to the Java shim code, rebuild and place the JAR where the Python
package expects it:

```shell
./scripts/build-shim.sh
```

Or manually:

```shell
cd java
./gradlew assemble
cp pymerlin-shim/build/libs/pymerlin-shim.jar ../pymerlin/_internal/jars/
```

The JAR lives inside the `pymerlin` Python source directory so it is included in the pip
distribution. `pymerlin package` copies whatever JAR is at that path — so re-copying after a
rebuild is required, or a packaged model ships stale shim classes.

The shim's polyglot/GraalPy dependencies are `compileOnly`: the worker's classloader supplies
them at runtime, so they are deliberately kept **out** of the shim JAR (bundling them would
pack hundreds of megabytes of Python runtime into every uploaded model). See
`java/pymerlin-shim/build.gradle` for the dependency rationale, including why `gson` is
still bundled.

## Known limitations and open work

Phases 1–4 of the GraalPy migration plus the 0.1.1 cell-evolution work closed most of the
functional gaps the earlier subprocess architecture had: `call()`, `wait_until` with real
conditions, linear (interpolated) resources, general cell evolution (user-defined
`evolution` functions, including clamped linear cells), model configuration, the
`MissionModelBase` helper for improved type checking, and temp-directory cleanup all work
now (see `roadmap.md` and `cell_evolution_roadmap.md`). What remains:

### Functional gaps

- **Only primitive activity/config parameter types.** Parameters typed as `int`, `float`,
  `str`, or `bool` map to the matching `ValueSchema`; lists, dicts, enums, `Duration`, or
  custom classes fall through to `ValueSchema.STRING` and appear in the PlanDev UI as
  unvalidated string fields.

### Operational issues

- **No simulation timeout.** If a model's Python code hangs (e.g. an infinite loop with no
  `delay`), the worker task thread blocks indefinitely — there is no watchdog.
- **Python tracebacks are not yet surfaced to the PlanDev UI.** In-process, an uncaught
  model error arrives Java-side as a `PolyglotException` carrying the Python stack, so this
  is now fixable (unlike the old subprocess path where it was lost to stderr) — but the
  wiring to put it in the user-facing simulation-failure message is not done. Tracked as a
  Phase 6 item (`roadmap.md` §11.5).

### Open questions carried in the roadmap

- **Model-side `finally:` during task abort** may not run when a task is cancelled
  (`roadmap.md` §11.6) — needs re-verification against the real abort path, and if
  confirmed, documenting as a model-authoring constraint.
- **Concurrent same-tick emits to the same cell** were never exercised end-to-end
  (`roadmap.md` §6.5/§6.7); multi-threaded activity execution against a shared cell is not
  yet proven.

### Tests

The in-process JUnit suite (`DemoModelSimulationTest`, `SpanTimingTest`,
`CallSemanticsTest`) runs against a real GraalPy runtime + provisioned `python-resources`
venv — i.e. the built worker image, via the `dockerTestBundle` task (see
`java/pymerlin-shim/build.gradle`). On a stock JDK without that environment the tests
`assumeTrue`-skip rather than false-fail. Python-side cell-evolution tests live in
`tests/test_cell_evolution.py` and run under plain pytest.
