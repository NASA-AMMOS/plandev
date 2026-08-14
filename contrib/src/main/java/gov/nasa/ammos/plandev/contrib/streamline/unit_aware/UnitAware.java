package gov.nasa.jpl.aerie.contrib.streamline.unit_aware;

import java.util.Comparator;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.UnaryOperator;

import static java.util.function.Function.identity;

/**
 * A value of type T with an attached unit.
 * This can be rescaled to other units measuring the same dimension.
 */
public interface UnitAware<T> {

  default T value(Unit desiredUnit) {
    return in(desiredUnit).value();
  }

  T value();

  Unit unit();

  UnitAware<T> in(Unit desiredUnit);

  UnitAware<T> map(UnaryOperator<T> unitInvariantFunction);

  /**
   * General constructor, used primarily by library code.
   */
  static <T> UnitAware<T> unitAware(T value, Unit unit, Function<Unit, UnitAware<T>> rescale) {

    return new UnitAware<>() {
      @Override
      public T value() {
        return value;
      }

      @Override
      public Unit unit() {
        return unit;
      }

      @Override
      public UnitAware<T> in(Unit desiredUnit) {
        if (unit == desiredUnit) {
          // Short-circuit for performance
          return this;
        }
        if (!unit.dimension.equals(desiredUnit.dimension)) {
          // TODO: Should this be its own kind of exception? A UnitConversionException or DimensionMismatchException?
          throw new IllegalArgumentException("Cannot convert %s to desired unit %s due to dimension mismatch (%s vs %s)."
                .formatted(unit, desiredUnit, unit.dimension, desiredUnit.dimension));
        }
        return rescale.apply(desiredUnit);
      }

      @Override
      public UnitAware<T> map(final UnaryOperator<T> unitInvariantFunction) {
        return unitAware(unitInvariantFunction.apply(value), unit, rescale);
      }

      @Override
      public boolean equals(Object obj) {
        if (obj == this) return true;
        if (obj == null || obj.getClass() != this.getClass()) return false;
        var that = (UnitAware<?>) obj;
        return Objects.equals(value, that.value()) && Objects.equals(unit, that.unit());
      }

      @Override
      public int hashCode() {
        return Objects.hash(value, unit);
      }

      @Override
      public String toString() {
        return "UnitAware[" + "value=" + value + ", " + "unit=" + unit + ']';
      }
    };
  }

  /**
   * Scaling-function constructor, used primarily in code outside this library.
   */
  static <T> UnitAware<T> unitAware(T value, Unit unit, BiFunction<T, Double, T> scaling) {
    return unitAware(
        value,
        unit,
        desiredUnit -> unitAware(scaling.apply(value, unit.multiplier / desiredUnit.multiplier), desiredUnit, scaling));
  }

  static <T extends Comparable<T>> Comparator<UnitAware<T>> comparator() {
    return Comparator.comparing(identity(), (p, q) -> p.value().compareTo(q.value(p.unit())));
  }
}
