package gov.nasa.jpl.aerie.scheduler;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * represents an inclusive interval of partially ordered values
 *
 * the semantics of a range include both of its endpoints, as well as any
 * value that compares greater than or equal to the minimum and less than or
 * equal to the maximum
 *
 * ranges may have the same minimum and maximum value, in which case it admits
 * only values that compare equal to those bounds
 */
//TODO: use some standard library type
//TODO: probably want some expression of open/half-open intervals too
public class Range<T extends Comparable<T>> implements Comparable<Range<T>> {

  /**
   * creates a new range that reprsents only a single allowed value
   *
   * @param singleton IN the single value that is admitted by the new range
   */
  public Range(T singleton) {
    if (singleton == null) {
      throw new IllegalArgumentException(
          "creating range with null Rangesingleton value");
    }

    this.minimum = singleton;
    this.maximum = singleton;
  }

  public boolean isSingleton() {
    return this.minimum.equals(this.maximum);
  }

  /**
   * creates a new range with given minimum and maximum bounds
   *
   * the range admits both endpoints and any value between them
   *
   * the minimum and maximum may be the same value
   *
   * @param minimum IN the inclusive minimum bound of the new range
   * @param maximum IN the inclusive maximum bound of the new range
   */
  public Range(T minimum, T maximum) {
    //TODO: consider allowing unbounded ranges via null min/max
    if (minimum == null) {
      throw new IllegalArgumentException(
          "creating range with null minimum");
    }
    if (maximum == null) {
      throw new IllegalArgumentException(
          "creating range with null maximum");
    }
    if (minimum.compareTo(maximum) > 0) {
      throw new IllegalArgumentException(
          "creating range with mis-ordered minimum=" + minimum
          + " maximum=" + maximum);
    }

    this.minimum = minimum;
    this.maximum = maximum;
  }

  /**
   * factory creates a new range with given minimum and maximum bounds
   *
   * the range admits both endpoints and any value between them
   *
   * the minimum and maximum may be the same value
   *
   * @param minimum IN the inclusive minimum bound of the new range
   * @param maximum IN the inclusive maximum bound of the new range
   */
  public static <T extends Comparable<T>> Range<T> of(T minimum, T maximum) {
    return new Range<>(minimum,maximum);
  }

  /**
   * the inclusive lower bound of the range
   */
  private final T minimum;

  /**
   * the inclusive upper bound of the range
   */
  private final T maximum;

  /**
   * fetch the inclusive lower bound of the range
   *
   * @return the inclusive lower bound of the range
   */
  public T getMinimum() {
    return minimum;
  }

  /**
   * fetch the inclusive upper bound of the range
   *
   * @return the inclusive upper bound of the range
   */
  public T getMaximum() {
    return maximum;
  }

  /**
   * determines if this range admits the queried value
   *
   * the range inclusively admits values equal to its upper and lower bounds
   * as well as any value between the two
   *
   * @param probe IN the value to test if it is in the range
   * @return true iff the queried value is within the inclusive range, false
   *     otherwise
   */
  public boolean contains(T probe) {

    return (probe.compareTo(minimum) >= 0)
           && (probe.compareTo(maximum) <= 0);
  }

  public List<Range<T>> subsetFullyContained(List<Range<T>> windows) {
    List<Range<T>> ret = new ArrayList<>();
    for (var win : windows) {
      if (this.contains(win)) {
        ret.add(win);
      }
    }
    return ret;
  }

  /**
   * Returns new range from intersection with other range
   *
   * @param otherRange range to be intersected with
   * @return a new range from intersection with otherRange
   */
  public Range<T> intersect(Range<T> otherRange) {
    if (otherRange.minimum.compareTo(this.maximum) > 0 || this.minimum.compareTo(otherRange.maximum) > 0) {
      return null;
    } else {
      return new Range<>(
          Collections.max(Arrays.asList(this.minimum, otherRange.minimum)),
          Collections.min(Arrays.asList(this.maximum, otherRange.maximum)));
    }
  }

  public List<Range<T>> subtract(Range<T> otherRange) {
    List<Range<T>> retList = new ArrayList<>();
    var intersect = intersect(otherRange);
    if (intersect == null) {
      //case 1 : disjoint => return this interval
      retList.add(new Range<>(this.minimum, this.maximum));
    } else {
      if (intersect.equalsRange(this)) {
        //case 2, return nothing
      } else if (this.contains(otherRange)) {
        // case 3 : make two
        if (this.getMinimum().compareTo(intersect.getMinimum()) != 0) {
          retList.add(new Range<>(this.getMinimum(), intersect.getMinimum()));
        }
        if (this.getMaximum().compareTo(intersect.getMaximum()) != 0) {
          retList.add(new Range<>(intersect.getMaximum(), this.getMaximum()));
        }
      } else {
        //simple intersection
        //other is before
        if (intersect.contains(this.getMinimum())) {
          retList.add(new Range<>(intersect.getMaximum(), this.getMaximum()));
          //other is after
        } else if (intersect.contains(this.getMaximum())) {
          retList.add(new Range<>(this.getMinimum(), intersect.getMinimum()));
        }
      }
    }

    return retList;
  }

  public boolean isBefore(@NotNull Range<T> otherRange) {
    return this.getMaximum().compareTo(otherRange.getMinimum()) < 0;
  }

  public boolean isAfter(Range<T> otherRange) {
    return this.getMinimum().compareTo(otherRange.getMaximum()) > 0;
  }

  public boolean isAdjacent(Range<T> otherRange) {
    return this.getMinimum().compareTo(otherRange.getMaximum()) == 0
           || this.getMaximum().compareTo(otherRange.getMinimum()) == 0;
  }


  /**
   * return a range enveloping the two passed ranges
   *
   * @param range1 the first range to consider
   * @param range2 the second range to consider
   * @return a range enveloping the two passed ranges
   */
  public Range<T> envelop(@NotNull Range<T> range1, @NotNull Range<T> range2) {
    return new Range<>(
        Collections.min(Arrays.asList(range1.getMinimum(), range2.getMinimum())),
        Collections.max(Arrays.asList(range1.getMaximum(), range2.getMaximum())));
  }

  public boolean contains(@NotNull Range<T> otherRange) {
    return otherRange.getMinimum().compareTo(this.getMinimum()) >= 0
           && otherRange.getMaximum().compareTo(this.getMaximum()) <= 0;
  }

  @Override
  public int hashCode() {
    return Objects.hash(this.minimum, this.maximum);
  }

  @Override
  @SuppressWarnings("unchecked")
  public boolean equals(Object o) {
    if (o instanceof Range<?>) {
      return equalsRange((Range<T>) o);
    }
    return false;
  }

  public boolean equalsRange(Range<T> otherRange) {
    return this.getMinimum().compareTo(otherRange.getMinimum()) == 0
           && this.getMaximum().compareTo(otherRange.getMaximum()) == 0;
  }

  /**
   * {@inheritDoc}
   *
   * gives a simple human-readable reprsentation of the closed interval range
   */
  @Override
  public String toString() {
    return "[" + minimum + "," + maximum + "]";
  }

  @Override
  public int compareTo(Range<T> o) {
    final var comparator = Comparator.comparing(Range<T>::getMinimum).thenComparing(Range::getMaximum);
    return comparator.compare(this,o);
  }

}
