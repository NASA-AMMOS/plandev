"""
Tests for cell evolution.

These tests run against the standalone Python simulation framework (_framework.py).
Java-side tests require a running Aerie worker and are out of scope here.
"""

import contextlib as _contextlib
import math as _math

from pymerlin import MissionModel
from pymerlin import simulate
from pymerlin._internal import _globals
from pymerlin._internal._registrar import Registrar
from pymerlin._internal._schedule import Directive, Schedule
from pymerlin._internal._server import _ModelState, _parse_value
from pymerlin.clock import clock
from pymerlin.duration import ZERO, Duration, SECONDS
from pymerlin.model_actions import delay

# ---------------------------------------------------------------------------
# Evolution functions
# ---------------------------------------------------------------------------

def constant_slope(rate):
    """Evolution: value increases linearly at `rate` per second."""
    def evolution(value, d):
        return value + rate * d.to_number_in(SECONDS)
    return evolution


def linear_evolution(x, d):
    """Evolution for (value, rate) tuples: value integrates by rate * dt."""
    value, rate = x
    return value + rate * d.to_number_in(SECONDS), rate


# ---------------------------------------------------------------------------
# Test models
# ---------------------------------------------------------------------------

@MissionModel
class SimpleEvolvingModel:
    def __init__(self, registrar: Registrar):
        self.counter = registrar.cell(0.0, evolution=constant_slope(1))
        registrar.resource("counter", self.counter.get)


@MissionModel
class TupleEvolvingModel:
    def __init__(self, registrar: Registrar):
        self.state = registrar.cell((0.0, 2.0), evolution=linear_evolution)
        registrar.resource("state", self.state.get)


@MissionModel
class MixedModel:
    """Model with both evolving and non-evolving cells."""
    def __init__(self, registrar: Registrar):
        self.evolving = registrar.cell(0.0, evolution=constant_slope(10))
        self.discrete = registrar.cell(0)
        self.clk = clock(registrar)
        registrar.resource("evolving", self.evolving.get)
        registrar.resource("discrete", self.discrete.get)


# ---------------------------------------------------------------------------
# Tests — basic evolution
# ---------------------------------------------------------------------------

def test_evolution_value_at_known_time():
    """An evolving cell with rate=1/s should equal 60 after 60 seconds."""

    @SimpleEvolvingModel.ActivityType
    def check_value(mission: SimpleEvolvingModel):
        assert mission.counter.get() == 0.0
        delay(Duration.of(60, SECONDS))
        assert mission.counter.get() == 60.0

    simulate(
        SimpleEvolvingModel,
        Schedule.build(("00:00:00", Directive("check_value", {}))),
        "00:05:00",
    )


def test_evolution_profile_segments():
    """Evolution should produce correct profile segment values."""
    profiles, _spans, _events = simulate(
        SimpleEvolvingModel,
        Schedule.empty(),
        "00:01:00",
    )
    # One segment covering the full minute; dynamics is the value at segment start (0.0)
    assert "counter" in profiles
    segs = profiles["counter"]
    assert len(segs) == 1
    assert segs[0].dynamics == 0.0
    assert segs[0].extent == Duration.of(60, SECONDS)


def test_evolution_tuple_state():
    """Tuple-based evolution: (value, rate) should integrate correctly."""

    @TupleEvolvingModel.ActivityType
    def check_tuple(mission: TupleEvolvingModel):
        v0, r0 = mission.state.get()
        assert v0 == 0.0
        assert r0 == 2.0
        delay(Duration.of(30, SECONDS))
        v1, r1 = mission.state.get()
        assert v1 == 60.0  # 2.0 * 30s
        assert r1 == 2.0

    simulate(
        TupleEvolvingModel,
        Schedule.build(("00:00:00", Directive("check_tuple", {}))),
        "00:05:00",
    )


# ---------------------------------------------------------------------------
# Tests — evolution + mid-sim emit
# ---------------------------------------------------------------------------

def test_evolution_after_emit():
    """After an explicit emit resets the cell, evolution should continue from the new value."""

    @SimpleEvolvingModel.ActivityType
    def reset_and_check(mission: SimpleEvolvingModel):
        delay(Duration.of(10, SECONDS))
        assert mission.counter.get() == 10.0
        mission.counter.set(100.0)
        assert mission.counter.get() == 100.0
        delay(Duration.of(5, SECONDS))
        assert mission.counter.get() == 105.0  # 100 + 1*5

    simulate(
        SimpleEvolvingModel,
        Schedule.build(("00:00:00", Directive("reset_and_check", {}))),
        "00:05:00",
    )


def test_evolution_profile_after_emit():
    """Profile segments should reflect evolution and emits at correct times."""

    @SimpleEvolvingModel.ActivityType
    def emit_midway(mission: SimpleEvolvingModel):
        delay(Duration.of(30, SECONDS))
        mission.counter.set(1000.0)
        delay(Duration.of(30, SECONDS))

    profiles, _spans, _events = simulate(
        SimpleEvolvingModel,
        Schedule.build(("00:00:00", Directive("emit_midway", {}))),
        "00:01:00",
    )
    segs = profiles["counter"]
    # Segment 1: 0s–30s, dynamics is the value at t=0 (0.0)
    assert segs[0].dynamics == 0.0
    assert segs[0].extent == Duration.of(30, SECONDS)
    # Segment 2: 30s–60s, dynamics is the evolved value at t=30s (30.0).
    # The emit(1000.0) happens mid-tick after the snapshot; the framework
    # captures the pre-emit evolved value for the segment start.
    assert segs[1].dynamics == 30.0
    assert segs[1].extent == Duration.of(30, SECONDS)


# ---------------------------------------------------------------------------
# Tests — mixed evolving and non-evolving cells
# ---------------------------------------------------------------------------

def test_mixed_model_evolution():
    """Non-evolving cells should be unaffected by evolution stepping."""

    @MixedModel.ActivityType
    def check_mixed(mission: MixedModel):
        assert mission.evolving.get() == 0.0
        assert mission.discrete.get() == 0
        delay(Duration.of(10, SECONDS))
        assert mission.evolving.get() == 100.0  # 10 * 10s
        assert mission.discrete.get() == 0  # unchanged
        mission.discrete.set(42)
        delay(Duration.of(5, SECONDS))
        assert mission.evolving.get() == 150.0  # 10 * 15s
        assert mission.discrete.get() == 42  # still 42

    simulate(
        MixedModel,
        Schedule.build(("00:00:00", Directive("check_mixed", {}))),
        "00:05:00",
    )


# ---------------------------------------------------------------------------
# Tests — clock.py evolution
# ---------------------------------------------------------------------------

def test_clock_evolution():
    """Verify that clock.py's evolution function works correctly."""

    @MixedModel.ActivityType
    def check_clock(mission: MixedModel):
        c = mission.clk.start()
        assert c.get() == Duration.ZERO
        delay(Duration.of(9, SECONDS))
        assert c.get() == Duration.of(9, SECONDS)
        c.reset()
        assert c.get() == Duration.ZERO
        delay(Duration.of(5, SECONDS))
        assert c.get() == Duration.of(5, SECONDS)

    simulate(
        MixedModel,
        Schedule.build(("00:00:00", Directive("check_clock", {}))),
        "00:05:00",
    )


# ---------------------------------------------------------------------------
# Tests — Java-backed initialization contract
#
# These cover the _server.py side of what the Java shim calls at instantiate() time.
# They are plain Python (no GraalPy host needed) but they pin the exact contract whose
# violation produced, in a real simulation:
#     ValueError: too many values to unpack (expected 2)  in _energy_evolution
# ---------------------------------------------------------------------------

def test_parse_value_requires_typed_reference():
    """_parse_value dispatches on `reference`'s runtime TYPE, not on the string.

    Passing the value string as its own reference (which the Java shim briefly did when
    allocating evolving cells) makes every isinstance branch fall through to the str
    fallback, silently stringifying the cell.
    """
    # Correct: a real tuple reference round-trips to a tuple.
    assert _parse_value("(1.5, 2.0)", (0.0, 0.0)) == (1.5, 2.0)
    assert _parse_value("3.5", 0.0) == 3.5
    assert _parse_value("7", 0) == 7

    # The regression: string-as-its-own-reference yields a str, NOT a tuple.
    assert _parse_value("(0.0, 0.0)", "(0.0, 0.0)") == "(0.0, 0.0)"


def test_get_initial_values_preserves_python_types():
    """get_initial_values() must hand Java live Python objects, not str(value).

    describe_cells()'s `initial` is str(value) and cannot be converted back to a tuple
    or Duration without knowing the target type, so evolving cells are allocated from
    these objects instead. If this regresses to strings, a tuple-valued evolving cell
    fails on its first step() with "too many values to unpack".
    """

    @MissionModel
    class TypedModel:
        def __init__(self, registrar: Registrar):
            self.scalar = registrar.cell(0.0, evolution=constant_slope(1))
            self.pair = registrar.cell((0.0, 2.0), evolution=linear_evolution)
            self.clk = clock(registrar)

    state = _ModelState(TypedModel, {})
    initials = state.get_initial_values()
    cells = state.describe_cells()
    evolutions = state.get_evolution_functions()

    # One entry per cell, in registrar.cells order -- the index is the Python/Java contract.
    assert len(initials) == len(cells) == len(evolutions)

    by_type = {type(v) for v in initials}
    assert tuple in by_type, f"tuple initial was not preserved: {initials}"
    assert float in by_type, f"float initial was not preserved: {initials}"
    assert Duration in by_type, f"Duration initial was not preserved: {initials}"

    # Nothing may arrive as the repr of itself.
    assert "(0.0, 2.0)" not in initials


def test_evolution_functions_accept_their_initial_values():
    """Each evolution function must survive being called with its own initial value.

    This is exactly the first thing the engine does on a time advance, and it is where
    the stringified-tuple bug surfaced -- so drive it directly rather than trusting that
    the types merely look right.
    """

    @MissionModel
    class TypedModel:
        def __init__(self, registrar: Registrar):
            self.scalar = registrar.cell(0.0, evolution=constant_slope(1))
            self.pair = registrar.cell((0.0, 2.0), evolution=linear_evolution)
            self.clk = clock(registrar)

    state = _ModelState(TypedModel, {})
    initials = state.get_initial_values()

    # get_evolution_functions() wraps each user fn to take (value, elapsed_micros),
    # which is the signature Java's CellType.step() actually calls.
    for initial, wrapped in zip(initials, state.get_evolution_functions()):
        if wrapped is None:
            continue
        result = wrapped(initial, 1_000_000)  # advance one second
        assert type(result) is type(initial), (
            f"evolution changed value type: {type(initial)} -> {type(result)}")

    # And concretely, one second of the (value, rate) pair integrates by its rate.
    pair_initial = next(v for v in initials if isinstance(v, tuple))
    pair_fn = next(
        w for v, w in zip(initials, state.get_evolution_functions())
        if isinstance(v, tuple) and w is not None)
    assert pair_fn(pair_initial, 1_000_000) == (2.0, 2.0)


def test_clock_initial_value_is_a_duration():
    """clock()'s cell starts at Duration.ZERO; its evolution adds elapsed time.

    Duration is the case a str round-trip cannot rescue at all -- there is no parse
    branch for it in _parse_value -- so it depends entirely on get_initial_values()
    handing over the real object.
    """

    @MissionModel
    class ClockOnly:
        def __init__(self, registrar: Registrar):
            self.clk = clock(registrar)

    state = _ModelState(ClockOnly, {})
    (initial,) = state.get_initial_values()
    (wrapped,) = state.get_evolution_functions()

    assert initial == ZERO
    assert isinstance(initial, Duration)
    assert wrapped(initial, 5_000_000) == Duration.of(5, SECONDS)


# ---------------------------------------------------------------------------
# Tests — derived resources over evolving cells
#
# A resource declared as cell.map(fn) must still be traceable to its backing cell.
# The Java path registers resources PER CELL, so a resource whose cell cannot be
# identified is never created at all -- it vanishes from the simulation silently
# rather than failing loudly, which is how /temperature_c disappeared once it
# became a tuple-valued cell exposed through a projection.
# ---------------------------------------------------------------------------

def test_mapped_resource_is_associated_with_its_cell():
    """cell.map(...) resources must resolve to a cell in describe_cells()."""

    @MissionModel
    class MappedModel:
        def __init__(self, registrar: Registrar):
            self.pair = registrar.cell((5.0, 0.0), evolution=linear_evolution)
            registrar.resource("/first", self.pair.map(lambda s: s[0]))

    state = _ModelState(MappedModel, {})
    described = {c.get("resource") for c in state.describe_cells()}
    assert "/first" in described, (
        "mapped resource lost its backing cell; Java would never register it")


def test_mapped_resource_types_from_projected_value():
    """The resource's declared type comes from what it PUBLISHES, not the raw cell.

    A cell holding a tuple whose projection yields a float must be declared "float";
    typing it off the tuple would declare a string resource in Aerie.
    """

    @MissionModel
    class MappedModel:
        def __init__(self, registrar: Registrar):
            self.pair = registrar.cell((5.0, 0.0), evolution=linear_evolution)
            registrar.resource("/first", self.pair.map(lambda s: s[0]))

    state = _ModelState(MappedModel, {})
    (cell,) = [c for c in state.describe_cells() if c.get("resource") == "/first"]
    assert cell["type"] == "float", f'expected float, got {cell["type"]}'
    assert cell.get("evolving") is True


def test_resource_projection_extracts_published_value():
    """get_resource_projections() returns raw-value -> published-string functions."""

    @MissionModel
    class MappedModel:
        def __init__(self, registrar: Registrar):
            self.plain = registrar.cell(1.0)
            self.pair = registrar.cell((5.0, 2.0), evolution=linear_evolution)
            registrar.resource("/plain", self.plain)
            registrar.resource("/first", self.pair.map(lambda s: s[0]))

    state = _ModelState(MappedModel, {})
    cells = state.describe_cells()
    projections = state.get_resource_projections()
    assert len(projections) == len(cells)

    by_resource = {
        c.get("resource"): p for c, p in zip(cells, projections)}

    # A bare cell resource publishes its value as-is -- no projection needed.
    assert by_resource["/plain"] is None
    # A mapped one projects the tuple down to its first element.
    assert by_resource["/first"]((7.5, 2.0)) == "7.5"


def test_unmapped_cell_resource_still_works():
    """Registering a bare cell (no .map) must keep working -- the common case."""

    @MissionModel
    class PlainModel:
        def __init__(self, registrar: Registrar):
            self.temp = registrar.cell(0.0, evolution=constant_slope(1))
            registrar.resource("/temp", self.temp)

    state = _ModelState(PlainModel, {})
    (cell,) = [c for c in state.describe_cells() if c.get("resource") == "/temp"]
    assert cell["type"] == "float"
    assert cell.get("evolving") is True


# ---------------------------------------------------------------------------
# Tests — value types across the Java boundary
#
# The standalone framework keeps live Python objects, so it cannot catch types that
# break only when marshalled. These simulate the Java path with a fake java_actions
# to pin the contract that a Duration-valued evolving cell (pymerlin.clock) is read
# and written as an OBJECT, never via str().
#
# Regression: clock.start() does `-self._system_clock.get()`, which raised
#   TypeError: bad operand type for unary-: 'str'
# once the value came back as "+00:00:00.0000.0" instead of a Duration.
# ---------------------------------------------------------------------------

class _FakeJavaActions:
    """Minimal stand-in for the Java host object, storing live Python objects."""

    def __init__(self, values):
        self._values = list(values)
        self.ask_calls = 0
        self.ask_object_calls = 0

    def ask(self, index):
        self.ask_calls += 1
        return str(self._values[index])

    def askObject(self, index):
        self.ask_object_calls += 1
        return self._values[index]

    def emitCell(self, index, value_str):
        self._values[index] = value_str

    def emitCellObject(self, index, value):
        self._values[index] = value


def _with_fake_java(values):
    fake = _FakeJavaActions(values)
    _globals.java_actions = fake
    return fake


def _clear_fake_java():
    _globals.java_actions = None


def test_evolving_duration_cell_reads_as_object():
    """A Duration-valued evolving cell must not come back as a string.

    str(Duration) is "+00:00:00.0000.0", and Duration.from_string cannot parse it, so
    the string path is unrecoverable -- clock.start()'s unary minus fails on it.
    """
    registrar = Registrar()
    cell = registrar.cell(ZERO, evolution=lambda x, d: x + d)
    cell._cell_index = 0
    cell._value_type = Duration

    fake = _with_fake_java([Duration.of(9, SECONDS)])
    try:
        value = cell.get()
        assert isinstance(value, Duration), f"got {type(value).__name__}: {value!r}"
        assert value == Duration.of(9, SECONDS)
        # Unary minus is exactly what clock.start() does; a str would raise TypeError.
        assert -value == Duration.of(-9, SECONDS)
        assert fake.ask_object_calls == 1
    finally:
        _clear_fake_java()


def test_evolving_duration_cell_writes_as_object():
    """Writing a Duration-valued evolving cell keeps it a Duration, not str()."""
    registrar = Registrar()
    cell = registrar.cell(ZERO, evolution=lambda x, d: x + d)
    cell._cell_index = 0
    cell._value_type = Duration

    fake = _with_fake_java([ZERO])
    try:
        cell.emit(Duration.of(30, SECONDS))
        stored = fake._values[0]
        assert isinstance(stored, Duration), f"stored as {type(stored).__name__}: {stored!r}"
        assert stored == Duration.of(30, SECONDS)
    finally:
        _clear_fake_java()


def test_non_evolving_cell_still_uses_string_path():
    """Only evolving cells take the object path; discrete cells are unchanged."""
    registrar = Registrar()
    cell = registrar.cell(1.5)
    cell._cell_index = 0
    cell._value_type = float

    fake = _with_fake_java([2.5])
    try:
        assert cell.get() == 2.5
        assert fake.ask_calls == 1
        assert fake.ask_object_calls == 0
    finally:
        _clear_fake_java()


# ---------------------------------------------------------------------------
# Tests — bounded linear cells (Aerie ClampedIntegrator equivalent)
#
# A linear cell integrates value + rate*t with no bounds by default. For a
# physically bounded quantity that is wrong: a battery at 100% with the solar
# panels generating a surplus keeps climbing to 130%, 200%, ...
# ---------------------------------------------------------------------------

def test_linear_cell_bounds_are_described():
    """minimum/maximum reach Java through describe_cells()."""

    @MissionModel
    class BoundedModel:
        def __init__(self, registrar: Registrar):
            self.battery = registrar.linear(100.0, minimum=0.0, maximum=100.0)
            registrar.resource("/battery", self.battery)

    state = _ModelState(BoundedModel, {})
    (cell,) = [c for c in state.describe_cells() if c.get("resource") == "/battery"]
    assert cell["type"] == "linear"
    assert cell["minimum"] == "0.0"
    assert cell["maximum"] == "100.0"


def test_linear_cell_without_bounds_stays_unbounded():
    """Bounds are opt-in: an unbounded cell must not gain them implicitly.

    Cumulative counters (data volume, total energy) are legitimately unbounded, and
    silently clamping them would be a worse bug than the one bounds fix.
    """

    @MissionModel
    class UnboundedModel:
        def __init__(self, registrar: Registrar):
            self.buffer = registrar.linear(0.0)
            registrar.resource("/buffer", self.buffer)

    state = _ModelState(UnboundedModel, {})
    (cell,) = [c for c in state.describe_cells() if c.get("resource") == "/buffer"]
    assert "minimum" not in cell
    assert "maximum" not in cell


def test_linear_cell_accepts_one_sided_bounds():
    """minimum and maximum are independent -- a floor without a ceiling is valid."""

    @MissionModel
    class FloorModel:
        def __init__(self, registrar: Registrar):
            self.tank = registrar.linear(50.0, minimum=0.0)
            registrar.resource("/tank", self.tank)

    state = _ModelState(FloorModel, {})
    (cell,) = [c for c in state.describe_cells() if c.get("resource") == "/tank"]
    assert cell["minimum"] == "0.0"
    assert "maximum" not in cell


# ---------------------------------------------------------------------------
# Tests — bound-crossing expiry for linear cells
#
# Clamping the stored value is not enough on its own. A real profile segment is
# {initial, rate} and is EXTRAPOLATED across its extent, so a battery at 99.583%
# charging into a 4-minute segment was drawn climbing to 100.897 even though
# step() pinned the stored value at 100. The cell has to EXPIRE at the crossing
# so the engine cuts a segment there.
#
# These mirror ShimModelType's getExpiry/getDynamics arithmetic in Python. They
# pin the maths so it can be tested anywhere; verifying the Java wiring itself
# needs a provisioned GraalPy host, so those assertions live in the JUnit suite.
# ---------------------------------------------------------------------------


def _expiry_seconds(value, rate, minimum, maximum):
    """Mirror of ShimModelType's linear-cell getExpiry."""
    if rate == 0.0:
        return None
    bound = maximum if rate > 0 else minimum
    if bound is None:
        return None
    seconds = (bound - value) / rate
    if seconds <= 0.0:
        return None
    return _math.ceil(seconds * 1e6) / 1e6


def _reported_slope(value, rate, minimum, maximum):
    """Mirror of ShimModelType's linear-cell getDynamics slope-zeroing."""
    eps = 1e-9
    if maximum is not None and value >= maximum - eps and rate > 0:
        return 0.0
    if minimum is not None and value <= minimum + eps and rate < 0:
        return 0.0
    return rate


def test_expiry_schedules_the_bound_crossing():
    """A charging battery expires exactly when it would reach 100%."""
    rate = (20.0 / 100.0) * (100.0 / 3600.0)  # +20 W surplus, 100 Wh capacity
    expiry = _expiry_seconds(99.583, rate, 0.0, 100.0)
    assert expiry is not None
    # 0.417% remaining at 0.005556 %/s
    assert abs(expiry - 75.06) < 0.01
    # The segment that expiry produces must land ON the bound, not past it.
    assert abs(99.583 + rate * expiry - 100.0) < 1e-6


def test_segment_never_extrapolates_past_the_bound():
    """The reported segment must not exceed the maximum over its own extent.

    Regression: the observed profile ran 99.583 -> 100.89674 because the segment
    kept its positive slope for a full 4 minutes with no expiry to cut it.
    """
    rate = (20.0 / 100.0) * (100.0 / 3600.0)
    value = 99.583
    expiry = _expiry_seconds(value, rate, 0.0, 100.0)
    slope = _reported_slope(value, rate, 0.0, 100.0)

    # Expiry rounds UP to the next microsecond (expiring early would leave the engine
    # re-scheduling the same crossing), so the segment may exceed the bound by at most
    # one microsecond of slope. That is invisible in a profile; the 0.897 the bug
    # produced was not.
    one_micro_of_slope = abs(slope) * 1e-6
    end_of_segment = value + slope * expiry
    assert end_of_segment <= 100.0 + one_micro_of_slope, f"segment overshot to {end_of_segment}"

    # Without the expiry the same slope over the real 4-minute gap overshoots --
    # this is the bug, asserted so the test explains itself.
    assert value + slope * 236.5 > 100.5


def test_slope_is_flat_once_pinned_at_a_bound():
    """At the bound the reported slope is zero, so the next segment is flat."""
    rate = (20.0 / 100.0) * (100.0 / 3600.0)
    assert _reported_slope(100.0, rate, 0.0, 100.0) == 0.0
    # ...and a hair under the bound still counts, since the crossing lands there
    # through floating-point arithmetic.
    assert _reported_slope(100.0 - 1e-12, rate, 0.0, 100.0) == 0.0


def test_discharging_is_unaffected_until_the_floor():
    """Only the bound the cell is heading TOWARD matters."""
    rate = (-30.0 / 100.0) * (100.0 / 3600.0)
    # Discharging from 100% keeps its slope -- the max is behind it.
    assert _reported_slope(100.0, rate, 0.0, 100.0) == rate
    # Expiry targets the floor, not the ceiling.
    expiry = _expiry_seconds(100.0, rate, 0.0, 100.0)
    assert abs(100.0 + rate * expiry - 0.0) < 1e-6
    # At the floor, flat.
    assert _reported_slope(0.0, rate, 0.0, 100.0) == 0.0


def test_unbounded_cell_never_expires():
    """Cumulative counters have no bound to cross, so they schedule nothing."""
    assert _expiry_seconds(50.0, 1.0, None, None) is None
    assert _expiry_seconds(50.0, 0.0, 0.0, 100.0) is None      # no rate
    assert _expiry_seconds(100.0, 1.0, 0.0, 100.0) is None     # already at bound


# ---------------------------------------------------------------------------
# Real evolution — Step 7 tests (real_evolution_roadmap.md §4.7)
# ---------------------------------------------------------------------------
# Tests 1–4 are plain Python (no GraalPy host required).
# Test 5 (JUnit, provisioned host) is out of scope here per the roadmap.
# ---------------------------------------------------------------------------

def test_real_dynamics_without_evolution_raises():
    """dynamics='real' requires an evolution function."""
    r = Registrar()
    with _pytest_raises(ValueError, match="evolution"):
        r.cell(0.0, dynamics="real")


def test_real_dynamics_without_resolution_raises():
    """dynamics='real' requires a resolution."""
    r = Registrar()
    with _pytest_raises(ValueError, match="resolution"):
        r.cell(0.0, evolution=constant_slope(1), dynamics="real")


def test_real_dynamics_invalid_value_raises():
    """dynamics must be 'discrete' or 'real'."""
    r = Registrar()
    with _pytest_raises(ValueError, match="polynomial"):
        r.cell(0.0, dynamics="polynomial")


def test_real_dynamics_non_numeric_published_type_raises():
    """A string-valued real cell must fail loudly at describe_cells() time (§3.5)."""
    @MissionModel
    class _M:
        def __init__(self, registrar: Registrar):
            self.mode = registrar.cell(
                "IDLE",
                evolution=lambda v, d: v,
                resolution=Duration.of(30, SECONDS),
                dynamics="real",
            )
            registrar.resource("/mode", self.mode)

    state = _ModelState(_M, {})
    with _pytest_raises(ValueError, match="numeric"):
        state.describe_cells()


def test_describe_cells_emits_dynamics_real():
    """describe_cells() carries dynamics:'real' for opted-in cells only."""
    @MissionModel
    class _M:
        def __init__(self, registrar: Registrar):
            self.temp = registrar.cell(
                5.0,
                evolution=constant_slope(2),
                resolution=Duration.of(30, SECONDS),
                dynamics="real",
            )
            registrar.resource("/temp", self.temp)
            self.count = registrar.cell(0, evolution=lambda v, d: v + 1,
                                        resolution=Duration.of(60, SECONDS))
            registrar.resource("/count", self.count)

    state = _ModelState(_M, {})
    cells = state.describe_cells()
    temp_desc  = next(c for c in cells if c.get("resource") == "/temp")
    count_desc = next(c for c in cells if c.get("resource") == "/count")

    assert temp_desc.get("dynamics") == "real", temp_desc
    assert temp_desc["evolving"] is True
    assert "resolution_micros" in temp_desc

    assert "dynamics" not in count_desc, (
        "discrete evolving cell must not carry dynamics key")


def test_real_dynamics_slope_arithmetic():
    """Slope for constant_slope(2) at 30s resolution must be exactly 2.0/s.

    The secant formula mirrors what Java's getDynamics() computes:
        slope = (evolution(v, resolution_micros) - v) / resolution_seconds

    For a linear evolution fn v + 2*t the secant IS the derivative, so the
    result must match to float precision (no approximation error).
    """
    resolution_seconds = 30.0

    initial_value = 5.0
    evolution_fn  = constant_slope(2)   # f(v, d) = v + 2 * d.to_number_in(SECONDS)

    # Replicate the lookahead formula from ShimModelType.getDynamics (Option A):
    now  = initial_value
    ahead_value = evolution_fn(initial_value, Duration.of(resolution_seconds, SECONDS))
    slope = (ahead_value - now) / resolution_seconds

    assert abs(slope - 2.0) < 1e-9, f"expected slope 2.0/s, got {slope}"


def test_real_dynamics_slope_arithmetic_with_projection():
    """Slope is derived from the PROJECTED value, not the raw cell state.

    A tuple cell (temperature, heat_input) with a .map(lambda s: s[0])
    projection must produce the slope of the temperature component only.
    """
    resolution_seconds = 30.0

    def tuple_evolution(v, d):
        dt = d.to_number_in(SECONDS)
        return (v[0] + 2.0 * dt, v[1])   # temperature ramps at 2/s, heat_input constant

    project = lambda s: s[0]
    initial = (5.0, 15.0)

    now_raw   = initial
    ahead_raw = tuple_evolution(initial, Duration.of(resolution_seconds, SECONDS))

    now   = project(now_raw)
    ahead = project(ahead_raw)
    slope = (ahead - now) / resolution_seconds

    assert abs(slope - 2.0) < 1e-9, f"expected slope 2.0/s, got {slope}"


def test_real_dynamics_regression_discrete_unchanged():
    """A discrete evolving cell must not gain a dynamics key — regression guard."""
    @MissionModel
    class _M:
        def __init__(self, registrar: Registrar):
            self.temp = registrar.cell(
                5.0,
                evolution=constant_slope(1),
                resolution=Duration.of(30, SECONDS),
            )
            registrar.resource("/temp", self.temp)

    state = _ModelState(_M, {})
    cells = state.describe_cells()
    temp_desc = next(c for c in cells if c.get("resource") == "/temp")

    assert "dynamics" not in temp_desc, (
        f"discrete cell must not carry dynamics key: {temp_desc}")
    assert temp_desc["evolving"] is True
    assert temp_desc["type"] == "float"


# ---------------------------------------------------------------------------
# Helper: minimal pytest.raises stand-in so these tests run without pytest
# (when invoked directly) and also work under pytest normally.
# ---------------------------------------------------------------------------


@_contextlib.contextmanager
def _pytest_raises(exc_type, match=None):
    """Minimal stand-in for pytest.raises usable in both pytest and __main__."""
    import re as _re
    try:
        yield
    except exc_type as e:
        if match is not None:
            assert _re.search(match, str(e)), (
                f"Expected pattern {match!r} in: {e!r}")
    else:
        raise AssertionError(f"Expected {exc_type.__name__} to be raised")
