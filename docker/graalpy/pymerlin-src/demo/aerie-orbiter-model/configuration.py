"""
Mission configuration.

Port of ``missionmodel.Configuration`` and the ``*SimConfig`` records
(``BatterySimConfig``, ``SolarArraySimConfig``, ``PowerModelSimConfig``,
``DataModelSimConfig``).  In Aerie these are the simulation-configuration
records the planner can override; here they are plain dataclasses with the same
default values and a ``default()`` factory mirroring ``defaultConfiguration()``.
"""

from dataclasses import dataclass, field

from .power.solar_array import ArrayDeploymentStates


@dataclass
class BatterySimConfig:
    battery_capacity: float = 94.5    # Ah  (VERITAS CSR Table F.2-9, EOL)
    bus_voltage: float = 28.0         # V
    initial_soc: float = 100.0        # %

    @staticmethod
    def default() -> "BatterySimConfig":
        return BatterySimConfig()


@dataclass
class SolarArraySimConfig:
    deployment_state: ArrayDeploymentStates = ArrayDeploymentStates.DEPLOYED
    array_mech_area: float = 16.0     # m^2
    packing_factor: float = 1.0
    cell_efficiency: float = 0.295
    conversion_efficiency: float = 0.9
    other_losses: float = 0.9

    @staticmethod
    def default() -> "SolarArraySimConfig":
        return SolarArraySimConfig()


@dataclass
class PowerModelSimConfig:
    battery_config: BatterySimConfig = field(default_factory=BatterySimConfig.default)
    solar_array_config: SolarArraySimConfig = field(default_factory=SolarArraySimConfig.default)

    @staticmethod
    def default() -> "PowerModelSimConfig":
        return PowerModelSimConfig()


@dataclass
class DataModelSimConfig:
    initial_max_volume: float = 50e9     # bits (50 Gb)
    initial_datarate: float = 1e6        # bps  (1 Mbps)

    @staticmethod
    def default() -> "DataModelSimConfig":
        return DataModelSimConfig()


@dataclass
class Configuration:
    spice_spacecraft_id: int = -74       # MRO
    power_config: PowerModelSimConfig = field(default_factory=PowerModelSimConfig.default)
    data_config: DataModelSimConfig = field(default_factory=DataModelSimConfig.default)
    off_point_angle: float = 70.0        # deg (worst-case off point)

    @staticmethod
    def default() -> "Configuration":
        return Configuration()
