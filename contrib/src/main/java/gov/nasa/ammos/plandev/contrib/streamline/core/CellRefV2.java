package gov.nasa.jpl.aerie.contrib.streamline.core;

import gov.nasa.jpl.aerie.contrib.streamline.core.monads.ErrorCatchingMonad;
import gov.nasa.jpl.aerie.merlin.framework.CellRef;
import gov.nasa.jpl.aerie.merlin.protocol.model.CellType;
import gov.nasa.jpl.aerie.merlin.protocol.model.EffectTrait;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;

import java.util.function.BinaryOperator;
import java.util.function.Predicate;

import static gov.nasa.jpl.aerie.contrib.streamline.core.ErrorCatching.failure;
import static gov.nasa.jpl.aerie.contrib.streamline.core.Expiring.expiring;
import static gov.nasa.jpl.aerie.contrib.streamline.debugging.Naming.*;
import static gov.nasa.jpl.aerie.merlin.protocol.types.Duration.ZERO;

/**
 * Utility class for a simplified allocate method.
 */
public final class CellRefV2 {
  private CellRefV2() {}

  /**
   * Allocate a new resource with an explicitly given effect type and effect trait.
   */
  public static <D extends Dynamics<?, D>, E extends DynamicsEffect<D>> CellRef<E, Cell<D>> allocate(ErrorCatching<Expiring<D>> initialDynamics, EffectTrait<E> effectTrait) {
    return CellRef.allocate(new Cell<>(initialDynamics), new CellType<>() {
      @Override
      public EffectTrait<E> getEffectType() {
        return effectTrait;
      }

      @Override
      public Cell<D> duplicate(Cell<D> cell) {
        return new Cell<>(cell.initialDynamics, cell.dynamics, cell.elapsedTime);
      }

      @Override
      public void apply(Cell<D> cell, E effect) {
        cell.initialDynamics = effect.apply(cell.dynamics).match(
            ErrorCatching::success,
                error -> failure(new RuntimeException(
                    "Applying effect '%s' failed.".formatted(getName(effect, null)), error)));
        cell.dynamics = cell.initialDynamics;
        cell.elapsedTime = ZERO;
      }

      @Override
      public void step(Cell<D> cell, Duration duration) {
        // Avoid accumulated round-off error in imperfect stepping
        // by always stepping up from the initial dynamics
        cell.elapsedTime = cell.elapsedTime.plus(duration);
        cell.dynamics = ErrorCatchingMonad.map(cell.initialDynamics, d ->
            expiring(d.data().step(cell.elapsedTime), d.expiry().minus(cell.elapsedTime)));
      }
    });
  }

  public static <D extends Dynamics<?, D>> EffectTrait<DynamicsEffect<D>> noncommutingEffects() {
    return resolvingConcurrencyBy((left, right) -> x -> {
          throw new UnsupportedOperationException(
              "Concurrent effects are not supported on this resource.");
        });
  }

  public static <D extends Dynamics<?, D>> EffectTrait<DynamicsEffect<D>> commutingEffects() {
    return resolvingConcurrencyBy((left, right) -> x -> right.apply(left.apply(x)));
  }

  public static <D extends Dynamics<?, D>> EffectTrait<DynamicsEffect<D>> autoEffects() {
    return autoEffects(testing((CommutativityTestInput<D> input) -> input.leftResult.equals(input.rightResult)));
  }

  public static <D extends Dynamics<?, D>> EffectTrait<DynamicsEffect<D>> autoEffects(
      Predicate<CommutativityTestInput<ErrorCatching<Expiring<D>>>> commutativityTest) {
    return resolvingConcurrencyBy((left, right) -> x -> {
      final var lrx = left.apply(right.apply(x));
      final var rlx = right.apply(left.apply(x));
      if (commutativityTest.test(new CommutativityTestInput<>(x, lrx, rlx))) {
        return lrx;
      } else {
        throw new UnsupportedOperationException(
            "Detected non-commuting concurrent effects!");
      }
    });
  }


  /**
   * Lift a commutativity test from data to dynamics,
   * correctly comparing expiry and error information in the process.
   */
  public static <D> Predicate<CommutativityTestInput<ErrorCatching<Expiring<D>>>> testing(Predicate<CommutativityTestInput<D>> test) {
    // If both expiring, compare expiry and data
    // If both error, compare error contents
    // If one is expiring and the other is error, return false
    return input -> input.leftResult.match(
        leftExpiring -> input.rightResult.match(
            rightExpiring -> leftExpiring.expiry().equals(rightExpiring.expiry()) && test.test(new CommutativityTestInput<>(
                input.original.match(Expiring::data, $ -> leftExpiring.data()),
                leftExpiring.data(),
                rightExpiring.data())),
            rightError -> false),
        leftError -> input.rightResult.match(
            rightExpiring -> false,
            rightError -> Resources.equivalentExceptions(leftError, rightError)));
  }

  public record CommutativityTestInput<D>(D original, D leftResult, D rightResult) {}

  public static <D extends Dynamics<?, D>> EffectTrait<DynamicsEffect<D>> resolvingConcurrencyBy(BinaryOperator<DynamicsEffect<D>> combineConcurrent) {
    return new EffectTrait<>() {
      @Override
      public DynamicsEffect<D> empty() {
        final DynamicsEffect<D> result = x -> x;
        name(result, "No-op");
        return result;
      }

      @Override
      public DynamicsEffect<D> sequentially(final DynamicsEffect<D> prefix, final DynamicsEffect<D> suffix) {
        final DynamicsEffect<D> result = x -> suffix.apply(prefix.apply(x));
        name(result, "(%s) then (%s)", prefix, suffix);
        return result;
      }

      @Override
      public DynamicsEffect<D> concurrently(final DynamicsEffect<D> left, final DynamicsEffect<D> right) {
        try {
          final DynamicsEffect<D> combined = combineConcurrent.apply(left, right);
          final DynamicsEffect<D> result = x -> {
                try {
                  return combined.apply(x);
                } catch (Exception e) {
                  return failure(e);
                }
              };
          name(result, "(%s) and (%s)", left, right);
          return result;
        } catch (Throwable e) {
          final DynamicsEffect<D> result = $ -> failure(e);
          name(result, "Failed to combine concurrent effects: (%s) and (%s)", left, right);
          return result;
        }
      }
    };
  }

  public static class Cell<D> {
    public ErrorCatching<Expiring<D>> initialDynamics;
    public ErrorCatching<Expiring<D>> dynamics;
    public Duration elapsedTime;

    public Cell(ErrorCatching<Expiring<D>> dynamics) {
      this(dynamics, dynamics, ZERO);
    }

    public Cell(ErrorCatching<Expiring<D>> initialDynamics, ErrorCatching<Expiring<D>> dynamics, Duration elapsedTime) {
      this.initialDynamics = initialDynamics;
      this.dynamics = dynamics;
      this.elapsedTime = elapsedTime;
    }
  }
}
