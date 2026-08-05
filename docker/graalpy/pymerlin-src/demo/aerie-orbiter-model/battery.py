"""
Battery model.

Port of ``missionmodel.power.BatteryModel``.  The battery is charged/discharged
by the *net* power (source minus demand); battery current is ``(P_prod - P_dem)
/ V_bus``.  In the Java model this current is integrated continuously with a
clamped integrator.  pymerlin's pure-Python engine does not autonomously evolve
cells, so the charge is instead advanced discretely by the mission's physics
daemon (see ``mission.py``) via :meth:`integrate`, which reproduces the same
clamp-to-[0, capacity] behaviour.
"""


class BatteryModel:
    def __init__(self, registrar, name, sim_config,
                 total_load_getter, power_production_getter):
        """
        :param name: prefix for this battery's resources (e.g. ``"cbe"``)
        :param sim_config: a :class:`BatterySimConfig`
        :param total_load_getter: getter -> spacecraft power demand (W)
        :param power_production_getter: getter -> power produced by the array (W)
        """
        self.name = name
        self.sim_config = sim_config
        self.bus_voltage = sim_config.bus_voltage          # V
        self.capacity_ah = sim_config.battery_capacity     # Ah
        self.capacity_wh = self.capacity_ah * self.bus_voltage
        self.power_demand = total_load_getter
        self.power_production = power_production_getter

        # State of charge stored as amp-hours; integrated by the physics daemon.
        initial_charge_ah = self.capacity_ah * sim_config.initial_soc / 100.0
        self.charge_ah = registrar.cell(initial_charge_ah)

    # --- Derived resources -----------------------------------------------------
    def battery_current(self) -> float:
        """Net current into (+) / out of (-) the battery (A):  I = (Pprod - Pdem) / Vbus."""
        return (_call(self.power_production) - _call(self.power_demand)) / self.bus_voltage

    def battery_charge(self) -> float:
        """Current charge in amp-hours (Ah)."""
        return self.charge_ah.get()

    def battery_soc(self) -> float:
        """State of charge (%)."""
        return self.charge_ah.get() / self.capacity_ah * 100.0

    def battery_full(self) -> bool:
        return self.battery_soc() >= 100.0

    def battery_empty(self) -> bool:
        return self.battery_soc() <= 0.0

    # --- Discrete integration step (called by the physics daemon) --------------
    def integrate(self, dt_seconds: float):
        """Advance charge by the net current over ``dt_seconds``, clamped to capacity."""
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


def _call(source):
    return source() if callable(source) else source
