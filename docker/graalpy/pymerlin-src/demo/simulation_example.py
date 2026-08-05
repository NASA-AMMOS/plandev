"""
Simulation example demonstrating pymerlin basics with plotting.

This is the Python script version of simulation_example.ipynb with updated API.
"""

from pymerlin import MissionModel, MissionModelBase, simulate, Schedule, Directive
from pymerlin.model_actions import delay, wait_until


@MissionModel
class Mission(MissionModelBase):
    def __init__(self, registrar):
        self.cell1 = registrar.cell("init")

        registrar.resource("/cell1", self.cell1.get)
        registrar.resource("/cell2", self.cell1.get)
        registrar.topic("/cell1")


@Mission.ActivityType
def activity1(mission):
    mission.cell1.emit(lambda x: "foo")
    result = mission.cell1.get()
    assert result == "foo", result
    delay("00:30:00")
    mission.cell1.emit(lambda x: "bar")


@Mission.ActivityType
def activity2(mission):
    # This will wait forever since the condition is never defined
    # Included for demonstration purposes only
    wait_until(lambda: False)


def main():
    """Run simulation and plot results"""
    
    # Build schedule
    schedule = Schedule.build(
        ("00:10:00", Directive("activity1", {})),
        ("01:00:00", Directive("activity1", {}))
    )
    duration = "01:20:00"
    
    # Run simulation
    print("Running simulation...")
    profiles, spans, events = simulate(Mission, schedule, duration)
    
    print("\nSimulation complete!")
    print(f"  Generated {len(spans)} activity spans")
    print(f"  Tracked {len(profiles)} resources")
    print(f"\nProfiles: {list(profiles.keys())}")
    print("\nSpans:")
    for span in spans:
        print(f"  - {span.type} at {span.start}, duration: {span.duration}")
    
    # Plot results
    try:
        from pymerlin._internal._plot import plot_spans, plot_profiles
        from bokeh.plotting import show, output_file
        from bokeh.layouts import gridplot
        
        print("\nGenerating plots...")
        
        # Configure output to HTML file
        output_file("simulation_results.html", title="Simulation Results")
        
        p1 = plot_spans(spans, duration)
        p2 = plot_profiles(profiles, duration, x_range=p1.x_range)
        
        p1.xaxis.visible = False
        
        print("Opening plots in browser...")
        show(gridplot([[p1], [p2]]))
        print("✓ Plots saved to simulation_results.html and opened in browser")
        
    except ImportError as e:
        print(f"\nSkipping plots (bokeh not installed): {e}")
    except Exception as e:
        print(f"\nError generating plots: {e}")


if __name__ == "__main__":
    main()
