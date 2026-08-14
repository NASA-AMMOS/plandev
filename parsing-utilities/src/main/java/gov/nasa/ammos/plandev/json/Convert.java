package gov.nasa.jpl.aerie.json;

import java.util.Objects;
import java.util.function.Function;

/**
 * An infallible two-way conversion between types {@code S} and {@code T}.
 *
 * <p> When round-tripping a value of type {@code T} through {@code S} and back, the result must be equal to the
 * original value. That is, {@code Objects.equals(from(to(t)), t)} must always be true. </p>
 *
 * @param <S>
 *   the "source" type of the conversion
 * @param <T>
 *   the "target" type of the conversion
 */
public interface Convert<S, T> {
  /**
   * Converts a value forward from the source type to the target type.
   *
   * @param source
   *   the value to convert
   * @return
   *   the converted value
   */
  T from(S source);

  /**
   * Converts a value backward to the source type from the target type.
   *
   * @param target
   *   the value to convert
   * @return
   *   the converted value
   */
  S to(T target);

  /**
   * Constructs a conversion from two individual transformations. The first function must be
   * a left inverse of the second, i.e. {@code Objects.equals(from(to(t)), t)} must always be true.
   *
   * @param <S>
   *   the "source" type of the conversion
   * @param <T>
   *   the "target" type of the conversion
   * @param from
   *   an infallible transformation from the source type to the target type
   * @param to
   *   an infallible transformation to the source type from the target type
   */
  static <S, T> Convert<S, T> between(final Function<S, ? extends T> from, final Function<? super T, S> to) {
    Objects.requireNonNull(from);
    Objects.requireNonNull(to);

    return new Convert<>() {
      @Override
      public T from(final S source) {
        return from.apply(source);
      }

      @Override
      public S to(final T target) {
        return to.apply(target);
      }
    };
  }

  /**
   * Constructs a trivial conversion between a type and itself.
   *
   * <p> This is most useful as an initial value when accumulating multiple conversions together. </p>
   *
   * @param <T>
   *   the type to trivially convert into itself
   * @return
   *   a trivial two-way conversion from a type to itself
   */
  static <T> Convert<T, T> identity() {
    return Convert.between(Function.identity(), Function.identity());
  }

  /**
   * Extends this two-way conversion to a new target type by chaining with another conversion.
   *
   * @param <X>
   *   the new "target" type
   * @param other
   *   a conversion from this target type to the new target type
   * @return
   *   a combined conversion from this source type to the new target type
   */
  default <X> Convert<S, X> compose(final Convert<T, X> other) {
    Objects.requireNonNull(other);

    return Convert.between(
        $ -> other.from(this.from($)),
        $ -> this.to(other.to($)));
  }

  /**
   * Inverts this two-way conversion, swapping its source and target types.
   *
   * @return
   *   a two-way conversion with the same logic as this one, but with its source and target types swapped
   */
  default Convert<T, S> invert() {
    return Convert.between(this::to, this::from);
  }
}
