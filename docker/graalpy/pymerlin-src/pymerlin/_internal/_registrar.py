import ast
from contextlib import contextmanager

from pymerlin._internal import _globals


class Registrar:
    def __init__(self):
        self.cells = []
        self.resources = []
        self.topics = []

    def cell(self, initial_value, evolution=None, resolution=None, dynamics="discrete"):
        """
        Declare a cell.

        ``evolution`` is ``fn(current_value, elapsed_duration) -> new_value``, called
        automatically as simulation time advances.

        ``resolution`` is the maximum time the engine may let pass before re-sampling an
        evolving cell, and it only affects how the resource PROFILE is recorded -- reads
        are always exact, because the engine steps the cell to the read time regardless.

        It matters for NONLINEAR evolution. Aerie samples a discrete resource only when
        something queries the cell, so a stretch of simulation with no reads collapses into
        one profile segment holding just the endpoint -- an exponential decay then renders
        as a single cliff rather than a curve. Setting a resolution makes the cell expire
        that often, so the engine re-queries and the profile follows the curve.

        Leave it ``None`` for evolution that is linear in time (a straight line needs no
        intermediate samples) or where only read-time values matter. Smaller values mean
        more profile fidelity and more evolution calls; pick the coarsest value that still
        renders acceptably.

        ``dynamics`` controls the resource type published to PlanDev:

        - ``"discrete"`` (default): each profile segment holds a flat value.
        - ``"real"``: each profile segment carries ``{initial, rate}`` — a value and a
          slope — so the segment is drawn as a sloped chord. The slope is the secant
          over one ``resolution`` interval, computed by evaluating the evolution function
          one interval ahead. Requires both ``evolution`` and ``resolution``.

        Note that ``dynamics="real"`` does NOT remove sampling; a nonlinear function still
        needs one segment per ``resolution`` interval. It changes what each segment
        *looks like* (chord vs flat step), not how many there are. The ``resolution`` also
        sets the lookahead interval for slope estimation, so changing it alters computed
        slopes.

        **Standalone simulate() is unaffected.** The pure-Python engine always produces
        flat-value ``ProfileSegment`` objects regardless of this setting — ``dynamics="real"``
        only takes effect when the model runs in-process inside a PlanDev worker via GraalPy.
        Use ``simulate()`` to check model logic; use the JUnit suite for profile-fidelity
        assertions.
        """
        if dynamics not in ("discrete", "real"):
            raise ValueError(
                f"dynamics must be 'discrete' or 'real', got {dynamics!r}")
        if dynamics == "real" and evolution is None:
            raise ValueError(
                "dynamics='real' requires an evolution function — a non-evolving "
                "cell has no curve to compute a slope from")
        if dynamics == "real" and resolution is None:
            raise ValueError(
                "dynamics='real' requires a resolution — the resolution sets the "
                "lookahead interval for slope estimation")
        ref = CellRef()
        ref._is_evolving = evolution is not None
        ref._dynamics = dynamics
        if resolution is not None:
            ref._resolution = resolution
        self.cells.append((ref, initial_value, evolution))
        return ref

    def linear(self, initial_value, rate=0.0, minimum=None, maximum=None):
        """
        Declare a continuously-integrating (linear) cell (roadmap §7.2).

        The cell's value evolves as ``value + rate * elapsed_seconds`` — Java backs it
        with an Aerie ``RealDynamics`` resource that ramps between discrete events instead
        of snapshotting the last emitted value. ``set_rate(...)`` changes the slope (e.g.
        start/stop draining); ``emit(...)`` still applies a discrete jump to the value.
        Scoped to linear dynamics only, which is all Aerie's ``RealDynamics`` can represent.

        ``minimum`` / ``maximum`` bound the integrated value, mirroring Aerie's
        ``ClampedIntegrator``. Without them a quantity that is physically bounded — a
        battery's state of charge, a tank, a buffer — integrates straight past its limit
        (a battery charging at 100% keeps climbing to 130%, 200%, ...). Both are optional
        and independent; leave them unset for genuinely unbounded quantities like
        cumulative counters.
        """
        ref = LinearCellRef(float(rate))
        ref._minimum = None if minimum is None else float(minimum)
        ref._maximum = None if maximum is None else float(maximum)
        self.cells.append((ref, float(initial_value), None))
        return ref

    def resource(self, name, f):
        """
        Declare a resource to track
        :param name: The name of the resource
        :param f: A function to calculate the resource, or a cell that contains the value of the resource
        """
        if not callable(f):
            cell = f
            # Bind through a wrapper rather than storing the bare `cell.get`, so the
            # cell/projection metadata a derived Gettable carries (see Gettable.map)
            # survives into self.resources. The Java path needs it to know which cell
            # backs this resource and how to project that cell's raw value.
            getter = _ResourceGetter(cell)
            self.resources.append((name, getter))
            return
        self.resources.append((name, f))

    def topic(self, name):
        pass


class _ResourceGetter:
    """Callable view of a Gettable/CellRef registered as a resource.

    Exists so `registrar.resource(name, some_gettable)` keeps `_source_cell` and
    `_projection` reachable on the stored getter; a bare bound `cell.get` would drop both,
    and the Java path would then be unable to tell which cell backs the resource.
    """

    def __init__(self, gettable):
        self._gettable = gettable
        self._source_cell = getattr(gettable, "_source_cell", None)
        self._projection = getattr(gettable, "_projection", None)

    def __call__(self):
        return self._gettable.get()


class Gettable:
    def __init__(self, func, source_cell=None):
        self.func = func
        # The CellRef this value is ultimately derived from, if any. Carried so a DERIVED
        # resource (e.g. cell.map(lambda t: t[0])) can still be tied back to the cell that
        # backs it -- the Java path registers resources per-cell, so a resource with no
        # identifiable source cell is silently never created.
        self._source_cell = source_cell
        # Raw cell value -> this resource's value, when derived via map(); None means the
        # value is used as-is.
        self._projection = None

    def get(self):
        return self.func()

    def map(self, new_func):
        derived = Gettable(lambda: new_func(self.get()), source_cell=self._source_cell)
        # Keep the projection itself (raw cell value -> resource value) reachable. The Java
        # resource getter holds the RAW cell state, so it needs to apply this before
        # stringifying; composing through an existing projection keeps chained maps correct.
        prior = self._projection
        derived._projection = (lambda v: new_func(prior(v))) if prior else new_func
        return derived

    def __add__(self, other):
        if _is_gettable(other):
            return Gettable(lambda: self.get() + other.get())
        else:
            return Gettable(lambda: self.get() + other)

    def __sub__(self, other):
        if _is_gettable(other):
            return Gettable(lambda: self.get() - other.get())
        else:
            return Gettable(lambda: self.get() - other)

    def __mul__(self, other):
        if _is_gettable(other):
            return Gettable(lambda: self.get() * other.get())
        else:
            return Gettable(lambda: self.get() * other)

    def __div__(self, other):
        if _is_gettable(other):
            return Gettable(lambda: self.get() / other.get())
        else:
            return Gettable(lambda: self.get() / other)

    def __pow__(self, other, modulo=None):
        if _is_gettable(other):
            return Gettable(lambda: self.get() ** other.get())
        else:
            return Gettable(lambda: self.get() ** other)

    def __mod__(self, other):
        if _is_gettable(other):
            return Gettable(lambda: self.get() % other.get())
        else:
            return Gettable(lambda: self.get() % other)

def _is_gettable(obj):
    return callable(getattr(obj, "get", None))

class CellRef(Gettable):
    """
    A reference to an allocated piece of simulation state.

    Under Java-backed execution (Phase 4, roadmap §7), get() calls through to
    ModelActions.ask(cellId) via java_actions.ask(cell_index), which registers a read
    dependency in QueryContext — enabling real waitUntil (the engine re-evaluates when
    the cell's topic changes). emit() applies the event function locally (for typed
    lambda support) then sends the string result to Java via java_actions.emitCell().

    Falls back to the _globals.cell_values_by_id dict when java_actions is None
    (standalone simulation via _framework.py, or model __init__ before activities run).
    """

    def __init__(self):
        super().__init__(self._get)
        # A cell is its own source, so anything derived from it via map() keeps a path
        # back to the cell the Java side must register the resource against.
        self._source_cell = self
        self.id = None
        self.topic = None
        self._cell_index = None    # sequential int, set by _ModelState (Phase 4)
        self._value_type = str     # type of the cell value, set by _ModelState
        self._resolution = None    # max re-sample interval for evolving cells (Duration)
        self._is_evolving = False  # True when declared with evolution=, set by Registrar.cell
        self._dynamics = "discrete"  # "discrete" or "real", set by Registrar.cell

    def emit(self, event):
        if not callable(event):
            event = set_value(event)
        current = self._get()  # reads from Java if available, minimizing stale-value races
        new_val = event(current)
        _globals.cell_values_by_id[self.id] = new_val
        ja = _globals.java_actions
        if ja is not None and self._cell_index is not None:
            # Mirror _get: an evolving cell keeps the live Python object on the Java side,
            # so hand the object over rather than str(new_val). Duration in particular has
            # no string form the Java side can parse back into a Duration.
            if self._is_evolving:
                ja.emitCellObject(self._cell_index, new_val)
            else:
                ja.emitCell(self._cell_index, str(new_val))

    def set(self, new_value):
        self.emit(set_value(new_value))

    def add(self, addend):
        self.emit(lambda x: x + addend)

    def _get(self):
        ja = _globals.java_actions
        if ja is not None and self._cell_index is not None:
            # An evolving cell holds a live Python object on the Java side, so read it
            # back directly instead of via str(). The string path cannot represent every
            # value type -- a Duration stringifies to "+00:00:00.0000.0", which no parse
            # here recovers -- and round-tripping floats through text loses precision.
            if self._is_evolving:
                obj = ja.askObject(self._cell_index)
                if obj is not None:
                    return obj
            val_str = ja.ask(self._cell_index)
            return self._convert_from_java(val_str)
        return _globals.cell_values_by_id[self.id]

    def _convert_from_java(self, val_str):
        """Convert a string value from Java back to the Python type."""
        if self._value_type is float:
            return float(val_str)
        elif self._value_type is int:
            return int(float(val_str))
        elif self._value_type is bool:
            return val_str.lower() in ("true", "1")
        elif self._value_type in (tuple, list):
            return ast.literal_eval(str(val_str))
        return val_str

    def __iadd__(self, other):
        self.emit(lambda x: x + other)
        return self

    def __isub__(self, other):
        self.emit(lambda x: x - other)
        return self

    def __imul__(self, other):
        self.emit(lambda x: x * other)
        return self

    def __idiv__(self, other):
        self.emit(lambda x: x / other)
        return self

    def __imod__(self, other):
        self.emit(lambda x: x % other)
        return self

class LinearCellRef(CellRef):
    """
    A continuously-integrating cell (roadmap §7.2), declared via ``registrar.linear``.

    Behaves like a normal :class:`CellRef` for discrete reads/writes (``get``/``emit``),
    but additionally carries a *rate*: under Java-backed execution the cell's value ramps
    as ``value + rate * elapsed_seconds`` between events, wired to an Aerie
    ``RealDynamics`` resource. ``set_rate`` changes the slope as a discrete event; the
    continuously-integrating value itself is owned by the Java cell (its ``step`` hook),
    so ``get`` always reflects the ramped value at the current instant.
    """

    def __init__(self, initial_rate=0.0):
        super().__init__()
        self._is_linear = True
        self._initial_rate = float(initial_rate)
        self._value_type = float
        # Optional integration bounds (see Registrar.linear); None means unbounded.
        self._minimum = None
        self._maximum = None

    def set_rate(self, rate):
        """Set the cell's rate of change (units per second) as a discrete event."""
        rate = float(rate)
        ja = _globals.java_actions
        if ja is not None and self._cell_index is not None:
            ja.setRate(self._cell_index, rate)


def set_value(new_value):
    return lambda x: new_value

@contextmanager
def using(cell_ref, quantity):
    cell_ref += quantity
    yield
    cell_ref -= quantity

# class FunctionalEffect:
#     def __init__(self, f):
#         self.f = f
#
#     def apply(self, state):
#         return self.f(state)
#
#     class Java:
#         implements = ["java.util.function.Function"]


def add_number(addend):
    pass
