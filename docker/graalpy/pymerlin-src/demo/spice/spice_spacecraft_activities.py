"""
Mars Orbiter Mission with Real Spacecraft Activities

This example demonstrates SPICE integration with realistic spacecraft activities:
1. Uses spice_resource for continuous tracking (no fake update activities)
2. Shows real activities like imaging and downlink
3. Activities read SPICE resources to make operational decisions
4. Demonstrates ISO 8601 duration format for scheduling (Aerie-compatible)

Key Features:
    - SPICE epoch set with ISO 8601 timestamp: "2026-04-05T12:00:00Z"
    - Activities scheduled with ISO 8601 durations: Duration.parse_iso8601("PT2H30M")
    - Compatible with Aerie's Duration.parseISO8601() format

Requirements:
    - spiceypy (pip install pymerlin[spice])
    - SPICE kernels (same as spice_mro_example.py)
"""

from pymerlin import MissionModel, MissionModelBase, simulate, Schedule, Directive
from pymerlin.spice import SpiceKernel, spice_resource, duration_to_et
from pymerlin.clock import clock
from pymerlin.model_actions import delay
from pymerlin.duration import Duration



@MissionModel
class MarsOrbiter(MissionModelBase):
    """
    A Mars orbiter mission model with realistic spacecraft operations.
    
    The spacecraft can:
    - Take images of Mars surface targets
    - Downlink data to Earth
    - Charge batteries using solar power
    
    All activities use SPICE resources to make operational decisions.
    """
    
    def __init__(self, registrar):
        # Initialize clock
        clock_maker = clock(registrar)
        self.clock = clock_maker._system_clock
        
        # Initialize SPICE with MRO kernels
        kernel_root = "/Users/remyr/Desktop/pymerlin/kernels"
        self.spice = SpiceKernel(registrar, kernel_paths=[
            f"{kernel_root}/lsk/naif0012.tls",
            f"{kernel_root}/pck/pck00011.tpc",
            f"{kernel_root}/spk/planets/de440s.bsp",
            f"{kernel_root}/spk/planets/mar099.bsp",
            f"{kernel_root}/spk/mro/mro_psp_rec.bsp",
        ])
        self.spice.load_kernels()
        
        # Set mission epoch using ISO 8601 timestamp format
        # SPICE accepts ISO 8601, DOY, and native formats
        self.epoch_et = self.spice.utc_to_et("2026-04-05T12:00:00Z")  # ISO 8601 format
        # Alternative: self.epoch_et = self.spice.utc_to_et("2026-095T12:00:00")  # DOY format
        
        # Mission state (managed by activities, not fake updates)
        self.battery_charge = registrar.cell(80.0)  # Percent
        self.data_buffer = registrar.cell(0.0)      # GB
        self.images_taken = registrar.cell(0)       # Count
        
        # Register mission state resources
        registrar.resource("/spacecraft/battery", self.battery_charge.get)
        registrar.resource("/spacecraft/data_buffer", self.data_buffer.get)
        registrar.resource("/spacecraft/images_taken", self.images_taken.get)
        
        # Continuous SPICE resources (no activities needed!)
        # These compute on-demand using spice_resource
        
        # Altitude above Mars surface
        def compute_altitude(pos):
            distance = (pos[0]**2 + pos[1]**2 + pos[2]**2)**0.5
            mars_radius = 3396.19  # km
            return distance - mars_radius
        
        registrar.resource("/spacecraft/altitude",
            spice_resource(self.spice, self.clock, self.epoch_et,
                          "MRO", "MARS", "J2000", compute_altitude))
        
        # Distance to Earth (for communication planning)
        registrar.resource("/spacecraft/earth_distance",
            spice_resource(self.spice, self.clock, self.epoch_et,
                          "MRO", "EARTH", "J2000"))
        
        # Distance to Sun (for solar power estimation)
        registrar.resource("/spacecraft/sun_distance",
            spice_resource(self.spice, self.clock, self.epoch_et,
                          "MRO", "SUN", "J2000"))
    
    def get_altitude(self):
        """Compute current altitude above Mars surface."""
        sim_time = self.clock.get()
        et = duration_to_et(sim_time, self.epoch_et)
        pos = self.spice.position("MRO", "MARS", "J2000", et)
        distance = (pos[0]**2 + pos[1]**2 + pos[2]**2)**0.5
        mars_radius = 3396.19  # km
        return distance - mars_radius
    
    def get_earth_distance(self):
        """Compute current distance to Earth."""
        sim_time = self.clock.get()
        et = duration_to_et(sim_time, self.epoch_et)
        pos = self.spice.position("MRO", "EARTH", "J2000", et)
        return (pos[0]**2 + pos[1]**2 + pos[2]**2)**0.5
    
    def get_sun_distance(self):
        """Compute current distance to Sun."""
        sim_time = self.clock.get()
        et = duration_to_et(sim_time, self.epoch_et)
        pos = self.spice.position("MRO", "SUN", "J2000", et)
        return (pos[0]**2 + pos[1]**2 + pos[2]**2)**0.5


@MarsOrbiter.ActivityType
def take_image(mission: MarsOrbiter, target: str, exposure_time: float = 5.0):
    """
    Capture an image of a Mars surface target.
    
    This activity:
    - Checks altitude is suitable for imaging
    - Consumes battery power
    - Generates data for downlink
    - Records the image
    
    Args:
        target: Name of the target (e.g., "Olympus Mons")
        exposure_time: Image exposure time in minutes
    """
    # Check current altitude from SPICE
    altitude = mission.get_altitude()
    
    if altitude < 250.0:
        print(f"⚠️  Altitude too low for imaging: {altitude:.1f} km")
        print(f"   Skipping image of {target}")
        return
    
    if altitude > 400.0:
        print(f"⚠️  Altitude too high for imaging: {altitude:.1f} km")
        print(f"   Skipping image of {target}")
        return
    
    # Check battery level
    battery = mission.battery_charge.get()
    if battery < 20.0:
        print(f"⚠️  Battery too low for imaging: {battery:.1f}%")
        print(f"   Skipping image of {target}")
        return
    
    # Take the image
    print(f"📷 Imaging {target}")
    print(f"   Altitude: {altitude:.1f} km")
    print(f"   Battery: {battery:.1f}%")
    
    # Imaging takes time and consumes power
    delay(f"00:{int(exposure_time):02d}:00")
    
    # Update spacecraft state
    power_consumed = exposure_time * 2.0  # 2% per minute
    mission.battery_charge.set(battery - power_consumed)
    
    data_generated = 0.5  # GB per image
    current_data = mission.data_buffer.get()
    mission.data_buffer.set(current_data + data_generated)
    
    image_count = mission.images_taken.get()
    mission.images_taken.set(image_count + 1)
    
    print(f"   ✓ Image captured ({data_generated} GB)")
    print(f"   Battery: {mission.battery_charge.get():.1f}%")


@MarsOrbiter.ActivityType
def downlink_data(mission: MarsOrbiter):
    """
    Downlink stored data to Earth.
    
    This activity:
    - Checks Earth distance for communication feasibility
    - Transmits data buffer to Earth
    - Consumes battery power
    """
    # Check Earth distance from SPICE
    earth_distance = mission.get_earth_distance()
    light_seconds = earth_distance / 299792.458
    
    # Check if Earth is too far (superior conjunction)
    if earth_distance > 400_000_000:  # 400 million km
        print(f"⚠️  Earth too far for downlink: {earth_distance:,.0f} km")
        print(f"   One-way light time: {light_seconds/60:.1f} minutes")
        print( "   Skipping downlink (superior conjunction)")
        return
    
    # Check battery level
    battery = mission.battery_charge.get()
    if battery < 30.0:
        print(f"⚠️  Battery too low for downlink: {battery:.1f}%")
        return
    
    # Check if there's data to send
    data_buffer = mission.data_buffer.get()
    if data_buffer < 0.1:
        print(f"ℹ️  No data to downlink ({data_buffer:.2f} GB)")
        return
    
    # Perform downlink
    print( "📡 Downlinking data to Earth")
    print(f"   Distance: {earth_distance:,.0f} km ({light_seconds/60:.1f} min light time)")
    print(f"   Data: {data_buffer:.2f} GB")
    print(f"   Battery: {battery:.1f}%")
    
    # Downlink takes time based on data volume
    # Assume 2 Mbps data rate = 0.24 GB/min
    downlink_time = data_buffer / 0.24  # minutes
    delay(f"00:{int(downlink_time):02d}:00")
    
    # Update spacecraft state
    power_consumed = downlink_time * 3.0  # 3% per minute
    mission.battery_charge.set(battery - power_consumed)
    mission.data_buffer.set(0.0)
    
    print( "   ✓ Downlink complete")
    print(f"   Battery: {mission.battery_charge.get():.1f}%")


@MarsOrbiter.ActivityType
def charge_battery(mission: MarsOrbiter, duration_minutes: float = 30.0):
    """
    Charge batteries using solar panels.
    
    This activity:
    - Checks Sun distance for solar power availability
    - Charges batteries over time
    - Spacecraft is idle during charging
    
    Args:
        duration_minutes: How long to charge (minutes)
    """
    # Check Sun distance from SPICE
    sun_distance = mission.get_sun_distance()
    au = sun_distance / 149597870.7  # Convert km to AU
    
    # Solar flux decreases with distance squared
    solar_flux = 1361.0 / (au * au)  # W/m²
    
    battery = mission.battery_charge.get()
    
    print( "☀️  Charging batteries")
    print(f"   Sun distance: {sun_distance:,.0f} km ({au:.3f} AU)")
    print(f"   Solar flux: {solar_flux:.1f} W/m²")
    print(f"   Battery: {battery:.1f}%")
    
    # Charge for specified duration
    delay(f"00:{int(duration_minutes):02d}:00")
    
    # Charge rate depends on solar flux
    # Assume 3 m² panels, 30% efficiency
    charge_rate = (solar_flux * 3.0 * 0.30) / 100.0  # % per minute
    charge_gained = charge_rate * duration_minutes
    
    new_battery = min(100.0, battery + charge_gained)
    mission.battery_charge.set(new_battery)
    
    print( "   ✓ Charging complete")
    print(f"   Battery: {new_battery:.1f}% (+{new_battery - battery:.1f}%)")


def main():
    print("=" * 70)
    print("Mars Orbiter Mission - Realistic Spacecraft Operations")
    print("=" * 70)
    print()
    print("This simulation demonstrates:")
    print("  1. spice_resource for continuous tracking (no fake activities)")
    print("  2. Real spacecraft activities (imaging, downlink, charging)")
    print("  3. Activities read SPICE resources for operational decisions")
    print("  4. ISO 8601 duration format for activity scheduling (Aerie-compatible)")
    print("=" * 70)
    print()
    
    # Build a realistic mission schedule
    # Can use traditional HH:MM:SS format or ISO 8601 duration format (PT12H30M)
    schedule = Schedule.build(
        # Start with some imaging - using traditional format
        ("00:00:00", Directive("take_image", {"target": "Olympus Mons", "exposure_time": 5.0})),
        
        # Using ISO 8601 duration format (Aerie-compatible)
        (Duration.parse_iso8601("PT30M"), Directive("take_image", {"target": "Valles Marineris", "exposure_time": 3.0})),
        (Duration.parse_iso8601("PT1H"), Directive("take_image", {"target": "Gale Crater", "exposure_time": 4.0})),
        
        # Downlink the data - ISO 8601 format
        (Duration.parse_iso8601("PT2H"), Directive("downlink_data", {})),
        
        # Charge batteries - ISO 8601 format
        (Duration.parse_iso8601("PT3H"), Directive("charge_battery", {"duration_minutes": 45.0})),
        
        # More imaging - mixing formats for demonstration
        ("04:30:00", Directive("take_image", {"target": "Jezero Crater", "exposure_time": 5.0})),
        (Duration.parse_iso8601("PT5H"), Directive("take_image", {"target": "Hellas Basin", "exposure_time": 3.0})),
        
        # Final downlink - ISO 8601 format
        (Duration.parse_iso8601("PT6H"), Directive("downlink_data", {})),
    )
    
    try:
        profiles, spans, events = simulate(
            MarsOrbiter,
            schedule,
            "08:00:00"
        )
        
        print()
        print("=" * 70)
        print("Simulation Complete!")
        print("=" * 70)
        print(f"Activities executed: {len(spans)}")
        print(f"Resources tracked: {len(profiles)}")
        print()
        
        # Show activity timeline with ISO 8601 duration format
        print("Activity Timeline:")
        for span in spans:
            activity_name = span.type
            start_time = span.start
            # Show both traditional and ISO 8601 format
            iso_format = start_time.to_iso8601()
            print(f"  T+{start_time} ({iso_format}) - {activity_name}")
        
        print()
        print("Resources:")
        for resource_name in sorted(profiles.keys()):
            print(f"  - {resource_name}")
        print("=" * 70)
        
    except Exception as e:
        print(f"\n❌ Simulation failed: {e}")
        print("\nMake sure you have the MRO kernel files at the correct path.")
        import traceback
        traceback.print_exc()


if __name__ == "__main__":
    main()
