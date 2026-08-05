"""
Example demonstrating SPICE integration with pymerlin.

This example shows how to use NASA's SPICE toolkit within a pymerlin simulation
to compute celestial body positions and other geometric quantities.

This demo computes the Moon's distance from Earth over time as a simple example.
To compute spacecraft positions, you would use a spacecraft ephemeris kernel
and query the spacecraft's position instead.

Prerequisites:
    pip install pymerlin[spice]
    
You'll also need SPICE kernel files. For this example, you need:
    - A leap seconds kernel (e.g., naif0012.tls)
    - A planetary ephemeris (e.g., de440s.bsp) - contains Moon/Earth positions

Download kernels from: https://naif.jpl.nasa.gov/naif/data.html
"""

from pymerlin import MissionModel, MissionModelBase, simulate, Schedule, Directive
from pymerlin.spice import SpiceKernel, duration_to_et, SPICE_AVAILABLE
from pymerlin.model_actions import delay, spawn
from pymerlin.clock import clock
from pymerlin.duration import Duration

KERNELS_PATH_ROOT = "/Users/remyr/Desktop/pymerlin/kernels"

if not SPICE_AVAILABLE:
    print("This example requires spiceypy. Install with: pip install pymerlin[spice]")
    exit(1)


@MissionModel
class SpacecraftMission(MissionModelBase):
    """
    Example mission model that uses SPICE for geometric calculations.
    
    This demo tracks the Moon's distance from Earth. For a real spacecraft
    mission, you would load a spacecraft kernel and query spacecraft positions.
    """
    
    def __init__(self, registrar):
        # Initialize simulation clock
        clock_maker = clock(registrar)
        self.clock = clock_maker._system_clock
        
        # Initialize SPICE with kernel files
        # NOTE: Update these paths to point to your actual SPICE kernels
        self.spice = SpiceKernel(registrar, kernel_paths=[
             f"{KERNELS_PATH_ROOT}/lsk/naif0012.tls",           # Leap seconds kernel
             f"{KERNELS_PATH_ROOT}/spk/planets/de440s.bsp",     # Planetary ephemeris (Moon/Earth)
             # f"{KERNELS_PATH_ROOT}/spk/mro/mro_psp.bsp"       # Spacecraft kernel (not used in this demo)
        ])
        
        # Load kernels at mission start
        self.spice.load_kernels()
        
        # Define epoch (simulation start time in ephemeris time)
        # For example: J2000 epoch
        self.epoch_et = 0.0  # Or use: self.spice.utc_to_et("2024-01-01T00:00:00")
        
        # Cell to store Moon's distance from Earth (km)
        self.distance = registrar.cell(0.0)
        
        # Register distance as a resource
        registrar.resource("/moon/distance", self.distance.get)


@SpacecraftMission.ActivityType
def update_position(mission: SpacecraftMission, target: str = "MOON", observer: str = "EARTH"):
    """
    Activity that computes distance between two bodies using SPICE.
    
    Args:
        mission: The mission model instance
        target: Target body name (default: "MOON")
        observer: Observer body name (default: "EARTH")
    """

    # Get current simulation time
    sim_time = mission.clock.get()
    
    # Convert to ephemeris time
    et = duration_to_et(sim_time, mission.epoch_et)
    
    # Compute position using SPICE
    position = mission.spice.position(target, observer, "J2000", et)
    
    # Calculate distance
    distance = (position[0]**2 + position[1]**2 + position[2]**2)**0.5
    
    # Convert to native Python float (numpy types don't serialize well to Java)
    distance = float(distance)
    
    # Update the distance cell
    mission.distance.set(distance)
    
    print(f"At simulation time {sim_time}: {target} distance from {observer}: {distance:.2f} km")


@SpacecraftMission.ActivityType
def periodic_position_update(mission: SpacecraftMission, interval: str = "01:00:00", 
                                   duration_str: str = "24:00:00"):
    """
    Periodically update spacecraft position.
    
    Args:
        mission: The mission model instance
        interval: Time between updates (default: 1 hour)
        duration_str: Total duration to run updates (default: 24 hours)
    """
    
    
    total = Duration.from_string(duration_str)
    step = Duration.from_string(interval)
    elapsed = Duration.from_string("00:00:00")
    
    while elapsed < total:
        # Spawn an update activity
        spawn(update_position(mission))
        
        # Wait for the next interval
        delay(interval)
        elapsed = elapsed + step


def main():
    """
    Run a simple SPICE-enabled simulation.
    """
    print("=" * 60)
    print("SPICE Integration Example - Moon Distance from Earth")
    print("=" * 60)
    print()
    print("This example computes the Moon's distance from Earth at")
    print("different times using SPICE ephemeris data.")
    print()
    print("To compute spacecraft positions, load a spacecraft kernel")
    print("and query the spacecraft body instead of 'MOON'.")
    print("=" * 60)
    print()
    
    # Create a schedule with position update activities
    schedule = Schedule.build(
        ("00:00:00", Directive("update_position", {"target": "MOON", "observer": "EARTH"})),
        ("06:00:00", Directive("update_position", {"target": "MOON", "observer": "EARTH"})),
        ("12:00:00", Directive("update_position", {"target": "MOON", "observer": "EARTH"})),
        ("18:00:00", Directive("update_position", {"target": "MOON", "observer": "EARTH"})),
    )
    
    # Run simulation for 24 hours
    try:
        profiles, spans, events = simulate(
            SpacecraftMission,
            schedule,
            "24:00:00"
        )
        
        print("\nSimulation completed successfully!")
        print(f"Generated {len(spans)} activity spans")
        print(f"Tracked {len(profiles)} resources")
        
    except Exception as e:
        print(f"\nSimulation failed: {e}")
        print("\nThis is expected if SPICE kernels are not configured.")


if __name__ == "__main__":
    main()
