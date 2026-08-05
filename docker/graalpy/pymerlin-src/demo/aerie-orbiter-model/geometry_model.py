"""
Geometry model (SPICE-based).

Port of the ``missionmodel.geometry`` package: ``GenericGeometryResources``,
``GenericGeometryCalculator``, ``SpiceResourcePopulater`` and
``EclipseTypes``, plus the body configuration from
``default_geometry_config.json``.

The Java model uses NASA SPICE (via JNISpice) to compute many time-dependent
geometric quantities and a ``BodyGeometryGenerator`` daemon that steps through
time writing those quantities into discrete resources.  Here the same idea is
implemented with pymerlin's :class:`~pymerlin.spice.SpiceKernel`; the stepping
daemon lives in ``mission.py`` and calls :meth:`GeometryCalculator.compute`.

SPICE is optional: if ``spiceypy``/kernels are unavailable, the geometry
resources simply keep the representative defaults set here so the rest of the
model (power, data, telecom, radar) still simulates.
"""

import math
from enum import Enum

try:
    from pymerlin.spice import SpiceKernel, duration_to_et, SPICE_AVAILABLE
except Exception:  # pragma: no cover - spice extra not installed
    SpiceKernel = None
    SPICE_AVAILABLE = False

    def duration_to_et(duration, epoch_et=0.0):
        return epoch_et

#: km per astronomical unit.
AU_TO_KM = 149597870.691


class EclipseTypes(Enum):
    NONE = "NONE"
    PARTIAL = "PARTIAL"
    ANNULAR = "ANNULAR"
    FULL = "FULL"


class Body:
    """Configuration for one celestial body (subset of the Java ``Body``)."""

    def __init__(self, name, naif_id, frame, radius_km, mu,
                 calculate_altitude=False, calculate_earth_sc_angle=False,
                 calculate_beta_angle=False, calculate_orbit_parameters=False):
        self.name = name
        self.naif_id = naif_id
        self.frame = frame
        self.radius_km = radius_km        # mean equatorial radius (km)
        self.mu = mu                      # gravitational parameter (km^3/s^2)
        self.calculate_altitude = calculate_altitude
        self.calculate_earth_sc_angle = calculate_earth_sc_angle
        self.calculate_beta_angle = calculate_beta_angle
        self.calculate_orbit_parameters = calculate_orbit_parameters


# Body set from default_geometry_config.json: MARS is the primary (all flags on).
DEFAULT_BODIES = {
    "SUN":   Body("SUN", 10, "IAU_SUN", 695700.0, 1.32712440018e11),
    "EARTH": Body("EARTH", 399, "IAU_EARTH", 6378.14, 3.986004418e5),
    "MARS":  Body("MARS", 499, "IAU_MARS", 3396.19, 4.282837e4,
                  calculate_altitude=True, calculate_earth_sc_angle=True,
                  calculate_beta_angle=True, calculate_orbit_parameters=True),
}


class GeometryResources:
    """Mutable cells for the geometric quantities, one set per body plus globals."""

    def __init__(self, registrar, bodies):
        self.bodies = bodies

        # Per-body cells.
        self.spacecraft_body_range = {}      # km
        self.spacecraft_body_speed = {}      # km/s
        self.body_half_angle_size = {}       # deg
        self.sun_spacecraft_body_angle = {}  # deg
        self.sun_body_spacecraft_angle = {}  # deg
        self.spacecraft_altitude = {}        # km
        self.beta_angle_by_body = {}         # deg
        self.earth_spacecraft_body_angle = {}  # deg
        self.orbit_inclination_by_body = {}  # deg
        self.orbit_period_by_body = {}       # s
        self.spacecraft_eclipse_by_body = {}
        self.periapsis = {}
        self.apoapsis = {}

        for name, body in bodies.items():
            self.spacecraft_body_range[name] = registrar.cell(
                1.5 * AU_TO_KM if name == "SUN" else 1.0e5)
            self.spacecraft_body_speed[name] = registrar.cell(0.0)
            self.body_half_angle_size[name] = registrar.cell(0.0)
            self.sun_spacecraft_body_angle[name] = registrar.cell(0.0)
            self.sun_body_spacecraft_angle[name] = registrar.cell(0.0)
            self.spacecraft_eclipse_by_body[name] = registrar.cell(EclipseTypes.NONE)
            self.periapsis[name] = registrar.cell(False)
            self.apoapsis[name] = registrar.cell(False)
            if body.calculate_altitude:
                self.spacecraft_altitude[name] = registrar.cell(300.0)
            if body.calculate_beta_angle:
                self.beta_angle_by_body[name] = registrar.cell(0.0)
            if body.calculate_earth_sc_angle:
                self.earth_spacecraft_body_angle[name] = registrar.cell(0.0)
            if body.calculate_orbit_parameters:
                self.orbit_inclination_by_body[name] = registrar.cell(0.0)
                self.orbit_period_by_body[name] = registrar.cell(0.0)

        # Non-arrayed / global cells.
        self.any_spacecraft_eclipse = registrar.cell(EclipseTypes.NONE)
        self.occultation = registrar.cell(0)
        self.fraction_of_sun_not_in_eclipse = registrar.cell(1.0)
        self.spacecraft_declination = registrar.cell(0.0)
        self.spacecraft_right_ascension = registrar.cell(0.0)

    def spacecraft_sun_range_au(self) -> float:
        """Derived: spacecraft-Sun range converted from km to AU."""
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
        registrar.resource(
            "AnySpacecraftEclipse", lambda: self.any_spacecraft_eclipse.get().name)
        registrar.resource("Occultation", self.occultation)
        registrar.resource("FractionOfSunNotInEclipse", self.fraction_of_sun_not_in_eclipse)
        registrar.resource("SpacecraftBodyRange_SUN_AU", self.spacecraft_sun_range_au)


class GeometryCalculator:
    """
    Port of ``GenericGeometryCalculator``.  Computes geometric quantities from
    SPICE state vectors and writes them into :class:`GeometryResources`.

    Analogous to the Java ``BodyGeometryGenerator`` daemon, :meth:`compute` is
    called repeatedly by the mission's physics daemon with the elapsed
    simulation time so absolute (ephemeris) time = ``epoch_et + elapsed``.
    """

    def __init__(self, resources, bodies, epoch_et=0.0,
                 spacecraft="MRO", frame="J2000", spice_kernel=None):
        self.res = resources
        self.bodies = bodies
        self.epoch_et = epoch_et
        self.spacecraft = spacecraft
        self.frame = frame
        self.spice = spice_kernel

    def compute(self, elapsed_duration):
        """Update all geometry resources for the current elapsed sim time."""
        if not (SPICE_AVAILABLE and self.spice is not None):
            return  # keep representative defaults when SPICE is unavailable
        et = duration_to_et(elapsed_duration, self.epoch_et)
        for name, body in self.bodies.items():
            self._compute_body(body, et)

    def _compute_body(self, body, et):
        # State of body relative to the spacecraft (km, km/s).
        state = self.spice.state(body.name, self.spacecraft, self.frame, et)
        r = (state[0], state[1], state[2])
        v = (state[3], state[4], state[5])
        r_norm = _norm(r)
        self.res.spacecraft_body_range[body.name].set(float(r_norm))
        self.res.spacecraft_body_speed[body.name].set(float(_norm(v)))
        if r_norm > 0:
            self.res.body_half_angle_size[body.name].set(
                math.degrees(math.asin(min(1.0, body.radius_km / r_norm))))

        # Sun/spacecraft/body angles (the Sun has no angle to itself).
        if body.name != "SUN":
            sun_wrt_body = self.spice.position("SUN", body.name, self.frame, et)
            self.res.sun_spacecraft_body_angle[body.name].set(
                math.degrees(_angle(_add(r, sun_wrt_body), r)))
            self.res.sun_body_spacecraft_angle[body.name].set(
                math.degrees(_angle(_scale(r, -1.0), sun_wrt_body)))

        # Altitude above the body surface.
        if body.calculate_altitude:
            self.res.spacecraft_altitude[body.name].set(float(r_norm - body.radius_km))

        # Beta angle: angle between the orbit-plane normal and the body->Sun vector.
        if body.calculate_beta_angle and body.name != "SUN":
            normal = _normalize(_cross(r, v))
            sun_wrt_body = self.spice.position("SUN", body.name, self.frame, et)
            self.res.beta_angle_by_body[body.name].set(
                math.degrees(_angle(normal, _scale(sun_wrt_body, -1.0))) - 90.0)

        # Earth-spacecraft-body angle.
        if body.calculate_earth_sc_angle:
            earth_wrt_sc = self.spice.position("EARTH", self.spacecraft, self.frame, et)
            self.res.earth_spacecraft_body_angle[body.name].set(
                math.degrees(_angle(earth_wrt_sc, r)))

        # Two-body orbit elements (inclination, period) when actually orbiting.
        if body.calculate_orbit_parameters:
            incl, period, ecc = _orbit_elements(r, v, body.mu)
            if ecc is not None and ecc < 1.0:
                self.res.orbit_inclination_by_body[body.name].set(float(math.degrees(incl)))
                self.res.orbit_period_by_body[body.name].set(float(period))


# --- tiny 3-vector helpers (avoid a numpy dependency) -------------------------

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
    """Return (inclination_rad, period_s, eccentricity) from a state vector."""
    r_norm = _norm(r)
    v2 = _dot(v, v)
    if r_norm == 0 or mu == 0:
        return 0.0, 0.0, None
    energy = v2 / 2.0 - mu / r_norm
    if energy == 0:
        return 0.0, 0.0, None
    a = -mu / (2.0 * energy)                     # semi-major axis
    h = _cross(r, v)
    h_norm = _norm(h)
    inclination = math.acos(max(-1.0, min(1.0, h[2] / h_norm))) if h_norm > 0 else 0.0
    # eccentricity vector
    e_vec = _scale(_add(_scale(r, v2 - mu / r_norm),
                        _scale(v, -_dot(r, v))), 1.0 / mu)
    ecc = _norm(e_vec)
    period = 2.0 * math.pi * math.sqrt(a ** 3 / mu) if a > 0 and ecc < 1.0 else 0.0
    return inclination, period, ecc
