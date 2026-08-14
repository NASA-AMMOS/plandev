package gov.nasa.jpl.aerie.contrib.streamline.unit_aware;

import java.util.Objects;

/**
 * A unit of measure in the QUDV system.
 */
public final class Unit {
  public static final Unit SCALAR = new Unit(Dimension.SCALAR, 1, "(scalar)", "(scalar)");

  public final Dimension dimension;
  public final double multiplier;
  public final String longName;
  public final String shortName;

  private Unit(final Dimension dimension, final double multiplier) {
    this(dimension, multiplier, null, null);
  }

  private Unit(final Dimension dimension, final double multiplier, final String longName, final String shortName) {
    this.dimension = dimension;
    this.multiplier = multiplier;
    this.longName = longName;
    this.shortName = shortName;
  }

  public static Unit createBase(final String shortName, final String longName, final Dimension dimension) {
    // TODO: Track base units, to detect and prevent collisions
    assert(dimension.isBase());
    return new Unit(dimension, 1, longName, shortName);
  }

  /**
   * Create a "local" unit. Local units denote a locally-relevant concept that doesn't need to be integrated across models into the broader unit system.
   *
   * <p>
   * Local units are given their own unique base dimension with the same name, so are distinct from all other base dimensions.
   * </p>
   *
   * <p>
   *     For example, to record that instrument A takes 6 observations per hour, we could declare a local unit in the instrument A model for observations and use it to derive "observations / hour", like so:
   *     <br/>
   *     <code>
   *         Unit Observations = Unit.createLocalUnit("observations");<br/>
   *         UnitAware&lt;Double&gt; observationRate = quantity(6, Observations.divide(HOUR));
   *     </code>
   *     <br/>
   *     If instrument B also declared a local unit called "observations", this would be incommensurate with instrument A's "observations" unit.
   * </p>
   */
  public static Unit createLocalUnit(final String name) {
    return createBase(name, name, Dimension.createBase(name));
  }

  // Used for naming compound units
  public static Unit derived(final String shortName, final String longName, final Unit definingUnit) {
    return derived(shortName, longName, 1, definingUnit);
  }

  public static Unit derived(final String shortName, final String longName, final UnitAware<Double> definingQuantity) {
    return derived(shortName, longName, definingQuantity.value(), definingQuantity.unit());
  }

  public static Unit derived(final String shortName, final String longName, final double multiplier, final Unit baseUnit) {
    return new Unit(baseUnit.dimension, baseUnit.multiplier * multiplier, longName, shortName);
  }

  public Unit multiply(Unit other) {
    return new Unit(dimension.multiply(other.dimension), multiplier * other.multiplier);
  }

  public Unit divide(Unit other) {
    return new Unit(dimension.divide(other.dimension), multiplier / other.multiplier);
  }

  public Unit power(int power) {
    return power(Rational.rational(power));
  }

  public Unit power(Rational power) {
    return new Unit(dimension.power(power), Math.pow(multiplier, power.doubleValue()));
  }

  @Override
  public boolean equals(final Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    Unit unit = (Unit) o;
    return Double.compare(unit.multiplier, multiplier) == 0 && Objects.equals(dimension, unit.dimension);
  }

  @Override
  public int hashCode() {
    return Objects.hash(dimension, multiplier);
  }

  @Override
  public String toString() {
     if (longName != null) {
       return longName;
     } else {
       // TODO: Better name derivation, by tracking named base units
       return "Unit{" + multiplier + " in " + dimension + "}";
     }
  }
}
