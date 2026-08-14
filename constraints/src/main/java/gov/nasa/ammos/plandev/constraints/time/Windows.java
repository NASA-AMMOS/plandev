package gov.nasa.jpl.aerie.constraints.time;

import gov.nasa.jpl.aerie.constraints.model.LinearEquation;
import gov.nasa.jpl.aerie.constraints.model.LinearProfile;
import gov.nasa.jpl.aerie.constraints.model.Profile;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import gov.nasa.jpl.aerie.constraints.time.Interval.Inclusivity;
import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;
import org.apache.commons.lang3.tuple.Pair;

import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * A boolean profile, which can contain gaps (a.k.a. nulls).
 *
 * Backed by an {@link IntervalMap} of type {@link Boolean}. This class provides additional operations
 * which are only valid on bools.
 */
public final class Windows implements Iterable<Segment<Boolean>>, IntervalContainer<Windows>, Profile<Windows> {
  private final IntervalMap<Boolean> segments;

  /** Creates an empty Windows */
  public Windows() {
    this.segments = IntervalMap.of();
  }

  /** Creates a Windows from potentially unordered, overlapping segments */
  @SafeVarargs
  public Windows(final Segment<Boolean>... segments) {
    this.segments = IntervalMap.of(segments);
  }

  /** Creates a Windows from potentially unordered, overlapping segments */
  public Windows(final List<Segment<Boolean>> segments) {
    this.segments = IntervalMap.of(segments);
  }

  /** Creates a Windows with a single segment */
  public Windows(final Interval interval, final boolean value) {
    this.segments = IntervalMap.of(interval, value);
  }

  /** Creates a Windows that is equal to a given value for all representable times */
  public Windows(final boolean bool) {
    this.segments = IntervalMap.of(bool);
  }

  /** Wraps an IntervalMap of Booleans in Windows. */
  public Windows(final IntervalMap<Boolean> segments) {
    this.segments = segments;
  }

  /**
   * Perform the and operation on two Windows.
   *
   * The operation is symmetric, even in the case of gaps. The truth table is:
   *
   * L | R | L and R
   * :---:|:---:|:---:
   * T | T | T
   * T | F | F
   * F | F | F
   * T | N | N
   * F | N | F
   * N | N | N
   *
   * N stands for Null.
   *
   * @param other right operand
   * @return a new Windows
   */
  public Windows and(final Windows other) {
    return new Windows(IntervalMap.map2(
        this.segments, other.segments,
        (l, r) -> {
          if (l.isPresent() && r.isPresent()) {
            return Optional.of(l.get() && r.get());
          } else if (l.isPresent()) {
            return l.get() ? Optional.empty() : Optional.of(Boolean.FALSE);
          } else if (r.isPresent()) {
            return r.get() ? Optional.empty() : Optional.of(Boolean.FALSE);
          } else {
            return Optional.empty();
          }
        }
    ));
  }

  /**
   * Perform the or operation on two Windows.
   *
   * The operation is symmetric, even in the case of gaps. The truth table is:
   *
   * L | R | L or R
   * :---:|:---:|:---:
   * T | T | T
   * T | F | T
   * F | F | F
   * T | N | T
   * F | N | N
   * N | N | N
   *
   * N stands for Null.
   *
   * @param other right operand
   * @return a new Windows
   */
  public Windows or(final Windows other) {
    return new Windows(IntervalMap.map2(
        this.segments, other.segments,
        (l, r) -> {
          if (l.isPresent() && r.isPresent()) {
            return Optional.of(l.get() || r.get());
          } else if (l.isPresent()) {
            return l.get() ? Optional.of(true) : Optional.empty();
          } else if (r.isPresent()) {
            return r.get() ? Optional.of(true) : Optional.empty();
          } else {
            return Optional.empty();
          }
        }
    ));
  }

  /**
   * "Adds" two Windows together.
   *
   * It is almost identical to {@link Windows#or}, except that F + N = F instead of N.
   *
   * L | R | L + R
   * :---:|:---:|:---:
   * T | T | T
   * T | F | T
   * F | F | F
   * T | N | T
   * F | N | F
   * N | N | N
   *
   * N stands for Null.
   *
   * @param other right operand
   * @return a new Windows
   */
  public Windows add(final Windows other) {
    return new Windows(IntervalMap.map2(
        this.segments, other.segments,
        (l, r) -> {
          if (l.isPresent() && r.isPresent()) {
            return Optional.of(l.get() || r.get());
          } else if (l.isPresent()) {
            return l;
          } else return r;
        }
    ));
  }

  /**
   * Inverts the truth value of each segment.
   *
   * Leaves gaps unchanged.
   *
   * @return a new Windows
   */
  public Windows not() {
    //should not be a subtraction because then if it was null originally, then subtracting original from forever
    //  yields true where once was null, which isn't good. we want a simple inversion of true and false here, without
    //  filling nulls.
    return new Windows(this.segments.map(b -> !b));
  }

  /** Gets the time and inclusivity of the leading edge of the first true segment */
  public Optional<Pair<Duration, Interval.Inclusivity>> minTrueTimePoint(){
    for (final var segment: this.segments) {
      if (segment.value()) {
        final var window = segment.interval();
        return Optional.of(Pair.of(window.start, window.startInclusivity));
      }
    }
    return Optional.empty();
  }

  /** Gets the time and inclusivity of the trailing edge of the last true segment */
  public Optional<Pair<Duration, Interval.Inclusivity>> maxTrueTimePoint(){
    for (var segment : segments.segments().reversed()) {
      if (segment.value()) {
        final var window = segment.interval();
        return Optional.of(Pair.of(window.end, window.endInclusivity));
      }
    }
    return Optional.empty();
  }

  /**
   * Sets all true segments that are not fully contained in the interval to false.
   *
   * @return a new Windows
   */
  public Windows trueSubsetContainedIn(final Interval interval) {
    var result = new Windows(interval, false);
    for (final var segment: this.segments) {
      if (segment.value() && interval.contains(segment.interval())) {
        result = result.set(segment.interval(), true);
      }
    }
    return result;
  }

  /**
   * Sets a specific true segment to false.
   *
   * If the argument is N and N >= 0, it will set the Nth true segment to false.
   * If N < 0, it will index true segments from the end instead, where N = -1 is the last true segment.
   *
   * @return a new Windows
   */
  public Windows removeTrueSegment(final int indexToRemove) {
    if (indexToRemove >= 0) {
      int index = 0;
      for (final var interval : this.segments.iterateEqualTo(true)) {
        if (index == indexToRemove) {
          return new Windows(this.segments.set(interval, false));
        } else {
          index += 1;
        }
      }
    } else {
      int index = -1;
      for (var segment : segments.segments().reversed()) {
        if (segment.value()) {
          if (index == indexToRemove) {
            return new Windows(this.segments.set(segment.interval(), false));
          } else {
            index -= 1;
          }
        }
      }
    }
    return this;
  }

  /**
   * Sets all but a specific true segment to false.
   *
   * If the argument is N and N >= 0, it will set all but the Nth true segment to false.
   * If N < 0, it will index true segments from the end instead, where N = -1 is the last true segment.
   *
   * @return a new Windows
   */
  public Windows keepTrueSegment(final int indexToKeep) {
    final var builder = IntervalMap.<Boolean>builder().set(this.segments);
    if (indexToKeep >= 0) {
      int index = 0;
      for (final var interval : this.segments.iterateEqualTo(true)) {
        if (index != indexToKeep) {
          builder.set(Segment.of(interval, false));
        }
        index += 1;
      }
    } else {
      int index = -1;
      for (var segment : segments.segments().reversed()) {
        if (segment.value()) {
          if (index != indexToKeep) {
            builder.set(Segment.of(segment.interval(), false));
          }
          index -= 1;
        }
      }
    }

    return new Windows(builder.build());
  }

  /** Whether all the true segments of the given Windows are contained in the true segments of this. */
  public boolean includes(final Windows other) {
    //if you have:
    //  other:    ---TTTT---TTT------
    //  original: ---------TTTFF-----
    //  then you fail twice, once because first interval not contained at all, second because overlap with false
    //  we can do this with a map2 with a truthtable, so wherever inclusion holds we say true, if its wrong we say false
    //  and then reduce and if there's any falses you failed overall.

    // other |  orig   | output
    //  T    |    T     |   T
    //  T    |    F     |   F
    //  T    |    N     |   F
    //  F    |    T     |   T     //probably won't pass false as a value anyways, but just in case we should handle
    //  F    |    F     |   T     //  in case user passes a NewWindows from another method that has falses...
    //  F    |    N     |   N     //since its false, not a problem if undefined. we handle actual null checks in isNotNull
    //  N    |    T     |   N
    //  N    |    F     |   N
    //  N    |    N     |   N

    final var inclusion = IntervalMap.map2(
        this.segments, other.segments,
        ($original, $other) -> $other.map($ -> !$ || ($original.isPresent() && $original.get()))
    );

    //anywhere where the above has false means inclusion wasn't perfect, so squash and get a truth value:
    return StreamSupport.stream(inclusion.spliterator(), false).allMatch(Segment::value);
  }

  /** Whether the given interval is contained in a true segment in this. */
  public boolean includes(final Interval probe) {
    return this.includes(new Windows(probe, true));
  }

  /**
   * Sets true segments shorter than `minDur` or longer than `maxDur` to false.
   *
   * @return a new Windows
   */
  public Windows filterByDuration(Duration minDur, Duration maxDur) {
    if (minDur.longerThan(maxDur)) {
      throw new IllegalArgumentException("MaxDur %s must be greater than MinDur %s".formatted(minDur.toString(), maxDur.toString()));
    }

    return new Windows(this.segments.map((value, interval) -> {
      if (!value) return false;

      final var duration = interval.duration();
      return !(duration.shorterThan(minDur) || duration.longerThan(maxDur));
    }));
  }

  /**
   * Shifts the true segments' start and end points by the given durations.
   *
   * Also shifts the false segments' start and end points by the opposite durations;
   * i.e. the start is shifted by `fromEnd` and the end is shifted by `fromStart`.
   * This keeps the falses in line with the trues when there are no gaps,
   * and expands/shrinks gaps accordingly when there are.
   *
   * @param fromStart duration to shift false -> true rising edges
   * @param fromEnd duration to shift true -> false falling edges
   * @return a new Windows
   */
  @Override
  public Windows shiftEdges(Duration fromStart, Duration fromEnd) {
    final var builder = IntervalMap.<Boolean>builder();

    for (final var segment : this.segments) {
      final var interval = segment.interval();

      final var shiftedInterval = (segment.value()) ? (
          Interval.between(
              interval.start.saturatingPlus(fromStart), interval.startInclusivity,
              interval.end.saturatingPlus(fromEnd),     interval.endInclusivity)
      ) : (
          Interval.between(
              interval.start.saturatingPlus(fromEnd), interval.startInclusivity,
              interval.end.saturatingPlus(fromStart), interval.endInclusivity)
      );

      builder.set(Segment.of(shiftedInterval, segment.value()));
    }
    return new Windows(builder.build());
  }

  @Override
  public Windows shiftBy(Duration duration) {
    return this.shiftEdges(duration, duration);
  }

  /**
   * Converts this into Spans and splits each Span into sub-spans.
   *
   * @param numberOfSubWindows number of sub-spans for each span.
   * @param internalStartInclusivity Inclusivity for any newly generated span start points (default Inclusive in eDSL).
   * @param internalEndInclusivity Inclusivity for any newly generated span end points (default Exclusive in eDSL).
   * @return a new Spans
   * @throws UnsplittableSpanException if any span contains only one point or contains fewer microseconds than `numberOfSubSpans`.
   * @throws UnsplittableSpanException if any span contains {@link Duration#MIN_VALUE} or {@link Duration#MAX_VALUE} (representing unbounded intervals)
   * @throws InvalidGapsException if there are any gaps in the windows.
   */
  @Override
  public Spans split(final Interval bounds, final int numberOfSubWindows, final Inclusivity internalStartInclusivity, final Inclusivity internalEndInclusivity) {
    return this.intoSpans(bounds).split(bounds, numberOfSubWindows, internalStartInclusivity, internalEndInclusivity);
  }

  @Override
  public LinearProfile accumulatedDuration(final Duration unit) {
    final var builder = IntervalMap.<LinearEquation>builder();

    double accumulator = 0.0;
    for (final var segment: this.segments) {
      final var interval = segment.interval();
      final var rate = segment.value() ? Duration.SECOND.ratioOver(unit) : 0.0;
      final var line = new LinearEquation(
          segment.value() ? interval.start : Duration.ZERO,
          accumulator,
          !interval.isPoint() ? rate : 0.0 // allows coalescing of instantaneous true points
      );
      builder.set(Segment.of(interval, line));
      accumulator += segment.value() ? segment.interval().duration().ratioOver(unit) : 0.0;
    }

    return new LinearProfile(builder.build());
  }

  /**
   * Places an instantaneous true segment at the start of each true segment.
   *
   * Since gaps represent "unknown", true segments that come after a gap don't have a known start point.
   * So instead their first known point is unset and the rest is set to false.
   *
   * True segments that explicitly come directly after false and include their start point have all except their
   * start point set to false. If they don't include the start point, then the whole interval is set to false and the
   * start point is set true.
   *
   * @return a new Windows
   */
  public Windows starts() {
    var result = IntervalMap.<Boolean>builder().set(this.segments).build();
    for (final var segment : result.segments()) {
      if (segment.value()) {
        final boolean meetsFalse;
        if (segment == result.first()) {
          meetsFalse = false;
        } else {
          var s = this.segments.segments().lower(segment);
          meetsFalse = s != null && Interval.meets(s.interval(), segment.interval());
        }
        if (meetsFalse) {
          result = result.set(Interval.at(segment.interval().start), true);
          result = result.set(Interval.between(
              segment.interval().start,
              Inclusivity.Exclusive,
              segment.interval().end,
              segment.interval().endInclusivity), false);
        } else {
          result = result.set(segment.interval(), false);
          if (!segment.interval().contains(Duration.MIN_VALUE)) {
            result = result.unset(Interval.at(segment.interval().start));
          }
        }
      }
    }
    return new Windows(result);
  }

  /**
   * Places an instantaneous true segment at the end of each true segment.
   *
   * Since gaps represent "unknown", true segments that come before a gap don't have a known end point.
   * So instead their last known point is unset and the rest is set to false.
   *
   * True segments that explicitly come directly before false and include their end point have all except their
   * end point set to false. If they don't include the end point, then the whole interval is set to false and the
   * end point is set true.
   *
   * @return a new Windows
   */
  @Override
  public Windows ends() {
    var result = IntervalMap.<Boolean>builder().set(this.segments).build();
    for (final var segment : this.segments.segments()) {
      if (segment.value()) {
        final boolean meetsFalse;
        if (segment == this.segments.segments().last()) {
          meetsFalse = false;
        } else {
          var s = this.segments.segments().higher(segment);
          meetsFalse = s != null && Interval.meets(segment.interval(), s.interval());
        }
        if (meetsFalse) {
          result = result.set(Interval.between(
              segment.interval().start,
              segment.interval().startInclusivity,
              segment.interval().end,
              Inclusivity.Exclusive), false);
          result = result.set(Interval.at(segment.interval().end), true);
        } else {
          result = result.set(segment.interval(), false);
          if (!segment.interval().contains(Duration.MAX_VALUE)) {
            result = result.unset(Interval.at(segment.interval().end));
          }
        }
      }
    }
    return new Windows(result);
  }

  /**
   * Converts this into a Spans object, where each true segment is a Span.
   *
   * @throws InvalidGapsException if there are any gaps in the windows.
   */
  public Spans intoSpans(final Interval bounds) {
    boolean boundsStartContained = false;
    boolean boundsEndContained = false;
    if(this.segments.size() == 1){
      if (segments.first().interval().contains(bounds.start) ||
          Interval.hasSameStart(segments.first().interval(), bounds)) boundsStartContained = true;
      if (segments.first().interval().contains(bounds.end) ||
          Interval.hasSameEnd(segments.first().interval(), bounds)) boundsEndContained = true;
    }
    Interval leftInterval = null;
    Interval rightInterval = null;
    for (final var segment : this.segments.segments()) {
      rightInterval = segment.interval();
      if (leftInterval == null) {
        leftInterval = rightInterval;
        continue;
      }
      if((leftInterval.contains(bounds.start) || rightInterval.contains(bounds.start)) ||
         Interval.hasSameStart(leftInterval, bounds) || Interval.hasSameStart(rightInterval, bounds)) boundsStartContained = true;
      if((leftInterval.contains(bounds.end) || rightInterval.contains(bounds.end)) ||
         Interval.hasSameEnd(leftInterval, bounds) || Interval.hasSameEnd(rightInterval, bounds)) boundsEndContained = true;
      if (leftInterval.isStrictlyBefore(bounds)) continue;
      if (rightInterval.isStrictlyAfter(bounds)) continue;
      if (!leftInterval.adjacent(rightInterval)) {
        var message = new StringBuilder("cannot convert Windows with gaps into Spans (gap at ");
        final var gap = Interval.between(leftInterval.end, leftInterval.endInclusivity.opposite(), rightInterval.start, rightInterval.startInclusivity.opposite());
        message.append(gap.toString());
        message.append(").");
        throw new InvalidGapsException(message.toString());
      }
      leftInterval = rightInterval;
    }
    if (!boundsStartContained) throw new InvalidGapsException("cannot convert Windows with gaps into Spans (gap detected at plan bounds start)");
    if (!boundsEndContained) throw new InvalidGapsException("cannot convert Windows with gaps into Spans (gap detected at plan bounds end)");
    return new Spans(stream()
        .filter(Segment::value)
        .map($ -> Interval.intersect(bounds, $.interval()))
        .filter($ -> !$.isEmpty())
        .toList());
  }

  @Override
  public boolean isConstant() {
    return segments.size() <= 1;
  }

  /** Assigns a default value to all gaps in the profile. */
  @Override
  public Windows assignGaps(final Windows def) {
    return new Windows(
        IntervalMap.map2(
            this.segments, def.segments,
            (original, defaultSegment) -> original.isPresent() ? original : defaultSegment
        )
    );
  }

  @Override
  public Optional<SerializedValue> valueAt(final Duration timepoint) {
    final var matchPiece = segments
        .stream()
        .filter($ -> $.interval().contains(timepoint))
        .findFirst();
    return matchPiece
        .map(a -> SerializedValue.of(a.value()));
  }

  @Override
  public Windows equalTo(final Windows other) {
    return new Windows(
        IntervalMap.map2(
            this.segments, other.segments,
            (left, right) -> left.isPresent() && right.isPresent()
                ? Optional.of(left.get() == right.get())
                : Optional.empty()
        )
    );
  }

  @Override
  public Windows notEqualTo(final Windows other) {
    return equalTo(other).not();
  }

  @Override
  public Windows changePoints() {
    Segment previousSegment = null;
    final var result = IntervalMap.<Boolean>builder().set(this.segments.map($ -> false));
    for (final var segment : this.segments.segments()) {
      if (segment == this.segments.first()) {
        if (!segment.interval().contains(Duration.MIN_VALUE)) {
          result.unset(Interval.at(segment.interval().start));
        }
      } else {
        if (Interval.meets(previousSegment.interval(), segment.interval())) {
          if (!previousSegment.value().equals(segment.value())) {
            result.set(Interval.at(segment.interval().start), true);
          }
        } else {
          result.unset(Interval.at(segment.interval().start));
        }
      }
      previousSegment = segment;
    }

    return new Windows(result.build());
  }

  ////// DELEGATED METHODS

  /** Delegated to {@link IntervalMap#set(Interval, Object)} */
  public Windows set(final Interval interval, final boolean value) {
    return new Windows(segments.set(interval, value));
  }

  /** Delegated to {@link IntervalMap#set(List, Object)} */
  public Windows set(final List<Interval> intervals, final boolean value) {
    return new Windows(segments.set(intervals, value));
  }

  /** Delegated to {@link IntervalMap#set(IntervalMap)} */
  public Windows set(final Windows other) {
    return new Windows(segments.set(other.segments));
  }

  /** Delegated to {@link IntervalMap#unset(Interval...)} */
  public Windows unset(final Interval... intervals) {
    return new Windows(segments.unset(intervals));
  }

  /** Delegated to {@link IntervalMap#unset(List)} */
  public Windows unset(final List<Interval> intervals) {
    return new Windows(segments.unset(intervals));
  }

  /** Delegated to {@link IntervalMap#select(Interval...)} */
  @Override
  public Windows select(final Interval... intervals) {
    return new Windows(segments.select(intervals));
  }

  /** Delegated to {@link IntervalMap#select(List)} */
  public Windows select(final List<Interval> intervals) {
    return new Windows(segments.select(intervals));
  }

  /** Delegated to {@link IntervalMap#size()} */
  public int size() {
    return segments.size();
  }

  /** Delegated to {@link IntervalMap#isEmpty()} */
  public boolean isEmpty() {
    return segments.isEmpty();
  }

  /** Delegated to {@link IntervalMap#iterator()} */
  @Override
  public Iterator<Segment<Boolean>> iterator() {
    return segments.iterator();
  }

  /** Delegated to {@link IntervalMap#iterateEqualTo(Object)} */
  public Iterable<Interval> iterateEqualTo(final boolean value) {
    return segments.iterateEqualTo(value);
  }

  public Iterator<Segment<Boolean>> reverseIterator() {
    return segments.reverseIterator();
  }

  /** Delegated to {@link IntervalMap#stream} */
  public Stream<Segment<Boolean>> stream() {
    return segments.stream();
  }

  @Override
  public boolean equals(final Object other) {
    if (!(other instanceof final Windows w)) return false;
    return segments.equals(w.segments);
  }

  @Override
  public String toString() {
    return segments.toString();
  }

  /**
   * toString only for the true segments
   */
  public String trueSegmentsToString(){
    final var sb = new StringBuilder();
    sb.append("[");
    this.iterateEqualTo(true).forEach(w -> sb.append(w.toString()));
    sb.append("]");
    return sb.toString();
  }
}
