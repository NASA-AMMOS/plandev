package gov.nasa.jpl.aerie.constraints.time;

import gov.nasa.jpl.aerie.constraints.model.ActivityInstance;
import gov.nasa.jpl.aerie.constraints.model.LinearEquation;
import gov.nasa.jpl.aerie.constraints.model.LinearProfile;
import gov.nasa.jpl.aerie.constraints.time.Interval.Inclusivity;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * A collection of intervals that can overlap.
 */
public class Spans implements IntervalContainer<Spans>, Iterable<Segment<Optional<Spans.Metadata>>> {
  private final List<Segment<Optional<Metadata>>> intervals;

  public record Metadata(ActivityInstance activityInstance){}

  public Spans() {
    this.intervals = new ArrayList<>();
  }

  public Spans(final List<Segment<Optional<Metadata>>> intervals) {
    this.intervals = new ArrayList<>(intervals);
  }

  public Spans(final Spans spans){
    this.intervals = new ArrayList<>(spans.intervals);
  }

  public Spans(final Iterable<Interval> iter) {
    this.intervals = new ArrayList<>();
    StreamSupport.stream(iter.spliterator(), false).filter($ -> !$.isEmpty())
                 .forEach(itv -> this.intervals.add(Segment.of(itv, Optional.empty())));
  }

  public Spans(final Interval... intervals) {
    this.intervals = new ArrayList<>();
    Arrays.stream(intervals).filter($ -> !$.isEmpty()).forEach(itv -> this.intervals.add(Segment.of(itv, Optional.empty())));
  }

  @SafeVarargs
  public Spans(final Segment<Optional<Metadata>>... intervals) {
    this.intervals = new ArrayList<>();
    Arrays.stream(intervals).filter($ -> !$.interval().isEmpty()).forEach(this.intervals::add);
  }

  public Windows intoWindows() {
    return new Windows(false).set(this.intervals.stream().map(Segment::interval).toList(), true);
  }

  public void add(final Interval window) {
    if (!window.isEmpty()) {
      this.intervals.add(Segment.of(window, Optional.empty()));
    }
  }

  public void add(final Interval window, Optional<Metadata> metadata) {
    if (!window.isEmpty()) {
      this.intervals.add(Segment.of(window, metadata));
    }
  }
  public void addAll(final Spans iter) {
    this.intervals.addAll(iter.intervals);
  }

  public void addAll(final Iterable<Segment<Optional<Metadata>>> iter) {
    StreamSupport
        .stream(iter.spliterator(), false)
        .filter($ -> !$.interval().isEmpty())
        .forEach(this.intervals::add);
  }

  public Spans map(final Function<Interval, Interval> mapper) {
    final var ret = new Spans();
    this.intervals.forEach(interval -> {
        final var newInterval = mapper.apply(interval.interval());
        if(!newInterval.isEmpty())
          ret.add(newInterval, interval.value());
    });
    return ret;
  }

  public Spans flatMap(final Function<Interval, ? extends Stream<Interval>> mapper) {
    final var ret = new Spans();
    this.intervals.forEach(interval -> mapper.apply(interval.interval()).filter(x -> !x.isEmpty()).forEach(
        newInterval -> ret.add(newInterval, interval.value())));
    return ret;
  }

  public Spans filter(final Predicate<Interval> filter) {
    final var pred = new Predicate<Segment<Optional<Metadata>>>() {
      @Override
      public boolean test(final Segment<Optional<Metadata>> intervalOptionalPair) {
        return filter.test(intervalOptionalPair.interval());
      }
    };
    return new Spans(this.intervals.stream().filter(pred).toList());
  }

  /**
   * Splits each span into sub-spans.
   *
   * @param numberOfSubSpans number of sub-spans for each span.
   * @param internalStartInclusivity Inclusivity for any newly generated span start points (default Inclusive in eDSL).
   * @param internalEndInclusivity Inclusivity for any newly generated span end points (default Exclusive in eDSL).
   * @return a new Spans
   * @throws UnsplittableSpanException if any span contains only one point or contains fewer microseconds than `numberOfSubSpans`.
   * @throws UnsplittableSpanException if any span contains {@link Duration#MIN_VALUE} or {@link Duration#MAX_VALUE} (representing unbounded intervals)
   */
  @Override
  public Spans split(final Interval bounds, final int numberOfSubSpans, final Inclusivity internalStartInclusivity, final Inclusivity internalEndInclusivity) {
    if (numberOfSubSpans == 1) {
      return new Spans(this);
    }
    return this.flatMap(x -> {
      // Width of each sub-window, rounded down to the microsecond
      final var width = Duration.divide(Duration.subtract(x.end, x.start), numberOfSubSpans);

      final var numberOfMicroSeconds = Duration.subtract(x.end, x.start).in(Duration.MICROSECOND);

      // We throw an exception if the interval contains fewer microseconds than the requested number of sub-spans.
      if (x.isSingleton()) {
        throw new UnsplittableSpanException("Cannot split an instantaneous span into " + numberOfSubSpans + " pieces.");
      } else if (numberOfMicroSeconds < numberOfSubSpans) {
        throw new UnsplittableSpanException("Cannot split a span only " + numberOfMicroSeconds + " microseconds long into " + numberOfSubSpans + " pieces.");
      }

      // Throw an exception if trying to split an "unbounded" interval.
      // It is unlikely that a user will ever need to split a Windows or Spans that includes +/- infinity.
      //
      // If they do, and it is a legitimate use case that we should support, this block should be replaced
      // with a check that returns the unbounded interval unchanged, because the split points on an unbounded
      // interval will be outside the finite range of `Duration`.
      if (x.contains(Duration.MIN_VALUE)) {
        throw new UnsplittableSpanException("Cannot split an unbounded span. (interval contains MIN_VALUE, which is a stand-in for -infinity.");
      } else if (x.contains(Duration.MAX_VALUE)) {
        throw new UnsplittableSpanException("Cannot split an unbounded span. (interval contains MAX_VALUE, which is a stand-in for +infinity.");
      }

      var cursor = Duration.add(x.start, width);
      final List<Interval> ret = new ArrayList<>();
      ret.add(Interval.between(x.start, x.startInclusivity, cursor, internalEndInclusivity));
      for (int i = 1; i < numberOfSubSpans - 1; i++) {
        final var nextCursor = Duration.add(cursor, width);
        ret.add(Interval.between(cursor, internalStartInclusivity, nextCursor, internalEndInclusivity));
        cursor = nextCursor;
      }
      ret.add(Interval.between(cursor, internalStartInclusivity, x.end, x.endInclusivity));
      return ret.stream().map($ -> Interval.intersect(bounds, $));
    });
  }

  /**
   * Evaluates whether this set of spans is a strict subset of another set of spans
   * {[1,3]} is not a strict subset of {[0,4]}
   * {[1,3]} is a strict subset of {[1, 3], [5, 6]}
   * @param otherSpans the other set of spans
   * @return true if the other set of spans contains exactly all the spans in this set, false otherwise
   */
  public boolean isCollectionSubsetOf(final Spans otherSpans){
    return new HashSet<>(otherSpans.intervals).containsAll(this.intervals);
  }

  public Spans intersectWith(final Windows windows){
    final var ret = new Spans();
    this.intervals.forEach(x -> windows.iterateEqualTo(true).iterator().forEachRemaining(y -> ret.add(Interval.intersect(x.interval(), y), x.value())));
    return ret;
  }

  @Override
  public LinearProfile accumulatedDuration(final Duration unit) {
    final var profiles = new ArrayList<LinearProfile>(this.intervals.size() + 1);

    final var zero = new LinearEquation(Duration.ZERO, 0, 0);

    for (final var segment: this.intervals) {
      final var interval = segment.interval();
      if (interval.isPoint()) continue; // ignore instantaneous point spans
      final var rate = Duration.SECOND.ratioOver(unit);
      final var total = interval.duration().ratioOver(unit);
      final var slopedLine = new LinearEquation(
          interval.start,
          0.0,
          rate
      );
      profiles.add(new LinearProfile(
          Segment.of(Interval.between(Duration.MIN_VALUE, Inclusivity.Inclusive, interval.start, interval.startInclusivity.opposite()), zero),
          Segment.of(interval, slopedLine),
          Segment.of(Interval.between(interval.end, interval.endInclusivity.opposite(), Duration.MAX_VALUE, Inclusivity.Inclusive), new LinearEquation(Duration.ZERO, total, 0))
      ));
    }

    return profiles.stream().reduce(new LinearProfile(Segment.of(Interval.FOREVER, zero)), LinearProfile::plus);
  }


  @Override
  public Spans starts() {
    return this.map($ -> Interval.at($.start));
  }

  @Override
  public Spans ends() {
    return this.map($ -> Interval.at($.end));
  }

  @Override
  public Spans shiftEdges(final Duration fromStart, final Duration fromEnd) {
    return this.map($ -> Interval.between($.start.plus(fromStart), $.startInclusivity, $.end.plus(fromEnd), $.endInclusivity))
        .filter($ -> !$.isEmpty());
  }

  @Override
  public Spans select(final Interval... intervals) {
    return this.flatMap($ -> Arrays.stream(intervals).map(selection -> Interval.intersect($, selection)));
  }

  @Override
  public boolean equals(final Object obj) {
    if (!(obj instanceof final Spans spans)) return false;

    return Objects.equals(this.intervals, spans.intervals);
  }

  @Override
  public int hashCode() {
    return Objects.hash(this.intervals);
  }

  @Override
  public String toString() {
    return this.intervals.toString();
  }

  @Override
  public Iterator<Segment<Optional<Metadata>>> iterator() {
    return this.intervals.iterator();
  }
}
