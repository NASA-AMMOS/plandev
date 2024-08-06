package gov.nasa.ammos.aerie.procedural.timeline

import gov.nasa.jpl.aerie.merlin.protocol.types.Duration
import gov.nasa.ammos.aerie.procedural.timeline.util.duration.rangeTo
import gov.nasa.ammos.aerie.procedural.timeline.payloads.IntervalLike

/**
 * An Interval on the timeline, represented by start and end points
 * and start and end inclusivity.
 */
data class Interval @JvmOverloads constructor(
    /***/ @JvmField val start: Duration,
    /***/ @JvmField val end: Duration,
    /** Whether this interval contains its start time. */
    @JvmField val startInclusivity: Inclusivity = Inclusivity.Inclusive,
    /** Whether this interval contains its end time. */
    @JvmField val endInclusivity: Inclusivity = startInclusivity
): IntervalLike<Interval> {

  /**
   * Labels to indicate whether an interval includes its endpoints.
   */
  enum class Inclusivity {
    /***/ Inclusive,
    /***/ Exclusive;

    /** Returns the opposite inclusivity. */
    fun opposite(): Inclusivity = if ((this == Inclusive)) Exclusive else Inclusive
    /** Returns true if this is `Exclusive` and the argument is `Inclusive`; otherwise false. */
    fun moreRestrictiveThan(other: Inclusivity): Boolean = this == Exclusive && other == Inclusive
  }

  /**
   * Needed to satisfy the IntervalLike interface. No need to use in a non-polymorphic context.
   *
   * @suppress
   */
  override val interval
    get() = this

  /**
   * Needed to satisfy the IntervalLike interface. No need to use in a non-polymorphic context.
   *
   * @suppress
   */
  override fun withNewInterval(i: Interval) = i

  /** Whether this interval includes its start point. */
  fun includesStart() = startInclusivity == Inclusivity.Inclusive
  /** Whether this interval includes its end point. */
  fun includesEnd() = endInclusivity == Inclusivity.Inclusive

  /**
   * Whether this interval contains any points.
   *
   * An interval can be empty if it ends before it starts (negative duration),
   * or if it has zero duration and excludes both of its endpoints.
   */
  fun isEmpty() =
      if (end < start) true
      else if (end > start) false
      else !(includesStart() && includesEnd())

  /**
   * Whether this interval contains only a single point.
   *
   * Use this instead of `.duration.isZero()` to avoid overflow on long intervals.
   */
  fun isPoint() = (includesStart() && includesEnd() && (start === end))

  /**
   * Shifts the start and end points equally forward or backward in time.
   *
   * @param shiftStart Duration to shift the start by. Negative means backward in time.
   * @param shiftEnd Duration to shift the end by. Defaults to [shiftStart]
   */
  @JvmOverloads fun shiftBy(shiftStart: Duration, shiftEnd: Duration = shiftStart) = between(
      start.saturatingPlus(shiftStart),
      end.saturatingPlus(shiftEnd),
      startInclusivity,
      endInclusivity
  )

  /** Length of the interval, represented as a Duration object. */
  fun duration(): Duration = if(isEmpty()) Duration.ZERO else end - start

  /** Whether this interval happens after another interval with no overlap. */
  infix fun isStrictlyAfter(x: Interval) = compareStartToEnd(x) > 0
  /** Whether this interval happens before another interval with no overlap. */
  infix fun isStrictlyBefore(x: Interval) = compareEndToStart(x) < 0

  /**
   * Compare the start times of this and another interval.
   *
   * @param other the interval to compare to
   * @return -1 if this interval starts first, 1 if the other interval starts first, or 0 if the two intervals start at the same time.
   *
   * If the start inclusivities are not equal, the inclusive start is considered to be first.
   *
   * Assumes neither interval is empty.
   */
  fun compareStarts(other: Interval): Int {
    val timeComparison: Int = start.compareTo(other.start)
    return if (timeComparison != 0) timeComparison
      else if (startInclusivity == other.startInclusivity) 0
      else if (startInclusivity == Inclusivity.Inclusive) -1
      else 1
  }

  /**
   * Compare the end times of this and another interval.
   *
   * @param other the interval to compare to
   * @return -1 if this interval ends first, 1 if the other interval ends first, or 0 if the two intervals end at the same time.
   *
   * If the end inclusivities are not equal, the exclusive end is considered to be first.
   *
   * Assumes neither interval is empty.
   */
  fun compareEnds(other: Interval): Int {
    val timeComparison: Int = end.compareTo(other.end)
    return if (timeComparison != 0) timeComparison
      else if (endInclusivity == other.endInclusivity) 0
      else if (endInclusivity == Inclusivity.Inclusive) 1
      else -1
  }

  /** Opposite of [compareEndToStart]. */
  fun compareStartToEnd(other: Interval) = -other.compareEndToStart(this)

  /**
   * Compares the end of this interval to the start of another interval.
   *
   * Returns -1 if this ends before the other starts.
   * Returns 1 if this ends after (see below) the other starts.
   * Returns 0 if this exactly meets (see below) the other with no overlap
   *
   * To clarify, `compareEndToStart([a, b), [b, c)) == 0`,
   * but `compareEndToStart([a, b], [b, c]) == 1`. This might be unintuitive,
   * but I've found this to be much more useful in practice than `-1` and `0`, respectively.
   * This is because as long as this interval starts before the argument, we get a few properties:
   * - -1 indicates a gap between them
   * - 1 indicates overlap between them
   * - 0 indicates no gap and no overlap
   *
   * Assumes neither interval is empty.
   */
  fun compareEndToStart(other: Interval): Int {
    val timeComparison: Int = this.end.compareTo(other.start)
    return if (timeComparison != 0) timeComparison
      else if (this.endInclusivity != other.startInclusivity) 0
      else if (this.endInclusivity == Inclusivity.Inclusive) 1
      else -1
  }

  /**
   * Whether this and another interval start at the same time, accounting for inclusivity.
   *
   * Assumes neither interval is empty.
   */
  infix fun hasSameStartAs(other: Interval) = compareStarts(other) == 0
  /**
   * Whether this and another interval end at the same time, accounting for inclusivity.
   *
   * Assumes neither interval is empty.
   */
  infix fun hasSameEndAs(other: Interval) = compareEnds(other) == 0

  /**
   * Whether this interval precedes another and touches it without overlap.
   *
   * Assumes neither interval is empty.
   */
  infix fun meets(other: Interval) = (this.end == other.start) && (this.endInclusivity != other.startInclusivity)
  /**
   * Whether this interval comes after another and touches it without overlap.
   *
   * Assumes neither interval is empty.
   */
  infix fun metBy(other: Interval) = other meets this

  /** Whether this interval [meets] or is [metBy] another. */
  infix fun adjacentTo(x: Interval) = metBy(x) || meets(x)

  /** Whether a given time is contained in this interval. */
  operator fun contains(d: Duration) = !intersection(at(d)).isEmpty()
  /** Whether this interval contains the entirety of another. */
  operator fun contains(x: Interval) = intersection(x) == x

  /**
   * Calculates the intersection between this interval and another.
   *
   * If either interval is empty, or there is no overlap, the result will be
   * an empty interval. The exact endpoints of the empty interval are not meaningful,
   * only the fact that it is empty.
   */
  infix fun intersection(other: Interval): Interval {
    if (this.isEmpty() || other.isEmpty()) return EMPTY

    val start: Duration
    val startInclusivity: Inclusivity
    if (compareStarts(other) > 0) {
      start = this.start
      startInclusivity = this.startInclusivity
    } else {
      start = other.start
      startInclusivity = other.startInclusivity
    }
    val end: Duration
    val endInclusivity: Inclusivity
    if (compareEnds(other) < 0) {
      end = this.end
      endInclusivity = this.endInclusivity
    } else {
      end = other.end
      endInclusivity = other.endInclusivity
    }
    return between(start, end, startInclusivity, endInclusivity)
  }

  /**
   * Calculates the union between this interval and another, as a list of intervals.
   *
   * The union of two intervals is not necessarily an interval, if they do not overlap. In this case
   * the two intervals are returned in the list separately.
   * If they do overlap, the list will contain a single element which is the union interval.
   *
   * If either interval is empty, it will not be included in the result.
   */
  infix fun union(other: Interval): List<Interval> {
    if (intersection(other).isEmpty() && !adjacentTo(other)) return listOf(this, other).filterNot { it.isEmpty() }

    val start: Duration
    val startInclusivity: Inclusivity
    val end: Duration
    val endInclusivity: Inclusivity

    if (compareStarts(other) < 0) {
      start = this.start
      startInclusivity = this.startInclusivity
    } else {
      start = other.start
      startInclusivity = other.startInclusivity
    }
    if (compareEnds(other) > 0) {
      end = this.end
      endInclusivity = this.endInclusivity
    } else {
      end = other.end
      endInclusivity = other.endInclusivity
    }
    return listOf(between(start, end, startInclusivity, endInclusivity))
  }

  /** The smallest interval that contains both this and another interval. */
  infix fun hull(other: Interval): Interval {
    val union = union(other)
    return if (union.isEmpty()) EMPTY
    else if (union.size == 1) union[0]
    else {
      val sorted = union.sortedWith(Interval::compareStarts)
      between(sorted[0].start, sorted[1].end, sorted[0].startInclusivity, sorted[1].endInclusivity)
    }
  }

  /**
   * Removes all points in the argument interval from this interval. This is essentially the opposite of union.
   *
   * If the two intervals intersect, the result will be this interval with the intersection removed.
   * If the removal splits this interval into two pieces, they are returned as separate elements of a list.
   *
   * @return a list of intervals containing all points in this interval which are not contained in the argument.
   */
  operator fun minus(other: Interval): List<Interval> {
    if (isEmpty()) return listOf()
    if (intersection(other).isEmpty()) return listOf(this)

    val left = between(start, other.start, startInclusivity, other.startInclusivity.opposite())
    val right = between(other.end, end, other.endInclusivity.opposite(), endInclusivity)
    return listOf(left, right).filterNot { it.isEmpty() }
  }

  /***/
  override fun toString(): String {
    return if (isEmpty()) {
      "(empty)"
    } else {
      "${if (includesStart()) "[" else "("}$start, $end${if (includesEnd()) "]" else ")"}"
    }
  }

  /** Helper functions for constructing Intervals. */
  companion object {
    /**
     * Constructs an interval between two durations.
     *
     * @param start The starting time of the interval.
     * @param end The ending time of the interval.
     * @return A non-empty interval if start < end, or an empty interval otherwise.
     */
    @JvmOverloads @JvmStatic fun between(
        start: Duration,
        end: Duration,
        startInclusivity: Inclusivity = Inclusivity.Inclusive,
        endInclusivity: Inclusivity = startInclusivity
    ) = Interval(start, end, startInclusivity, endInclusivity)

    /**
     * Constructs an interval between two durations that includes its start and excludes its end.
     *
     * @param start The starting time of the interval.
     * @param end The ending time of the interval.
     */
    @JvmStatic fun betweenClosedOpen(start: Duration, end: Duration) = between(start, end, Inclusivity.Inclusive, Inclusivity.Exclusive)

    /** Constructs an interval containing a single time point, represented as a Duration object. */
    @JvmStatic fun at(point: Duration) = point .. point

    /** Shorthand for an empty interval. */
    @JvmField val EMPTY: Interval = Duration.ZERO .. (Duration.ZERO - Duration.EPSILON)

    /** The widest representable interval (from long min to long max microseconds). */
    @JvmField val MIN_MAX = Duration.MIN_VALUE .. Duration.MAX_VALUE
  }
}
