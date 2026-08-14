package gov.nasa.jpl.aerie.contrib.cells.counters;

import gov.nasa.jpl.aerie.contrib.traits.CommutativeMonoid;
import gov.nasa.jpl.aerie.merlin.framework.CellRef;
import gov.nasa.jpl.aerie.merlin.protocol.model.CellType;
import gov.nasa.jpl.aerie.merlin.protocol.model.EffectTrait;

import java.util.Objects;
import java.util.function.BinaryOperator;
import java.util.function.Function;
import java.util.function.UnaryOperator;

public final class CounterCell<T> {
  private final UnaryOperator<T> duplicator;
  private final BinaryOperator<T> adder;
  private T value;

  public CounterCell(final T initialValue, final BinaryOperator<T> adder, final UnaryOperator<T> duplicator) {
    this.duplicator = Objects.requireNonNull(duplicator);
    this.adder = Objects.requireNonNull(adder);
    this.value = Objects.requireNonNull(initialValue);
  }

  public static <Event, T> CellRef<Event, CounterCell<T>>
  allocate(
      final T initialValue,
      final T zero,
      final BinaryOperator<T> adder,
      final UnaryOperator<T> duplicator,
      final Function<Event, T> interpreter) {
    return CellRef.allocate(
        new CounterCell<>(initialValue, adder, duplicator),
        new CounterCellType<>(zero, adder),
        interpreter);
  }

  public T getValue() {
    // Perform a defensive copy to prevent callers from accidentally mutating this Counter.
    return this.duplicator.apply(this.value);
  }

  public static final class CounterCellType<T> implements CellType<T, CounterCell<T>> {
    private final EffectTrait<T> monoid;

    public CounterCellType(final T zero, final BinaryOperator<T> adder) {
      this.monoid = new CommutativeMonoid<>(zero, adder);
    }

    @Override
    public EffectTrait<T> getEffectType() {
      return this.monoid;
    }

    @Override
    public CounterCell<T> duplicate(final CounterCell<T> cell) {
      return new CounterCell<>(cell.value, cell.adder, cell.duplicator);
    }

    @Override
    public void apply(final CounterCell<T> cell, final T effect) {
      cell.value = cell.adder.apply(cell.value, effect);
    }
  }
}
