import pytest
from pymerlin.duration import Duration, SECONDS, HOURS, MINUTES, MICROSECONDS


def test_parse_iso8601_basic():
    """Test basic ISO 8601 duration parsing"""
    # Test hours
    d = Duration.parse_iso8601("PT1H")
    assert d.to_number_in(HOURS) == 1.0
    
    # Test minutes
    d = Duration.parse_iso8601("PT30M")
    assert d.to_number_in(MINUTES) == 30.0
    
    # Test seconds
    d = Duration.parse_iso8601("PT45S")
    assert d.to_number_in(SECONDS) == 45.0


def test_parse_iso8601_combined():
    """Test ISO 8601 duration with combined units"""
    # Hours and minutes
    d = Duration.parse_iso8601("PT1H30M")
    assert d.to_number_in(MINUTES) == 90.0
    
    # Hours, minutes, and seconds
    d = Duration.parse_iso8601("PT12H30M45S")
    expected = (12 * 3600) + (30 * 60) + 45
    assert d.to_number_in(SECONDS) == expected
    
    # All components
    d = Duration.parse_iso8601("P1DT12H30M45S")
    expected = (1 * 24 * 3600) + (12 * 3600) + (30 * 60) + 45
    assert d.to_number_in(SECONDS) == expected


def test_parse_iso8601_fractional_seconds():
    """Test ISO 8601 duration with fractional seconds"""
    d = Duration.parse_iso8601("PT45.5S")
    assert abs(d.to_number_in(SECONDS) - 45.5) < 1e-6
    
    d = Duration.parse_iso8601("PT1.123456S")
    assert abs(d.to_number_in(SECONDS) - 1.123456) < 1e-6


def test_parse_iso8601_days():
    """Test ISO 8601 duration with days"""
    d = Duration.parse_iso8601("P1D")
    assert d.to_number_in(HOURS) == 24.0
    
    d = Duration.parse_iso8601("P2D")
    assert d.to_number_in(HOURS) == 48.0
    
    d = Duration.parse_iso8601("P1DT12H")
    assert d.to_number_in(HOURS) == 36.0


def test_to_iso8601_basic():
    """Test basic Duration to ISO 8601 conversion"""
    # Hours only
    d = Duration.of(1, HOURS)
    assert d.to_iso8601() == "PT1H"
    
    # Minutes only
    d = Duration.of(30, MINUTES)
    assert d.to_iso8601() == "PT30M"
    
    # Seconds only
    d = Duration.of(45, SECONDS)
    assert d.to_iso8601() == "PT45S"


def test_to_iso8601_combined():
    """Test Duration to ISO 8601 with multiple components"""
    # Hours and minutes
    d = Duration.of(1, HOURS).plus(Duration.of(30, MINUTES))
    assert d.to_iso8601() == "PT1H30M"
    
    # Hours, minutes, and seconds
    d = Duration.of(12, HOURS).plus(Duration.of(30, MINUTES)).plus(Duration.of(45, SECONDS))
    assert d.to_iso8601() == "PT12H30M45S"


def test_to_iso8601_fractional():
    """Test Duration to ISO 8601 with fractional seconds"""
    d = Duration.of(45, SECONDS).plus(Duration.of(500000, MICROSECONDS))
    result = d.to_iso8601()
    assert "PT45.5S" in result or "PT45.500000S" in result


def test_roundtrip_iso8601():
    """Test roundtrip conversion: ISO 8601 -> Duration -> ISO 8601"""
    test_cases = [
        "PT1H",
        "PT30M",
        "PT45S",
        "PT1H30M",
        "PT12H30M45S",
    ]
    
    for original in test_cases:
        parsed = Duration.parse_iso8601(original)
        back = parsed.to_iso8601()
        # Parse again to compare values (format might differ slightly)
        reparsed = Duration.parse_iso8601(back)
        assert parsed == reparsed, f"Roundtrip failed for {original}: {original} -> {back}"


def test_parse_iso8601_zero():
    """Test parsing zero duration"""
    d = Duration.parse_iso8601("PT0S")
    assert d == Duration.ZERO


def test_to_iso8601_zero():
    """Test converting zero duration to ISO 8601"""
    result = Duration.ZERO.to_iso8601()
    assert result == "PT0S"


def test_parse_iso8601_large_values():
    """Test parsing large duration values"""
    # 100 hours
    d = Duration.parse_iso8601("PT100H")
    assert d.to_number_in(HOURS) == 100.0
    
    # 1000 minutes
    d = Duration.parse_iso8601("PT1000M")
    assert d.to_number_in(MINUTES) == 1000.0


def test_parse_iso8601_invalid_format():
    """Test that invalid ISO 8601 formats raise errors"""
    with pytest.raises(ValueError):
        Duration.parse_iso8601("12H30M")  # Missing PT prefix
    
    with pytest.raises(ValueError):
        Duration.parse_iso8601("invalid")  # Not ISO 8601
    
    with pytest.raises(ValueError):
        Duration.parse_iso8601("T12H")  # Missing P prefix


def test_from_string_still_works():
    """Test that original from_string method still works"""
    d = Duration.from_string("12:30:45")
    assert d.to_number_in(HOURS) == 12.5125
    
    d = Duration.from_string("+01:00:00")
    assert d.to_number_in(HOURS) == 1.0
    
    d = Duration.from_string("-01:00:00")
    assert d.to_number_in(HOURS) == -1.0


def test_iso8601_vs_from_string():
    """Test that ISO 8601 and from_string produce same results for equivalent durations"""
    # 12 hours
    d1 = Duration.parse_iso8601("PT12H")
    d2 = Duration.from_string("12:00:00")
    assert d1 == d2
    
    # 1.5 hours (90 minutes)
    d1 = Duration.parse_iso8601("PT90M")
    d2 = Duration.from_string("01:30:00")
    assert d1 == d2
