"""
Driver for the pymerlin Aerie Orbiter model.

Mirrors the pymerlin ``demo`` (``demo/simulation_example.py``): build a
``Schedule`` of ``Directive`` s, run ``simulate`` and report the results.

Two example schedules are shown:

* ``run_coherent_demo`` uses the single ``run_orbiter_demo`` scenario activity,
  which sequences a representative operational timeline (deploy, radar science,
  eclipse, downlink) inline.  This produces fully co-evolving profiles on
  pymerlin's pure-Python engine.

* ``run_directive_schedule`` uses the individual, faithful activity types the
  way a planner would.  Each runs to completion in schedule order on the
  pure-Python engine; under the Java/Merlin shim these interleave with the
  continuous resources exactly as in the original Java model.

To enable live SPICE geometry, install ``pymerlin[spice]`` and set
``Mission.KERNEL_PATHS`` / ``Mission.EPOCH_UTC`` before simulating (see below).
"""

from pymerlin import simulate, Schedule, Directive
from orbiter_model.mission import Mission
from orbiter_model.configuration import Configuration


def configure_mission():
    """Set per-plan configuration on the model class (see Mission docstring)."""
    Mission.CONFIG = Configuration.default()
    Mission.EPOCH_UTC = "2026-04-05T12:00:00Z"
    Mission.SPACECRAFT = "MRO"
    Mission.FRAME = "J2000"
    # To use real SPICE geometry, point this at your kernels, e.g.:
    # Mission.KERNEL_PATHS = [
    #     "/path/to/lsk/naif0012.tls",
    #     "/path/to/pck/pck00011.tpc",
    #     "/path/to/spk/planets/de440s.bsp",
    #     "/path/to/spk/planets/mar099.bsp",
    #     "/path/to/spk/mro/mro_psp_rec.bsp",
    # ]
    Mission.KERNEL_PATHS = None


def run_coherent_demo():
    """Single inline scenario -> coherent multi-subsystem profiles."""
    schedule = Schedule.build(
        ("00:00:00", Directive("run_orbiter_demo", {"step_str": "00:05:00"})),
    )
    return simulate(Mission, schedule, "04:15:00")


def run_directive_schedule():
    """A planner-style schedule of individual activity types (schedule order)."""
    schedule = Schedule.build(
        ("00:00:00", Directive("SolarArrayDeployment", {"deploy_duration_min": 30.0})),
        ("00:30:00", Directive("Radar_On", {})),
        ("00:30:05", Directive("ChangeRadarDataMode", {"mode": "HI_RES"})),
        ("01:30:00", Directive("SpacecraftEnterEclipse",
                               {"body": "MARS", "type": "FULL", "duration_str": "00:30:00"})),
        ("02:00:00", Directive("SpacecraftExitEclipse", {"body": "MARS"})),
        ("02:30:00", Directive("Radar_Off", {})),
        ("03:00:00", Directive("Downlink", {"duration_str": "00:45:00", "bit_rate": 1500.0})),
    )
    return simulate(Mission, schedule, "04:00:00")


def report(profiles, spans, title):
    print("=" * 70)
    print(title)
    print("=" * 70)
    print(f"Activities: {len(spans)}   Resources tracked: {len(profiles)}")
    print("\nActivity timeline:")
    for span in spans:
        print(f"  T+{span.start}  {span.type}  (dur {span.duration})")
    highlights = [
        "array.powerProduction", "cbebattery.batterySOC", "spacecraft.cbeLoad",
        "FractionOfSunNotInEclipse", "RadarDataMode", "RadarDataRate",
        "onboard.volume", "ground.receivedVolume",
    ]
    print("\nFinal resource values:")
    for name in highlights:
        if name in profiles and profiles[name]:
            val = profiles[name][-1].dynamics
            val = round(val, 3) if isinstance(val, float) else val
            print(f"  {name:28s} = {val}")
    print()


def main():
    configure_mission()

    profiles, spans, events = run_coherent_demo()
    report(profiles, spans, "Aerie Orbiter (pymerlin) - coherent scenario")

    profiles2, spans2, events2 = run_directive_schedule()
    report(profiles2, spans2, "Aerie Orbiter (pymerlin) - directive schedule")

    # Optional plotting, matching demo/simulation_example.py.
    try:
        from pymerlin._internal._plot import plot_spans, plot_profiles
        from bokeh.plotting import output_file, save
        from bokeh.layouts import gridplot

        output_file("orbiter_simulation.html", title="Aerie Orbiter Simulation")
        p1 = plot_spans(spans, "04:15:00")
        p2 = plot_profiles(profiles, "04:15:00", x_range=p1.x_range)
        p1.xaxis.visible = False
        save(gridplot([[p1], [p2]]))
        print("Plots saved to orbiter_simulation.html")
    except Exception as e:  # bokeh optional
        print(f"(Skipping plots: {e})")


if __name__ == "__main__":
    main()
