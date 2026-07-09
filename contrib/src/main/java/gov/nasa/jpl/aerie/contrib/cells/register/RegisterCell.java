package gov.nasa.jpl.aerie.contrib.cells.register;

import gov.nasa.jpl.aerie.merlin.framework.CellRef;
import gov.nasa.jpl.aerie.merlin.protocol.model.CellType;
import gov.nasa.jpl.aerie.merlin.protocol.model.EffectTrait;

import java.util.Objects;
import java.util.function.Function;
import java.util.function.UnaryOperator;

public final class RegisterCell<T> {
  private final UnaryOperator<T> duplicator;

  private T value;
  private boolean conflicted;

  public RegisterCell(final UnaryOperator<T> duplicator, final T initialValue, final boolean conflicted) {
    this.duplicator = Objects.requireNonNull(duplicator);
    this.value = Objects.requireNonNull(initialValue);
    this.conflicted = conflicted;
  }

  public static <Event, T> CellRef<Event, RegisterCell<T>>
  allocate(final UnaryOperator<T> duplicator, final T initialValue, final Function<Event, RegisterEffect<T>> interpreter) {
    return CellRef.allocate(
        new RegisterCell<>(duplicator, initialValue, false),
        new RegisterCellType<>(),
        interpreter);
  }

  public T getValue() {
    // Perform a defensive copy to prevent callers from accidentally mutating this Register.
    return this.duplicator.apply(this.value);
  }

  public boolean isConflicted() {
    return this.conflicted;
  }

  @Override
  public String toString() {
    return "RegisterCell{value=%s, conflicted=%s}".formatted(this.getValue(), this.isConflicted());
  }

  public static final class RegisterCellType<T> implements CellType<RegisterEffect<T>, RegisterCell<T>> {
    @Override
    public EffectTrait<RegisterEffect<T>> getEffectType() {
      return new RegisterEffect.Trait<>();
    }

    @Override
    public RegisterCell<T> duplicate(final RegisterCell<T> cell) {
      return new RegisterCell<>(cell.duplicator, cell.value, cell.conflicted);
    }

    @Override
    public void apply(final RegisterCell<T> cell, final RegisterEffect<T> effect) {
      if (effect.newValue != null) {
        cell.value = effect.newValue;
        cell.conflicted = effect.conflicted;
      } else if (effect.conflicted) {
        cell.conflicted = true;
      }
    }
  }
}
