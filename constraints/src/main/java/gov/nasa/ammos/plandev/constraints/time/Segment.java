package gov.nasa.jpl.aerie.constraints.time;

import gov.nasa.jpl.aerie.merlin.protocol.types.ObjectComparator;

import java.util.Comparator;

/** A basic container used by {@link IntervalMap} that associates an interval with a value */
public record Segment<V>(Interval interval, V value) implements Comparable<Segment<V>> {
  public static <V> Segment<V> of(final Interval interval, final V value) {
    return new Segment<>(interval, value);
  }

  @Override
  public int compareTo(final Segment<V> o) {
    final var comparator =
        Comparator.comparing(Segment<V>::interval).thenComparing(Segment::value, ObjectComparator.getInstance());
    return comparator.compare(this, o);
  }

}
