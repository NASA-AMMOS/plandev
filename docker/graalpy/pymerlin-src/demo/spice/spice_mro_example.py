"""
Example demonstrating SPICE integration with pymerlin using MRO spacecraft data.

This example shows how to use NASA's SPICE toolkit to track the Mars Reconnaissance
Orbiter (MRO) spacecraft position and compute mission-relevant geometric quantities.

Prerequisites:
    pip install pymerlin[spice]
    
Required SPICE kernel files:
    - naif0012.tls - Leap seconds kernel
    - de440s.bsp - Planetary ephemeris (Mars, Earth, Sun positions)
    - mar099.bsp - Mars system ephemeris (Mars barycenter)
    - pck00011.tpc - Planetary constants (Mars radius, etc.)
    - mro_psp.bsp - MRO spacecraft ephemeris

Download kernels from:
    - Generic kernels: https://naif.jpl.nasa.gov/pub/naif/generic_kernels/
    - MRO kernels: https://naif.jpl.nasa.gov/pub/naif/MRO/kernels/
    - Mars ephemeris: https://naif.jpl.nasa.gov/pub/naif/generic_kernels/spk/satellites/
"""

from pymerlin import MissionModel, MissionModelBase, simulate, Schedule, Directive
from pymerlin.spice import SpiceKernel, duration_to_et, spice_resource, SPICE_AVAILABLE
from pymerlin.clock import clock

try:
    import spiceypy as spice
except ImportError:
    SPICE_AVAILABLE = False

if not SPICE_AVAILABLE:
    print("This example requires spiceypy. Install with: pip install pymerlin[spice]")
    exit(1)


# ----- CONSTANTS AND MISSION CONSTRAINTS -----

KERNELS_PATH_ROOT = "/Users/remyr/Desktop/pymerlin/kernels"

LIGHTSPEED_KM_S = 299792.458

# Solar constant at 1 AU (Earth's distance): ~1361 W/m²
# 1 AU = 149,597,870.7 km
AU_KM = 149597870.7
SOLAR_CONSTANT = 1361.0  # W/m²

# Assume MRO has ~10 m² of solar panels with ~30% efficiency
PANEL_AREA = 10.0  # m²
EFFICIENCY = 0.30


@MissionModel
class MROmission(MissionModelBase):
    """
    Mars Reconnaissance Orbiter mission model using SPICE.
    
    Tracks MRO's orbital position and computes mission-relevant quantities like:
    - Distance from Mars
    - Distance from Earth (for communication)
    - Solar distance (for power calculations)
    """
    
    def __init__(self, registrar):

        # Initialize simulation clock
        clock_maker = clock(registrar)
        self.clock = clock_maker._system_clock
        
        # Initialize SPICE with MRO kernels
        self.spice = SpiceKernel(registrar, kernel_paths=[
             f"{KERNELS_PATH_ROOT}/lsk/naif0012.tls",           # Leap seconds kernel
             f"{KERNELS_PATH_ROOT}/spk/planets/de440s.bsp",     # Planetary ephemeris
             f"{KERNELS_PATH_ROOT}/spk/planets/mar099.bsp",     # Mars system ephemeris
             f"{KERNELS_PATH_ROOT}/pck/pck00011.tpc",           # Planetary constants (radii, etc)
             f"{KERNELS_PATH_ROOT}/spk/mro/mro_psp.bsp"         # MRO predicted trajectory
        ])
        
        # Load kernels at mission start
        self.spice.load_kernels()
        print("kernels loaded")
        
        # Set epoch to a time covered by the MRO kernel
        # This kernel covers: 2026-04-01 to 2026-06-14
        # Using a time well into the coverage to avoid edge effects
        self.epoch_et = self.spice.utc_to_et("2026-04-05T12:00:00")
        
        # Cells to store mission state (updated by activities)
        self.altitude = registrar.cell(0.0)           # Altitude above Mars surface (km)
        self.earth_distance = registrar.cell(0.0)     # Distance to Earth (km)
        self.solar_distance = registrar.cell(0.0)     # Distance to Sun (km)
        
        # Register cell-based resources (updated periodically by activities)
        registrar.resource("/mro/altitude", self.altitude.get)
        registrar.resource("/mro/earth_distance", self.earth_distance.get)
        registrar.resource("/mro/solar_distance", self.solar_distance.get)
        
        # Register a continuous spice_resource for comparison
        # This computes MRO-Mars distance on-demand without needing activity updates
        registrar.resource("/mro/mars_distance_continuous",
            spice_resource(self.spice, self.clock, self.epoch_et,
                          "MRO", "MARS", "J2000"))


@MROmission.ActivityType
def update_mro_state(mission: MROmission):
    """
    Update MRO's orbital state using SPICE.
    
    Computes:
    - Altitude above Mars surface
    - Distance to Earth (for communication planning)
    - Distance to Sun (for solar power estimation)
    """
    
    # Get current simulation time
    sim_time = mission.clock.get()
    
    # Convert to ephemeris time
    et = duration_to_et(sim_time, mission.epoch_et)
    
    # Compute MRO position relative to Mars
    mro_mars_pos = mission.spice.position("MRO", "MARS", "J2000", et)
    distance_from_mars_center = (mro_mars_pos[0]**2 + mro_mars_pos[1]**2 + mro_mars_pos[2]**2)**0.5
    
    # Get Mars radii from SPICE PCK kernel
    # bodvrd returns (dimension, values_array)
    # values_array contains [equatorial_radius, equatorial_radius, polar_radius] in km
    _, radii = spice.bodvrd("MARS", "RADII", 3)
    mars_mean_radius = float(sum(radii) / 3.0)  # Mean radius
    
    altitude = float(distance_from_mars_center - mars_mean_radius)
    
    # Compute MRO distance from Earth (for communication)
    mro_earth_pos = mission.spice.position("MRO", "EARTH", "J2000", et)
    earth_distance = (mro_earth_pos[0]**2 + mro_earth_pos[1]**2 + mro_earth_pos[2]**2)**0.5
    earth_distance = float(earth_distance)
    
    # Compute MRO distance from Sun (for solar power)
    mro_sun_pos = mission.spice.position("MRO", "SUN", "J2000", et)
    solar_distance = (mro_sun_pos[0]**2 + mro_sun_pos[1]**2 + mro_sun_pos[2]**2)**0.5
    solar_distance = float(solar_distance)
    
    # Update cells
    mission.altitude.set(altitude)
    mission.earth_distance.set(earth_distance)
    mission.solar_distance.set(solar_distance)
    
    # Convert simulation time to UTC for display
    current_utc = mission.spice.et_to_utc(et, "ISOC", 0)
    
    print(f"=== MRO State at {current_utc} ===")
    print(f"  Simulation time: {sim_time}")
    print(f"  Altitude above Mars: {altitude:,.2f} km")
    print(f"  Distance to Earth: {earth_distance:,.2f} km ({earth_distance/LIGHTSPEED_KM_S:.2f} light-seconds)")
    print(f"  Distance to Sun: {solar_distance:,.2f} km")
    print()


@MROmission.ActivityType
def check_communication_window(mission: MROmission):
    """
    Check if MRO can communicate with Earth.
    
    This is a simplified check - real communication planning would also consider:
    - Line of sight (is Mars blocking the view?)
    - Ground station availability
    - Antenna pointing
    - Signal strength
    """

    earth_dist = mission.earth_distance.get()
    
    # Light time in seconds
    light_time_seconds = earth_dist / LIGHTSPEED_KM_S
    light_time_minutes = light_time_seconds / 60.0
    
    print("Communication Check:")
    print(f"  One-way light time: {light_time_minutes:.2f} minutes")
    print(f"  Round-trip time: {light_time_minutes * 2:.2f} minutes")
    
    # Typical Mars-Earth distance ranges from ~55 million km to ~400 million km
    if earth_dist < 100_000_000:  # Less than 100 million km
        print("  Status: GOOD - Mars is relatively close to Earth")
    elif earth_dist < 250_000_000:  # Less than 250 million km
        print("  Status: NOMINAL - Standard Mars-Earth distance")
    else:
        print("  Status: CHALLENGING - Mars is far from Earth (superior conjunction)")
    print()


@MROmission.ActivityType
def estimate_solar_power(mission: MROmission):
    """
    Estimate available solar power based on distance from Sun.
    
    Solar power decreases with the square of distance from the Sun.
    """

    solar_dist = mission.solar_distance.get()
    
    # Calculate solar flux at MRO's distance
    solar_flux = SOLAR_CONSTANT * (AU_KM / solar_dist) ** 2
    
    estimated_power = solar_flux * PANEL_AREA * EFFICIENCY
    
    print("Solar Power Estimate:")
    print(f"  Distance from Sun: {solar_dist:,.0f} km ({solar_dist/AU_KM:.3f} AU)")
    print(f"  Solar flux: {solar_flux:.2f} W/m²")
    print(f"  Estimated power: {estimated_power:.2f} W")
    print()


def main():
    """
    Run MRO mission simulation with SPICE.
    """

    print("=" * 70)
    print("MRO Mission Simulation with SPICE")
    print("=" * 70)
    print()
    print("This example tracks the Mars Reconnaissance Orbiter using real")
    print("spacecraft ephemeris data and computes mission-relevant quantities.")
    print("=" * 70)
    print()
    
    # Create a schedule with MRO state updates
    schedule = Schedule.build(
        ("00:00:00", Directive("update_mro_state", {})),
        ("00:00:01", Directive("check_communication_window", {})),
        ("00:00:02", Directive("estimate_solar_power", {})),
        ("06:00:00", Directive("update_mro_state", {})),
        ("12:00:00", Directive("update_mro_state", {})),
        ("18:00:00", Directive("update_mro_state", {})),
    )
    
    # Run simulation for 24 hours
    try:
        profiles, spans, events = simulate(
            MROmission,
            schedule,
            "24:00:00"
        )
        
        print("\n" + "=" * 70)
        print("Simulation completed successfully!")
        print(f"Generated {len(spans)} activity spans")
        print(f"Tracked {len(profiles)} resources")
        print("=" * 70)
        print("\nResource Patterns Demonstrated:")
        print("  - Cell-based: /mro/altitude, /mro/earth_distance, /mro/solar_distance")
        print("    (Updated by activities at specific times)")
        print("  - spice_resource: /mro/mars_distance_continuous")
        print("    (Computed on-demand, always current)")
        print("=" * 70)
        
    except Exception as e:
        print(f"\nSimulation failed: {e}")
        print("\nMake sure you have the MRO kernel file at the correct path.")
        print("Check that the kernel covers the epoch time (2024-01-01).")


if __name__ == "__main__":
    main()
