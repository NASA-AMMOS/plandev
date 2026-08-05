"""
In-process model runtime for the PyMerlin shim, called directly by GraalBridge (Java) via
GraalPy host calls — no subprocess, no protocol, no queues.

An activity runs on the calling Java `ThreadedTask` thread. `delay`/`call` call straight
back into a Java host object (`java_actions`); emits route through `CellRef.emit` →
`java_actions.emitCell` (Phase 4, roadmap §7); `wait_until` passes the Python predicate to
`java_actions.waitUntil` as a `BooleanSupplier` and the engine re-evaluates it when cell
dependencies change (no polling); `spawn` schedules a fresh child `ThreadedTask` via
`java_actions.spawnActivity`. `CellRef.get` calls `java_actions.ask(cell_index)` which goes
through `ModelActions.ask(cellId)`, registering read dependencies in QueryContext for
waitUntil (Phase 4, roadmap §7).
"""

import ast
import importlib.util
import inspect
import json
import os
import sys
from typing import Any

from pymerlin._internal import _globals
from pymerlin._internal._registrar import Registrar
from pymerlin._internal._task_status import Awaiting, Calling, Delayed
from pymerlin.duration import MICROSECONDS, Duration

# ---------------------------------------------------------------------------
# Model loader
# ---------------------------------------------------------------------------

def _load_model_class(model_ref: str):
    if ":" not in model_ref:
        raise ValueError(f"model_ref must be 'path/to/file.py:ClassName', got: {model_ref!r}")
    file_path, class_name = model_ref.rsplit(":", 1)
    file_path = os.path.abspath(file_path)
    pkg_dir = os.path.dirname(file_path)
    pkg_init = os.path.join(pkg_dir, "__init__.py")
    module_stem = os.path.splitext(os.path.basename(file_path))[0]

    if os.path.exists(pkg_init):
        # Model is part of a package — load as a proper package so relative imports work.
        pkg_name = os.path.basename(pkg_dir)
        parent_dir = os.path.dirname(pkg_dir)
        if parent_dir not in sys.path:
            sys.path.insert(0, parent_dir)
        # Import the package first so the module can do relative imports.
        importlib.import_module(pkg_name)
        module = importlib.import_module(f"{pkg_name}.{module_stem}")
    else:
        # Standalone file — load directly.
        if pkg_dir not in sys.path:
            sys.path.insert(0, pkg_dir)
        spec = importlib.util.spec_from_file_location("_pymerlin_user_model", file_path)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)

    return getattr(module, class_name)


# ---------------------------------------------------------------------------
# Model introspection helpers
# ---------------------------------------------------------------------------

def _python_type_name(annotation, default=inspect.Parameter.empty) -> str:
    if annotation is not inspect.Parameter.empty and annotation is not Any:
        if annotation is int:
            return "int"
        if annotation is float:
            return "float"
        if annotation is str:
            return "str"
        if annotation is bool:
            return "bool"
    # Fall back to inferring from the default value
    if default is not inspect.Parameter.empty and default is not None:
        if isinstance(default, bool):
            return "bool"
        if isinstance(default, int):
            return "int"
        if isinstance(default, float):
            return "float"
        if isinstance(default, str):
            return "str"
    return "str"


def _describe_activity_types(model_class) -> dict:
    result = {}
    if not hasattr(model_class, "activity_types"):
        return result
    for name, task_def in model_class.activity_types.items():
        func = getattr(task_def, "raw_func", None) or task_def.inner
        sig = inspect.signature(func)
        params = {}
        for param_name, param in sig.parameters.items():
            if param_name == "mission":
                continue
            params[param_name] = {
                "type": _python_type_name(param.annotation, param.default),
                "required": param.default is inspect.Parameter.empty,
                "default": None if param.default is inspect.Parameter.empty else param.default,
            }
        result[name] = {"parameters": params}
    return result


def _describe_config(model_class) -> dict:
    """Describe a model's configuration parameters (roadmap §7 — model configuration).

    A model declares configuration the same way an activity declares parameters: through
    its constructor signature. Everything after ``self`` and the ``registrar`` (the first
    positional) is a configuration parameter, with type/required/default inferred exactly
    like ``_describe_activity_types`` does. A model with only ``(self, registrar)`` has no
    configuration, so the schema is empty and instantiation is unchanged.
    """
    try:
        sig = inspect.signature(model_class.__init__)
    except (ValueError, TypeError):
        return {"parameters": {}}
    names = [n for n in sig.parameters if n != "self"]
    config_names = names[1:]  # drop the registrar (first param after self)
    params = {}
    for param_name in config_names:
        param = sig.parameters[param_name]
        if param.kind in (inspect.Parameter.VAR_POSITIONAL, inspect.Parameter.VAR_KEYWORD):
            continue
        params[param_name] = {
            "type": _python_type_name(param.annotation, param.default),
            "required": param.default is inspect.Parameter.empty,
            "default": None if param.default is inspect.Parameter.empty else param.default,
        }
    return {"parameters": params}


def _describe_resources(registrar: Registrar) -> dict:
    result = {}
    for name, getter in registrar.resources:
        val = getter()
        if isinstance(val, bool):
            vtype = "bool"
        elif isinstance(val, int):
            vtype = "int"
        elif isinstance(val, float):
            vtype = "float"
        else:
            vtype = "str"
        result[name] = {"value_type": vtype}
    return result


def _child_args(model_class, instance) -> dict:
    """
    Reconstruct the activity arguments for a spawned/called child from the TaskInstance
    that `spawn(...)`/`call(...)` was handed. `make_instance` stores `instance.args`
    (positional, with the mission/model as args[0]) and `instance.kwargs`; here we drop the
    mission and map any remaining positionals onto the child's declared parameter names,
    matching the 'mission'-skipping convention `_describe_activity_types` already uses.
    """
    name = getattr(instance, "activity_name", None)
    result = dict(getattr(instance, "kwargs", {}) or {})
    positional = getattr(instance, "args", ()) or ()
    if name is not None and name in getattr(model_class, "activity_types", {}):
        task_def = model_class.activity_types[name]
        func = getattr(task_def, "raw_func", None) or task_def.inner
        param_names = [p for p in inspect.signature(func).parameters if p != "mission"]
        # positional[1:] skips the mission/model instance passed as the first argument
        for param_name, value in zip(param_names, positional[1:]):
            result.setdefault(param_name, value)
    return result


def _child_args_json(model_class, instance) -> str:
    """Same as `_child_args`, serialized to JSON so it crosses to Java as a plain String
    (keeps the PyActions host interface free of GraalPy `Value` types)."""
    return json.dumps(_child_args(model_class, instance))


# ---------------------------------------------------------------------------
# Reaction context — installed into _globals so delay()/call()/wait_until() call
# straight back into Java (roadmap §6.2)
# ---------------------------------------------------------------------------

class _ReactionContext:
    """
    There is no queue and no separate Python thread: the activity runs on the calling Java
    ThreadedTask thread, and delay/call/wait_until call straight back into the Java host
    object (`java_actions`) synchronously. Gate B proved a host call that parks the
    ThreadedTask thread (with Python frames live on its stack) does not hold the context
    lock, so the engine's next task can still enter the Context; §6.6 confirmed this holds
    for Aerie's real virtual threads, not just Gate B's platform-thread spike.

    Stateless apart from `java_actions`, which is a single shared object whose methods
    delegate to `ModelActions.*` and therefore act on whichever ThreadedTask thread is
    currently executing. That is why it is safe for concurrently-running activities to
    share one instance via the `_globals.reaction_context` global (§6.4) — every instance
    of this class is functionally interchangeable, since none of them carry per-activity
    state; Java's own thread-local `ModelActions` context is what makes the dispatch
    correct per-thread, not anything held here.
    """

    def __init__(self, java_actions):
        self._java = java_actions

    def yield_with(self, status):
        if isinstance(status, Delayed):
            from pymerlin.duration import MICROSECONDS
            self._java.delay(int(status.duration.to_number_in(MICROSECONDS)))
        elif isinstance(status, Calling):
            child = status.child
            self._java.callActivity(
                getattr(child, "activity_name", None),
                _child_args_json(_globals._current_context[2], child),
            )
        elif isinstance(status, Awaiting):
            # Phase 4 (§7): real cell-read-driven waitUntil. The Python predicate is
            # passed to Java as a BooleanSupplier (GraalPy auto-wraps). Inside the
            # Condition, the engine evaluates the predicate on the engine thread;
            # CellRef.get() calls java_actions.ask() which goes through QueryContext
            # and registers read dependencies. The engine re-evaluates when those
            # topics change — no polling.
            self._java.waitUntil(status.condition)


# ---------------------------------------------------------------------------
# Model state manager
# ---------------------------------------------------------------------------

class _ModelState:
    """
    Holds the single shared model instance. Phase 4 (roadmap §7) wires each Python
    CellRef to a real Aerie cell via sequential cell indices: CellRef.get() calls
    java_actions.ask(cell_index) which goes through ModelActions.ask(cellId), and
    CellRef.emit() applies the event locally then calls java_actions.emitCell(cell_index,
    str(new_val)). The local _globals.cell_values_by_id dict is still populated as a
    typed mirror for event function application (lambda x: x + 15.0 needs a float, not
    a string) and as a fallback during model __init__ before java_actions is available.
    """

    def __init__(self, model_class, config_json=None):
        self.model_class = model_class
        self._registrar = Registrar()
        # Model configuration (roadmap §7): Java passes the instantiated config as a JSON
        # object string; keys map to the model constructor's post-registrar parameters.
        # Absent/blank config → construct with defaults, so unconfigured models are unchanged.
        config_kwargs = {}
        if config_json:
            try:
                config_kwargs = json.loads(str(config_json)) or {}
            except (ValueError, TypeError):
                config_kwargs = {}
        self.model_instance = model_class(self._registrar, **config_kwargs)

        self.cell_values: dict = {}
        self.cell_id_to_resource: dict = {}

        for i, (cell_ref, initial_value, _evolution) in enumerate(self._registrar.cells):
            cell_ref.id = id(cell_ref)
            cell_ref._cell_index = i
            cell_ref._value_type = type(initial_value)
            self.cell_values[id(cell_ref)] = initial_value

        # Associate each resource with the cell that backs it. The Java path registers
        # resources per-cell (see ShimModelType.instantiate), so a resource that cannot be
        # traced to a cell here is never created on the Java side at all -- it silently
        # disappears from the simulation rather than failing loudly.
        for resource_name, getter in self._registrar.resources:
            # A Gettable derived from a cell (cell.map(...)) carries its origin explicitly.
            source = getattr(getter, "_source_cell", None)
            if source is None:
                source = getattr(getattr(getter, "__self__", None), "_source_cell", None)
            if source is not None:
                self.cell_id_to_resource[id(source)] = resource_name
                continue
            for cell_ref, _iv, _ev in self._registrar.cells:
                if getter == cell_ref.get or (
                    hasattr(getter, "__self__") and getter.__self__ is cell_ref
                ):
                    self.cell_id_to_resource[id(cell_ref)] = resource_name
                    break

        _globals.cell_values_by_id = self.cell_values
        _globals._current_context[2] = model_class

    def describe_cells(self) -> list:
        """Return cell metadata for Java to allocate real Aerie cells (Phase 4, §7).
        Cell order matches registrar.cells — indices are the contract between
        CellRef._cell_index (Python) and cellsByIndex (Java)."""
        cells = []
        for cell_ref, initial_value, _evolution in self._registrar.cells:
            current = _globals.cell_values_by_id.get(cell_ref.id, initial_value)
            res_name = self.cell_id_to_resource.get(id(cell_ref))
            if getattr(cell_ref, "_is_linear", False):
                # Continuously-integrating cell (roadmap §7.2): Java backs it with a
                # RealDynamics resource that ramps by `rate` per second between events.
                linear_desc = {
                    "type": "linear",
                    "initial": str(float(current)),
                    "rate": str(float(getattr(cell_ref, "_initial_rate", 0.0))),
                    "resource": res_name,
                }
                # Optional integration bounds (Aerie's ClampedIntegrator equivalent).
                # Omitted entirely when unset, so an unbounded cell stays unbounded.
                minimum = getattr(cell_ref, "_minimum", None)
                maximum = getattr(cell_ref, "_maximum", None)
                if minimum is not None:
                    linear_desc["minimum"] = str(float(minimum))
                if maximum is not None:
                    linear_desc["maximum"] = str(float(maximum))
                cells.append(linear_desc)
                continue
            # Type the resource by what it PUBLISHES, not by the cell's raw state: a cell
            # holding (temperature, heat_input) publishes a float, and typing it from the
            # tuple would declare a string resource in Aerie.
            typed_from = current
            projection = self._resource_projection_for(cell_ref)
            if projection is not None:
                typed_from = projection(current)
            if isinstance(typed_from, bool):
                vtype = "bool"
            elif isinstance(typed_from, int):
                vtype = "int"
            elif isinstance(typed_from, float):
                vtype = "float"
            else:
                vtype = "str"
            dynamics = getattr(cell_ref, "_dynamics", "discrete")
            # §3.5: RealDynamics is numeric — reject non-numeric published types early
            # rather than letting Java silently produce a broken resource. Check here
            # because this is the first point where both the cell and its projection
            # are known. (bool subclasses int, so it is excluded separately.)
            if dynamics == "real" and vtype not in ("int", "float"):
                raise ValueError(
                    f"dynamics='real' requires a numeric published value, but cell "
                    f"with resource {res_name!r} publishes type {vtype!r}. Use a "
                    f".map(fn) projection that returns a float, or use "
                    f"dynamics='discrete' for non-numeric evolving cells.")
            desc = {
                "initial": str(current),
                "resource": res_name,
                "type": vtype,
            }
            if _evolution is not None:
                desc["evolving"] = True
                # Max interval before the engine re-samples this cell, in microseconds
                # (drives Java's CellType.getExpiry). Absent means "never
                # expires", which is right for linear-in-time evolution but renders
                # nonlinear evolution as a single cliff between activity boundaries.
                resolution = getattr(cell_ref, "_resolution", None)
                if resolution is not None:
                    desc["resolution_micros"] = str(
                        int(resolution.to_number_in(MICROSECONDS)))
                if dynamics == "real":
                    desc["dynamics"] = "real"
            cells.append(desc)
        return cells

    def get_evolution_functions(self) -> list:
        """Return the evolution callable for each cell, or None if the cell has
        no evolution.  Order matches registrar.cells / describe_cells().
        Each non-None entry is wrapped so Java's CellType.step() can call it
        with (currentValue, elapsedMicros) directly."""
        return [_wrap_evolution(ev) if ev is not None else None
                for (_ref, _iv, ev) in self._registrar.cells]

    def get_initial_values(self) -> list:
        """Return each cell's initial value as a live Python object (not a string).
        Order matches registrar.cells / describe_cells().

        Evolving cells need this because describe_cells() only carries `initial` as
        str(value), and a str cannot be turned back into e.g. a tuple or a Duration
        without already knowing the target type. Handing Java the real object instead
        keeps the typed value intact from the very first step() -- reconstructing it
        from its repr is both lossy and unnecessary, since the object is right here."""
        values = []
        for cell_ref, initial_value, _ev in self._registrar.cells:
            values.append(_globals.cell_values_by_id.get(cell_ref.id, initial_value))
        return values

    def get_resource_value(self, name: str) -> str:
        for res_name, getter in self._registrar.resources:
            if res_name == name:
                return str(getter())
        raise KeyError(f"Unknown resource: {name!r}")

    def _resource_projection_for(self, cell_ref):
        """The raw-value -> resource-value function for `cell_ref`, or None."""
        for _res_name, getter in self._registrar.resources:
            source = getattr(getter, "_source_cell", None)
            if source is cell_ref:
                return getattr(getter, "_projection", None)
        return None

    def get_resource_projections(self) -> list:
        """Return, per cell (registrar.cells order), a callable that turns the cell's raw
        value into its resource value -- or None where the resource IS the raw value.

        A resource declared as `cell.map(fn)` shows fn(value), not the value: an evolving
        cell holding (temperature, heat_input) publishes just the temperature. Java's
        resource getter holds the raw cell state, so it needs this to project before
        stringifying, or the profile shows the whole tuple."""
        by_cell = {}
        for res_name, getter in self._registrar.resources:
            source = getattr(getter, "_source_cell", None)
            if source is None:
                source = getattr(getattr(getter, "__self__", None), "_source_cell", None)
            # Only a derived getter needs projecting; a bare cell.get is already the value.
            projection = getattr(getter, "_projection", None)
            if source is not None and projection is not None:
                by_cell[id(source)] = projection

        projections = []
        for cell_ref, _iv, _ev in self._registrar.cells:
            fn = by_cell.get(id(cell_ref))
            projections.append(_wrap_projection(fn) if fn is not None else None)
        return projections

    def describe_resources(self) -> dict:
        return _describe_resources(self._registrar)


# ---------------------------------------------------------------------------
# Cell evolution helpers
# ---------------------------------------------------------------------------

def _wrap_projection(fn):
    """Wrap a resource projection so Java can call it with a raw cell value and get back
    the resource's value as a string, ready to hand to the profile."""
    def _projected(value):
        return str(fn(value))
    return _projected


def _wrap_evolution(fn):
    """Wrap a user evolution function so Java's CellType.step() can call it with
    (currentValue, elapsedMicros) and get back the new Python value.
    The user's function signature is fn(current_value, elapsed_duration)."""
    def _stepped(current, micros):
        return fn(current, Duration.of(int(micros), MICROSECONDS))
    return _stepped


def _parse_value(value_str, reference):
    """Convert a string value from Java (emitCell) back to the Python type matching
    `reference` (the current cell Value). Used by EvolvingCell.apply() on the Java side.

    NOTE: `reference` must be the live Python cell value, never the incoming string --
    dispatch is on the reference's runtime type, so passing the string as both arguments
    makes every branch fall through to `str` and silently stringifies the cell."""
    if isinstance(reference, float):
        return float(value_str)
    if isinstance(reference, int):
        return int(float(value_str))
    if isinstance(reference, bool):
        return str(value_str).lower() in ("true", "1")
    if isinstance(reference, tuple):
        return ast.literal_eval(str(value_str))
    if isinstance(reference, list):
        return ast.literal_eval(str(value_str))
    return str(value_str)


# ---------------------------------------------------------------------------
# Graal direct-call entry point (roadmap §6) — the only way an activity runs
# ---------------------------------------------------------------------------

def run_activity_direct(model_state: "_ModelState", java_actions, activity_name: str, py_args: dict):
    """
    Run one activity to completion on the *calling* thread (a Java ThreadedTask).
    delay/call/wait_until call straight into `java_actions`; emits route through
    CellRef.emit → java_actions.emitCell (Phase 4); spawn schedules a fresh child
    ThreadedTask via `java_actions`.

    Returns normally when the activity function returns (Java then closes the span). If the
    activity raises, the exception propagates out through GraalPy to Java as a PolyglotException.
    """
    _globals.java_actions = java_actions
    _globals.reaction_context = _ReactionContext(java_actions)

    model_class = model_state.model_class

    def _spawner(child_instance):
        java_actions.spawnActivity(
            getattr(child_instance, "activity_name", None),
            _child_args_json(model_class, child_instance),
        )

    _globals._current_context[1] = _spawner

    task_def = model_class.activity_types[activity_name]
    raw_func = getattr(task_def, "raw_func", None)
    if raw_func is None:
        raise KeyError(f"Activity {activity_name!r} has no raw_func — was it decorated with @Mission.ActivityType?")
    raw_func(model_state.model_instance, **(py_args or {}))
