package gov.nasa.jpl.aerie.constraints.model;

import gov.nasa.jpl.aerie.constraints.time.Interval;
import gov.nasa.jpl.aerie.constraints.time.IntervalMap;
import gov.nasa.jpl.aerie.constraints.time.Segment;
import gov.nasa.jpl.aerie.constraints.time.Windows;
import gov.nasa.jpl.aerie.merlin.driver.engine.ProfileSegment;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;

import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

import static gov.nasa.jpl.aerie.constraints.time.Interval.Inclusivity.Exclusive;
import static gov.nasa.jpl.aerie.constraints.time.Interval.Inclusivity.Inclusive;

public final class DiscreteProfile implements Profile<DiscreteProfile>, Iterable<Segment<SerializedValue>> {
  public final IntervalMap<SerializedValue> profilePieces;

  public DiscreteProfile(final IntervalMap<SerializedValue> profilePieces) {
    this.profilePieces = profilePieces;
  }

  @SafeVarargs
  public DiscreteProfile(final Segment<SerializedValue>... profilePieces) {
    this.profilePieces = IntervalMap.of(profilePieces);
  }

  public DiscreteProfile(final List<Segment<SerializedValue>> profilePieces) {
    this.profilePieces = IntervalMap.of(profilePieces);
  }

  private static boolean profileOutsideBounds(final Segment<SerializedValue> piece, final Interval bounds){
    return piece.interval().isStrictlyBefore(bounds) || piece.interval().isStrictlyAfter(bounds);
  }

  @Override
  public Windows equalTo(final DiscreteProfile other) {
    return new Windows(
        IntervalMap.map2(
            this.profilePieces, other.profilePieces,
            (left, right) -> left.flatMap($l -> right.map($l::equals))
        )
    );
  }

  @Override
  public Windows notEqualTo(final DiscreteProfile other) {
    return new Windows(
        IntervalMap.map2(
            this.profilePieces, other.profilePieces,
            (left, right) -> left.flatMap($l -> right.map($r -> !$l.equals($r)))
        )
    );
  }

  @Override
  public Windows changePoints() {
    final var result = IntervalMap.<Boolean>builder().set(this.profilePieces.map($ -> false));
    for (final var segment : profilePieces) {
      if (segment == profilePieces.first()) {
        if (!segment.interval().contains(Duration.MIN_VALUE)) {
          result.unset(Interval.at(segment.interval().start));
        }
      } else {
        final var previousSegment = this.profilePieces.segments().lower(segment);
        if (previousSegment != null && Interval.meets(previousSegment.interval(), segment.interval())) {
          if (!previousSegment.value().equals(segment.value())) {
            result.set(Interval.at(segment.interval().start), true);
          }
        } else {
          result.unset(Interval.at(segment.interval().start));
        }
      }
    }

    return new Windows(result.build());
  }

  public Windows transitions(final SerializedValue oldState, final SerializedValue newState) {
    final var result = IntervalMap.<Boolean>builder().set(this.profilePieces.map($ -> false));
    for (final var segment : profilePieces) {
    //for (int i = 0; i < this.profilePieces.size(); i++) {
      //final var segment = this.profilePieces.get(i);
      if (segment == profilePieces.first()) {
        if (segment.value().equals(newState) && !segment.interval().contains(Duration.MIN_VALUE)) {
          result.unset(Interval.at(segment.interval().start));
        }
      } else {
        final var previousSegment = this.profilePieces.segments().lower(segment);
        if (previousSegment != null && Interval.meets(previousSegment.interval(), segment.interval())) {
          if (previousSegment.value().equals(oldState) && segment.value().equals(newState)) {
            result.set(Interval.at(segment.interval().start), true);
          }
        } else if (segment.value().equals(newState)) {
          result.unset(Interval.at(segment.interval().start));
        }
      }
    }

    return new Windows(result.build());
  }

  @Override
  public boolean isConstant() {
    return profilePieces.size() <= 1;
  }

  /** Assigns a default value to all gaps in the profile. */
  @Override
  public DiscreteProfile assignGaps(final DiscreteProfile def) {
    return new DiscreteProfile(
        IntervalMap.map2(
            this.profilePieces, def.profilePieces,
            (original, defaultSegment) -> original.isPresent() ? original : defaultSegment
        )
    );
  }

  @Override
  public DiscreteProfile shiftBy(final Duration duration) {
    final var builder = IntervalMap.<SerializedValue>builder();

    for (final var segment : this.profilePieces) {
      final var interval = segment.interval();
      final var shiftedInterval = interval.shiftBy(duration);

      builder.set(Segment.of(shiftedInterval, segment.value()));
    }
    return new DiscreteProfile(builder.build());
  }

  @Override
  public Optional<SerializedValue> valueAt(final Duration timepoint) {
    final var matchPiece = profilePieces
        .stream()
        .filter($ -> $.interval().contains(timepoint))
        .findFirst();
    return matchPiece
        .map(Segment::value);
  }

  public static DiscreteProfile fromSimulatedProfile(final List<ProfileSegment<SerializedValue>> simulatedProfile) {
    return fromProfileHelper(Duration.ZERO, simulatedProfile, Optional::of, true);
  }

  public static DiscreteProfile fromExternalProfile(final Duration offsetFromPlanStart, final List<ProfileSegment<Optional<SerializedValue>>> externalProfile) {
    return fromProfileHelper(offsetFromPlanStart, externalProfile, $ -> $, false);
  }

  private static <T> DiscreteProfile fromProfileHelper(
      final Duration offsetFromPlanStart,
      final List<ProfileSegment<T>> profile,
      final Function<T, Optional<SerializedValue>> transform,
      final boolean close
  ) {
    final var result = new IntervalMap.Builder<SerializedValue>();
    var cursor = offsetFromPlanStart;
    var c = 0;
    for (final var pair: profile) {
      final var nextCursor = cursor.plus(pair.extent());

      final var value = transform.apply(pair.dynamics());
      final Duration finalCursor = cursor;
      final var isLast = c == profile.size() - 1;
      value.ifPresent(
          $ -> result.set(
              Interval.between(finalCursor, Inclusive, nextCursor, (close && isLast) ? Inclusive : Exclusive),
              $
          )
      );

      cursor = nextCursor;
      c++;
    }

    return new DiscreteProfile(result.build());
  }

  @Override
  public Iterator<Segment<SerializedValue>> iterator() {
    return this.profilePieces.iterator();
  }

  @Override
  public boolean equals(final Object obj) {
    if (!(obj instanceof final DiscreteProfile other)) return false;
    return Objects.equals(this.profilePieces, other.profilePieces);
  }

  @Override
  public int hashCode() {
    return Objects.hash(this.profilePieces);
  }
}
