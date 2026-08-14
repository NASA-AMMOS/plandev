package gov.nasa.jpl.aerie.contrib.streamline.unit_aware;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import static java.util.Collections.reverseOrder;
import static java.util.Map.Entry.comparingByValue;
import static java.util.stream.Collectors.joining;

/**
 * A kind of quantity, which can be measured.
 * For example, length, time, energy, or data rate.
 *
 * <p>
 * Quantities with the same dimension but different units, like meters and miles,
 * can be compared, added, and subtracted.
 * Quantities with different dimensions, like meters and seconds,
 * cannot be added or subtracted, and are never equal.
 * </p>
 *
 * <p>
 * Base dimensions are declared using {@link Dimension#createBase}. Base dimensions are definitionally all distinct
 * from each other, and are distinct from all combinations of other base dimensions.
 * </p>
 *
 * <p>
 * Dimensions can be composed by multiplication, division, and exponentiation by a constant
 * to derive new dimensions, and composite units correlate to the composite dimension.
 * For example, Energy is the dimension defined as Mass * Length^2 / Time^2, and
 * Newton is a unit of Energy defined as Kilogram * Meter^2 / Second^2.
 * Internally, all dimensions are a map from base dimensions to their power. For example, Mass is stored (loosely) as
 * {@code {"Mass": 1}} and Energy is {@code {"Mass": 1, "Length": 2, "Time": -2}}.
 * </p>
 */
public sealed interface Dimension {
  Dimension SCALAR = new DerivedDimension(Map.of());

  Map<BaseDimension, Rational> basePowers();
  boolean isBase();


  default Dimension multiply(Dimension other) {
    var resultBasePowers = new HashMap<>(basePowers());
    for (var dimensionPower : other.basePowers().entrySet()) {
      var power = dimensionPower.getValue();
      resultBasePowers.compute(
          dimensionPower.getKey(),
          (k, p) -> p == null ? power : power.add(p));
    }
    return create(resultBasePowers);
  }

  default Dimension divide(Dimension other) {
    var resultBasePowers = new HashMap<>(basePowers());
    for (var dimensionPower : other.basePowers().entrySet()) {
      var power = dimensionPower.getValue().negate();
      resultBasePowers.compute(
          dimensionPower.getKey(),
          (k, p) -> p == null ? power : power.add(p));
    }
    return create(resultBasePowers);
  }

  default Dimension power(Rational power) {
    var resultBasePowers = new HashMap<BaseDimension, Rational>();
    for (var dimensionPower : basePowers().entrySet()) {
      resultBasePowers.put(dimensionPower.getKey(), dimensionPower.getValue().multiply(power));
    }
    return create(resultBasePowers);
  }

  private static Dimension create(Map<BaseDimension, Rational> basePowers) {
    var normalizedBasePowers = new HashMap<BaseDimension, Rational>();
    for (var entry : basePowers.entrySet()) {
      if (!entry.getValue().equals(Rational.ZERO)) {
        normalizedBasePowers.put(entry.getKey(), entry.getValue());
      }
    }

    if (normalizedBasePowers.isEmpty()) {
      return Dimension.SCALAR;
    } else if (normalizedBasePowers.size() == 1) {
      final var solePower = normalizedBasePowers.entrySet().stream().findAny().get();
      if (solePower.getValue().equals(Rational.ONE)) {
        // This actually *is* the base dimension, so return that instead
        // Normalizing like this lets us bootstrap using reference equality on base dimensions.
        return solePower.getKey();
      }
    }

    // Otherwise, this is some composite dimension, build it anew.
    return new DerivedDimension(normalizedBasePowers);
  }

  static Dimension createBase(String name) {
    return new BaseDimension(name);
  }

  final class BaseDimension implements Dimension {
    public final String name;

    private BaseDimension(final String name) {
      this.name = name;
    }

    @Override
    public Map<BaseDimension, Rational> basePowers() {
      return Map.of(this, Rational.ONE);
    }

    @Override
    public boolean isBase() {
      return true;
    }

    // Reference equality is sufficient here. Do *not* override equals/hashCode.

    @Override
    public String toString() {
      return name;
    }
  }

  final class DerivedDimension implements Dimension {
    private final Map<BaseDimension, Rational> basePowers;

    private DerivedDimension(final Map<BaseDimension, Rational> basePowers) {
      this.basePowers = basePowers;
    }

    @Override
    public Map<BaseDimension, Rational> basePowers() {
      return basePowers;
    }

    @Override
    public boolean isBase() {
      return false;
    }

    // Use semantic equality defined by the base powers map

    @Override
    public boolean equals(final Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;
      DerivedDimension that = (DerivedDimension) o;
      return Objects.equals(basePowers, that.basePowers);
    }

    @Override
    public int hashCode() {
      return Objects.hash(basePowers);
    }

    @Override
    public String toString() {
      return basePowers.entrySet().stream()
          .sorted(reverseOrder(comparingByValue()))
          .map(basePower -> formatBasePower(basePower.getKey(), basePower.getValue()))
          .collect(joining(" "));
    }

    private static String formatBasePower(BaseDimension d, Rational p) {
      if (p.equals(Rational.ONE)) {
        return "[%s]".formatted(d.name);
      } else if (p.denominator() == 1) {
        return "[%s]^%s".formatted(d.name, p.numerator());
      } else {
        return "[%s]^(%d/%d)".formatted(d.name, p.numerator(), p.denominator());
      }
    }
  }
}
