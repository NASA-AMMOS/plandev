import math

from pymerlin import MissionModel, MissionModelBase
from pymerlin.clock import clock
from pymerlin.duration import SECONDS, Duration
from pymerlin.model_actions import delay, wait_until, spawn


# Arbitrary demo battery capacity, Watt-hours. Only used to convert net Watts
# into a %/sec rate for battery_pct.
_BATTERY_CAPACITY_WH = 100.0

# --- Thermal model (cell evolution) ------------------------------------------------------
#
# Newton's law of cooling with a heat source:
#
#     dT/dt = -(T - T_eq) / tau,    T_eq = T_ambient + heat_input_w * K
#
# whose exact solution over an interval is
#
#     T(t + dt) = T_eq + (T(t) - T_eq) * exp(-dt / tau)
#
# Heat is modelled as an INPUT RATE (Watts), not as a step change in temperature. An
# instrument powering on raises heat input, which raises the equilibrium the spacecraft
# is relaxing toward; the temperature then RAMPS to that equilibrium over ~tau instead of
# teleporting. Power-off drops the input back and the temperature decays again. That is
# both physically right and the reason the profile shows a curve rather than a cliff.
#
# This is also why temperature_c is an EVOLVING cell rather than a linear() one: the
# approach to equilibrium is exponential, and linear()/RealDynamics can only represent a
# constant slope. Evolution means no activity has to "drive" the ramp -- it happens
# autonomously in CellType.step() as the engine advances time.
#
# tau is deliberately long relative to activity durations (25 min) so a multi-hour plan
# shows temperature still in motion rather than pinned at ambient the whole time.
_AMBIENT_TEMP_C = 5.0
_THERMAL_TAU_S = 1500.0

# Degrees of steady-state rise per Watt of heat input. Combined with _WASTE_HEAT_FRACTION
# below, collect_data's 15 W instrument settles at 5 + 15*0.8*0.8 = 14.6 C and downlink's
# 25 W transmitter at 21 C -- comfortably above ambient, so warm-up is visible on the plot,
# and hotter for the load that draws more, which is the point of deriving heat from power.
_THERMAL_C_PER_W = 0.8

# Fraction of drawn power that becomes waste heat in the structure. Not 1.0: some leaves as
# radiated RF rather than heating the bus. A single fraction across all loads is a
# simplification -- a real thermal model would weight each load separately (a transmitter
# radiates away much more of its draw than an instrument does).
_WASTE_HEAT_FRACTION = 0.8


def _thermal_evolution(state, elapsed):
    """Evolution function for temperature_c: exponential approach to equilibrium.

    State is the tuple (temperature_c, heat_input_w) -- the cell carries the heat input
    that sets its own target, so the engine can integrate it without an activity ticking
    it forward. Activities never set it directly -- it is derived from power draw (see
    `_recompute_heat_input`), and the temperature then follows on its own.

    Signature is the general evolution contract -- fn(current_value, elapsed_duration)
    -> new_value -- called automatically by the engine as time advances. Pure and cheap,
    which matters: under the Java-backed path this runs inside CellType.step(), on the
    engine thread, holding the GraalPy GIL for its duration.
    """
    temp_c, heat_input_w = state
    target_c = _AMBIENT_TEMP_C + heat_input_w * _THERMAL_C_PER_W
    dt_s = elapsed.to_number_in(SECONDS)
    return target_c + (temp_c - target_c) * math.exp(-dt_s / _THERMAL_TAU_S), heat_input_w


# Time constant for the electronics-box heatsink -- shorter than the structural tau
# because it is thermally coupled directly to the PCBs rather than through the airframe.
_HEATSINK_TAU_S = 300.0   # 5-minute time constant

# Steady-state heatsink rise per Watt. Higher than _THERMAL_C_PER_W because the
# heatsink has less thermal mass than the full structure and radiates less efficiently.
_HEATSINK_C_PER_W = 1.2


def _heatsink_evolution(state, elapsed):
    """Evolution function for heatsink_temp_c: exponential approach to a target derived
    from the current bus power.

    State is the tuple (heatsink_temp_c, target_c) -- the cell carries its own
    equilibrium target, exactly as temperature_c carries its heat input. The target must
    live in cell state rather than a module-level global: cell state is snapshotted by
    Aerie's duplicate() and travels with the cell, whereas a module global belongs to
    whichever GraalPy Context happens to be loaded. Each simulation reloads the module in
    a fresh Context, so a global resets to its declaration value independently of the
    cell it is supposed to describe -- the target silently reverts to ambient while the
    temperature keeps the value it had, and the two disagree.
    """
    temp_c, target_c = state
    dt_s = elapsed.to_number_in(SECONDS)
    return target_c + (temp_c - target_c) * math.exp(-dt_s / _HEATSINK_TAU_S), target_c


def _recompute_heatsink_target(mission):
    """Update the heatsink equilibrium target from the current bus power draw.

    Called alongside _recompute_heat_input so both thermal resources track the same
    power event. Emitting the CURRENT temperature alongside the new target lets evolution
    continue from where it left off rather than teleporting to the target -- the same
    emit-then-keep-evolving pattern as temperature_c.
    """
    current_hs, _old_target = mission.heatsink_temp_c.get()
    target_c = _AMBIENT_TEMP_C + mission.power_w.get() * _HEATSINK_C_PER_W
    mission.heatsink_temp_c.emit((current_hs, target_c))


def _recompute_heat_input(mission):
    """Re-derive the heat dumped into the structure from the CURRENT power draw.

    Heat is not an independent quantity an activity gets to set: electrical power drawn by
    the spacecraft's electronics ends up as waste heat. So this reads power_w rather than
    taking a value, and every activity that changes power_w calls it -- the same
    derived-from-inputs pattern `_recompute_power_effects` uses for battery_pct too.

    That means an activity says only "I draw 15 W" and the thermal consequence follows,
    instead of restating the same 15 W as a second, independently-maintained number that
    can silently disagree (a 25 W downlink producing no heat at all, say).

    Emitting the tuple with the CURRENT temperature is the key move: the temperature does
    not jump, only the equilibrium it is heading toward changes. That is the
    emit-then-keep-evolving interaction cell evolution exists to support.
    """
    current_temp_c, _old_input_w = mission.temperature_c.get()
    # Waste heat only -- power that leaves as RF (the transmitter) or sunlight collected
    # by the panels is not deposited in the structure. Scaled so the demo's activities land
    # at sensible temperatures rather than modelling any real bus.
    heat_input_w = mission.power_w.get() * _WASTE_HEAT_FRACTION
    mission.temperature_c.emit((current_temp_c, heat_input_w))


def _energy_evolution(state, elapsed):
    """Evolution function for energy_wh: integrate net power into cumulative energy.

    State is the tuple (cumulative_watt_hours, net_watts) -- the cell carries its own
    rate, so the engine can integrate it without an activity ticking it forward. Returns
    a tuple (immutable), which is what the roadmap recommends for evolving cell values:
    Aerie calls duplicate() to snapshot cell state, and immutable values make that copy
    trivially safe.
    """
    cumulative_wh, net_w = state
    dt_h = elapsed.to_number_in(SECONDS) / 3600.0
    return cumulative_wh + net_w * dt_h, net_w


def _recompute_power_effects(mission):
    """Re-derive everything that follows from the current power balance.

    Battery rate, accumulated energy, and thermal input are all CONSEQUENCES of power draw
    and solar generation, not independent quantities an activity sets alongside them. An
    activity therefore states only what it draws (`power_w.emit(...)`) and calls this;
    keeping each consequence as a second hardcoded number per activity is how a 25 W
    downlink ends up generating no heat at all.

    This mirrors stock Aerie's RealResource.minus(...) combinator (see merlin-framework's
    RealResource.java and examples/foo-missionmodel's
    `registrar.real("/batterySoC", this.source.minus(this.sink))`), which composes two
    resources' RealDynamics via component-wise {initial, rate} subtraction. Computed
    imperatively here, called after every change to either input, since pymerlin doesn't
    yet expose that resource-algebra layer to Python model authors -- same net numeric
    behavior either way.
    """
    net_w = mission.solar_generation_w.get() - mission.power_w.get()
    mission.battery_pct.set_rate((net_w / _BATTERY_CAPACITY_WH) * (100.0 / 3600.0))

    # Keep the evolving energy cell's carried rate in sync with the same net-power figure.
    # Emitting preserves the cumulative total already integrated and only swaps the rate,
    # so evolution resumes from the accumulated value rather than restarting -- the
    # emit-then-continue-evolving interaction worth exercising in a demo.
    cumulative_wh, _old_rate = mission.energy_wh.get()
    mission.energy_wh.emit((cumulative_wh, net_w))

    _recompute_heat_input(mission)
    _recompute_heatsink_target(mission)


@MissionModel
class Mission(MissionModelBase):
    def __init__(self, registrar, initial_battery_pct: float = 100.0, high_gain: bool = True):
        # Configuration (roadmap §7): constructor parameters after `registrar` become the
        # model's simulation-configuration schema, set per-plan in the Aerie UI. Downlink
        # transmits faster with the high-gain antenna; battery starts at the configured level.
        self.high_gain = high_gain

        self.power_w = registrar.cell(0.0)
        self.solar_generation_w = registrar.cell(0.0)
        self.data_volume_mb = registrar.linear(0.0)
        # Bounded: state of charge is a percentage, so it cannot integrate past 100 while
        # the panels generate a surplus, nor below 0 while draining. Without the bounds a
        # charging battery climbs to 130%, 200%, ... which is what Aerie's own model avoids
        # with ClampedIntegrator.
        self.battery_pct = registrar.linear(
            float(initial_battery_pct), minimum=0.0, maximum=100.0)
        self.mode = registrar.cell("IDLE")

        # --- Evolving cells (cell evolution) ---------------------------------------------
        #
        # These three exercise the `evolution=` path, and each covers a different part of
        # it. Activities still emit to them normally; evolution resumes from whatever was
        # last emitted, which is the interesting interaction to verify.

        # NONLINEAR evolution, tuple state (temperature_c, heat_input_w). Activities change
        # only the heat input; the temperature then ramps toward the resulting equilibrium
        # on its own -- no activity has to schedule the warm-up or the cooldown.
        #
        # `resolution` is needed precisely BECAUSE the approach is nonlinear: Aerie samples
        # a discrete resource only when something reads the cell, so a quiet stretch would
        # otherwise collapse into one profile segment holding just its endpoint and the
        # curve would render as a single cliff. 30s keeps the plotted profile smooth
        # without calling the evolution function excessively.
        #
        # `dynamics='real'` makes each 30-second segment a SLOPED CHORD rather than a flat
        # step: the Java shim evaluates the evolution function one resolution ahead to
        # get the secant slope, so the profile drawn in PlanDev follows the exponential
        # curve between samples. Physically appropriate because thermal approach to
        # equilibrium is a smooth exponential -- flat steps give the right endpoints but
        # show a staircase; real dynamics show the actual curve.
        self.temperature_c = registrar.cell(
            (_AMBIENT_TEMP_C, 0.0),
            evolution=_thermal_evolution,
            resolution=Duration.of(30, SECONDS),
            dynamics="real")

        # Electronics-box heatsink temperature, dynamics='real'. State is the tuple
        # (heatsink_temp_c, target_c): like temperature_c, the cell carries the
        # equilibrium it is relaxing toward, so evolution needs nothing beyond the cell
        # itself. The heatsink tracks the bus temperature but with a shorter time constant
        # (it is thermally coupled to the electronics directly, not through the structural
        # mass). Each segment is a sloped chord, so a warm-up shows as a smooth curve.
        self.heatsink_temp_c = registrar.cell(
            (_AMBIENT_TEMP_C, _AMBIENT_TEMP_C),
            evolution=_heatsink_evolution,
            resolution=Duration.of(30, SECONDS),
            dynamics="real")

        # TUPLE state: (cumulative_watt_hours, current_net_watts). Evolution integrates the
        # first element using the second, so the cell carries its own rate the way
        # test_simulation.py's linear_evolution does. Covers the non-scalar serialization
        # path (Java holds this as a GraalPy Value; _parse_value round-trips it on emit).
        self.energy_wh = registrar.cell((0.0, 0.0), evolution=_energy_evolution)

        # Duration-valued state -- the canonical evolution user from pymerlin.clock. Verifies
        # that a non-numeric, non-tuple Python type survives the boundary.
        self.mission_clock = clock(registrar)

        registrar.resource("/power_w", self.power_w)
        registrar.resource("/solar_generation_w", self.solar_generation_w)
        registrar.resource("/data_volume_mb", self.data_volume_mb)
        registrar.resource("/battery_pct", self.battery_pct)
        registrar.resource("/mode", self.mode)
        # Both evolving tuple cells expose their first element as the resource; the second
        # is the rate/input driving it, which is model internals rather than telemetry.
        #
        # Use .map() rather than a bare `lambda: cell.get()[0]`: a plain lambda is opaque,
        # so the resource cannot be traced back to the cell that backs it, and the Java
        # path (which registers resources per-cell) then never creates it at all. .map()
        # keeps that link.
        registrar.resource("/temperature_c", self.temperature_c.map(lambda s: s[0]))
        registrar.resource("/energy_wh", self.energy_wh.map(lambda s: s[0]))
        registrar.resource("/heatsink_temp_c", self.heatsink_temp_c.map(lambda s: s[0]))


@Mission.ActivityType
def collect_data(mission, data=1024):
    """Turns on the instrument, records data for 5 minutes, then powers off.
    The buffer fills continuously (linear RealDynamics) at data/duration MB/s over
    the collection window instead of jumping by `data` only when the activity ends.

    Powering the instrument on raises the HEAT INPUT, not the temperature directly: the
    structure then warms toward the new equilibrium on its own via temperature_c's
    evolution function, and cools again once the instrument powers off. Nothing here
    schedules the warm-up or the cooldown -- that autonomy is the point of cell evolution.
    """
    mission.mode.emit("COLLECTING")
    mission.power_w.emit(lambda x: x + 15.0)
    _recompute_power_effects(mission)

    duration_s = 5 * 60
    mission.data_volume_mb.set_rate(data / duration_s)

    delay("00:05:00")

    mission.data_volume_mb.set_rate(0.0)
    mission.power_w.emit(lambda x: x - 15.0)
    _recompute_power_effects(mission)
    mission.mode.emit("IDLE")

    spawn(compress_data(mission))


@Mission.ActivityType
def compress_data(mission):
    """Compresses data after collection — spawned as a child activity. Drains the
    buffer linearly (RealDynamics) down to 60% of its pre-compression volume over
    the 2-minute window, instead of jumping only when the activity ends."""
    mission.mode.emit("COMPRESSING")
    mission.power_w.emit(lambda x: x + 5.0)
    _recompute_power_effects(mission)

    duration_s = 2 * 60
    volume = mission.data_volume_mb.get()
    target = volume * 0.6
    mission.data_volume_mb.set_rate((target - volume) / duration_s)

    delay("00:02:00")

    mission.data_volume_mb.set_rate(0.0)
    mission.data_volume_mb.emit(target)
    mission.power_w.emit(lambda x: x - 5.0)
    _recompute_power_effects(mission)
    mission.mode.emit("IDLE")


@Mission.ActivityType
def downlink(mission):
    """Waits for data to be available, then transmits — draining the buffer at a
    constant rate over the 10-minute span so data_volume_mb ramps down continuously
    (linear RealDynamics) instead of stepping to zero only when the activity ends."""
    wait_until(lambda: mission.data_volume_mb.get() > 0.0)

    mission.mode.emit("DOWNLINKING")
    mission.power_w.emit(lambda x: x + 25.0)
    _recompute_power_effects(mission)

    # High-gain antenna drains the buffer in half the time (config-driven behavior).
    minutes = 5 if mission.high_gain else 10
    duration_s = minutes * 60
    volume = mission.data_volume_mb.get()
    mission.data_volume_mb.set_rate(-volume / duration_s)

    delay(f"00:{minutes:02d}:00")

    mission.data_volume_mb.set_rate(0.0)
    mission.data_volume_mb.emit(0.0)
    mission.power_w.emit(lambda x: x - 25.0)
    _recompute_power_effects(mission)
    mission.mode.emit("IDLE")


@Mission.ActivityType
def extend_solar_panels(mission, generation_w=20.0):
    """Deploys the solar panels. battery_pct's rate is derived from net power
    balance (this generation minus whatever else is currently drawing power), not
    an independent hardcoded charge rate — see _recompute_power_effects. Doesn't
    touch `mode`: that field tracks short-lived foreground activity state
    (COLLECTING, DOWNLINKING, ...), and panel deployment is a persistent
    background state that would otherwise get clobbered back to IDLE by the next
    unrelated activity."""
    mission.solar_generation_w.emit(generation_w)
    _recompute_power_effects(mission)


@Mission.ActivityType
def retract_solar_panels(mission):
    """Retracts the solar panels, removing their contribution to net power
    balance. battery_pct's rate is recomputed accordingly — it'll now reflect
    whatever else happens to be drawing power at the time, not simply "stop"."""
    mission.solar_generation_w.emit(0.0)
    _recompute_power_effects(mission)


@Mission.ActivityType
def safe_mode(mission):
    """Enters safe mode: powers down non-essential systems, waits, recovers."""
    mission.mode.emit("SAFE")
    mission.power_w.emit(3.0)
    _recompute_power_effects(mission)

    delay("00:30:00")

    mission.power_w.emit(0.0)
    _recompute_power_effects(mission)
    mission.mode.emit("IDLE")


@Mission.ActivityType
def thermal_soak(mission, heater_w=50.0):
    """Runs a `heater_w` survival heater, then does nothing but wait and observe.

    Like every other activity, this states only its POWER DRAW; the heat, and therefore
    the temperature rise, follows from that via _recompute_power_effects.

    This activity exists specifically to exercise cell evolution, and it is the clearest
    check that evolution is actually running: after the heater turns on there are NO
    further writes to temperature_c, so every subsequent change comes from the engine
    calling the evolution function as time advances. If evolution is not wired up the
    temperature stays flat and all the printed samples are identical -- an unmistakable
    failure signature.

    It also reads the mission clock and the evolving energy total, so a single activity
    covers all three evolving-cell value types (float, Duration, tuple).
    """
    clk = mission.mission_clock.start()

    mission.mode.emit("THERMAL_SOAK")
    mission.power_w.emit(lambda x: x + heater_w)
    _recompute_power_effects(mission)

    # Sample the warm-up at a few points. Each delay lets the engine step the cell forward;
    # the value should RISE toward the equilibrium the heater sets, quickly at first and
    # then more slowly -- equal-sized steps would mean evolution ran as linear, and a
    # constant would mean it did not run at all.
    for _ in range(4):
        delay("00:05:00")
        temp_c, _heat_w = mission.temperature_c.get()
        hs_c, _target_c = mission.heatsink_temp_c.get()
        print(f"[thermal_soak] t={clk.get()} "
              f"temp={temp_c:.2f}C "
              f"heatsink={hs_c:.2f}C "
              f"energy={mission.energy_wh.get()[0]:.3f}Wh")

    # Turn the heater off and let it coast back down on its own.
    mission.power_w.emit(lambda x: x - heater_w)
    _recompute_power_effects(mission)
    mission.mode.emit("IDLE")
