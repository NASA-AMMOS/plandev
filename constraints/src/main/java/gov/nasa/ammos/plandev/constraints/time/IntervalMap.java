package gov.nasa.jpl.aerie.constraints.time;

import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import org.apache.commons.lang3.function.TriFunction;

import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Stream;

import static gov.nasa.jpl.aerie.constraints.time.Interval.Inclusivity.Exclusive;
import static gov.nasa.jpl.aerie.constraints.time.Interval.Inclusivity.Inclusive;
import static gov.nasa.jpl.aerie.constraints.time.IntervalAlgebra.endBeforeStart;

/**
 * A generic container that maps non-overlapping intervals on the timeline to values.
 *
 * Currently, this is only used to represent Windows (booleans), but can be used to represent
 * any type of profile.
 *
 * Gaps in the timeline that have no associated value are allowed, and are often referred to as
 * `null`; although admittedly they are more analogous to `void`. It is not recommended to explicitly store `null`
 * values in the map, although there is technically no reason why you couldn't.
 *
 * The meaning of a gap is typically interpreted to mean "unknown" rather than "undefined".
 *
 * @param <V> Type of data associated with each interval
 */
public final class IntervalMap<V> implements Iterable<Segment<V>> {
  // INVARIANT: `segments` is list of non-empty, non-overlapping segments in ascending order.
  // INVARIANT: If two adjacent segments abut exactly (e.g. [0, 3), [3, 5]), their values are non-equal.
  private final TreeSet<Segment<V>> segments;

  // PRECONDITION: The list of `segments` meets the invariants of the class.
  private IntervalMap(final Collection<? extends Segment<V>> segments) {
    this.segments = new TreeSet<>(segments);
  }

  /** Creates an IntervalMap builder */
  public static <V> Builder<V> builder() {
    return new Builder<>();
  }

  /**
   * Creates an IntervalMap from a potentially un-ordered or overlapping list of segments.
   *
   * Segments are ordered. In the case of overlapping segments, the latter value in the list
   * overwrites the former.
   */
  public static <V> IntervalMap<V> of(final List<Segment<V>> segments) {
    final var builder = new Builder<V>();

    if (invariantsMet(segments)) {
      return new IntervalMap<>(segments);
    }
    for (final var segment : segments) {
      builder.set(segment.interval(), segment.value());
    }

    return builder.build();
  }

  /**
   * Check if invariants are met on a list of segments.<p/>
   * INVARIANT: `segments` is list of non-empty, non-overlapping segments in ascending order.<br/>
   * INVARIANT: If two adjacent segments abut exactly (e.g. [0, 3), [3, 5]), their values are non-equal.
   *
   * @param segments
   * @return
   * @param <V>
   */
  private static <V> boolean invariantsMet(Iterable<Segment<V>> segments) {
    // check if segments meets preconditions
    boolean segmentsOkay = true;
    Segment oldSegment = null;
    for (final var segment : segments) {
      if (segment.interval().isEmpty() ||
          (oldSegment != null &&
           (!endBeforeStart(oldSegment.interval(), segment.interval()) ||
            (segment.interval().start.equals(oldSegment.interval().end) && Objects.equals(segment.value(), oldSegment.value()))))) {
        segmentsOkay = false;
        break;
      }
      oldSegment = segment;
    }
    return segmentsOkay;
  }

  /** Creates an IntervalMap with a single segment. */
  public static <V> IntervalMap<V> of(final Interval interval, final V value) {
    return IntervalMap.of(List.of(Segment.of(interval, value)));
  }

  /**
   * Creates an IntervalMap from a potentially un-ordered or overlapping list of segments.
   *
   * Delegates to {@link IntervalMap#of(List)}.
   */
  @SafeVarargs
  public static <V> IntervalMap<V> of(final Segment<V>... segments) {
    return IntervalMap.of(Arrays.asList(segments));
  }

  /**
   * Creates an IntervalMap with many intervals all associated with the same value.
   *
   * Delegates to {@link IntervalMap#of(List)}.
   */
  public static <V> IntervalMap<V> of(final List<Interval> intervals, final V value) {
    return IntervalMap.of(intervals.stream().map($ -> Segment.of($, value)).toList());
  }

  /** Creates an IntervalMap that is equal to a single value for all representable time */
  public static <V> IntervalMap<V> of(final V value) {
   return IntervalMap.of(List.of(Segment.of(Interval.FOREVER, value)));
  }


  /**
   * Sets an interval to a value
   *
   * @return a new IntervalMap
   */
  public IntervalMap<V> set(final Interval interval, final V value) {
    return set(IntervalMap.of(interval, value));
  }

  /**
   * Sets a list of Intervals to a single value
   *
   * @return a new IntervalMap
   */
  public IntervalMap<V> set(final List<Interval> intervals, final V value) {
    return set(IntervalMap.of(intervals, value));
  }

  /**
   * Copys an IntervalMap on top of this.
   *
   * @return a new IntervalMap
   */
  public IntervalMap<V> set(final IntervalMap<V> other) {
    return IntervalMap.map2(
        this, other,
        (a, b) -> (b.isPresent()) ? b : a
    );
  }

  /**
   * Unsets the given intervals.
   *
   * Turns the given intervals into gaps, if they are not already.
   *
   * @return a new IntervalMap
   */
  public IntervalMap<V> unset(final Interval... intervals) {
    return this.unset(Arrays.stream(intervals).toList());
  }

  /**
   * Unsets the given intervals.
   *
   * Turns the given intervals into gaps, if they are not already.
   *
   * @return a new IntervalMap
   */
  public IntervalMap<V> unset(final List<Interval> intervals) {
    return map2(this, IntervalMap.of(intervals, new Object()), (l, r) -> r.isEmpty() ? l : Optional.empty());
  }

  /**
   * Unsets everything outside the given intervals.
   * @return a new IntervalMap
   */
  public IntervalMap<V> select(final Interval... intervals) {
    return select(Arrays.stream(intervals).toList());
  }

  /**
   * Unsets everything outside the given intervals.
   *
   * @return a new IntervalMap
   */
  public IntervalMap<V> select(final List<Interval> intervals) {
    return IntervalMap.map2(
        this, IntervalMap.of(intervals, new Object()),
        (l, r) -> r.isPresent() ? l : Optional.empty()
    );
  }

  /**
   * Maps intervals and the gaps between them in IntervalMap intervals to new values following some function transform
   *  which converts the old values and gaps to new values.
   *
   * @param transform a function which transforms an {@link Optional} to an Optional, where {@link Optional#empty()} corresponds
   *                  to a gap.
   * @return a new IntervalMap with newly mapped values
   * @param <R> The new value type that the returned IntervalMap's intervals should correspond to
   */
  public <R> IntervalMap<R> map(final Function<V, R> transform) {
    return this.map((v, i) -> transform.apply(v));
  }

  /**
   * Maps intervals and the gaps between them in IntervalMap intervals to new values following some function transform
   * which converts the old values and gaps to new values.
   *
   * The intervals can also be provided to the transform for inspection, but not modification.
   *
   * @param transform a function which transforms an {@link Optional} and {@link Interval} to an Optional, where {@link Optional#empty()} corresponds
   *                  to a gap.
   * @return a new IntervalMap with newly mapped values
   * @param <R> The new value type that the returned IntervalMap's intervals should correspond to
   */
  public <R> IntervalMap<R> map(final BiFunction<V, Interval, R> transform) {
    final var builder = IntervalMap.<R>builder();

    for (final var segment : this.segments) {
      builder.set(segment.interval(), transform.apply(segment.value(), segment.interval()));
    }

    return builder.build();
  }

  /**
   * A similar operation to {@link IntervalMap#map(Function)}, except it allows producing any number of segments
   * per interval. Each segment is then flattened into a new IntervalMap.
   *
   * The outputted segments do not need to be contained to the original interval, but in that case the results from
   * segments later in time will overwrite results from earlier in time. (i.e. last one wins).
   */
  public <R> IntervalMap<R> flatMap(final Function<V, Stream<Segment<R>>> transform) {
    return flatMap(($, interval) -> transform.apply($));
  }

  /**
   * A similar operation to {@link IntervalMap#map(BiFunction)}, except it allows producing any number of segments
   * per interval. Each segment is then flattened into a new IntervalMap.
   *
   * The outputted segments do not need to be contained to the original interval, but in that case the results from
   * segments later in time will overwrite results from earlier in time. (i.e. last one wins).
   */
  public <R> IntervalMap<R> flatMap(final BiFunction<V, Interval, Stream<Segment<R>>> transform) {
    final var unflattened = this.map(transform);
    final var result = IntervalMap.<R>builder();
    for (final var segment: unflattened) {
      segment.value().forEach(result::set);
    }
    return result.build();
  }

  /**
   * A generalized binary operation between two IntervalMaps.
   *
   * @param left left operand
   * @param right right operand
   * @param transform a function that transforms two {@link Optional}s of the left and right operands' types to an
   *                  optional of a new type.
   * @param <V1> value type of the left operand
   * @param <V2> value type of the right operand
   * @param <R> value type of the result
   * @return a new IntervalMap, the result of applying the transform
   */
  public static <V1, V2, R>
  IntervalMap<R> map2(
      final IntervalMap<V1> left,
      final IntervalMap<V2> right,
      final BiFunction<Optional<V1>, Optional<V2>, Optional<R>> transform
  ) {
    return map2(left, right, (interval, l, r) -> transform.apply(l, r));
  }

  /**
   * A generalized binary operation between two IntervalMaps.
   *
   * @param left left operand
   * @param right right operand
   * @param transform a function that transforms an interval and two {@link Optional}s of the left and right operands' types to an
   *                  optional of a new type.
   * @param <V1> value type of the left operand
   * @param <V2> value type of the right operand
   * @param <R> value type of the result
   * @return a new IntervalMap, the result of applying the transform
   */
  public static <V1, V2, R>
  IntervalMap<R> map2(
      final IntervalMap<V1> left,
      final IntervalMap<V2> right,
      final TriFunction<Interval, Optional<V1>, Optional<V2>, Optional<R>> transform
  ) {
    final var result = new TreeSet<Segment<R>>();//new ArrayList<Segment<R>>();

    var startTime = Duration.MIN_VALUE;
    var startInclusivity = Inclusive;
    Duration endTime;
    Interval.Inclusivity endInclusivity;

    final Iterator<Segment<V1>> leftIter = left.segments.iterator();
    final Iterator<Segment<V2>> rightIter = right.segments.iterator();

    Interval leftInterval;
    Interval rightInterval;
    Optional<V1> leftValue;
    Optional<V2> rightValue;

    Optional<R> previousValue = Optional.empty();

    boolean leftGetNext = true;
    boolean rightGetNext = true;
    boolean leftDone = false;
    boolean rightDone = false;
    Segment<V1> leftNextDefinedSegment = null;
    Segment<V2> rightNextDefinedSegment = null;
    Segment<R> lastSegmentAdded = null;

    while (startTime.shorterThan(Duration.MAX_VALUE) || startInclusivity == Inclusive) {
      if (!leftDone && (!leftGetNext || leftIter.hasNext())) {
        if (leftGetNext) leftNextDefinedSegment = leftIter.next();
        leftGetNext = false;
        if (leftNextDefinedSegment.interval().start.shorterThan(startTime) || (leftNextDefinedSegment.interval().start.equals(startTime) && !leftNextDefinedSegment.interval().startInclusivity.moreRestrictiveThan(startInclusivity))) {
          leftInterval = leftNextDefinedSegment.interval();
          leftValue = Optional.of(leftNextDefinedSegment.value());
          leftGetNext = true;
        } else {
          leftInterval = Interval.between(
              Duration.MIN_VALUE,
              Inclusive,
              leftNextDefinedSegment.interval().start,
              leftNextDefinedSegment.interval().startInclusivity.opposite());
          leftValue = Optional.empty();
        }
      } else {
        leftDone = true;
        leftInterval = Interval.FOREVER;
        leftValue = Optional.empty();
      }

      if (!rightDone && (!rightGetNext || rightIter.hasNext())) {
        if (rightGetNext) rightNextDefinedSegment = rightIter.next();
        rightGetNext = false;
        if (rightNextDefinedSegment.interval().start.shorterThan(startTime) || (rightNextDefinedSegment.interval().start.equals(startTime) && !rightNextDefinedSegment.interval().startInclusivity.moreRestrictiveThan(startInclusivity))) {
          rightInterval = rightNextDefinedSegment.interval();
          rightValue = Optional.of(rightNextDefinedSegment.value());
          rightGetNext = true;
        } else {
          rightInterval = Interval.between(
              Duration.MIN_VALUE,
              Inclusive,
              rightNextDefinedSegment.interval().start,
              rightNextDefinedSegment.interval().startInclusivity.opposite());
          rightValue = Optional.empty();
        }
      } else {
        rightDone = true;
        rightInterval = Interval.FOREVER;
        rightValue = Optional.empty();
      }

      if (leftInterval.end.equals(rightInterval.end)) {
        endTime = leftInterval.end;
        if (leftInterval.includesEnd() && rightInterval.includesEnd()) {
          endInclusivity = Inclusive;
        } else if (leftInterval.includesEnd()) {
          endInclusivity = Exclusive;
          leftGetNext = false;
        } else if (rightInterval.includesEnd()) {
          endInclusivity = Exclusive;
          rightGetNext = false;
        } else {
          endInclusivity = Exclusive;
          leftGetNext = false;
        }
      } else if (leftInterval.end.shorterThan(rightInterval.end)) {
        endTime = leftInterval.end;
        endInclusivity = leftInterval.endInclusivity;
        rightGetNext = false;
      } else {
        endTime = rightInterval.end;
        endInclusivity = rightInterval.endInclusivity;
        leftGetNext = false;
      }
      var finalInterval = Interval.between(startTime, startInclusivity, endTime, endInclusivity);
      if (finalInterval.isEmpty()) continue;

      var newValue = transform.apply(finalInterval, leftValue, rightValue);
      if (newValue.isPresent()) {
        if (!newValue.equals(previousValue)) {
          lastSegmentAdded = Segment.of(finalInterval, newValue.get());
          result.add(lastSegmentAdded);
        } else {
          var previousInterval = lastSegmentAdded.interval();
          result.remove(lastSegmentAdded);
          lastSegmentAdded =
              Segment.of(
                  Interval.unify(previousInterval, finalInterval),
                  newValue.get()
              );
          result.add(lastSegmentAdded);
        }
      }
      previousValue = newValue;
      startTime = endTime;
      startInclusivity = endInclusivity.opposite();
    }

    // SAFETY: ???
    return new IntervalMap<>(result);
  }

  /**
   * A similar operation to {@link IntervalMap#map2}, except it allows producing any number of segments
   * per overlapping interval. Each segment is then flattened into a new IntervalMap.
   */
  public static <V1, V2, R>
  IntervalMap<R> flatMap2(
      final IntervalMap<V1> left,
      final IntervalMap<V2> right,
      final BiFunction<Optional<V1>, Optional<V2>, Stream<Segment<R>>> transform
  ) {
    final var unflattened = map2(left, right, (l, r) -> Optional.of(transform.apply(l,r)));
    final var result = IntervalMap.<R>builder();
    for (final var segment: unflattened) {
      segment.value().forEach(result::set);
    }
    return result.build();
  }

  /** The number of defined intervals in this. */
  public int size() {
    return this.segments.size();
  }

  /** Whether this has no defined segments */
  public boolean isEmpty() {
    return this.segments.isEmpty();
  }

  @Override
  public Iterator<Segment<V>> iterator() {
    return this.segments.iterator();
  }

  public Iterator<Segment<V>> reverseIterator() {
    return this.segments.descendingIterator();
  }

  /** Creates an iterable over the Intervals where this map is equal to a value */
  public Iterable<Interval> iterateEqualTo(final V value) {
    return () -> this.segments
        .stream()
        .filter($ -> $.value().equals(value))
        .map(Segment::interval)
        .iterator();
  }

  public TreeSet<Segment<V>> segments() {
    return this.segments;
  }

  public Stream<Segment<V>> stream() {
    return this.segments.stream();
  }

  @Override
  public boolean equals(final Object other) {
    if (!(other instanceof final IntervalMap<?> o)) return false;
    return this.segments.equals(o.segments);
  }

  @Override
  public String toString() {
    return this.segments.toString();
  }

  public Segment<V> first() {
    if (segments == null) return null;
    return segments.first();
  }


  /** A builder for IntervalMap */
  public static final class Builder<V> {
    // INVARIANT: `segments` is list of non-empty, non-overlapping segments in ascending order.
    // INVARIANT: If two adjacent segments abut exactly (e.g. [0, 3), [3, 5]), their values are non-equal.
    private TreeSet<Segment<V>> segments;
    private boolean built = false;

    public Builder() {
      this.segments = new TreeSet<>();
    }

    public Builder<V> set(final IntervalMap<V> map) {
      for (final var segment: map) {
        set(segment);
      }
      return this;
    }

    public Builder<V> set(final Segment<V> segment) {
      return set(segment.interval(), segment.value());
    }

    public Builder<V> set(Interval interval, final V value) {
      if (this.built) throw new IllegalStateException();

      if (interval.isEmpty()) return this;

      // <> is `interval`, the interval to apply; [] is the currently-indexed interval in the map.
      // Cases: --[---]---<--->--
      Segment<V> s = null;
      var originalS = Segment.of(interval, value);
      var iter = this.segments.headSet(originalS, true).descendingIterator();
      Segment<V> lowerS = null;
      while (iter.hasNext()) {
        lowerS = iter.next();
        if (IntervalAlgebra.endsStrictlyBefore(lowerS.interval(), originalS.interval())) {
          break;
        } else {
          s = lowerS;
        }
      }
      if (s == null) { // there are no elements that start before `interval`
        s = this.segments.higher(originalS);
      }

      // Cases: --[---<---]--->-- and --[---<--->---]--
      if (s != null && IntervalAlgebra.startsBefore(s.interval(), interval)) {
        // If the intervals agree on their value, we can unify the old interval with the new one.
        // Otherwise, we'll snip the old one.
        if (Objects.equals(s.value(), value)) {
          segments.remove(s);
          interval = IntervalAlgebra.unify(s.interval(), interval);
        } else {
          final var prefix = IntervalAlgebra.intersect(s.interval(), IntervalAlgebra.strictLowerBoundsOf(interval));
          final var suffix = IntervalAlgebra.intersect(s.interval(), IntervalAlgebra.strictUpperBoundsOf(interval));
          this.segments.remove(s);
          s = Segment.of(prefix, s.value());
          this.segments.add(s);
          if (!IntervalAlgebra.isEmpty(suffix)) {
            s = Segment.of(suffix, s.value());
            this.segments.add(s);
          } else {
            s = this.segments.higher(s);
          }
        }
      }

      // Cases: --<---[---]--->--
      while (s != null && !IntervalAlgebra.endsAfter(s.interval(), interval)) {
        this.segments.remove(s);
        s = this.segments.higher(s);
      }

      // Cases: --<---[--->---]--
      if (s != null && !IntervalAlgebra.startsStrictlyAfter(s.interval(), interval)) {
        // If the intervals agree on their value, we can unify the old interval with the new one.
        // Otherwise, we'll snip the old one.
        this.segments.remove(s);
        if (Objects.equals(s.value(), value)) {
          interval = IntervalAlgebra.unify(s.interval(), interval);
        } else {
          final var suffix = IntervalAlgebra.intersect(s.interval(), IntervalAlgebra.strictUpperBoundsOf(interval));
          this.segments.add(Segment.of(suffix, s.value()));
        }
      }

      // now, everything left of s is strictly left of `interval`,
      // and everything right of s is strictly right of `interval`,
      // so adding this interval to the list is trivial.
      this.segments.add(Segment.of(interval, value));

      return this;
    }

    public Builder<V> unset(Interval interval) {
      if (this.built) throw new IllegalStateException();

      if (interval.isEmpty()) return this;

      Segment<V> s = Segment.of(interval, null);
      s = segments.ceiling(s);
      while (s != null && !IntervalAlgebra.startsStrictlyAfter(s.interval(), interval)) {
        // TODO -- This might be cleaner and more efficient with a headset, but the part at the end with
        //         ceiling() and first() doesn't fit.
        //         Actually, it might be better to rewrite since it was written based on segments being a list,
        //         and now segments is an ordered set.
        final var existingInterval = s.interval();
        if (IntervalAlgebra.startsBefore(interval, existingInterval)) {
          segments.remove(s);
          if (IntervalAlgebra.contains(interval, existingInterval)) {
            s = segments.lower(s);
          } else {
            final var newInterval = Interval.between(interval.end, interval.endInclusivity.opposite(),
                                                     existingInterval.end, existingInterval.endInclusivity);
            if (!newInterval.isEmpty()) {
              this.segments.add(Segment.of(newInterval, s.value()));
            } else {
              s = segments.lower(s);
            }
          }
        } else {
          this.segments.remove(s);
          var value = s.value();
          final var leftInterval = Interval.between(existingInterval.start, existingInterval.startInclusivity,
                                                    interval.start, interval.startInclusivity.opposite());
          if (!leftInterval.isEmpty()) {
            this.segments.add(Segment.of(leftInterval, value));
          } else {
            s = segments.lower(s);
          }
          if (IntervalAlgebra.endsAfter(existingInterval, interval)) {
            final var rightInterval = Interval.between(interval.end, interval.endInclusivity.opposite(),
                                                       existingInterval.end, existingInterval.endInclusivity);
            if (!rightInterval.isEmpty()) {
              this.segments.add(Segment.of(rightInterval, value));
            } else {
              s = segments.lower(s);
            }
            if (s == null && !segments.isEmpty()) s = segments.first();
            else s = segments.higher(s);
          }
        }
        if (s != null) s = segments.higher(s);
      }

      return this;
    }

    public IntervalMap<V> build() {
      if (this.built) throw new IllegalStateException();
      this.built = true;

      final var segments = this.segments;
      this.segments = null;

      // SAFETY: `segments` meets the same invariants as required by `IntervalMap`.
      return new IntervalMap<>(segments);
    }
  }
}
