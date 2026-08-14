package gov.nasa.jpl.aerie.contrib.streamline.core;

import gov.nasa.jpl.aerie.contrib.streamline.core.monads.ErrorCatchingMonad;

import java.util.function.Function;

/**
 * Sum type representing a value or a failure to produce a value.
 */
public sealed interface ErrorCatching<T> {
  <R> R match(Function<T, R> onSuccess, Function<Throwable, R> onError);

  static <T> ErrorCatching<T> success(T result) {
    return new Success<>(result);
  }

  static <T> ErrorCatching<T> failure(Throwable exception) {
    return new Failure<>(exception);
  }

  default <R> ErrorCatching<R> map(Function<T, R> f) {
    return ErrorCatchingMonad.map(this, f);
  }

  default T getOrThrow() {
    return match(
        Function.identity(),
        e -> {
          throw new RuntimeException(e);
        });
  }

  record Success<T>(T result) implements ErrorCatching<T> {
    @Override
    public <R> R match(final Function<T, R> onSuccess, final Function<Throwable, R> onError) {
      return onSuccess.apply(result);
    }
  }

  record Failure<T>(Throwable exception) implements ErrorCatching<T> {
    @Override
    public <R> R match(final Function<T, R> onSuccess, final Function<Throwable, R> onError) {
      return onError.apply(exception);
    }
  }
}
