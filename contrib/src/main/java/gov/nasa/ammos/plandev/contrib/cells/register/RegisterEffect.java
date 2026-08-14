package gov.nasa.jpl.aerie.contrib.cells.register;

import gov.nasa.jpl.aerie.merlin.protocol.model.EffectTrait;

import java.util.Objects;

public final class RegisterEffect<T> {
  public final T newValue;
  public final boolean conflicted;

  private RegisterEffect(final T newValue, final boolean conflicted) {
    this.newValue = newValue;
    this.conflicted = conflicted;
  }

  private static final RegisterEffect<?> EMPTY = new RegisterEffect<>(null, false);
  private static final RegisterEffect<?> CONFLICTED = new RegisterEffect<>(null, true);

  @SuppressWarnings("unchecked")
  public static <T> RegisterEffect<T> doNothing() {
    return (RegisterEffect<T>) EMPTY;
  }

  public static <T> RegisterEffect<T> set(final T newValue) {
    return new RegisterEffect<>(Objects.requireNonNull(newValue), false);
  }

  @SuppressWarnings("unchecked")
  public static <T> RegisterEffect<T> conflict() {
    return (RegisterEffect<T>) CONFLICTED;
  }

  @Override
  public String toString() {
    return (this.newValue != null) ? "set(%s)".formatted(this.newValue) : "noop()";
  }


  public static final class Trait<T> implements EffectTrait<RegisterEffect<T>> {
    @Override
    public RegisterEffect<T> empty() {
      return RegisterEffect.doNothing();
    }

    @Override
    public RegisterEffect<T> sequentially(final RegisterEffect<T> prefix, final RegisterEffect<T> suffix) {
      if (suffix.newValue != null) {
        // The suffix is a set (with or without a subsequent conflict); it strictly dominates the prefix.
        return suffix;
      } else if (!suffix.conflicted) {
        // The suffix is a no-op; take the prefix.
        return prefix;
      } else if (prefix.newValue != null) {
        // The suffix is a pure conflict, and the prefix performed a valid write.
        return new RegisterEffect<>(prefix.newValue, suffix.conflicted);
      } else {
        // The suffix is a pure conflict, and the prefix performs no write, so the suffix dominates the prefix.
        return suffix;
      }
    }

    @Override
    public RegisterEffect<T> concurrently(final RegisterEffect<T> left, final RegisterEffect<T> right) {
      /*
       left.hasValue | left.isConflicted | right.hasValue | right.isConflicted || ( left | right )
      ---------------+-------------------+----------------+--------------------++--------------------
      # The "one of them is a no-op" cluster
               false |             false |          true  |              true  || right
               false |             false |          true  |              false || right
               false |             false |          false |              true  || conflict (right)
               false |             false |          false |              false || noop (left/right)
               false |             true  |          false |              false || conflict (left)
               true  |             false |          false |              false || left
               true  |             true  |          false |              false || left
      # The "neither of them is a no-op" cluster
               false |             true  |          true  |              true  || conflict (left)
               false |             true  |          true  |              false || conflict (left)
               false |             true  |          false |              true  || conflict (left/right)
               true  |             false |          false |              true  || conflict (right)
               true  |             true  |          false |              true  || conflict (right)
               true  |             false |          true  |              true  || conflict
               true  |             false |          true  |              false || conflict
               true  |             true  |          true  |              true  || conflict
               true  |             true  |          true  |              false || conflict
       */
      if (left.newValue == null && !left.conflicted) {
        // Left is a no-op; take the right.
        return right;
      } else if (right.newValue == null && !right.conflicted) {
        // Right is a no-op; take the left.
        return left;
      } else {
        // Left and right are both doing *something*, causing a pure conflict.
        return conflict();
      }
    }
  }
}
