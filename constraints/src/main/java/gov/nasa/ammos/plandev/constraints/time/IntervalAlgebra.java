package gov.nasa.jpl.aerie.constraints.time;

import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;

import static gov.nasa.jpl.aerie.constraints.time.Interval.Inclusivity.Inclusive;

/**
 * Provides helpful static methods for transforming and operating on {@link Interval}s.
 *
 * The relational methods (e.g. {@link IntervalAlgebra#startsBefore} or {@link IntervalAlgebra#meets}) correspond to
 * Allen's Interval Algebra, and {@link IntervalAlgebra#unify} and {@link IntervalAlgebra#intersect} correspond to
 * the operations on a Boolean Algebra on intervals.
 *
 * @see <a href=”https://en.wikipedia.org/wiki/Allen%27s_interval_algebra”>Allen's Interval Algebra</a>
 * @see <a href=”https://en.wikipedia.org/wiki/Boolean_algebra_(structure)”>Boolean Algebra</a>
 */
public class IntervalAlgebra {

  private IntervalAlgebra() {}

  /**
   * Determines if an interval contains no points.
   * @param x an interval
   * @return `true` if no points on the timeline are in x, otherwise `false`.
   */
  public static boolean isEmpty(Interval x) {
    return x.isEmpty();
  }

  /**
   * The smallest interval that contains the union of the inputs.
   *
   * This is the "join" operation on the Boolean Algebra, but it is
   * named "unify" to be more suggestive of what it does. Since the union
   * of two intervals might not be an interval, this returns the smallest interval
   * than contains the union.
   *
   * @param x left operand
   * @param y right operand
   * @return smallest interval that contains both operands
   */
  public static Interval unify(final Interval x, final Interval y) {
    return Interval.unify(x, y);
  }

  /**
   * The intersection of two intervals.
   *
   * Also known as the "meet" operation on the Boolean Algebra.
   *
   * @param x left operand
   * @param y right operand
   * @return the intersection of the operands
   */
  public static Interval intersect(final Interval x, final Interval y) {
    return Interval.intersect(x, y);
  }

  /**
   * The set of strict lower bounds of an interval.
   *
   * A point is a lower bound of a subset if it is less than or equal
   * to every point in the subset. A *strict* lower bound is not a standard
   * mathematical concept, but we use it to mean a point that is strictly less than
   * every point in the subset.
   *
   * In the case of a 1-dimensional timeline, lower and upper bounds are always intervals.
   *
   * @param x an interval
   * @return the interval containing all points less than every point in x
   */
  public static Interval strictLowerBoundsOf(final Interval x) {
    if (x.isEmpty()) return Interval.FOREVER;
    return Interval.between(
        Duration.MIN_VALUE,
        Inclusive,
        x.start,
        x.startInclusivity.opposite()
    );
  }

  /**
   * The set of strict upper bounds of an interval.
   *
   * A point is an upper bound of a subset if it is greater than or equal
   * to every point in the subset. A *strict* greater bound is not a standard
   * mathematical concept, but we use it to mean a point that is strictly greater than
   * every point in the subset.
   *
   * In the case of a 1-dimensional timeline, lower and upper bounds are always intervals.
   *
   * @param x an interval
   * @return the interval containing all points greater than every point in x
   */
  public static Interval strictUpperBoundsOf(final Interval x) {
    if (x.isEmpty()) return Interval.FOREVER;
    return Interval.between(
        x.end,
        x.endInclusivity.opposite(),
        Duration.MAX_VALUE,
        Inclusive
    );
  }

  /**
   * Whether the start of one interval is before the start of another.  This assumes that the intervals are both
   * non-empty but does not check.
   * @param x the first interval
   * @param y the second interval
   * @return whether the start of x is before the start of y
   */
  public static boolean startBeforeStart(Interval x, Interval y) {
    return x.start.shorterThan(y.start) ||
           (x.start.equals(y.start) && (x.includesStart() && !y.includesStart()));
  }

  /**
   * Whether the end of one interval is before the start of another.  This assumes that the intervals are both
   * non-empty but does not check.
   * @param x the first interval
   * @param y the second interval
   * @return whether the end of x is before the start of y
   */
  public static boolean endBeforeStart(Interval x, Interval y) {
    return x.end.shorterThan(y.start) ||
           (x.end.equals(y.start) && (!x.includesEnd() || !y.includesStart()));
  }

  /**
   * Whether the end of one interval is before the end of another.  This assumes that the intervals are both
   * non-empty but does not check.
   * @param x the first interval
   * @param y the second interval
   * @return whether the end of x is before the end of y
   */
  public static boolean endBeforeEnd(Interval x, Interval y) {
    return x.end.shorterThan(y.end) ||
           (x.end.equals(y.end) && (!x.includesEnd() && y.includesEnd()));
  }

  /**
   * Whether the start of one interval is before the end of another.  This assumes that the intervals are both
   * non-empty but does not check.
   * @param x the first interval
   * @param y the second interval
   * @return whether the start of x is before the end of y
   */
  public static boolean startBeforeEnd(Interval x, Interval y) {
    return x.start.shorterThan(y.end);
  }


  /**
   * Whether any point is contained in both operands.
   *
   * @param x left operand
   * @param y right operand
   * @return whether the operands overlap
   */
  static boolean overlaps(Interval x, Interval y) {
    if (x.isEmpty() || y.isEmpty()) return false;
    return !endBeforeStart(x, y) && !endBeforeStart(y, x);
  }

  /**
   * Whether an outer interval is a superset of an inner interval.
   *
   * @param outer outer interval
   * @param inner inner interval
   * @return whether `outer` contains every point in `inner`
   */
  static boolean contains(Interval outer, Interval inner) {
    if (outer.isEmpty() || inner.isEmpty()) return false;
    return !startBeforeStart(inner, outer) && !endBeforeEnd(outer, inner);
  }

  /**
   * Whether an outer interval is a strict superset of an inner interval.
   *
   * Unlike {@link IntervalAlgebra#contains}, returns `false` if `inner` and `outer` are equal.
   *
   * @param outer outer interval
   * @param inner inner interval
   * @return whether `outer` contains every point in `inner`, but `inner` doesn't contain every point in `outer`
   */
  static boolean strictlyContains(Interval outer, Interval inner) {
    return contains(outer, inner) && !contains(inner, outer);
  }

  /**
   * Whether the operands are equal.
   *
   * @param x left operand
   * @param y right operand
   * @return whether the operands are equal
   */
  static boolean equals(Interval x, Interval y) {
    return x.equals(y);
  }

  /**
   * Whether an interval starts before another.
   *
   * @param x interval
   * @param y interval
   * @return whether the start point of x is before all points in y
   */
  static boolean startsBefore(Interval x, Interval y) {
    if (x.isEmpty() || y.isEmpty()) return false;
    return startBeforeStart(x, y);
  }

  /**
   * Whether an interval ends after another.
   *
   * @param x interval
   * @param y interval
   * @return whether the end point of x is after all points in y
   */
  static boolean endsAfter(Interval x, Interval y) {
    if (x.isEmpty() || y.isEmpty()) return false;
    return endBeforeEnd(y, x);
  }

  /**
   * Whether an interval starts after or is met by another.
   *
   * @param x interval
   * @param y interval
   * @return whether the start point of x is after or equal to all points in y
   */
  static boolean startsAfter(Interval x, Interval y) {
    return endsBefore(y, x);
  }

  /**
   * Whether an interval ends before or meets another.
   *
   * @param x interval
   * @param y interval
   * @return whether the end point of x is before or equal to all points in y
   */
  static boolean endsBefore(Interval x, Interval y) {
    return endsStrictlyBefore(x, y) || meets(x, y);
  }

  /**
   * Whether an interval starts strictly after another.
   *
   * @param x interval
   * @param y interval
   * @return whether the start point of x is strictly after all points in y
   */
  static boolean startsStrictlyAfter(Interval x, Interval y) {
    return endsStrictlyBefore(y, x);
  }

  /**
   * Whether an interval ends strictly before another.
   *
   * @param x interval
   * @param y interval
   * @return whether the end point of x is strictly before all points in y
   */
  static boolean endsStrictlyBefore(Interval x, Interval y) {
    if (x.isEmpty() || y.isEmpty()) return false;
    return x.end.shorterThan(y.start) ||
           (x.end.equals(y.start) && (!x.includesEnd() && !y.includesStart()));
  }

  /**
   * Whether an interval meets another interval.
   *
   * @param x interval
   * @param y interval
   * @return whether x ends when y begins, with no overlap and no gap
   */
  static boolean meets(Interval x, Interval y) {
    if (x.isEmpty() || y.isEmpty()) return false;
    return x.end.equals(y.start) && (x.endInclusivity != y.startInclusivity);
  }

  /**
   * Whether an interval is met by another interval.
   *
   * @param x interval
   * @param y interval
   * @return whether x begins when y ends, with no overlap and no gap
   */
  static boolean isMetBy(Interval x, Interval y) {
    return meets(y, x);
  }
}
