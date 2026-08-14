package gov.nasa.jpl.aerie.merlin.framework;

import java.util.Objects;
import java.util.function.Function;

/**
 * This class is the equivalent of the type `Result L R = Success L | Failure R` in a language with algebraic data types,
 * but adapted to the vagaries of the Java platform. The sum alternatives are represented as a closed family of classes
 * implementing a common interface. Case evaluation is represented by the visitor pattern.
 *
 * @param <Success> The type of the success variant of the Result.
 * @param <Failure> The type of the failure variant of the Result.
 */
public abstract class Result<Success, Failure> {
  private Result() {}

  public interface Visitor<Success, Failure, Output, Throws extends Throwable> {
    Output onSuccess(final Success value) throws Throws;
    Output onFailure(final Failure value) throws Throws;
  }

  public abstract <Output, Throws extends Throwable>
  Output match(final Visitor<Success, Failure, Output, Throws> visitor) throws Throws;

  /**
   * Factory method for the Left variant.
   */
  static public <Success, Failure> Result<Success, Failure> success(final Success value) {
    return new Result<>() {
      @Override
      public <Output, Throws extends Throwable>
      Output match(final Visitor<Success, Failure, Output, Throws> visitor) throws Throws {
        return visitor.onSuccess(value);
      }
    };
  }

  /**
   * Factory method for the Right variant.
   */
  static public <Success, Failure> Result<Success, Failure> failure(final Failure value) {
    return new Result<>() {
      @Override
      public <Output, Throws extends Throwable>
      Output match(final Visitor<Success, Failure, Output, Throws> visitor) throws Throws {
        return visitor.onFailure(value);
      }
    };
  }


  public enum Kind {
    Success, Failure;

    @Override
    public String toString() {
      switch (this) {
        case Success: return "Success";
        case Failure: return "Failure";
        default: throw new Error("Unexpected enum value of type " + this.getClass().getSimpleName());
      }
    }
  }

  @FunctionalInterface
  public interface VisitorCase<Input, Output, Throws extends Throwable> {
    Output apply(Input input) throws Throws;
  }

  /**
   * Convenience method for matching in a way syntactically similar to switch statements.
   */
  public <Output, Throws extends Throwable>
  Output match(
      final VisitorCase<Success, Output, Throws> onSuccess,
      final VisitorCase<Failure, Output, Throws> onFailure
  ) throws Throws {
    return this.match(new Visitor<Success, Failure, Output, Throws>() {
      @Override
      public Output onSuccess(final Success value) throws Throws {
        return onSuccess.apply(value);
      }

      @Override
      public Output onFailure(final Failure value) throws Throws {
        return onFailure.apply(value);
      }
    });
  }

  public <OutputSuccess, OutputFailure, Throws extends Throwable>
  Result<OutputSuccess, OutputFailure> map(
      final VisitorCase<Success, OutputSuccess, Throws> onSuccess,
      final VisitorCase<Failure, OutputFailure, Throws> onFailure
  ) throws Throws {
    return this.match(
        success -> Result.success(onSuccess.apply(success)),
        failure -> Result.failure(onFailure.apply(failure))
    );
  }

  public <Output, Throws extends Throwable>
  Result<Output, Failure> mapSuccess(final VisitorCase<Success, Output, Throws> onSuccess) throws Throws {
    return this.map(onSuccess, failure -> failure);
  }

  public <Output, Throws extends Throwable>
  Result<Success, Output> mapFailure(final VisitorCase<Failure, Output, Throws> onFailure) throws Throws {
    return this.map(success -> success, onFailure);
  }

  public <Throws extends Throwable> Success getSuccessOrThrow(final Function<Failure, Throws> factory) throws Throws {
    return this.match(
        success -> success,
        failure -> { throw factory.apply(failure); }
    );
  }

  public <Throws extends Throwable> Failure getFailureOrThrow(final Function<Success, Throws> factory) throws Throws {
    return this.match(
        success -> { throw factory.apply(success); },
        failure -> failure
    );
  }

  public Success getSuccessOrThrow() {
    return this.getSuccessOrThrow(_failure -> new RuntimeException("Expected success variant"));
  }

  public Failure getFailureOrThrow() {
    return this.getFailureOrThrow(_success -> new RuntimeException("Expected failure variant"));
  }

  public Kind getKind() {
    return this.match(
        _success -> Kind.Success,
        _failure -> Kind.Failure
    );
  }

  // SAFETY: If equals is overridden, then hashCode must also be overridden.
  @Override
  public boolean equals(final Object o) {
    if (!(o instanceof Result)) return false;
    final Result<?, ?> other = (Result<?, ?>)o;

    return this.match(
        success1 -> other.match(
            left2 -> Objects.equals(success1, left2),
            _right -> false
        ),
        failure1 -> other.match(
            _left -> false,
            right2 -> Objects.equals(failure1, right2)
        )
    );
  }

  @Override
  public int hashCode() {
    return this.match(
        success -> Objects.hash(Kind.Success, success),
        failure -> Objects.hash(Kind.Failure, failure)
    );
  }

  @Override
  public String toString() {
    return this.match(
        success -> Kind.Success.toString() + "(" + Objects.toString(success, "null") + ")",
        failure -> Kind.Failure.toString() + "(" + Objects.toString(failure, "null") + ")"
    );
  }
}
