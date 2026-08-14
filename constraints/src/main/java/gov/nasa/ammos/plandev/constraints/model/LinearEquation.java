package gov.nasa.jpl.aerie.constraints.model;

import gov.nasa.jpl.aerie.constraints.time.Interval;
import gov.nasa.jpl.aerie.constraints.time.Segment;
import gov.nasa.jpl.aerie.constraints.time.Windows;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;

import java.util.Objects;
import java.util.Optional;
import java.util.function.BiFunction;

import static gov.nasa.jpl.aerie.constraints.time.Interval.Inclusivity.Exclusive;
import static gov.nasa.jpl.aerie.constraints.time.Interval.Inclusivity.Inclusive;

/**
 * A linear equation in point-slope form.
 */
public final class LinearEquation implements Comparable<LinearEquation> {
  public final Duration initialTime;
  public final double initialValue;
  public final double rate;

  public LinearEquation(final Duration initialTime, final double initialValue, final double rate) {
    this.initialTime = initialTime;
    this.initialValue = initialValue;
    this.rate = rate;
  }

  public double valueAt(final Duration time) {
    final var change = this.rate*time.ratioOver(Duration.SECOND) - this.rate*this.initialTime.ratioOver(Duration.SECOND);
    return this.initialValue + change;
  }

  public LinearEquation shiftInitialTime(final Duration newInitialTime) {
    return new LinearEquation(
        newInitialTime,
        this.initialValue + newInitialTime.minus(this.initialTime).ratioOver(Duration.SECOND)*this.rate,
        this.rate
    );
  }

  public boolean changing() {
    return this.rate != 0;
  }

  private Optional<Duration> intersectionPointWith(final LinearEquation other) {
    if (this.rate == other.rate) return Optional.empty();

    /*
    Floating point noise can cause rates to be extremely near zero, when they should
    have been exactly zero. For example: `0.1 + 0.2 - 0.1 - 0.2 != 0`.

    This can cause the denominator below to be tiny, leading to long overflow later.
     */

    // If the following causes an exception, something really has gone wrong, and we don't want to catch it.
    final double numSeconds = (other.valueAt(this.initialTime) - this.initialValue) / (this.rate - other.rate);

    // Check if numSeconds is too big before putting it in a long.
    if (Math.abs(numSeconds) > ((double) Long.MAX_VALUE) / Duration.SECOND.dividedBy(Duration.MICROSECOND)) {
      return Optional.empty();
    }

    return Optional.of(
        this.initialTime.plus(
            Duration.roundNearest(
                numSeconds,
                Duration.SECONDS
            )
        )
    );
  }

  public Windows intervalsLessThan(final LinearEquation other) {
    return getInequalityIntervals(other, (l, r) -> l < r);
  }

  public Windows intervalsLessThanOrEqualTo(final LinearEquation other) {
    return getInequalityIntervals(other, (l, r) -> l <= r);
  }

  public Windows intervalsGreaterThan(final LinearEquation other) {
    return getInequalityIntervals(other, (l, r) -> l > r);
  }

  public Windows intervalsGreaterThanOrEqualTo(final LinearEquation other) {
    return getInequalityIntervals(other, (l, r) -> l >= r);
  }

  private Windows getInequalityIntervals(
      final LinearEquation other,
      final BiFunction<Double, Double, Boolean> op
  ) {
    final var intersectionOption = this.intersectionPointWith(other);

    if (intersectionOption.isEmpty()) {
      final var resultEverywhere = op.apply(this.initialValue, other.valueAt(this.initialTime));
      return new Windows(resultEverywhere);
    } else {
      final var intersection = intersectionOption.get();
      final var oneSecondBefore = intersection.minus(Duration.SECOND);
      final var oneSecondAfter = intersection.plus(Duration.SECOND);
      return new Windows(
          Segment.of(
              Interval.between(Duration.MIN_VALUE, Inclusive, intersection, Exclusive),
              op.apply(this.valueAt(oneSecondBefore), other.valueAt(oneSecondBefore))
          ),
          Segment.of(
              Interval.at(intersection),
              op.apply(this.valueAt(intersection), other.valueAt(intersection))
          ),
          Segment.of(
              Interval.between(intersection, Exclusive, Duration.MAX_VALUE, Inclusive),
              op.apply(this.valueAt(oneSecondAfter), other.valueAt(oneSecondAfter))
          )
      );
    }
  }

  public Windows intervalsEqualTo(final LinearEquation other) {
    final var intersection = this.intersectionPointWith(other);

    if (intersection.isEmpty()) {
      return new Windows(this.initialValue == other.valueAt(this.initialTime));
    } else {
      final var t = intersection.get();
      return new Windows(
          Segment.of(Interval.between(Duration.MIN_VALUE, Inclusive, t, Exclusive), false),
          Segment.of(Interval.at(t), true),
          Segment.of(Interval.between(t, Exclusive, Duration.MAX_VALUE, Inclusive), false)
      );
    }
  }

  public Windows intervalsNotEqualTo(final LinearEquation other) {
    return this.intervalsEqualTo(other).not();
  }

  public static Windows lessThan(LinearEquation left, LinearEquation right) {
    return left.intervalsLessThan(right);
  }

  public static Windows lessThanOrEqualTo(final LinearEquation left, final LinearEquation right) {
    return left.intervalsLessThanOrEqualTo(right);
  }

  public static Windows greaterThan(final LinearEquation left, final LinearEquation right) {
    return left.intervalsGreaterThan(right);
  }

  public static Windows greaterThanOrEqualTo(final LinearEquation left, final LinearEquation right) {
    return left.intervalsGreaterThanOrEqualTo(right);
  }

  public static Windows equalTo(final LinearEquation left, final LinearEquation right) {
    return left.intervalsEqualTo(right);
  }

  public static Windows notEqualTo(final LinearEquation left, final LinearEquation right) {
    return left.intervalsNotEqualTo(right);
  }

  public String toString() {
    return String.format(
            "{\n" +
            "  Initial Time: %s\n" +
            "  Initial Value: %s\n" +
            "  Rate: %s\n" +
            "}",
            this.initialTime,
            this.initialValue,
            this.rate
    );
  }

  @Override
  public boolean equals(final Object obj) {
    if(!(obj instanceof final LinearEquation other)) return false;

    return this.valueAt(Duration.ZERO) == other.valueAt(Duration.ZERO)
        && this.valueAt(Duration.MINUTE) == other.valueAt(Duration.MINUTE);
  }

  @Override
  public int hashCode() {
    return Objects.hash(this.initialValue, this.initialTime, this.rate);
  }

  @Override
  public int compareTo(final LinearEquation o) {
    int c = Double.compare(this.valueAt(Duration.ZERO), o.valueAt(Duration.ZERO));
    if (c != 0) return c;
    c = Double.compare(this.valueAt(Duration.MINUTE), o.valueAt(Duration.MINUTE));
    return c;
  }
}
