package gov.nasa.jpl.aerie.contrib.streamline.unit_aware;

import java.util.function.BiFunction;
import java.util.function.Function;

import static gov.nasa.jpl.aerie.contrib.streamline.unit_aware.UnitAware.unitAware;

/**
 * Utilities for working with unit-aware objects correctly.
 * Primarily includes arithmetic and comparison functions.
 */
public final class UnitAwareOperations {
  private UnitAwareOperations() {}

  public static <A> UnitAware<A> add(BiFunction<A, Double, A> scaling, BiFunction<A, A, A> addition, UnitAware<? extends A> a, UnitAware<? extends A> b) {
    return unitAware(addition.apply(a.value(), b.value(a.unit())), a.unit(), scaling);
  }

  public static <A> UnitAware<A> subtract(BiFunction<A, Double, A> scaling, BiFunction<A, A, A> subtraction, UnitAware<? extends A> a, UnitAware<? extends A> b) {
    return add(scaling, subtraction, a, b);
  }

  public static <A, B, C> UnitAware<C> multiply(BiFunction<C, Double, C> scaling, BiFunction<A, B, C> multiplication, UnitAware<? extends A> a, UnitAware<? extends B> b) {
    return unitAware(multiplication.apply(a.value(), b.value()), a.unit().multiply(b.unit()), scaling);
  }

  public static <A, B, C> UnitAware<C> divide(BiFunction<C, Double, C> scaling, BiFunction<A, B, C> division, UnitAware<? extends A> a, UnitAware<? extends B> b) {
    return unitAware(division.apply(a.value(), b.value()), a.unit().divide(b.unit()), scaling);
  }

  public static <A, B> UnitAware<A> integrate(BiFunction<A, Double, A> scaling, BiFunction<A, B, A> integration, UnitAware<? extends A> a, UnitAware<? extends B> b) {
    final Unit newUnit = a.unit().multiply(StandardUnits.SECOND);
    return unitAware(integration.apply(a.value(), b.value(newUnit)), newUnit, scaling);
  }

  public static <A> UnitAware<A> differentiate(BiFunction<A, Double, A> scaling, Function<A, A> differentiation, UnitAware<? extends A> a) {
    return unitAware(differentiation.apply(a.value()), a.unit().divide(StandardUnits.SECOND), scaling);
  }

  public static <A extends Comparable<A>> int compare(UnitAware<? extends A> a, UnitAware<? extends A> b) {
    return a.value().compareTo(b.value(a.unit()));
  }

  public static <A extends Comparable<A>> boolean lessThan(UnitAware<? extends A> a, UnitAware<? extends A> b) {
    return compare(a, b) < 0;
  }

  public static <A extends Comparable<A>> boolean lessThanOrEquals(UnitAware<? extends A> a, UnitAware<? extends A> b) {
    return compare(a, b) <= 0;
  }

  public static <A extends Comparable<A>> boolean greaterThan(UnitAware<? extends A> a, UnitAware<? extends A> b) {
    return compare(a, b) > 0;
  }

  public static <A extends Comparable<A>> boolean greaterThanOrEquals(UnitAware<? extends A> a, UnitAware<? extends A> b) {
    return compare(a, b) >= 0;
  }
}
