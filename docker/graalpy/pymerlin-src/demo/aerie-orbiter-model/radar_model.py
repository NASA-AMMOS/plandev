"""
Radar model.

Port of ``missionmodel.radar.RadarModel`` and
``missionmodel.radar.RadarDataCollectionMode``.  The radar's data-collection
mode maps to an instrument data rate (Mbps), exposed as a derived resource.
"""

from enum import Enum


class RadarDataCollectionMode(Enum):
    OFF = 0.0      # Mbps
    LOW_RES = 0.1
    MED_RES = 1.0
    HI_RES = 4.0

    @property
    def data_rate(self) -> float:
        return self.value


class RadarModel:
    def __init__(self, registrar):
        self.radar_data_mode = registrar.cell(RadarDataCollectionMode.OFF)

    def radar_data_rate(self) -> float:
        """Derived resource: current radar data rate (Mbps)."""
        return self.radar_data_mode.get().data_rate

    def register_states(self, registrar):
        registrar.resource("RadarDataMode", lambda: self.radar_data_mode.get().name)
        registrar.resource("RadarDataRate", self.radar_data_rate)
