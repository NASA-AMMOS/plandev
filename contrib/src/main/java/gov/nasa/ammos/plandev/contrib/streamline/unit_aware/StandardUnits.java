package gov.nasa.jpl.aerie.contrib.streamline.unit_aware;

/**
 * Collection of standard units, including all SI base units.
 */
public final class StandardUnits {
  private StandardUnits() {}

  // Base units, should correspond 1-1 with base Dimensions
  public static final Unit SECOND = Unit.createBase("s", "second", StandardDimensions.TIME);
  public static final Unit METER = Unit.createBase("m", "meter", StandardDimensions.LENGTH);
  public static final Unit KILOGRAM = Unit.createBase("kg", "kilogram", StandardDimensions.MASS);
  public static final Unit AMPERE = Unit.createBase("A", "ampere", StandardDimensions.CURRENT);
  public static final Unit KELVIN = Unit.createBase("K", "Kelvin", StandardDimensions.TEMPERATURE);
  public static final Unit CANDELA = Unit.createBase("cd", "candela", StandardDimensions.LUMINOUS_INTENSITY);
  public static final Unit MOLE = Unit.createBase("mol", "mole", StandardDimensions.AMOUNT);
  public static final Unit BIT = Unit.createBase("b", "bit", StandardDimensions.INFORMATION);
  public static final Unit RADIAN = Unit.createBase("rad", "radian", StandardDimensions.ANGLE);

  // REVIEW: What derived units should be included here?
  // Including a few arbitrarily to show a few different styles of derivation
  public static final Unit MILLISECOND = Unit.derived("ms", "millisecond", 1e-3, SECOND);
  public static final Unit MINUTE = Unit.derived("min", "minute", 60, SECOND);
  public static final Unit HOUR = Unit.derived("hr", "hour", 60, MINUTE);
  public static final Unit KILOMETER = Unit.derived("km", "kilometer", 1000, METER);
  public static final Unit BYTE = Unit.derived("B", "byte", 8, BIT);
  public static final Unit NEWTON = Unit.derived("N", "newton", KILOGRAM.multiply(METER).divide(SECOND.power(2)));
  public static final Unit MEGABIT_PER_SECOND = Unit.derived("Mbps", "megabit per second", 1e6, BIT.divide(SECOND));
  public static final Unit DEGREE = Unit.derived("deg", "degree", 180 / Math.PI, RADIAN);

  public static final Unit JOULE = Unit.derived("J", "joule", NEWTON.multiply(METER));
  public static final Unit WATT = Unit.derived("W", "watt", JOULE.divide(SECOND));
  public static final Unit COULOMB = Unit.derived("C", "coulomb", AMPERE.multiply(SECOND));
  public static final Unit VOLT = Unit.derived("V", "volt", JOULE.divide(COULOMB));

  /**
   * Astronomical unit as defined by
   * <a href="https://www.iau.org/static/resolutions/IAU2012_English.pdf">IAU 2012 Resolution B2</a>.
   */
  public static final Unit ASTRONOMICAL_UNIT = Unit.derived("au", "astronomical unit", 149_597_870_700.0, METER);
}
