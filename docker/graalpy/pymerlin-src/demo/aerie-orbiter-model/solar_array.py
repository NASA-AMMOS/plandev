"""
Solar array power-generation model.

Port of ``missionmodel.power.GenericSolarArray`` and
``missionmodel.power.ArrayDeploymentStates``.  Power production depends on the
spacecraft's distance from the Sun (AU), the array-to-Sun angle (deg), the
fraction of the Sun not in eclipse, and whether the array is deployed.
"""

import math
from enum import Enum


class ArrayDeploymentStates(Enum):
    UNDEPLOYED = "UNDEPLOYED"
    DEPLOYING = "DEPLOYING"
    DEPLOYED = "DEPLOYED"


class GenericSolarArray:
    #: Solar irradiance at 1 AU (W/m^2).
    SOLAR_INTENSITY_AT_EARTH = 1360.8

    def __init__(self, registrar, sim_config,
                 solar_distance_au, array_to_sun_angle_deg, eclipse_factor):
        """
        :param sim_config: a :class:`SolarArraySimConfig`
        :param solar_distance_au: getter -> spacecraft-Sun distance in AU
        :param array_to_sun_angle_deg: getter -> array/Sun angle in degrees
        :param eclipse_factor: getter -> fraction of the Sun not in eclipse (0..1)
        """
        self.sim_config = sim_config
        self.solar_distance = solar_distance_au
        self.array_to_sun_angle = array_to_sun_angle_deg
        self.eclipse_factor = eclipse_factor

        self.deployment_state = registrar.cell(sim_config.deployment_state)
        self.array_cell_area = registrar.cell(
            sim_config.array_mech_area * sim_config.packing_factor)

        # Losses that do not vary with simulation time.
        self.static_array_losses = (sim_config.cell_efficiency
                                    * sim_config.conversion_efficiency
                                    * sim_config.other_losses)

    def compute_solar_power(self, distance, cell_area, array_angle,
                            eclipse_loss, deployment_state) -> float:
        """Solar power (W); zero unless the array is fully deployed."""
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
        """Derived resource: instantaneous solar power output (W)."""
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


def _val(source):
    """Accept either a plain getter/cell or a constant number."""
    if callable(source):
        return source()
    if hasattr(source, "get"):
        return source.get()
    return source
