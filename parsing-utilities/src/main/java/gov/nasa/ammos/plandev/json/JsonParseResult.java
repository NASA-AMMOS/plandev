package gov.nasa.ammos.plandev.json;

import org.apache.commons.lang3.tuple.Pair;
import org.jetbrains.annotations.NotNull;

import javax.json.Json;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;

public sealed interface JsonParseResult<T> {
  record Success<T>(T result) implements JsonParseResult<T> {}
  record Failure<T>(FailureReason reason) implements JsonParseResult<T> {
    public <S> Failure<S> cast() {
      // SAFETY: `Failure<T>` contains no values of type `T`.
      @SuppressWarnings("unchecked")
      final var result = (Failure<S>) this;
      return result;
    }
  }

  static <T> JsonParseResult<T> success(final T value) {
    return new Success<>(value);
  }

  static <T> JsonParseResult<T> failure(FailureReason reason) {
    return new Failure<>(reason);
  }

  static <T> JsonParseResult<T> failure(String reason) {
    return new Failure<>(new FailureReason(reason));
  }

  static <T> JsonParseResult<T> failure() {
    return new Failure<>(new FailureReason("Unknown reason"));
  }

  /** Combine two results together. If either is a failure, returns a failure. */
  default <S, Result> JsonParseResult<Result> parWith(final JsonParseResult<S> other, final BiFunction<T, S, Result> step) {
    if (this instanceof Failure<T> f) {
      return new Failure<>(f.reason());
    } else if (this instanceof Success<T> s1) {
      if (other instanceof Failure<S> f) {
        return new Failure<>(f.reason());
      } else if (other instanceof Success<S> s2) {
        return new Success<>(step.apply(s1.result(), s2.result()));
      } else {
        throw new Error("Unexpected subtype");
      }
    } else {
      throw new Error("Unexpected subtype");
    }
  }

  default <S> JsonParseResult<Pair<T, S>> parWith(final JsonParseResult<S> other) {
    return this.parWith(other, Pair::of);
  }

  /** Prepends the given breadcrumb if the result is a failure. */
  default JsonParseResult<T> prependBreadcrumb(final Breadcrumb breadcrumb) {
    if (this instanceof Success<T> s) {
      return s;
    } else if (this instanceof Failure<T> f) {
      return new Failure<>(f.reason().prependBreadcrumb(breadcrumb));
    } else {
      throw new Error("Unexpected subtype");
    }
  }

  default <S> JsonParseResult<S> mapSuccess(final Function<T, S> transform) {
    if (this instanceof Success<T> s) {
      return new Success<>(transform.apply(s.result()));
    } else if (this instanceof Failure<T> f) {
      return f.cast();
    } else {
      throw new Error("Unexpected subtype");
    }
  }

  default boolean isFailure() {
    return (this instanceof Failure);
  }

  default <Throws extends Throwable>
  T getSuccessOrThrow(final Function<FailureReason, ? extends Throws> throwsSupplier) throws Throws {
    if (this instanceof Success<T> s) {
      return s.result();
    } else if (this instanceof Failure<T> f) {
      throw throwsSupplier.apply(f.reason());
    } else {
      throw new Error("Unexpected subtype");
    }
  }

  default T getSuccessOrThrow() {
    return this.getSuccessOrThrow($ -> new RuntimeException("Called getSuccessOrThrow on a Failure case: " + $));
  }

  record FailureReason(List<Breadcrumb> breadcrumbs, String reason) {
    public FailureReason(String reason) {
      this(new ArrayList<>(), reason);
    }

    public FailureReason prependBreadcrumb(Breadcrumb breadcrumb) {
      this.breadcrumbs.add(0, breadcrumb);
      return this;
    }

    @Override
    public @NotNull String toString() {
      final var breadcrumbsArray = Json.createArrayBuilder();
      breadcrumbs.forEach(b -> breadcrumbsArray.add(b.toString()));
      return Json.createObjectBuilder()
                 .add("breadcrumbs", breadcrumbsArray)
                 .add("message", reason())
                 .build().toString();
    }
  }
}
