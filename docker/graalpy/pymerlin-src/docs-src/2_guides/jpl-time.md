# JPL Time Format Implementation

## Overview

This document describes the implementation of JPL/PlanDev time format support in pymerlin. The implementation matches PlanDev's approach to time handling, distinguishing between:
- **Duration**: Relative time intervals (ISO 8601 duration format)
- **SPICE**: Absolute timestamps (ISO 8601 and DOY timestamp formats)

## What Was Implemented

### 1. ISO 8601 Duration Support (PlanDev-Compatible)

Added methods to the `Duration` class matching PlanDev's `Duration.parseISO8601()` and `Duration.toISO8601()`:

- **`Duration.parse_iso8601(iso8601_string)`**: Parse an ISO 8601 duration string
- **`duration.to_iso8601()`**: Convert a Duration to an ISO 8601 duration string

**Supported ISO 8601 duration formats:**
- `PT12H30M45S` (12 hours, 30 minutes, 45 seconds)
- `PT1H` (1 hour)
- `PT30M` (30 minutes)
- `PT45.5S` (45.5 seconds)
- `P1DT12H` (1 day and 12 hours)

**Note**: These parse **duration/interval** format (e.g., `PT12H`), not timestamp format (e.g., `2024-01-01T12:00:00Z`). This matches PlanDev's Duration class design.

### 2. SPICE Integration (Absolute Timestamps)

Extended `SpiceKernel` class to accept JPL time formats:

- **`SpiceKernel.utc_to_et(utc_string)`**: Now accepts ISO 8601, DOY, and SPICE native formats
  - Automatically detects format and converts appropriately
  - ISO 8601 → SPICE calendar format: `"YYYY MON DD HH:MM:SS.ffffff"`
  - DOY → SPICE DOY format: `"YYYY-DDD::HH:MM:SS.ffffff"`
  
Added convenience functions in `spice.py`:

- **`iso8601_to_et(spice_kernel, iso_string)`**: Direct ISO 8601 to ephemeris time conversion
- **`doy_to_et(spice_kernel, doy_string)`**: Direct DOY to ephemeris time conversion

## Key Features

1. **PlanDev Compatibility**: Duration methods match PlanDev's `parseISO8601()` and `toISO8601()` API
2. **Microsecond Precision**: All conversions maintain microsecond precision
3. **ISO 8601 Duration Format**: Supports standard duration format (`PT12H30M45S`)
4. **Negative Durations**: Supports negative time intervals
5. **SPICE Timestamp Support**: Handles ISO 8601 and DOY timestamp formats for absolute time
6. **Automatic Format Detection**: SPICE methods automatically detect and convert timestamp formats

## Usage Examples

### ISO 8601 Duration Format (PlanDev-Compatible)

```python
from pymerlin.duration import Duration, HOURS, MINUTES

# Parse ISO 8601 duration strings
d1 = Duration.parse_iso8601("PT12H30M45S")  # 12 hours, 30 minutes, 45 seconds
print(d1)  # +12:30:45.000000

d2 = Duration.parse_iso8601("PT1H")  # 1 hour
print(d2.to_number_in(HOURS))  # 1.0

d3 = Duration.parse_iso8601("PT90M")  # 90 minutes
print(d3.to_number_in(HOURS))  # 1.5

# Convert Duration to ISO 8601 format
duration = Duration.of(12, HOURS).plus(Duration.of(30, MINUTES))
print(duration.to_iso8601())  # "PT12H30M"

# Roundtrip conversion
original = "PT2H15M30.5S"
parsed = Duration.parse_iso8601(original)
back = parsed.to_iso8601()
print(f"{original} -> {parsed} -> {back}")
```

### Mission Planning with Durations

```python
from pymerlin.duration import Duration, HOURS, MINUTES

# Define activities as relative time intervals
activities = [
    (Duration.ZERO, "Launch"),
    (Duration.of(2, HOURS), "Orbit Insertion"),
    (Duration.of(6, HOURS).plus(Duration.of(30, MINUTES)), "Deploy Solar Panels"),
    (Duration.parse_iso8601("PT12H"), "First Downlink"),
]

# Display activity schedule
for duration, activity in activities:
    print(f"T+{duration} - {activity}")
    # Output: T++00:00:00.000000 - Launch
    #         T++02:00:00.000000 - Orbit Insertion
    #         T++06:30:00.000000 - Deploy Solar Panels
    #         T++12:00:00.000000 - First Downlink
```

## Integration with SPICE (Absolute Timestamps)

SPICE handles **absolute timestamps** (points in time), while Duration handles **relative intervals**. SPICE's `utc_to_et()` automatically accepts multiple timestamp formats:

```python
from pymerlin.spice import SpiceKernel, iso8601_to_et, doy_to_et, duration_to_et
from pymerlin.duration import Duration, HOURS

spice = SpiceKernel(registrar, kernel_paths=[...])
spice.load_kernels()

# Parse absolute timestamps to ephemeris time (ET)
# All three timestamp formats work automatically:
epoch_et_iso = spice.utc_to_et("2024-04-05T12:00:00Z")      # ISO 8601 timestamp
epoch_et_doy = spice.utc_to_et("2024-096T12:00:00")         # DOY timestamp
epoch_et_native = spice.utc_to_et("2024 APR 05 12:00:00")  # SPICE native

# Or use convenience functions
epoch_et = iso8601_to_et(spice, "2024-04-05T12:00:00Z")
epoch_et = doy_to_et(spice, "2024-096T12:00:00")

# Convert simulation time (Duration) to absolute time (ET)
sim_time = Duration.of(3, HOURS)  # 3 hours into simulation
absolute_et = duration_to_et(sim_time, epoch_et)  # Absolute ephemeris time

# Use Duration's ISO 8601 format for time intervals (not timestamps!)
interval = Duration.parse_iso8601("PT3H")  # 3 hour interval
print(interval.to_iso8601())  # "PT3H"
```

## Testing

### Duration Tests (`tests/test_jpl_time.py`)

Comprehensive tests for Duration class ISO 8601 duration support:

- Basic ISO 8601 duration parsing (`PT12H30M45S`)
- ISO 8601 with fractional seconds (`PT45.5S`)
- ISO 8601 with days (`P1DT12H`)
- Various duration formats (`PT1H`, `PT30M`, etc.)
- Roundtrip conversions (parse → Duration → to_iso8601)
- Negative durations
- Edge cases (zero duration, very large durations)
- Invalid format error handling

### SPICE Integration Tests (`tests/test_spice.py`)

Extended SPICE tests to cover JPL time formats:

- `test_spice_iso8601_format()` - ISO 8601 format acceptance in `utc_to_et()`
- `test_spice_doy_format()` - DOY format acceptance in `utc_to_et()`
- `test_iso8601_to_et_helper()` - ISO 8601 convenience function
- `test_doy_to_et_helper()` - DOY convenience function

Run tests with:
```bash
python -m pytest tests/test_jpl_time.py -v
python -m pytest tests/test_spice.py -v
```

## Files Modified

- **`pymerlin/duration.py`**: 
  - Added `from_iso8601()`, `from_doy()`, `to_iso8601()`, `to_doy()` static/instance methods
  - Added `_parse_iso8601()` and `_parse_doy()` helper functions
  - Added imports: `datetime`, `timedelta`, `timezone`, `re`

- **`pymerlin/spice.py`**: 
  - Extended `SpiceKernel.utc_to_et()` to auto-detect and convert ISO 8601 and DOY formats
  - Added `_is_iso8601_format()` and `_is_doy_format()` helper methods
  - Added `iso8601_to_et()` and `doy_to_et()` convenience functions
  - Updated module docstring with JPL time format examples
  - Added imports: `re`, `_parse_iso8601`, `_parse_doy` from duration module

- **`tests/test_spice.py`**:
  - Added 4 new tests for JPL time format support in SPICE integration
  - Updated imports to include `iso8601_to_et` and `doy_to_et`

## Files Created

- **`tests/test_jpl_time.py`**: Comprehensive test suite (20+ tests) for JPL time formats
- **`JPL_TIME_IMPLEMENTATION.md`**: This documentation file

## Implementation Status

### ✅ Completed

1. **Duration class extensions** - Added ISO 8601 and DOY format parsers and converters
2. **SPICE integration** - Extended `utc_to_et()` to support ISO 8601 and DOY formats
3. **Comprehensive testing** - Added 20+ tests for Duration and 4 tests for SPICE integration

### 🔄 Remaining Steps

4. **Update documentation** - Add JPL time format examples to SPICE guide (`docs-src/2_guides/spice.md`)
5. **PlanDev validation** - Verify compatibility with PlanDev's time representation (if applicable)

## Compatibility

- **Python Version**: Requires Python 3.7+ (uses `datetime.fromisoformat`)
- **Dependencies**: Uses only Python standard library (`datetime`, `re`)
- **Backward Compatibility**: All existing Duration functionality remains unchanged

## Technical Notes

### Time Representation

- All timestamps are converted to UTC internally
- Duration calculations use microsecond precision (matching pymerlin's internal representation)
- Epoch-based approach allows working with absolute timestamps while maintaining Duration's relative time semantics

### Timezone Handling

- ISO 8601 parser converts all timestamps to UTC
- 'Z' suffix is treated as UTC (+00:00)
- Timezone offsets are properly handled and converted

### Leap Year Support

- DOY format correctly handles leap years (366 days)
- Day 60 in a leap year is February 29
- Day 366 in a leap year is December 31

### SPICE Format Conversion

When converting JPL time formats to SPICE:

- **ISO 8601** → SPICE calendar format: `"2024 JAN 01 12:00:00.000000"`
  - Uses month abbreviations (JAN, FEB, etc.)
  - Uppercase format required by SPICE
  
- **DOY** → SPICE DOY format: `"2024-001::12:00:00.000000"`
  - Uses `::` delimiter to indicate DOY format
  - Maintains 3-digit day-of-year with zero padding
  
- Format detection uses regex patterns:
  - DOY: `^\d{4}-\d{3}T` (3 digits after dash)
  - ISO 8601: `^\d{4}-\d{2}-\d{2}T` (2 digits for month and day)
