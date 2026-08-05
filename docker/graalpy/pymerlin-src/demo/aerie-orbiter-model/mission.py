"""
Top-level Aerie Orbiter mission model, ported to pymerlin.

This is the pymerlin analogue of ``missionmodel.Mission`` plus the activity
types declared in ``package-info.java``.  The ``Mission`` class wires together
the geometry, power (PEL / solar array / battery), data, telecom and radar
subsystems and registers all of their resources.

Because pymerlin's schedule ``Directive`` only supplies the registrar to the
model constructor, per-plan configuration (config record, SPICE epoch, kernel
paths) is provided via class attributes that a driver can override before
calling ``simulate`` (see ``main.py``).  This mirrors how the pymerlin ``demo``
SPICE examples hardcode their configuration on the model class.

Time-dependent state that the Java model evolves continuously (SPICE geometry,
battery charge, data volumes) is advanced here by the ``advance_state`` daemon
activity, matching pymerlin's discrete pure-Python engine and the Java model's
own stepping ``BodyGeometryGenerator``.
"""

from pymerlin import MissionModel, MissionModelBase
from pymerlin.model_actions import delay, spawn
from pymerlin.duration import Duration, SECONDS

from .configuration import Configuration
from .geometry.geometry_model import (
    GeometryResources, GeometryCalculator, DEFAULT_BODIES, EclipseTypes,
    SpiceKernel, SPICE_AVAILABLE,
)
from .power.pel_model import PELModel
from .power.solar_array import GenericSolarArray, ArrayDeploymentStates
from .power.battery import BatteryModel
from .power.pel_states import (
    X_TWTA_State, Ka_TWTA_State, SSR_State, IDST_State, Prop_State,
    Radar_State, Radar_Heaters_State, Imager_State, Imager_Heaters_State,
    Heaters_State, HarnessLoss_State,
)
from .data.data_model import Data
from .telecom.telecom_model import TelecomModel
from .radar.radar_model import RadarModel, RadarDataCollectionMode


@MissionModel
class Mission(MissionModelBase):
    # --- Per-plan configuration (override before simulate; see main.py) --------
    CONFIG: Configuration = Configuration.default()
    EPOCH_UTC: str = "2026-04-05T12:00:00Z"     # SPICE epoch / plan start
    SPACECRAFT: str = "MRO"                       # SPICE spacecraft body name
    FRAME: str = "J2000"
    KERNEL_PATHS = None                           # list of SPICE kernel paths, or None

    def __init__(self, registrar):
        config = self.CONFIG or Configuration.default()

        # --- Geometry model (SPICE) -------------------------------------------
        self.bodies = DEFAULT_BODIES
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

        # Attitude placeholder: constant off-Sun angle (deg).
        self.off_sun_angle = registrar.cell(config.off_point_angle)

        # --- Power model ------------------------------------------------------
        self.pel = PELModel(registrar)
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

        # --- Data model -------------------------------------------------------
        self.data_rate = registrar.cell(config.data_config.initial_datarate)   # bps
        self.max_volume = registrar.cell(config.data_config.initial_max_volume)  # bits
        self.data = Data(registrar, num_buckets=2,
                         max_volume_getter=self.max_volume.get,
                         data_rate_getter=self.data_rate.get)

        # --- Telecom & Radar models ------------------------------------------
        self.telecom = TelecomModel(registrar)
        self.radar = RadarModel(registrar)

        # --- Register every subsystem's resources ----------------------------
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


# --- Helpers ------------------------------------------------------------------

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
# Physics daemon: advances SPICE geometry, battery charge and data volumes.
# =============================================================================

@Mission.ActivityType
def advance_state(mission, duration_str="24:00:00", step_str="00:01:00"):
    """
    Step the time-dependent state forward across the plan.

    Each step it (1) recomputes SPICE geometry, (2) integrates both batteries
    over the step using the current net power, and (3) integrates the data
    volumes.  Schedule one of these at plan start spanning the whole plan.
    """
    total = _seconds(duration_str)
    dt = _seconds(step_str)
    elapsed = 0.0
    while elapsed < total:
        mission.geometry_calc.compute(Duration.of(int(elapsed), SECONDS))
        mission.cbe_battery.integrate(dt)
        mission.mev_battery.integrate(dt)
        mission.data.integrate(dt)
        delay(step_str)
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
def SpacecraftEnterEclipse(mission, body="MARS", type="FULL", duration_str="00:30:00"):
    """Enter an eclipse of ``type``, ramping FractionOfSunNotInEclipse over 10 segments."""
    new_type = EclipseTypes[type]
    prior_type = mission.geometry_res.spacecraft_eclipse_by_body[body].get()
    mission.geometry_res.spacecraft_eclipse_by_body[body].set(new_type)
    mission.geometry_res.any_spacecraft_eclipse.set(new_type)

    if _worst_eclipse(mission) == EclipseTypes.FULL:
        mission.geometry_res.fraction_of_sun_not_in_eclipse.set(0.0)
    elif new_type == EclipseTypes.NONE:
        mission.geometry_res.fraction_of_sun_not_in_eclipse.set(1.0)
    else:
        num_segments = 10
        seg_seconds = _seconds(duration_str) / num_segments
        for i in range(num_segments):
            if prior_type == EclipseTypes.NONE:
                frac = 1.0 - (i / num_segments)   # full Sun -> partial
            else:
                frac = i / num_segments           # partial -> full Sun
            mission.geometry_res.fraction_of_sun_not_in_eclipse.set(frac)
            delay(Duration.of(int(seg_seconds), SECONDS))


@Mission.ActivityType
def SpacecraftExitEclipse(mission, body="MARS"):
    mission.geometry_res.spacecraft_eclipse_by_body[body].set(EclipseTypes.NONE)
    worst = _worst_eclipse(mission)
    mission.geometry_res.any_spacecraft_eclipse.set(worst)
    if worst == EclipseTypes.NONE:
        mission.geometry_res.fraction_of_sun_not_in_eclipse.set(1.0)
    delay(Duration.SECOND)


@Mission.ActivityType
def AddSpacecraftEclipses(mission, search_duration_str="24:00:00",
                          observer="SUN", occulting_body="MARS", useDSK=False):
    """
    Spawner that, in the Java model, runs a SPICE occultation search and spawns
    ``SpacecraftEnterEclipse`` / ``SpacecraftExitEclipse`` for each eclipse
    window found.  A SPICE geometry-finder search is not available through the
    pymerlin SpiceKernel wrapper, so this spans the search window; schedule the
    enter/exit eclipse activities directly (as the Java atomics allow).
    """
    delay(search_duration_str)


# =============================================================================
# Power activities
# =============================================================================

@Mission.ActivityType
def SolarArrayDeployment(mission, deploy_duration_min=30.0):
    mission.array.set_deployment_state(ArrayDeploymentStates.DEPLOYING)
    delay(Duration.of(int(deploy_duration_min * 60), SECONDS))
    mission.array.set_deployment_state(ArrayDeploymentStates.DEPLOYED)


# =============================================================================
# Data activities
# =============================================================================

@Mission.ActivityType
def ChangeDataGenerationRate(mission, bin=0, rate=0.0):
    """Instantly set the net generation rate of a bin (bps).  Positive => receive."""
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
def GenerateData(mission, bin=0, rate=None, volume=None, duration_str=None):
    """
    Generate data in a bin.  Specify at least two of rate (bps), volume (bits),
    duration; the third is derived, matching ``GenerateData.derivedValues``.
    """
    rate, volume, dur_s = _derive_generate(rate, volume, duration_str)
    b = mission.data.get_onboard_bin(bin)
    b.desired_receive_rate.set(b.desired_receive_rate.get() + rate)
    delay(Duration.of(int(dur_s), SECONDS))
    b.desired_receive_rate.set(b.desired_receive_rate.get() - rate)


def _derive_generate(rate, volume, duration_str):
    dur_s = _seconds(duration_str) if duration_str is not None else None
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
def DeleteData(mission, volume=float("inf"), limit_to_sent_data=True, bin=0):
    """Delete up to ``volume`` bits from a bin, optionally only already-downlinked data."""
    sc = mission.data.get_onboard_bin(bin)
    gnd = mission.data.get_ground_bin(bin)
    current_volume = sc.volume.get()
    not_yet_downlinked = sc.received.get() - gnd.received.get()
    already_downlinked = current_volume - not_yet_downlinked
    cap = already_downlinked if limit_to_sent_data else float("inf")
    deleted = max(0.0, min(volume, min(current_volume, cap)))
    sc.volume.set(current_volume - deleted)
    sc.removed.set(sc.removed.get() + deleted)


@Mission.ActivityType
def PlaybackData(mission, volume=None, duration_str=None):
    """
    Downlink data.  With a duration goal, downlinks for that long; with a volume
    goal, downlinks until that many bits have been sent (over volume/dataRate).
    The physics daemon performs the actual bin-to-ground transfer each step.
    """
    if volume is not None and volume == 0.0:
        return
    mission.data.downlink_active.set(True)
    if volume is not None:
        mission.data.volume_requested.set(volume)
        rate = mission.data.data_rate()
        window_s = volume / rate if rate > 0 else 0.0
    else:
        window_s = _seconds(duration_str) if duration_str is not None else 0.0
    delay(Duration.of(int(window_s), SECONDS))
    mission.data.downlink_active.set(False)
    mission.data.volume_requested.set(0.0)


@Mission.ActivityType
def ReprioritizeData(mission, volume=0.0, bin=0, new_bin=1):
    """Move up to ``volume`` bits from one onboard bin to another."""
    from_bin = mission.data.get_onboard_bin(bin)
    to_bin = mission.data.get_onboard_bin(new_bin)
    current_volume = from_bin.volume.get()
    receivable = to_bin.volume_ub - to_bin.volume.get()
    moved = max(0.0, min(volume, min(current_volume, receivable)))
    from_bin.volume.set(current_volume - moved)
    from_bin.removed.set(from_bin.removed.get() + moved)
    to_bin.volume.set(to_bin.volume.get() + moved)
    to_bin.received.set(to_bin.received.get() + moved)


# =============================================================================
# Telecom activity
# =============================================================================

@Mission.ActivityType
def Downlink(mission, duration_str="01:00:00", bit_rate=1000.0):
    """
    Perform a downlink pass: configure the playback data rate, play back data,
    set the downlink power configuration, then restore instrument states and
    delete the downlinked data.  ``bit_rate`` is in kbps (<= 2000).
    """
    if bit_rate > 2000.0:
        raise ValueError("Collection rate is beyond buffer limit of 2000 kbps")

    mission.data_rate.set(bit_rate * 1000.0)   # kbps -> bps
    spawn(PlaybackData(mission, duration_str=duration_str))

    # Downlink power configuration.
    mission.pel.x_twtaState.set(X_TWTA_State.ON)
    mission.pel.ka_twtaState.set(Ka_TWTA_State.ON)
    mission.pel.ssrState.set(SSR_State.DOWNLINK)
    mission.pel.idstState.set(IDST_State.DOWNLINK)
    mission.pel.propState.set(Prop_State.DOWNLINK)
    prev_radar = mission.pel.radarState.get()
    mission.pel.radarState.set(Radar_State.DOWNLINK)
    mission.pel.radar_heatersState.set(Radar_Heaters_State.OFF)
    prev_imager = mission.pel.imagerState.get()
    mission.pel.imager_heatersState.set(Imager_Heaters_State.DOWNLINK)
    mission.pel.heatersState.set(Heaters_State.DOWNLINK)
    mission.pel.harnesslossState.set(HarnessLoss_State.DOWNLINK)

    delay(duration_str)

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
    new_rate = new_mode.data_rate            # Mbps
    bin0 = mission.data.get_onboard_bin(0)
    if new_rate > 0:
        bin0.desired_receive_rate.set(new_rate * 1e6)   # Mbps -> bps
        bin0.desired_remove_rate.set(0.0)
    else:
        bin0.desired_receive_rate.set(0.0)
        bin0.desired_remove_rate.set(0.0)
    mission.radar.radar_data_mode.set(new_mode)


# =============================================================================
# Coherent demo scenario (single sequential thread).
#
# pymerlin's pure-Python engine runs each activity to completion before the
# next, so a long integrating daemon cannot interleave with separately
# scheduled directives.  Under the Java/Merlin shim engine the modular
# activities above interleave with continuous resources as in the original
# model.  For a self-contained demonstration on the pure-Python engine, this
# single activity sequences a representative operational timeline inline,
# integrating the batteries and data each step so every subsystem's resources
# co-evolve coherently.
# =============================================================================

@Mission.ActivityType
def run_orbiter_demo(mission, step_str="00:05:00"):
    dt = _seconds(step_str)

    def step(minutes):
        for _ in range(int(minutes * 60 / dt)):
            mission.geometry_calc.compute(Duration.of(0, SECONDS))
            mission.cbe_battery.integrate(dt)
            mission.mev_battery.integrate(dt)
            mission.data.integrate(dt)
            delay(step_str)

    # Deploy the solar array, then coast.
    mission.array.set_deployment_state(ArrayDeploymentStates.DEPLOYED)
    step(30)

    # Radar science collection: powers up the radar and generates data in bin 0.
    mission.pel.radarState.set(Radar_State.ON)
    mission.pel.heatersState.set(Heaters_State.RADAR_ON)
    mission.radar.radar_data_mode.set(RadarDataCollectionMode.HI_RES)
    mission.data.get_onboard_bin(0).desired_receive_rate.set(
        RadarDataCollectionMode.HI_RES.data_rate * 1e6)  # Mbps -> bps
    step(60)

    # Enter a full eclipse: solar power drops to zero, battery discharges.
    mission.geometry_res.spacecraft_eclipse_by_body["MARS"].set(EclipseTypes.FULL)
    mission.geometry_res.any_spacecraft_eclipse.set(EclipseTypes.FULL)
    mission.geometry_res.fraction_of_sun_not_in_eclipse.set(0.0)
    step(45)

    # Exit eclipse: solar power returns.
    mission.geometry_res.spacecraft_eclipse_by_body["MARS"].set(EclipseTypes.NONE)
    mission.geometry_res.any_spacecraft_eclipse.set(EclipseTypes.NONE)
    mission.geometry_res.fraction_of_sun_not_in_eclipse.set(1.0)
    step(30)

    # Stop radar collection and downlink the collected data to the ground.
    mission.pel.radarState.set(Radar_State.DOWNLINK)
    mission.data.get_onboard_bin(0).desired_receive_rate.set(0.0)
    mission.data_rate.set(1500.0 * 1000.0)   # 1500 kbps -> bps
    mission.pel.x_twtaState.set(X_TWTA_State.ON)
    mission.pel.ka_twtaState.set(Ka_TWTA_State.ON)
    mission.pel.ssrState.set(SSR_State.DOWNLINK)
    mission.data.downlink_active.set(True)
    step(60)
    mission.data.downlink_active.set(False)

    # Delete the data that has been downlinked and power down the downlink chain.
    DeleteData(mission, volume=float("inf"), limit_to_sent_data=True, bin=0).run()
    mission.pel.x_twtaState.set(X_TWTA_State.OFF)
    mission.pel.ka_twtaState.set(Ka_TWTA_State.OFF)
    mission.pel.ssrState.set(SSR_State.ON)
    mission.pel.radarState.set(Radar_State.OFF)
    step(30)
