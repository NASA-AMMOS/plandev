package gov.nasa.jpl.aerie.constraints.model;

import gov.nasa.jpl.aerie.constraints.time.Interval;
import gov.nasa.jpl.aerie.constraints.time.IntervalMap;
import gov.nasa.jpl.aerie.constraints.time.Segment;
import gov.nasa.jpl.aerie.constraints.time.Windows;
import gov.nasa.jpl.aerie.merlin.driver.engine.ProfileSegment;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import gov.nasa.jpl.aerie.merlin.protocol.types.RealDynamics;
import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;

import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;

import static gov.nasa.jpl.aerie.constraints.time.Interval.Inclusivity.Exclusive;
import static gov.nasa.jpl.aerie.constraints.time.Interval.Inclusivity.Inclusive;

public final class LinearProfile implements Profile<LinearProfile>, Iterable<Segment<LinearEquation>> {
  public final IntervalMap<LinearEquation> profilePieces;

  public LinearProfile(final IntervalMap<LinearEquation> profilePieces) {
    this.profilePieces = Objects.requireNonNull(profilePieces);
  }

  @SafeVarargs
  public LinearProfile(final Segment<LinearEquation>... profilePieces) {
    this(IntervalMap.of(profilePieces));
  }

  public LinearProfile(final List<Segment<LinearEquation>> profilePieces) {
    this(IntervalMap.of(profilePieces));
  }

  @Override
  public Windows equalTo(final LinearProfile other) {
    return this.getWindowsSatisfying(other, LinearEquation::equalTo);
  }

  @Override
  public Windows notEqualTo(final LinearProfile other) {
    return this.getWindowsSatisfying(other, LinearEquation::notEqualTo);
  }

  public Windows lessThan(final LinearProfile other) {
    return this.getWindowsSatisfying(other, LinearEquation::lessThan);
  }

  public Windows lessThanOrEqualTo(final LinearProfile other) {
    return this.getWindowsSatisfying(other, LinearEquation::lessThanOrEqualTo);
  }

  public Windows greaterThan(final LinearProfile other) {
    return this.getWindowsSatisfying(other, LinearEquation::greaterThan);
  }

  public Windows greaterThanOrEqualTo(final LinearProfile other) {
    return this.getWindowsSatisfying(other, LinearEquation::greaterThanOrEqualTo);
  }

  public LinearProfile plus(final LinearProfile other) {
    return new LinearProfile(
        IntervalMap.map2(
            this.profilePieces, other.profilePieces,
            (l, r) -> {
              if (l.isPresent() && r.isPresent()) {
                final var left = l.get();
                final var right = r.get();
                final var shiftedRight = right.shiftInitialTime(left.initialTime);
                return Optional.of(new LinearEquation(left.initialTime, left.initialValue + shiftedRight.initialValue, left.rate + right.rate));
              } else {
                return Optional.empty();
              }
            }
        )
    );
  }

  public LinearProfile times(final double multiplier) {
    return new LinearProfile(this.profilePieces.map(
        $ -> new LinearEquation($.initialTime, $.initialValue * multiplier, $.rate * multiplier)
    ));
  }

  public LinearProfile rate() {
    return new LinearProfile(this.profilePieces.map(
        $ -> new LinearEquation($.initialTime, $.rate, 0.0)
    ));
  }

  private Windows getWindowsSatisfying(final LinearProfile other, final BiFunction<LinearEquation, LinearEquation, Windows> condition) {
    return new Windows(
        IntervalMap.map2(this.profilePieces, other.profilePieces, (l, r) -> {
          if (l.isPresent() && r.isPresent()) {
            return Optional.of(condition.apply(l.get(), r.get()));
          } else {
            return Optional.empty();
          }
        }).flatMap((windows, interval) -> windows.select(interval).stream())
    );
  }

    @Override
    public Windows changePoints() {
      final var result = IntervalMap.<Boolean>builder().set(this.profilePieces.map(LinearEquation::changing));
      for (final var segment : profilePieces) {
        final var startTime = segment.interval().start;
        if (segment == profilePieces.first()) {
          if (!segment.interval().contains(Duration.MIN_VALUE)) {
            result.unset(Interval.at(startTime));
          }
        } else {
          final var previousSegment = this.profilePieces.segments().lower(segment);

          if (previousSegment != null && Interval.meets(previousSegment.interval(), segment.interval())) {
            if (previousSegment.value().valueAt(startTime) != segment.value().valueAt(startTime)) {
              result.set(Interval.at(startTime), true);
            }
          } else {
            result.unset(Interval.at(startTime));
          }
        }
      }

      return new Windows(result.build());
    }


  @Override
  public boolean isConstant() {
    return profilePieces.isEmpty() ||
           (profilePieces.size() == 1 && !profilePieces.first().value().changing());
  }

  /** Assigns a default value to all gaps in the profile. */
  @Override
  public LinearProfile assignGaps(final LinearProfile def) {
    return new LinearProfile(
        IntervalMap.map2(
            this.profilePieces, def.profilePieces,
            (original, defaultSegment) -> original.isPresent() ? original : defaultSegment
        )
    );
  }

  @Override
  public Optional<SerializedValue> valueAt(final Duration timepoint) {
    return profilePieces
        .stream()
        .filter($ -> $.interval().contains(timepoint))
        .findFirst()
        .map(linearEquationSegment -> SerializedValue.of(linearEquationSegment.value().valueAt(timepoint)));
  }

  @Override
  public LinearProfile shiftBy(final Duration duration) {
    final var builder = IntervalMap.<LinearEquation>builder();

    for (final var segment : this.profilePieces) {
      final var interval = segment.interval();
      final var shiftedInterval = interval.shiftBy(duration);

      final var shiftedValue = new LinearEquation(
          segment.value().initialTime.saturatingPlus(duration),
          segment.value().initialValue,
          segment.value().rate
      );

      builder.set(Segment.of(shiftedInterval, shiftedValue));
    }
    return new LinearProfile(builder.build());
  }

  public static LinearProfile fromSimulatedProfile(final List<ProfileSegment<RealDynamics>> simulatedProfile) {
    return fromProfileHelper(Duration.ZERO, simulatedProfile, Optional::of, true);
  }

  public static LinearProfile fromExternalProfile(final Duration offsetFromPlanStart, final List<ProfileSegment<Optional<RealDynamics>>> externalProfile) {
    return fromProfileHelper(offsetFromPlanStart, externalProfile, $ -> $, false);
  }

  private static <T> LinearProfile fromProfileHelper(
      final Duration offsetFromPlanStart,
      final List<ProfileSegment<T>> profile,
      final Function<T, Optional<RealDynamics>> transform,
      final boolean close
  ) {
    final var result = new IntervalMap.Builder<LinearEquation>();
    var cursor = offsetFromPlanStart;
    var c = 0;
    for (final var pair: profile) {
      final var nextCursor = cursor.plus(pair.extent());
      final var isLast = c == profile.size() - 1;
      final var value = transform.apply(pair.dynamics());
      final Duration finalCursor = cursor;
      value.ifPresent(
          $ -> result.set(
              Interval.between(finalCursor, Inclusive, nextCursor, (close && isLast) ? Inclusive :Exclusive),
              new LinearEquation(
                  finalCursor,
                  $.initial,
                  $.rate
              )
          )
      );

      cursor = nextCursor;
      c++;
    }

    return new LinearProfile(result.build());
  }

  @Override
  public Iterator<Segment<LinearEquation>> iterator() {
    return this.profilePieces.iterator();
  }

  public String toString() {
    return this.profilePieces.toString();
  }

  @Override
  public boolean equals(final Object obj) {
    if (!(obj instanceof final LinearProfile other)) return false;

    return Objects.equals(this.profilePieces, other.profilePieces);
  }

  @Override
  public int hashCode() {
    return Objects.hash(this.profilePieces);
  }
}
