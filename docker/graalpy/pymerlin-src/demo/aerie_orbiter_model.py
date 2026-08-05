"""
Aerie Orbiter mission model — single-file version.

All subsystems (geometry, power, data, telecom, radar) are inlined here so
this file can be used as a standalone pymerlin model with no package imports.
"""

import math
from dataclasses import dataclass, field
from enum import Enum

from pymerlin import MissionModel, MissionModelBase
from pymerlin.model_actions import delay, spawn
from pymerlin.duration import Duration, SECONDS

try:
    from pymerlin.spice import SpiceKernel, duration_to_et, SPICE_AVAILABLE
except Exception:
    SpiceKernel = None
    SPICE_AVAILABLE = False

    def duration_to_et(duration, epoch_et=0.0):
        return epoch_et


# =============================================================================
# PEL states
# =============================================================================

class PELState(Enum):
    def __init__(self, cbe: float, mev: float):
        self.cbe = cbe
        self.mev = mev


class EPS_State(PELState):
    OFF  = (0.0,   0.0)
    ON   = (29.8, 29.8)
    SAFE = (32.8, 32.8)


class CDH_State(PELState):
    OFF = (0.0,   0.0)
    ON  = (30.0, 30.0)


class SSR_State(PELState):
    OFF      = (0.0,   0.0)
    ON       = (19.2, 19.2)
    DOWNLINK = (21.4, 21.4)


class Heaters_State(PELState):
    SURVIVAL = (114.4, 114.4)
    DOWNLINK = (173.4, 173.4)
    RADAR_ON = (137.6, 137.6)


class ADCS_State(PELState):
    OFF     = (0.0,    0.0)
    ON      = (79.0,  79.0)
    TURNING = (187.0, 187.0)


class HarnessLoss_State(PELState):
    OFF      = (0.0,   0.0)
    DOWNLINK = (38.4, 38.4)
    RADAR_ON = (38.2, 38.2)
    RADAR_OFF = (16.2, 16.2)
    TCM      = (61.9, 61.9)


class Prop_State(PELState):
    OFF      = (0.0,    0.0)
    DOWNLINK = (67.7,  67.7)
    TCM      = (249.6, 249.6)


class IDST_State(PELState):
    OFF      = (0.0,   0.0)
    ON       = (16.0, 16.0)
    DOWNLINK = (22.0, 22.0)


class X_TWTA_State(PELState):
    OFF = (0.0,    0.0)
    ON  = (173.4, 173.4)


class Ka_TWTA_State(PELState):
    OFF = (0.0,    0.0)
    ON  = (135.4, 135.4)


class Radar_State(PELState):
    OFF      = (0.0,    0.0)
    DOWNLINK = (198.2, 198.2)
    ON       = (543.2, 543.2)


class Imager_State(PELState):
    OFF = (0.0,   0.0)
    ON  = (13.0, 13.0)


class Radar_Heaters_State(PELState):
    OFF              = (0.0,    0.0)
    CRUISE_SURVIVAL  = (164.8, 164.8)
    SCIENCE_SURVIVAL = (9.5,    9.5)


class Imager_Heaters_State(PELState):
    OFF              = (0.0,    0.0)
    CRUISE_SURVIVAL  = (123.1, 123.1)
    IMAGER_ON        = (92.4,  92.4)
    DOWNLINK         = (14.0,  14.0)
    SCIENCE_SURVIVAL = (105.2, 105.2)


# =============================================================================
# Solar array
# =============================================================================

class ArrayDeploymentStates(Enum):
    UNDEPLOYED = "UNDEPLOYED"
    DEPLOYING  = "DEPLOYING"
    DEPLOYED   = "DEPLOYED"


def _val(source):
    if callable(source):
        return source()
    if hasattr(source, "get"):
        return source.get()
    return source


class GenericSolarArray:
    SOLAR_INTENSITY_AT_EARTH = 1360.8

    def __init__(self, registrar, sim_config,
                 solar_distance_au, array_to_sun_angle_deg, eclipse_factor):
        self.sim_config = sim_config
        self.solar_distance = solar_distance_au
        self.array_to_sun_angle = array_to_sun_angle_deg
        self.eclipse_factor = eclipse_factor

        self.deployment_state = registrar.cell(sim_config.deployment_state)
        self.array_cell_area = registrar.cell(
            sim_config.array_mech_area * sim_config.packing_factor)

        self.static_array_losses = (sim_config.cell_efficiency
                                    * sim_config.conversion_efficiency
                                    * sim_config.other_losses)

    def compute_solar_power(self, distance, cell_area, array_angle,
                            eclipse_loss, deployment_state) -> float:
        if deployment_state != ArrayDeploymentStates.DEPLOYED:
            return 0.0
        if distance <= 0.0:
            return 0.0
        return (self.SOLAR_INTENSITY_AT_EARTH / (distance * distance)
                * cell_area
                * self.static_array_losses
                * math.cos(math.radians(array_angle))
                * eclipse_loss)

    def power_production(self) -> float:
        return self.compute_solar_power(
            _val(self.solar_distance),
            self.array_cell_area.get(),
            _val(self.array_to_sun_angle),
            _val(self.eclipse_factor),
            self.deployment_state.get(),
        )

    def set_deployment_state(self, new_state: ArrayDeploymentStates):
        self.deployment_state.set(new_state)

    def register_states(self, registrar):
        registrar.resource("array.powerProduction", self.power_production)
        registrar.resource("spacecraft.solarDistance", lambda: _val(self.solar_distance))
        registrar.resource("spacecraft.arrayToSunAngle", lambda: _val(self.array_to_sun_angle))


# =============================================================================
# Battery
# =============================================================================

def _call(source):
    return source() if callable(source) else source


class BatteryModel:
    def __init__(self, registrar, name, sim_config,
                 total_load_getter, power_production_getter):
        self.name = name
        self.sim_config = sim_config
        self.bus_voltage = sim_config.bus_voltage
        self.capacity_ah = sim_config.battery_capacity
        self.capacity_wh = self.capacity_ah * self.bus_voltage
        self.power_demand = total_load_getter
        self.power_production = power_production_getter

        initial_charge_ah = self.capacity_ah * sim_config.initial_soc / 100.0
        self.charge_ah = registrar.cell(initial_charge_ah)

    def battery_current(self) -> float:
        return (_call(self.power_production) - _call(self.power_demand)) / self.bus_voltage

    def battery_charge(self) -> float:
        return self.charge_ah.get()

    def battery_soc(self) -> float:
        return self.charge_ah.get() / self.capacity_ah * 100.0

    def battery_full(self) -> bool:
        return self.battery_soc() >= 100.0

    def battery_empty(self) -> bool:
        return self.battery_soc() <= 0.0

    def integrate(self, dt_seconds: float):
        delta_ah = self.battery_current() * (dt_seconds / 3600.0)
        new_charge = self.charge_ah.get() + delta_ah
        new_charge = max(0.0, min(self.capacity_ah, new_charge))
        self.charge_ah.set(new_charge)

    def register_states(self, registrar):
        p = self.name + "battery."
        registrar.resource(p + "batteryCurrent", self.battery_current)
        registrar.resource(p + "batterySOC", self.battery_soc)
        registrar.resource(p + "batteryCharge", self.battery_charge)
        registrar.resource(p + "batteryFull", self.battery_full)
        registrar.resource(p + "batteryEmpty", self.battery_empty)


# =============================================================================
# PEL model
# =============================================================================

_COMPONENTS = [
    ("eps",           EPS_State,            EPS_State.OFF),
    ("cdh",           CDH_State,            CDH_State.OFF),
    ("ssr",           SSR_State,            SSR_State.OFF),
    ("heaters",       Heaters_State,        Heaters_State.SURVIVAL),
    ("adcs",          ADCS_State,           ADCS_State.OFF),
    ("harnessloss",   HarnessLoss_State,    HarnessLoss_State.OFF),
    ("prop",          Prop_State,           Prop_State.OFF),
    ("idst",          IDST_State,           IDST_State.OFF),
    ("x_twta",        X_TWTA_State,         X_TWTA_State.OFF),
    ("ka_twta",       Ka_TWTA_State,        Ka_TWTA_State.OFF),
    ("radar",         Radar_State,          Radar_State.OFF),
    ("imager",        Imager_State,         Imager_State.OFF),
    ("radar_heaters", Radar_Heaters_State,  Radar_Heaters_State.OFF),
    ("imager_heaters",Imager_Heaters_State, Imager_Heaters_State.OFF),
]


class PELModel:
    def __init__(self, registrar):
        self._registrar = registrar
        self.state_cells = {}
        for name, _enum, initial in _COMPONENTS:
            cell = registrar.cell(initial)
            self.state_cells[name] = cell
            setattr(self, name + "State", cell)

    def cbe_total_load(self) -> float:
        return sum(cell.get().cbe for cell in self.state_cells.values())

    def mev_total_load(self) -> float:
        return sum(cell.get().mev for cell in self.state_cells.values())

    def register_states(self, registrar):
        for name, _enum, _initial in _COMPONENTS:
            cell = self.state_cells[name]
            registrar.resource(name + "State", (lambda c: lambda: c.get().name)(cell))
        registrar.resource("spacecraft.cbeLoad", self.cbe_total_load)
        registrar.resource("spacecraft.mevLoad", self.mev_total_load)


# =============================================================================
# Data model
# =============================================================================

MAX_BOUND = float("inf")


class Bucket:
    def __init__(self, registrar, name, volume_ub=MAX_BOUND):
        self.name = name
        self.volume = registrar.cell(0.0)
        self.received = registrar.cell(0.0)
        self.removed = registrar.cell(0.0)
        self.desired_receive_rate = registrar.cell(0.0)
        self.desired_remove_rate = registrar.cell(0.0)
        self._volume_ub = volume_ub

    @property
    def volume_ub(self) -> float:
        return self._volume_ub() if callable(self._volume_ub) else self._volume_ub

    def register_states(self, registrar):
        n = self.name
        registrar.resource(n + ".volume", self.volume)
        registrar.resource(n + ".receivedVolume", self.received)
        registrar.resource(n + ".removedVolume", self.removed)
        registrar.resource(n + ".desiredReceiveRate", self.desired_receive_rate)
        registrar.resource(n + ".desiredRemoveRate", self.desired_remove_rate)


class Data:
    def __init__(self, registrar, num_buckets, max_volume_getter, data_rate_getter):
        self._registrar = registrar
        self.max_volume = max_volume_getter
        self.data_rate = data_rate_getter

        self.onboard_buckets = [Bucket(registrar, f"scBin{i}") for i in range(num_buckets)]
        self.ground_buckets  = [Bucket(registrar, f"gndBin{i}") for i in range(num_buckets)]

        self.downlink_active  = registrar.cell(False)
        self.volume_requested = registrar.cell(0.0)

    def get_onboard_bin(self, i: int) -> Bucket:
        return self.onboard_buckets[i]

    def get_ground_bin(self, i: int) -> Bucket:
        return self.ground_buckets[i]

    def onboard_volume(self) -> float:
        return sum(b.volume.get() for b in self.onboard_buckets)

    def ground_received(self) -> float:
        return sum(b.received.get() for b in self.ground_buckets)

    def integrate(self, dt_seconds: float):
        cap = self.max_volume()
        used = 0.0
        for b in self.onboard_buckets:
            net_rate = b.desired_receive_rate.get() - b.desired_remove_rate.get()
            new_vol = b.volume.get() + net_rate * dt_seconds
            remaining_cap = max(0.0, cap - used)
            upper = min(b.volume_ub, remaining_cap)
            new_vol = max(0.0, min(new_vol, upper))
            stored = new_vol - b.volume.get()
            if stored > 0:
                b.received.set(b.received.get() + stored)
            elif stored < 0:
                b.removed.set(b.removed.get() - stored)
            b.volume.set(new_vol)
            used += new_vol

        if self.downlink_active.get():
            bits_left = self.data_rate() * dt_seconds
            vol_goal = self.volume_requested.get()
            if vol_goal > 0:
                bits_left = min(bits_left, vol_goal)
            total_moved = 0.0
            for sc, gnd in zip(self.onboard_buckets, self.ground_buckets):
                if bits_left <= 0:
                    break
                available = min(sc.received.get() - gnd.received.get(), sc.volume.get())
                move = max(0.0, min(bits_left, available))
                if move > 0:
                    gnd.received.set(gnd.received.get() + move)
                    bits_left -= move
                    total_moved += move
            if vol_goal > 0:
                self.volume_requested.set(max(0.0, vol_goal - total_moved))

    def register_states(self, registrar):
        for b in self.onboard_buckets:
            b.register_states(registrar)
        for b in self.ground_buckets:
            b.register_states(registrar)
        registrar.resource("onboard.volume", self.onboard_volume)
        registrar.resource("ground.receivedVolume", self.ground_received)
        registrar.resource("playbackDataRate", lambda: self.data_rate())
        registrar.resource("volumeRequestedToDownlink", self.volume_requested)


# =============================================================================
# Telecom model
# =============================================================================

BOLTZMANN_CONSTANT = 1.380649e-23


class TelecomModel:
    def __init__(self, registrar):
        self.downlink_bit_rate = registrar.cell(0.0)

    def register_resources(self, registrar):
        registrar.resource("downlinkBitRate", self.downlink_bit_rate)


# =============================================================================
# Radar model
# =============================================================================

class RadarDataCollectionMode(Enum):
    OFF     = 0.0
    LOW_RES = 0.1
    MED_RES = 1.0
    HI_RES  = 4.0

    @property
    def data_rate(self) -> float:
        return self.value


class RadarModel:
    def __init__(self, registrar):
        self.radar_data_mode = registrar.cell(RadarDataCollectionMode.OFF)

    def radar_data_rate(self) -> float:
        return self.radar_data_mode.get().data_rate

    def register_states(self, registrar):
        registrar.resource("RadarDataMode", lambda: self.radar_data_mode.get().name)
        registrar.resource("RadarDataRate", self.radar_data_rate)


# =============================================================================
# Geometry model
# =============================================================================

AU_TO_KM = 149597870.691


class EclipseTypes(Enum):
    NONE    = "NONE"
    PARTIAL = "PARTIAL"
    ANNULAR = "ANNULAR"
    FULL    = "FULL"


class Body:
    def __init__(self, name, naif_id, frame, radius_km, mu,
                 calculate_altitude=False, calculate_earth_sc_angle=False,
                 calculate_beta_angle=False, calculate_orbit_parameters=False):
        self.name = name
        self.naif_id = naif_id
        self.frame = frame
        self.radius_km = radius_km
        self.mu = mu
        self.calculate_altitude = calculate_altitude
        self.calculate_earth_sc_angle = calculate_earth_sc_angle
        self.calculate_beta_angle = calculate_beta_angle
        self.calculate_orbit_parameters = calculate_orbit_parameters


DEFAULT_BODIES = {
    "SUN":   Body("SUN",   10, "IAU_SUN",   695700.0, 1.32712440018e11),
    "EARTH": Body("EARTH", 399, "IAU_EARTH", 6378.14,  3.986004418e5),
    "MARS":  Body("MARS",  499, "IAU_MARS",  3396.19,  4.282837e4,
                  calculate_altitude=True, calculate_earth_sc_angle=True,
                  calculate_beta_angle=True, calculate_orbit_parameters=True),
}


class GeometryResources:
    def __init__(self, registrar, bodies):
        self.bodies = bodies

        self.spacecraft_body_range    = {}
        self.spacecraft_body_speed    = {}
        self.body_half_angle_size     = {}
        self.sun_spacecraft_body_angle  = {}
        self.sun_body_spacecraft_angle  = {}
        self.spacecraft_altitude      = {}
        self.beta_angle_by_body       = {}
        self.earth_spacecraft_body_angle = {}
        self.orbit_inclination_by_body   = {}
        self.orbit_period_by_body        = {}
        self.spacecraft_eclipse_by_body  = {}
        self.periapsis = {}
        self.apoapsis  = {}

        for name, body in bodies.items():
            self.spacecraft_body_range[name]    = registrar.cell(1.5 * AU_TO_KM if name == "SUN" else 1.0e5)
            self.spacecraft_body_speed[name]    = registrar.cell(0.0)
            self.body_half_angle_size[name]     = registrar.cell(0.0)
            self.sun_spacecraft_body_angle[name] = registrar.cell(0.0)
            self.sun_body_spacecraft_angle[name] = registrar.cell(0.0)
            self.spacecraft_eclipse_by_body[name] = registrar.cell(EclipseTypes.NONE)
            self.periapsis[name] = registrar.cell(False)
            self.apoapsis[name]  = registrar.cell(False)
            if body.calculate_altitude:
                self.spacecraft_altitude[name] = registrar.cell(300.0)
            if body.calculate_beta_angle:
                self.beta_angle_by_body[name] = registrar.cell(0.0)
            if body.calculate_earth_sc_angle:
                self.earth_spacecraft_body_angle[name] = registrar.cell(0.0)
            if body.calculate_orbit_parameters:
                self.orbit_inclination_by_body[name] = registrar.cell(0.0)
                self.orbit_period_by_body[name]      = registrar.cell(0.0)

        self.any_spacecraft_eclipse          = registrar.cell(EclipseTypes.NONE)
        self.occultation                     = registrar.cell(0)
        self.fraction_of_sun_not_in_eclipse  = registrar.cell(1.0)
        self.spacecraft_declination          = registrar.cell(0.0)
        self.spacecraft_right_ascension      = registrar.cell(0.0)

    def spacecraft_sun_range_au(self) -> float:
        return self.spacecraft_body_range["SUN"].get() / AU_TO_KM

    def register_states(self, registrar):
        for name in self.bodies:
            registrar.resource(f"SpacecraftBodyRange_{name}", self.spacecraft_body_range[name])
            registrar.resource(f"SpacecraftBodySpeed_{name}", self.spacecraft_body_speed[name])
            registrar.resource(f"BodyHalfAngleSize_{name}", self.body_half_angle_size[name])
            registrar.resource(f"SunSpacecraftBodyAngle_{name}", self.sun_spacecraft_body_angle[name])
            registrar.resource(f"SunBodySpacecraftAngle_{name}", self.sun_body_spacecraft_angle[name])
            registrar.resource(
                f"SpacecraftEclipseByBody_{name}",
                (lambda c: lambda: c.get().name)(self.spacecraft_eclipse_by_body[name]))
            registrar.resource(f"Periapsis_{name}", self.periapsis[name])
            registrar.resource(f"Apoapsis_{name}", self.apoapsis[name])
            if name in self.spacecraft_altitude:
                registrar.resource(f"SpacecraftAltitude_{name}", self.spacecraft_altitude[name])
            if name in self.beta_angle_by_body:
                registrar.resource(f"BetaAngle_{name}", self.beta_angle_by_body[name])
            if name in self.earth_spacecraft_body_angle:
                registrar.resource(f"EarthSpacecraftAngle_{name}", self.earth_spacecraft_body_angle[name])
            if name in self.orbit_inclination_by_body:
                registrar.resource(f"orbitInclinationByBody_{name}", self.orbit_inclination_by_body[name])
                registrar.resource(f"orbitPeriodByBody_{name}", self.orbit_period_by_body[name])
        registrar.resource("AnySpacecraftEclipse", lambda: self.any_spacecraft_eclipse.get().name)
        registrar.resource("Occultation", self.occultation)
        registrar.resource("FractionOfSunNotInEclipse", self.fraction_of_sun_not_in_eclipse)
        registrar.resource("SpacecraftBodyRange_SUN_AU", self.spacecraft_sun_range_au)


class GeometryCalculator:
    def __init__(self, resources, bodies, epoch_et=0.0,
                 spacecraft="MRO", frame="J2000", spice_kernel=None):
        self.res = resources
        self.bodies = bodies
        self.epoch_et = epoch_et
        self.spacecraft = spacecraft
        self.frame = frame
        self.spice = spice_kernel

    def compute(self, elapsed_duration):
        if not (SPICE_AVAILABLE and self.spice is not None):
            return
        et = duration_to_et(elapsed_duration, self.epoch_et)
        for name, body in self.bodies.items():
            self._compute_body(body, et)

    def _compute_body(self, body, et):
        state = self.spice.state(body.name, self.spacecraft, self.frame, et)
        r = (state[0], state[1], state[2])
        v = (state[3], state[4], state[5])
        r_norm = _norm(r)
        self.res.spacecraft_body_range[body.name].set(float(r_norm))
        self.res.spacecraft_body_speed[body.name].set(float(_norm(v)))
        if r_norm > 0:
            self.res.body_half_angle_size[body.name].set(
                math.degrees(math.asin(min(1.0, body.radius_km / r_norm))))

        if body.name != "SUN":
            sun_wrt_body = self.spice.position("SUN", body.name, self.frame, et)
            self.res.sun_spacecraft_body_angle[body.name].set(
                math.degrees(_angle(_add(r, sun_wrt_body), r)))
            self.res.sun_body_spacecraft_angle[body.name].set(
                math.degrees(_angle(_scale(r, -1.0), sun_wrt_body)))

        if body.calculate_altitude:
            self.res.spacecraft_altitude[body.name].set(float(r_norm - body.radius_km))

        if body.calculate_beta_angle and body.name != "SUN":
            normal = _normalize(_cross(r, v))
            sun_wrt_body = self.spice.position("SUN", body.name, self.frame, et)
            self.res.beta_angle_by_body[body.name].set(
                math.degrees(_angle(normal, _scale(sun_wrt_body, -1.0))) - 90.0)

        if body.calculate_earth_sc_angle:
            earth_wrt_sc = self.spice.position("EARTH", self.spacecraft, self.frame, et)
            self.res.earth_spacecraft_body_angle[body.name].set(
                math.degrees(_angle(earth_wrt_sc, r)))

        if body.calculate_orbit_parameters:
            incl, period, ecc = _orbit_elements(r, v, body.mu)
            if ecc is not None and ecc < 1.0:
                self.res.orbit_inclination_by_body[body.name].set(float(math.degrees(incl)))
                self.res.orbit_period_by_body[body.name].set(float(period))


def _add(a, b):
    return (a[0] + b[0], a[1] + b[1], a[2] + b[2])

def _scale(a, s):
    return (a[0] * s, a[1] * s, a[2] * s)

def _dot(a, b):
    return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]

def _cross(a, b):
    return (a[1] * b[2] - a[2] * b[1],
            a[2] * b[0] - a[0] * b[2],
            a[0] * b[1] - a[1] * b[0])

def _norm(a):
    return math.sqrt(_dot(a, a))

def _normalize(a):
    n = _norm(a)
    return _scale(a, 1.0 / n) if n > 0 else a

def _angle(a, b):
    na, nb = _norm(a), _norm(b)
    if na == 0 or nb == 0:
        return 0.0
    c = max(-1.0, min(1.0, _dot(a, b) / (na * nb)))
    return math.acos(c)

def _orbit_elements(r, v, mu):
    r_norm = _norm(r)
    v2 = _dot(v, v)
    if r_norm == 0 or mu == 0:
        return 0.0, 0.0, None
    energy = v2 / 2.0 - mu / r_norm
    if energy == 0:
        return 0.0, 0.0, None
    a = -mu / (2.0 * energy)
    h = _cross(r, v)
    h_norm = _norm(h)
    inclination = math.acos(max(-1.0, min(1.0, h[2] / h_norm))) if h_norm > 0 else 0.0
    e_vec = _scale(_add(_scale(r, v2 - mu / r_norm), _scale(v, -_dot(r, v))), 1.0 / mu)
    ecc = _norm(e_vec)
    period = 2.0 * math.pi * math.sqrt(a ** 3 / mu) if a > 0 and ecc < 1.0 else 0.0
    return inclination, period, ecc


# =============================================================================
# Configuration
# =============================================================================

@dataclass
class BatterySimConfig:
    battery_capacity: float = 94.5
    bus_voltage: float      = 28.0
    initial_soc: float      = 100.0

    @staticmethod
    def default() -> "BatterySimConfig":
        return BatterySimConfig()


@dataclass
class SolarArraySimConfig:
    deployment_state:      ArrayDeploymentStates = ArrayDeploymentStates.DEPLOYED
    array_mech_area:       float = 16.0
    packing_factor:        float = 1.0
    cell_efficiency:       float = 0.295
    conversion_efficiency: float = 0.9
    other_losses:          float = 0.9

    @staticmethod
    def default() -> "SolarArraySimConfig":
        return SolarArraySimConfig()


@dataclass
class PowerModelSimConfig:
    battery_config:    BatterySimConfig    = field(default_factory=BatterySimConfig.default)
    solar_array_config: SolarArraySimConfig = field(default_factory=SolarArraySimConfig.default)

    @staticmethod
    def default() -> "PowerModelSimConfig":
        return PowerModelSimConfig()


@dataclass
class DataModelSimConfig:
    initial_max_volume: float = 50e9
    initial_datarate:   float = 1e6

    @staticmethod
    def default() -> "DataModelSimConfig":
        return DataModelSimConfig()


@dataclass
class Configuration:
    spice_spacecraft_id: int   = -74
    power_config: PowerModelSimConfig = field(default_factory=PowerModelSimConfig.default)
    data_config:  DataModelSimConfig  = field(default_factory=DataModelSimConfig.default)
    off_point_angle: float = 70.0

    @staticmethod
    def default() -> "Configuration":
        return Configuration()


# =============================================================================
# Mission model
# =============================================================================

@MissionModel
class Mission(MissionModelBase):
    CONFIG: Configuration = Configuration.default()
    EPOCH_UTC: str  = "2026-04-05T12:00:00Z"
    SPACECRAFT: str = "MRO"
    FRAME: str      = "J2000"
    KERNEL_PATHS    = None

    def __init__(self, registrar):
        config = self.CONFIG or Configuration.default()

        self.bodies       = DEFAULT_BODIES
        self.geometry_res = GeometryResources(registrar, self.bodies)

        spice_kernel = None
        epoch_et = 0.0
        if SPICE_AVAILABLE and self.KERNEL_PATHS:
            spice_kernel = SpiceKernel(registrar, kernel_paths=list(self.KERNEL_PATHS))
            spice_kernel.load_kernels()
            epoch_et = spice_kernel.utc_to_et(self.EPOCH_UTC)
        self.geometry_calc = GeometryCalculator(
            self.geometry_res, self.bodies, epoch_et=epoch_et,
            spacecraft=self.SPACECRAFT, frame=self.FRAME, spice_kernel=spice_kernel)

        self.off_sun_angle = registrar.cell(config.off_point_angle)

        self.pel   = PELModel(registrar)
        self.array = GenericSolarArray(
            registrar,
            config.power_config.solar_array_config,
            solar_distance_au=self.geometry_res.spacecraft_sun_range_au,
            array_to_sun_angle_deg=self.off_sun_angle.get,
            eclipse_factor=self.geometry_res.fraction_of_sun_not_in_eclipse.get,
        )
        self.cbe_battery = BatteryModel(
            registrar, "cbe", config.power_config.battery_config,
            self.pel.cbe_total_load, self.array.power_production)
        self.mev_battery = BatteryModel(
            registrar, "mev", config.power_config.battery_config,
            self.pel.mev_total_load, self.array.power_production)

        self.data_rate  = registrar.cell(config.data_config.initial_datarate)
        self.max_volume = registrar.cell(config.data_config.initial_max_volume)
        self.data = Data(registrar, num_buckets=2,
                         max_volume_getter=self.max_volume.get,
                         data_rate_getter=self.data_rate.get)

        self.telecom = TelecomModel(registrar)
        self.radar   = RadarModel(registrar)

        self.geometry_res.register_states(registrar)
        registrar.resource("offSunAngle", self.off_sun_angle)
        self.pel.register_states(registrar)
        self.array.register_states(registrar)
        self.cbe_battery.register_states(registrar)
        self.mev_battery.register_states(registrar)
        self.data.register_states(registrar)
        self.telecom.register_resources(registrar)
        self.radar.register_states(registrar)

    def get_data(self) -> Data:
        return self.data


# =============================================================================
# Helpers
# =============================================================================

_ECLIPSE_SEVERITY = {
    EclipseTypes.NONE: 0, EclipseTypes.PARTIAL: 1,
    EclipseTypes.ANNULAR: 1, EclipseTypes.FULL: 2,
}


def _worst_eclipse(model) -> EclipseTypes:
    worst = EclipseTypes.NONE
    for cell in model.geometry_res.spacecraft_eclipse_by_body.values():
        t = cell.get()
        if _ECLIPSE_SEVERITY[t] > _ECLIPSE_SEVERITY[worst]:
            worst = t
    return worst


def _seconds(duration_str) -> float:
    return Duration.from_string(duration_str).to_number_in(SECONDS)


# =============================================================================
# Physics daemon
# =============================================================================

@Mission.ActivityType
def advance_state(mission, duration_us: int = 86400000000, step_us: int = 60000000):
    """Step geometry, battery charge and data volumes forward across the plan."""
    duration_us, step_us = int(duration_us), int(step_us)
    total = duration_us / 1_000_000
    dt    = step_us / 1_000_000
    elapsed = 0.0
    while elapsed < total:
        mission.geometry_calc.compute(Duration.of(int(elapsed), SECONDS))
        mission.cbe_battery.integrate(dt)
        mission.mev_battery.integrate(dt)
        mission.data.integrate(dt)
        delay(Duration.of(step_us, Duration.MICROSECONDS))
        elapsed += dt


# =============================================================================
# Geometry activities
# =============================================================================

@Mission.ActivityType
def Apoapsis(mission, body="MARS"):
    mission.geometry_res.apoapsis[body].set(True)
    delay(Duration.SECOND)
    mission.geometry_res.apoapsis[body].set(False)


@Mission.ActivityType
def Periapsis(mission, body="MARS"):
    mission.geometry_res.periapsis[body].set(True)
    delay(Duration.SECOND)
    mission.geometry_res.periapsis[body].set(False)


@Mission.ActivityType
def EnterOccultation(mission, body="MARS", station="DSS-24"):
    mission.geometry_res.occultation.add(1)


@Mission.ActivityType
def ExitOccultation(mission, body="MARS", station="DSS-24"):
    mission.geometry_res.occultation.add(-1)


@Mission.ActivityType
def SpacecraftEnterEclipse(mission, body: str = "MARS", type: str = "FULL", duration: int = 1800000000):
    """Enter an eclipse of ``type``, ramping FractionOfSunNotInEclipse over 10 segments."""
    duration = int(duration)
    new_type  = EclipseTypes[type]
    prior_type = mission.geometry_res.spacecraft_eclipse_by_body[body].get()
    mission.geometry_res.spacecraft_eclipse_by_body[body].set(new_type)
    mission.geometry_res.any_spacecraft_eclipse.set(new_type)

    if _worst_eclipse(mission) == EclipseTypes.FULL:
        mission.geometry_res.fraction_of_sun_not_in_eclipse.set(0.0)
    elif new_type == EclipseTypes.NONE:
        mission.geometry_res.fraction_of_sun_not_in_eclipse.set(1.0)
    else:
        num_segments = 10
        seg_seconds  = (duration / 1_000_000) / num_segments
        for i in range(num_segments):
            if prior_type == EclipseTypes.NONE:
                frac = 1.0 - (i / num_segments)
            else:
                frac = i / num_segments
            mission.geometry_res.fraction_of_sun_not_in_eclipse.set(frac)
            delay(Duration.of(int(seg_seconds * 1_000_000), Duration.MICROSECONDS))


@Mission.ActivityType
def SpacecraftExitEclipse(mission, body="MARS"):
    mission.geometry_res.spacecraft_eclipse_by_body[body].set(EclipseTypes.NONE)
    worst = _worst_eclipse(mission)
    mission.geometry_res.any_spacecraft_eclipse.set(worst)
    if worst == EclipseTypes.NONE:
        mission.geometry_res.fraction_of_sun_not_in_eclipse.set(1.0)
    delay(Duration.SECOND)


@Mission.ActivityType
def AddSpacecraftEclipses(mission, search_duration_us: int = 86400000000,
                          observer: str = "SUN", occulting_body: str = "MARS", useDSK: bool = False):
    """Placeholder for SPICE occultation search; schedule enter/exit activities directly."""
    delay(Duration.of(int(search_duration_us), Duration.MICROSECONDS))


# =============================================================================
# Power activities
# =============================================================================

@Mission.ActivityType
def SolarArrayDeployment(mission, duration_us: int = 1800000000):
    duration_us = int(duration_us)
    mission.array.set_deployment_state(ArrayDeploymentStates.DEPLOYING)
    delay(Duration.of(duration_us, Duration.MICROSECONDS))
    mission.array.set_deployment_state(ArrayDeploymentStates.DEPLOYED)


# =============================================================================
# Data activities
# =============================================================================

@Mission.ActivityType
def ChangeDataGenerationRate(mission, bin: int = 0, rate: float = 0.0):
    """Instantly set the net generation rate of a bin (bps). Positive => receive."""
    bin, rate = int(bin), float(rate)
    if rate == 0.0:
        return
    b = mission.data.get_onboard_bin(bin)
    if rate > 0:
        b.desired_receive_rate.set(rate)
        b.desired_remove_rate.set(0.0)
    else:
        b.desired_receive_rate.set(0.0)
        b.desired_remove_rate.set(-rate)


@Mission.ActivityType
def GenerateData(mission, bin: int = 0, rate: float = None, volume: float = None, duration_s: int = None):
    """
    Generate data in a bin. Specify at least two of rate (bps), volume (bits),
    duration_s (seconds); the third is derived.
    """
    rate, volume, dur_s = _derive_generate(rate, volume, duration_s)
    b = mission.data.get_onboard_bin(bin)
    b.desired_receive_rate.set(b.desired_receive_rate.get() + rate)
    delay(Duration.of(int(dur_s), SECONDS))
    b.desired_receive_rate.set(b.desired_receive_rate.get() - rate)


def _derive_generate(rate, volume, duration_s):
    if rate is not None:
        rate = float(rate)
    if volume is not None:
        volume = float(volume)
    dur_s = float(duration_s) if duration_s is not None else None
    have = (rate is not None) + (volume is not None) + (dur_s is not None)
    if have < 2:
        raise ValueError("Two or three of rate, volume, duration must be specified.")
    if rate is not None and volume is not None and dur_s is None:
        dur_s = volume / rate
    elif rate is not None and volume is None and dur_s is not None:
        volume = rate * dur_s
    elif rate is None and volume is not None and dur_s is not None:
        rate = volume / dur_s
    return rate, volume, dur_s


@Mission.ActivityType
def DeleteData(mission, volume: float = float("inf"), limit_to_sent_data: bool = True, bin: int = 0):
    """Delete up to ``volume`` bits from a bin, optionally only already-downlinked data."""
    volume, bin = float(volume), int(bin)
    if isinstance(limit_to_sent_data, str):
        limit_to_sent_data = limit_to_sent_data.lower() not in ("false", "0", "")
    sc  = mission.data.get_onboard_bin(bin)
    gnd = mission.data.get_ground_bin(bin)
    current_volume    = sc.volume.get()
    not_yet_downlinked = sc.received.get() - gnd.received.get()
    already_downlinked = current_volume - not_yet_downlinked
    cap     = already_downlinked if limit_to_sent_data else float("inf")
    deleted = max(0.0, min(volume, min(current_volume, cap)))
    sc.volume.set(current_volume - deleted)
    sc.removed.set(sc.removed.get() + deleted)


@Mission.ActivityType
def PlaybackData(mission, volume: float = None, duration_us: int = None):
    """Downlink data for a fixed duration or until a volume goal is met."""
    if volume is not None:
        volume = float(volume)
    if duration_us is not None:
        duration_us = int(duration_us)
    if volume is not None and volume == 0.0:
        return
    mission.data.downlink_active.set(True)
    if volume is not None:
        mission.data.volume_requested.set(volume)
        rate = mission.data.data_rate()
        window_us = int(volume / rate * 1_000_000) if rate > 0 else 0
    else:
        window_us = duration_us if duration_us is not None else 0
    delay(Duration.of(window_us, Duration.MICROSECONDS))
    mission.data.downlink_active.set(False)
    mission.data.volume_requested.set(0.0)


@Mission.ActivityType
def ReprioritizeData(mission, volume: float = 0.0, bin: int = 0, new_bin: int = 1):
    """Move up to ``volume`` bits from one onboard bin to another."""
    volume, bin, new_bin = float(volume), int(bin), int(new_bin)
    from_bin = mission.data.get_onboard_bin(bin)
    to_bin   = mission.data.get_onboard_bin(new_bin)
    current_volume = from_bin.volume.get()
    receivable     = to_bin.volume_ub - to_bin.volume.get()
    moved = max(0.0, min(volume, min(current_volume, receivable)))
    from_bin.volume.set(current_volume - moved)
    from_bin.removed.set(from_bin.removed.get() + moved)
    to_bin.volume.set(to_bin.volume.get() + moved)
    to_bin.received.set(to_bin.received.get() + moved)


# =============================================================================
# Telecom activity
# =============================================================================

@Mission.ActivityType
def Downlink(mission, duration: int = 3600000000, bit_rate: float = 1000.0):
    """Perform a downlink pass at ``bit_rate`` kbps (<= 2000)."""
    duration_us, bit_rate = int(duration), float(bit_rate)
    if bit_rate > 2000.0:
        raise ValueError("Collection rate is beyond buffer limit of 2000 kbps")

    mission.data_rate.set(bit_rate * 1000.0)
    spawn(PlaybackData(mission, duration_us=duration_us))

    mission.pel.x_twtaState.set(X_TWTA_State.ON)
    mission.pel.ka_twtaState.set(Ka_TWTA_State.ON)
    mission.pel.ssrState.set(SSR_State.DOWNLINK)
    mission.pel.idstState.set(IDST_State.DOWNLINK)
    mission.pel.propState.set(Prop_State.DOWNLINK)
    prev_radar  = mission.pel.radarState.get()
    mission.pel.radarState.set(Radar_State.DOWNLINK)
    mission.pel.radar_heatersState.set(Radar_Heaters_State.OFF)
    prev_imager = mission.pel.imagerState.get()
    mission.pel.imager_heatersState.set(Imager_Heaters_State.DOWNLINK)
    mission.pel.heatersState.set(Heaters_State.DOWNLINK)
    mission.pel.harnesslossState.set(HarnessLoss_State.DOWNLINK)

    delay(Duration.of(duration_us, Duration.MICROSECONDS))

    spawn(DeleteData(mission, volume=float("inf"), limit_to_sent_data=True, bin=0))

    mission.pel.x_twtaState.set(X_TWTA_State.OFF)
    mission.pel.ka_twtaState.set(Ka_TWTA_State.OFF)
    mission.pel.ssrState.set(SSR_State.ON)
    mission.pel.idstState.set(IDST_State.ON)
    mission.pel.propState.set(Prop_State.OFF)

    if prev_radar == Radar_State.OFF:
        mission.pel.radarState.set(Radar_State.OFF)
        mission.pel.radar_heatersState.set(Radar_Heaters_State.SCIENCE_SURVIVAL)
        mission.pel.heatersState.set(Heaters_State.SURVIVAL)
        mission.pel.harnesslossState.set(HarnessLoss_State.RADAR_OFF)
    else:
        mission.pel.radarState.set(Radar_State.ON)
        mission.pel.radar_heatersState.set(Radar_Heaters_State.OFF)
        mission.pel.heatersState.set(Heaters_State.RADAR_ON)
        mission.pel.harnesslossState.set(HarnessLoss_State.RADAR_ON)

    if prev_imager == Imager_State.OFF:
        mission.pel.imagerState.set(Imager_State.OFF)
        mission.pel.imager_heatersState.set(Imager_Heaters_State.SCIENCE_SURVIVAL)
    else:
        mission.pel.imagerState.set(Imager_State.ON)
        mission.pel.imager_heatersState.set(Imager_Heaters_State.IMAGER_ON)


# =============================================================================
# Radar activities
# =============================================================================

@Mission.ActivityType
def Radar_On(mission):
    mission.pel.radarState.set(Radar_State.ON)
    delay(Duration.SECOND)


@Mission.ActivityType
def Radar_Off(mission):
    mission.pel.radarState.set(Radar_State.OFF)
    spawn(ChangeRadarDataMode(mission, mode="OFF"))
    delay(Duration.SECOND)


@Mission.ActivityType
def ChangeRadarDataMode(mission, mode="LOW_RES"):
    """Set the radar data-collection mode and feed its data rate into data bin 0."""
    new_mode = RadarDataCollectionMode[mode]
    new_rate = new_mode.data_rate
    bin0 = mission.data.get_onboard_bin(0)
    if new_rate > 0:
        bin0.desired_receive_rate.set(new_rate * 1e6)
        bin0.desired_remove_rate.set(0.0)
    else:
        bin0.desired_receive_rate.set(0.0)
        bin0.desired_remove_rate.set(0.0)
    mission.radar.radar_data_mode.set(new_mode)


# =============================================================================
# Demo scenario
# =============================================================================

@Mission.ActivityType
def run_orbiter_demo(mission, step_us: int = 300000000):
    """Self-contained operational timeline: deploy → radar science → eclipse → downlink."""
    step_us = int(step_us)
    dt = step_us / 1_000_000

    def step(minutes):
        for _ in range(int(minutes * 60 / dt)):
            mission.geometry_calc.compute(Duration.of(0, SECONDS))
            mission.cbe_battery.integrate(dt)
            mission.mev_battery.integrate(dt)
            mission.data.integrate(dt)
            delay(Duration.of(step_us, Duration.MICROSECONDS))

    mission.array.set_deployment_state(ArrayDeploymentStates.DEPLOYED)
    step(30)

    mission.pel.radarState.set(Radar_State.ON)
    mission.pel.heatersState.set(Heaters_State.RADAR_ON)
    mission.radar.radar_data_mode.set(RadarDataCollectionMode.HI_RES)
    mission.data.get_onboard_bin(0).desired_receive_rate.set(
        RadarDataCollectionMode.HI_RES.data_rate * 1e6)
    step(60)

    mission.geometry_res.spacecraft_eclipse_by_body["MARS"].set(EclipseTypes.FULL)
    mission.geometry_res.any_spacecraft_eclipse.set(EclipseTypes.FULL)
    mission.geometry_res.fraction_of_sun_not_in_eclipse.set(0.0)
    step(45)

    mission.geometry_res.spacecraft_eclipse_by_body["MARS"].set(EclipseTypes.NONE)
    mission.geometry_res.any_spacecraft_eclipse.set(EclipseTypes.NONE)
    mission.geometry_res.fraction_of_sun_not_in_eclipse.set(1.0)
    step(30)

    mission.pel.radarState.set(Radar_State.DOWNLINK)
    mission.data.get_onboard_bin(0).desired_receive_rate.set(0.0)
    mission.data_rate.set(1500.0 * 1000.0)
    mission.pel.x_twtaState.set(X_TWTA_State.ON)
    mission.pel.ka_twtaState.set(Ka_TWTA_State.ON)
    mission.pel.ssrState.set(SSR_State.DOWNLINK)
    mission.data.downlink_active.set(True)
    step(60)
    mission.data.downlink_active.set(False)

    DeleteData(mission, volume=float("inf"), limit_to_sent_data=True, bin=0).run()
    mission.pel.x_twtaState.set(X_TWTA_State.OFF)
    mission.pel.ka_twtaState.set(Ka_TWTA_State.OFF)
    mission.pel.ssrState.set(SSR_State.ON)
    mission.pel.radarState.set(Radar_State.OFF)
    step(30)
