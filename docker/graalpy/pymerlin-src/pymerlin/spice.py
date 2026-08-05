"""
SPICE integration for pymerlin

This module provides utilities for using NASA's SPICE toolkit within pymerlin simulations.
SPICE (Spacecraft Planet Instrument C-matrix Events) is used for computing geometric information
about spacecraft, planets, and other solar system bodies.

The module supports multiple time formats for Aerie compatibility:
- ISO 8601: "2024-01-01T00:00:00Z"
- DOY (Day of Year): "2024-001T00:00:00"
- SPICE native: "2024 JAN 01 00:00:00"

To use this module, install pymerlin with the spice extra:
    pip install pymerlin[spice]

Example usage:
    from pymerlin import MissionModel
    from pymerlin.spice import SpiceKernel, spice_resource
    from pymerlin.clock import clock
    
    @MissionModel
    class MyMission:
        def __init__(self, registrar):
            # Initialize clock
            clock_maker = clock(registrar)
            self.clock = clock_maker._system_clock
            
            # Initialize SPICE
            self.spice = SpiceKernel(registrar, kernel_paths=[
                "/path/to/naif0012.tls",  # Leap seconds kernel
                "/path/to/de440.bsp",      # Planetary ephemeris
                "/path/to/spacecraft.bsp"  # Spacecraft ephemeris
            ])
            self.spice.load_kernels()
            
            # Use ISO 8601 or DOY format for Aerie compatibility
            self.epoch_et = self.spice.utc_to_et("2024-096T12:00:00")  # DOY format
            # or: self.epoch_et = self.spice.utc_to_et("2024-04-05T12:00:00Z")  # ISO 8601
            
            # Create a resource that computes spacecraft distance at simulation time
            registrar.resource("/spacecraft/distance", 
                spice_resource(self.spice, self.clock, self.epoch_et,
                              "SPACECRAFT", "EARTH", "J2000"))
"""

import re
from typing import List, Callable, Tuple

try:
    import spiceypy as spice
    SPICE_AVAILABLE = True
except ImportError:
    SPICE_AVAILABLE = False
    spice = None

from .duration import Duration, SECONDS, _parse_iso8601_timestamp, _parse_doy


class SpiceKernel:
    """
    Manages SPICE kernel loading and provides utilities for SPICE computations.
    
    This class handles kernel lifecycle within a pymerlin simulation, ensuring
    kernels are loaded at simulation start and unloaded at simulation end.
    """
    
    def __init__(self, registrar, kernel_paths: List[str]):
        """
        Initialize SPICE kernel manager.
        
        Args:
            registrar: The mission model registrar
            kernel_paths: List of paths to SPICE kernel files to load
        """
        if not SPICE_AVAILABLE:
            raise ImportError(
                "spiceypy is not installed. Install it with: pip install pymerlin[spice]"
            )
        
        self.kernel_paths = kernel_paths
        self._kernels_loaded = False
    
    def load_kernels(self):
        """Load all configured SPICE kernels."""
        if self._kernels_loaded:
            return
        
        for kernel_path in self.kernel_paths:
            spice.furnsh(kernel_path)
        
        self._kernels_loaded = True
    
    def unload_kernels(self):
        """Unload all SPICE kernels."""
        if not self._kernels_loaded:
            return
        
        spice.kclear()
        self._kernels_loaded = False
    
    def position(self, target: str, observer: str, frame: str, et: float) -> Tuple[float, float, float]:
        """
        Compute position of target relative to observer.
        
        Args:
            target: Name of target body
            observer: Name of observing body
            frame: Reference frame (e.g., "J2000")
            et: Ephemeris time (seconds past J2000)
        
        Returns:
            Tuple of (x, y, z) position in km
        """
        if not self._kernels_loaded:
            self.load_kernels()
        
        state, _ = spice.spkez(
            spice.bodn2c(target),
            et,
            frame,
            "NONE",
            spice.bodn2c(observer)
        )
        return state[0], state[1], state[2]
    
    def velocity(self, target: str, observer: str, frame: str, et: float) -> Tuple[float, float, float]:
        """
        Compute velocity of target relative to observer.
        
        Args:
            target: Name of target body
            observer: Name of observing body
            frame: Reference frame (e.g., "J2000")
            et: Ephemeris time (seconds past J2000)
        
        Returns:
            Tuple of (vx, vy, vz) velocity in km/s
        """
        if not self._kernels_loaded:
            self.load_kernels()
        
        state, _ = spice.spkez(
            spice.bodn2c(target),
            et,
            frame,
            "NONE",
            spice.bodn2c(observer)
        )
        return state[3], state[4], state[5]
    
    def state(self, target: str, observer: str, frame: str, et: float) -> Tuple[float, float, float, float, float, float]:
        """
        Compute full state (position and velocity) of target relative to observer.
        
        Args:
            target: Name of target body
            observer: Name of observing body
            frame: Reference frame (e.g., "J2000")
            et: Ephemeris time (seconds past J2000)
        
        Returns:
            Tuple of (x, y, z, vx, vy, vz)
        """
        if not self._kernels_loaded:
            self.load_kernels()
        
        state, _ = spice.spkez(
            spice.bodn2c(target),
            et,
            frame,
            "NONE",
            spice.bodn2c(observer)
        )
        return tuple(state)
    
    def utc_to_et(self, utc_string: str) -> float:
        """
        Convert UTC time string to ephemeris time.
        
        Supports multiple time formats:
        - SPICE native: "2024 JAN 01 00:00:00"
        - ISO 8601: "2024-01-01T00:00:00Z" or "2024-01-01T00:00:00+00:00"
        - DOY (Day of Year): "2024-001T00:00:00"
        
        Args:
            utc_string: UTC time string in any supported format
        
        Returns:
            Ephemeris time in seconds past J2000
            
        Examples:
            >>> spice.utc_to_et("2024-01-01T00:00:00Z")  # ISO 8601
            >>> spice.utc_to_et("2024-001T00:00:00")     # DOY format
            >>> spice.utc_to_et("2024 JAN 01 00:00:00")  # SPICE native
        """
        if not self._kernels_loaded:
            self.load_kernels()
        
        # Try to detect format and convert to SPICE-compatible format if needed
        # Check DOY first since it's more specific (3-digit day)
        if self._is_doy_format(utc_string):
            # Convert DOY to SPICE format via datetime
            dt = _parse_doy(utc_string)
            # Convert to SPICE DOY format: "YYYY-DDD::HR:MN:SC.###"
            doy = dt.timetuple().tm_yday
            time_part = dt.strftime("%H:%M:%S.%f")
            spice_format = f"{dt.year}-{doy:03d}::{time_part}"
            return spice.str2et(spice_format)
        
        elif self._is_iso8601_format(utc_string):
            # Convert ISO 8601 to SPICE format via datetime
            dt = _parse_iso8601_timestamp(utc_string)
            # Convert to SPICE calendar format: "YYYY MON DD HR:MN:SC.###"
            spice_format = dt.strftime("%Y %b %d %H:%M:%S.%f")
            return spice.str2et(spice_format.upper())
        
        else:
            # Assume SPICE native format
            return spice.str2et(utc_string)
    
    def _is_doy_format(self, time_string: str) -> bool:
        """Check if string is in DOY format (YYYY-DDDTHH:MM:SS)."""
        # Must have exactly 3 digits after the dash before T
        return bool(re.match(r'^\d{4}-\d{3}T\d{2}:\d{2}:\d{2}', time_string))
    
    def _is_iso8601_format(self, time_string: str) -> bool:
        """Check if string is in ISO 8601 format (YYYY-MM-DDTHH:MM:SS)."""
        # Must have 2 digits for month and day (YYYY-MM-DD)
        return bool(re.match(r'^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}', time_string))
    
    def et_to_utc(self, et: float, format: str = "ISOC", precision: int = 3) -> str:
        """
        Convert ephemeris time to UTC string.
        
        Args:
            et: Ephemeris time in seconds past J2000
            format: Output format ("ISOC", "ISOD", "C", "D", "J")
            precision: Number of decimal places for seconds
        
        Returns:
            UTC time string
        """
        if not self._kernels_loaded:
            self.load_kernels()
        
        return spice.et2utc(et, format, precision)


def spice_resource(spice_kernel: SpiceKernel, 
                   clock_cell,
                   epoch_et: float,
                   target: str, 
                   observer: str, 
                   frame: str, 
                   computation_fn: Callable = None) -> Callable:
    """
    Create a resource function that computes SPICE-derived scalar values at simulation time.
    
    Note: Resources must return Java-serializable types (numbers, strings, booleans).
    Raw SPICE tuples (position, velocity, state) cannot be directly returned as resources.
    Use this helper to compute derived scalar quantities like distance, speed, etc.
    
    Args:
        spice_kernel: SpiceKernel instance
        clock_cell: Clock cell that provides current simulation time
        epoch_et: Ephemeris time at simulation start (seconds past J2000)
        target: Name of target body
        observer: Name of observing body
        frame: Reference frame (e.g., "J2000")
        computation_fn: Optional function that takes (position_tuple) and returns a scalar.
                       If None, returns distance magnitude by default.
    
    Returns:
        A function that can be registered as a resource
    
    Example:
        # Compute distance
        registrar.resource("/spacecraft/distance", 
            spice_resource(self.spice, self.clock, self.epoch_et,
                          "SPACECRAFT", "EARTH", "J2000"))
        
        # Compute custom quantity
        def compute_altitude(pos):
            distance = (pos[0]**2 + pos[1]**2 + pos[2]**2)**0.5
            return distance - 6371.0  # Subtract Earth radius
        
        registrar.resource("/spacecraft/altitude",
            spice_resource(self.spice, self.clock, self.epoch_et,
                          "SPACECRAFT", "EARTH", "J2000", compute_altitude))
    """
    
    if computation_fn is None:
        # Default: compute distance magnitude
        def default_distance(pos):
            return (pos[0]**2 + pos[1]**2 + pos[2]**2)**0.5
        computation_fn = default_distance
    
    def resource_fn():
        # Get current simulation time
        sim_time = clock_cell.get()
        
        # Convert to ephemeris time
        et = duration_to_et(sim_time, epoch_et)
        
        # Compute position
        position = spice_kernel.position(target, observer, frame, et)
        
        # Apply computation function to get scalar result
        return float(computation_fn(position))
    
    return resource_fn


def duration_to_et(duration: Duration, epoch_et: float = 0.0) -> float:
    """
    Convert a pymerlin Duration to SPICE ephemeris time.
    
    Args:
        duration: Duration since simulation start
        epoch_et: Ephemeris time at simulation start (seconds past J2000)
    
    Returns:
        Ephemeris time in seconds past J2000
    """
    return epoch_et + duration.to_number_in(SECONDS)


def et_to_duration(et: float, epoch_et: float = 0.0) -> Duration:
    """
    Convert SPICE ephemeris time to a pymerlin Duration.
    
    Args:
        et: Ephemeris time in seconds past J2000
        epoch_et: Ephemeris time at simulation start (seconds past J2000)
    
    Returns:
        Duration since simulation start
    """
    seconds = et - epoch_et
    return Duration.of(seconds, SECONDS)


def iso8601_to_et(spice_kernel: SpiceKernel, iso_string: str) -> float:
    """
    Convert ISO 8601 timestamp to SPICE ephemeris time.
    
    This is a convenience wrapper around SpiceKernel.utc_to_et() for ISO 8601 format.
    
    Args:
        spice_kernel: SpiceKernel instance with loaded kernels
        iso_string: ISO 8601 formatted timestamp (e.g., "2024-01-01T00:00:00Z")
    
    Returns:
        Ephemeris time in seconds past J2000
        
    Example:
        >>> epoch_et = iso8601_to_et(spice, "2024-04-05T12:00:00Z")
    """
    return spice_kernel.utc_to_et(iso_string)


def doy_to_et(spice_kernel: SpiceKernel, doy_string: str) -> float:
    """
    Convert DOY (Day of Year) timestamp to SPICE ephemeris time.
    
    This is a convenience wrapper around SpiceKernel.utc_to_et() for DOY format.
    
    Args:
        spice_kernel: SpiceKernel instance with loaded kernels
        doy_string: DOY formatted timestamp (e.g., "2024-096T12:00:00")
    
    Returns:
        Ephemeris time in seconds past J2000
        
    Example:
        >>> epoch_et = doy_to_et(spice, "2024-096T12:00:00")
    """
    return spice_kernel.utc_to_et(doy_string)


__all__ = [
    "SpiceKernel",
    "spice_resource",
    "duration_to_et",
    "et_to_duration",
    "iso8601_to_et",
    "doy_to_et",
    "SPICE_AVAILABLE"
]
