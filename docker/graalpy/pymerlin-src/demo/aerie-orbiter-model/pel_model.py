"""
PEL (Power Equipment List) model.

Port of ``missionmodel.power.pel.PELModel``.  It owns one *state cell* per
spacecraft component (each holding a :class:`PELState`) and derives the total
Current-Best-Estimate (CBE) and Maximum-Expected-Value (MEV) power loads by
summing the per-component loads.  Those two totals feed the battery model.
"""

from .pel_states import (
    EPS_State, CDH_State, SSR_State, Heaters_State, ADCS_State,
    HarnessLoss_State, Prop_State, IDST_State, X_TWTA_State, Ka_TWTA_State,
    Radar_State, Imager_State, Radar_Heaters_State, Imager_Heaters_State,
)

# (attribute name, State enum, initial state) for every component in the PEL.
_COMPONENTS = [
    ("eps", EPS_State, EPS_State.OFF),
    ("cdh", CDH_State, CDH_State.OFF),
    ("ssr", SSR_State, SSR_State.OFF),
    ("heaters", Heaters_State, Heaters_State.SURVIVAL),
    ("adcs", ADCS_State, ADCS_State.OFF),
    ("harnessloss", HarnessLoss_State, HarnessLoss_State.OFF),
    ("prop", Prop_State, Prop_State.OFF),
    ("idst", IDST_State, IDST_State.OFF),
    ("x_twta", X_TWTA_State, X_TWTA_State.OFF),
    ("ka_twta", Ka_TWTA_State, Ka_TWTA_State.OFF),
    ("radar", Radar_State, Radar_State.OFF),
    ("imager", Imager_State, Imager_State.OFF),
    ("radar_heaters", Radar_Heaters_State, Radar_Heaters_State.OFF),
    ("imager_heaters", Imager_Heaters_State, Imager_Heaters_State.OFF),
]


class PELModel:
    def __init__(self, registrar):
        self._registrar = registrar
        # One mutable state cell per component, exposed as ``<name>State`` so
        # activities can do e.g. ``model.pel.radarState.set(Radar_State.ON)``.
        self.state_cells = {}
        for name, _enum, initial in _COMPONENTS:
            cell = registrar.cell(initial)
            self.state_cells[name] = cell
            setattr(self, name + "State", cell)

    # --- Derived load resources ------------------------------------------------
    def cbe_total_load(self) -> float:
        """Total CBE load (W): sum of every component's current-state CBE load."""
        return sum(cell.get().cbe for cell in self.state_cells.values())

    def mev_total_load(self) -> float:
        """Total MEV load (W): sum of every component's current-state MEV load."""
        return sum(cell.get().mev for cell in self.state_cells.values())

    def register_states(self, registrar):
        """Register component states and the two spacecraft-level load totals."""
        for name, _enum, _initial in _COMPONENTS:
            cell = self.state_cells[name]
            registrar.resource(name + "State", (lambda c: lambda: c.get().name)(cell))
        registrar.resource("spacecraft.cbeLoad", self.cbe_total_load)
        registrar.resource("spacecraft.mevLoad", self.mev_total_load)
