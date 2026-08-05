# Using SPICE for Geometry

The SPICE toolkit, provided by NASA JPL's NAIF, is popular for use in orbital calculations. [spiceypy](https://spiceypy.readthedocs.io/)
provides python bindings to that toolkit.

pymerlin provides integration with SPICE through the `pymerlin.spice` module, allowing you to compute spacecraft positions, velocities, and other geometric quantities within your simulations.

## Installation

To use SPICE with pymerlin, install the optional `spice` dependency:

```bash
pip install pymerlin[spice]
```

## Getting SPICE Kernels

SPICE requires kernel files that contain the data needed for computations. You'll typically need:

- **Leap seconds kernel** (e.g., `naif0012.tls`) - Contains leap second information
- **Planetary ephemeris** (e.g., `de440.bsp`) - Contains planetary positions
- **Spacecraft ephemeris** (e.g., `spacecraft.bsp`) - Contains your spacecraft's trajectory

Download kernels from the [NAIF website](https://naif.jpl.nasa.gov/naif/data.html).

## Basic Usage

### Setting up SPICE in Your Mission Model

```python
from pymerlin import MissionModel
from pymerlin.spice import SpiceKernel
from pymerlin.clock import clock

@MissionModel
class MyMission:
    def __init__(self, registrar):
        # Initialize clock for time tracking
        self.clock = clock(registrar)
        
        # Initialize SPICE with kernel files
        self.spice = SpiceKernel(registrar, kernel_paths=[
            "/path/to/naif0012.tls",
            "/path/to/de440.bsp",
            "/path/to/spacecraft.bsp"
        ])
        
        # Load kernels
        self.spice.load_kernels()
        
        # Set simulation epoch in ephemeris time
        self.epoch_et = self.spice.utc_to_et("2024-01-01T00:00:00")
```

### Computing Positions

```python
from pymerlin.spice import duration_to_et

@MyMission.ActivityType
def compute_position(mission):
    # Get current simulation time
    sim_time = mission.clock.get()
    
    # Convert to ephemeris time
    et = duration_to_et(sim_time, mission.epoch_et)
    
    # Compute position of Moon relative to Earth in J2000 frame
    position = mission.spice.position("MOON", "EARTH", "J2000", et)
    print(f"Moon position (km): {position}")
    
    # Compute velocity
    velocity = mission.spice.velocity("MOON", "EARTH", "J2000", et)
    print(f"Moon velocity (km/s): {velocity}")
    
    # Get full state (position + velocity)
    state = mission.spice.state("MOON", "EARTH", "J2000", et)
    print(f"Moon state: {state}")
```

### Creating SPICE-based Resources

You can create resources that expose SPICE computations:

```python
@MissionModel
class MyMission:
    def __init__(self, registrar):
        self.clock = clock(registrar)
        self.spice = SpiceKernel(registrar, kernel_paths=[...])
        self.spice.load_kernels()
        self.epoch_et = self.spice.utc_to_et("2024-01-01T00:00:00")
        
        # Create a resource that computes spacecraft distance from Earth
        def spacecraft_distance():
            sim_time = self.clock.get()
            et = duration_to_et(sim_time, self.epoch_et)
            pos = self.spice.position("SPACECRAFT", "EARTH", "J2000", et)
            return (pos[0]**2 + pos[1]**2 + pos[2]**2)**0.5
        
        registrar.resource("/spacecraft/distance", spacecraft_distance)
```

## Time Conversion

pymerlin provides utilities to convert between simulation time and SPICE ephemeris time:

```python
from pymerlin.spice import duration_to_et, et_to_duration
from pymerlin.duration import Duration

# Convert Duration to ephemeris time
sim_time = Duration.from_string("12:00:00")  # 12 hours into simulation
et = duration_to_et(sim_time, epoch_et=0.0)

# Convert ephemeris time back to Duration
duration = et_to_duration(et, epoch_et=0.0)
```

## Complete Example

See `demo/spice_example.py` for a complete working example that demonstrates:
- Setting up SPICE kernels
- Computing positions during activities
- Creating SPICE-based resources
- Periodic position updates

## API Reference

### SpiceKernel Class

**`SpiceKernel(registrar, kernel_paths)`**
- Manages SPICE kernel loading and provides computation utilities
- `kernel_paths`: List of paths to SPICE kernel files

**Methods:**
- `load_kernels()` - Load all configured kernels
- `unload_kernels()` - Unload all kernels
- `position(target, observer, frame, et)` - Compute position vector
- `velocity(target, observer, frame, et)` - Compute velocity vector
- `state(target, observer, frame, et)` - Compute full state vector
- `utc_to_et(utc_string)` - Convert UTC to ephemeris time
- `et_to_utc(et, format, precision)` - Convert ephemeris time to UTC

### Utility Functions

**`duration_to_et(duration, epoch_et)`**
- Convert pymerlin Duration to SPICE ephemeris time

**`et_to_duration(et, epoch_et)`**
- Convert SPICE ephemeris time to pymerlin Duration

## Additional Resources

- [SPICE Toolkit Documentation](https://naif.jpl.nasa.gov/naif/toolkit.html)
- [spiceypy Documentation](https://spiceypy.readthedocs.io/)
- [NAIF Data Archive](https://naif.jpl.nasa.gov/naif/data.html)
