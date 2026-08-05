# Architecture

pymerlin lets you write [PlanDev](https://github.com/NASA-AMMOS/aerie) mission models
in Python and run them on a discrete-event simulation engine, without re-implementing that
engine yourself.

## Two ways a model runs

The same `@MissionModel` class runs in two different runtimes, depending on what you're doing:

- **Local `simulate()`** — a **pure-Python** discrete-event engine (`_internal/_framework.py`).
  This is what you use for rapid prototyping: a notebook, `pymerlin.simulate(...)`, the
  tutorials. No Java, no GraalPy, no subprocess — just Python. It exists so you can iterate on a
  model without a deployed PlanDev.
- **Packaged upload** — the model runs on PlanDev's *own* simulation engine, **in-process** inside
  the PlanDev worker's JVM, on an embedded [GraalPy](https://www.graalvm.org/python/)
  interpreter. Java and Python call each other directly across the polyglot boundary — there is
  no subprocess and no serialization protocol between them. This is what `pymerlin package`
  produces and what an uploaded model uses.

The rest of this document describes the packaged, in-process path, since that's the one with
the interesting Java↔Python boundary; the local engine is an ordinary Python program.

> **History.** Earlier versions of pymerlin used [py4j](https://www.py4j.org/) (Python owns
> the process, launches Java as a subprocess). That cannot produce a PlanDev-uploadable JAR,
> because PlanDev requires **Java** to own the process and load mission models through its own
> classloader. A middle iteration replaced py4j with a Java-owned subprocess speaking
> newline-delimited JSON over stdin/stdout; the current architecture replaces *that* with an
> in-process GraalPy `Context`. The full rationale and the phase-by-phase migration record are
> in [`roadmap.md`](../../roadmap.md); the interface itself is documented in
> [Shim architecture](shim-protocol.md).

## In-process execution

The prebuilt shim JAR (`pymerlin-shim.jar`) implements PlanDev's `MerlinPlugin` SPI. When
PlanDev loads a packaged model:

- **Registration** (`getDirectiveTypes()` / `getConfigurationType()`) — the shim asks GraalPy
  to introspect the model *class* (`_describe_activity_types`, `_describe_config`) and returns
  the activity/configuration schema to the PlanDev UI. The model is not instantiated for this.
- **Simulation** (`instantiate()`) — the shim builds the model's `_ModelState`, allocates a
  real PlanDev cell for each declared resource, and runs each activity body on the calling
  PlanDev task thread. `delay()`, `emit()`, `spawn()`, `call()`, and `wait_until()` call
  straight back into a Java host object (`PyActions`) and into the PlanDev engine.

Because the whole thing is one JVM, a model error surfaces Java-side as a `PolyglotException`
carrying the Python stack, cells are real PlanDev cells (so `wait_until` registers genuine read
dependencies rather than polling), `call()` blocks the parent task until the child completes,
and cell evolution functions (`registrar.cell(initial, evolution=fn)`) are called automatically
by the engine as time advances — none of which the subprocess protocol could do cleanly.

## Approachability over performance

The main tenet of pymerlin is approachability, and its aim is to enable rapid prototyping of
models and activities. Running in-process removed the old socket/serialization overhead, but
the guidance is unchanged: someone who wants to seriously engineer simulation performance
should port their model to Java. That gives a single Java process to instrument and analyze,
rather than a hybrid system that is harder to characterize.

## Round trips

Some objects the mission model provides to the simulation driver are _pass-through_ objects —
the driver merely hands them back to the mission model when appropriate. In-process, a
pass-through object is a live Python object referenced across the polyglot boundary as an
`org.graalvm.polyglot.Value`; it does not need to be converted to a Java type. Certain global
variables in `_globals.py` still cache Python objects for this reason.

For resources and activity arguments (the things represented as `SerializedValue` on the Java
side), it remains important _not_ to rely on that pass-through cache: those values must be
marshalled into and out of ordinary types so a Python model integrates with inputs generated
elsewhere in the PlanDev system and produces outputs the rest of the system can read.

## Async/await vs threads

pymerlin originally required tasks to be defined as async functions (coroutines), but that was
in tension with the "approachability over performance" principle. Version 0.0.8 replaced async
functions with regular functions and used threads instead. This significantly simplified the
implementation and the mental model. In the in-process design, an activity body runs
synchronously on its PlanDev task thread and yields to the engine through host calls
(`delay`, `wait_until`, `call`); GraalPy releases its interpreter lock across those host calls,
so other tasks can run concurrently (see `roadmap.md` Gate B). If thread switching turns out
to be a bottleneck, async tasks should be reintroduced as an optional alternative.

## Use pythonic idioms

To the extent possible, pymerlin should expose pythonic APIs. This means:
- Use `snake_case` for functions, methods, and variables
- Use `CAPITAL_SNAKE_CASE` for constants
- Use `lowercasenospaces` for modules
- Use `TitleCase` for classes
- Leverage context managers for cleanup
- Prefer duck typing to explicit inheritance
- Public API should include docstrings and type annotations
- Prefix private attributes with one underscore
- Prefer simple attributes to properties
- Override operators where appropriate
- Enumerate entry points into public API in the __init__.py's __all__ attribute.

Consult [PEP8](https://peps.python.org/pep-0008/#naming-conventions) for additional ideas.

## Emphasize debuggability

On one hand, we should do our best not to show users Java stack traces — or at least show them
only when useful and not overwhelming. On the other hand, we must not _obscure_ useful
debugging information. In-process this balance is easier to strike than it was over a
subprocess: a model exception arrives as a `PolyglotException` with the Python traceback
attached, so the Python-level cause is recoverable rather than lost to a worker's stderr.
Surfacing that traceback all the way to the PlanDev UI is tracked as remaining work
(`roadmap.md` §11.5).
