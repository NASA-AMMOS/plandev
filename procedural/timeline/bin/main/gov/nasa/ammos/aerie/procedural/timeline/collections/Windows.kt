package gov.nasa.ammos.aerie.procedural.timeline.collections

import gov.nasa.ammos.aerie.procedural.timeline.BaseTimeline
import gov.nasa.ammos.aerie.procedural.timeline.BoundsTransformer
import gov.nasa.ammos.aerie.procedural.timeline.Interval
import gov.nasa.ammos.aerie.procedural.timeline.Timeline
import gov.nasa.ammos.aerie.procedural.timeline.ops.NonZeroDurationOps
import gov.nasa.ammos.aerie.procedural.timeline.ops.SerialOps
import gov.nasa.ammos.aerie.procedural.timeline.ops.coalesce.CoalesceIntervalsOp
import gov.nasa.ammos.aerie.procedural.timeline.util.preprocessList
import gov.nasa.ammos.aerie.procedural.timeline.util.sorted
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration

/** A coalescing timeline of [Intervals][Interval] with no extra data. */
data class Windows(private val timeline: Timeline<Interval, Windows>):
    Timeline<Interval, Windows> by timeline,
    SerialOps<Interval, Windows>,
    CoalesceIntervalsOp<Windows>,
    NonZeroDurationOps<Interval, Windows>
{
  constructor(vararg intervals: Interval): this(intervals.asList())
  constructor(intervals: List<Interval>): this(BaseTimeline(::Windows, preprocessList(intervals) { true }))

  /** Calculates the union of this and another [Windows]. */
  infix fun union(other: Windows) = unsafeOperate { opts ->
    val combined = collect(opts) + other.collect(opts)
    combined.sorted()
  }

  /** Calculates the union of this and a single [Interval]. */
  infix fun union(other: Interval) = union(Windows(other))

  /** Calculates the intersection of this and another [Windows]. */
  infix fun intersection(other: Windows) =
      unsafeMap2(::Windows, other) { _, _, i -> i }

  /** Calculates the intersection of this and a single [Interval]. */
  infix fun intersection(other: Interval) = select(other)

  /** Calculates the complement; i.e. highlights everything that is not highlighted in this timeline. */
  fun complement() = unsafeOperate { opts ->
    val result = mutableListOf(opts.bounds)
    for (interval in collect(opts)) {
      result += result.removeLast() - interval
    }
    result
  }

  /** Subtracts the intersection with another [Windows] from this. */
  fun minus(other: Windows) = intersection(other.complement())

  /**
   * Subtracts the intersection with a single [Interval] from this.
   *
   * Essentially a rename of [gov.nasa.ammos.aerie.procedural.timeline.ops.GeneralOps.unset].
   */
  fun minus(other: Interval) = unset(other)

  /**
   * Returns a new [Windows] where each interval is replaced by just the point at its start time.
   *
   * Doesn't care about inclusivity.
   * If an input interval doesn't contain its start point, the output will still be at the same time.
   */
  fun starts() = unsafeMapIntervals(BoundsTransformer.IDENTITY, false) {
    Interval.at(it.start)
  }

  /**
   * Returns a new [Windows] where each interval is replaced by just the point at its end time.
   *
   * Doesn't care about inclusivity.
   * If an input interval doesn't contain its end point, the output will still be at the same time.
   */
  fun ends() = unsafeMapIntervals(BoundsTransformer.IDENTITY, false) {
    Interval.at(it.end)
  }

  /**
   * Independently shift the start and end points of each interval.
   *
   * The start and end can be shifted by different amounts, stretching or squishing the interval.
   * If the interval is empty after the shift, it is removed.
   *
   * Unlike [gov.nasa.ammos.aerie.procedural.timeline.ops.ParallelOps.shiftEndpoints], this function
   * DOES coalesce the output. If you stretch the intervals such that they start to
   * overlap, those overlapping intervals will be combined into one. This means that applying
   * the reverse operation (i.e. `windows.shiftEndpoints(Duration.ZERO, Duration.MINUTE).shiftEndpoints(Duration.ZERO, Duration.MINUTE.negate())`
   * does NOT necessarily result in the same timeline.
   *
   * To turn off coalescing behavior, convert it into a [Universal] timeline first with `.isolate($ -> true)`
   * or `.unsafeCast(Universal::new)`. You can undo this with `.highlight($ -> true)`.
   */
  fun shiftEndpoints(shiftStart: Duration, shiftEnd: Duration = shiftStart) =
    unsafeMapIntervals(
      { i ->
        Interval.between(
          Duration.min(i.start.minus(shiftStart), i.start.minus(shiftEnd)),
          Duration.max(i.end.minus(shiftStart), i.end.minus(shiftEnd)),
          i.startInclusivity,
          i.endInclusivity
        )
      },
      true
    ) { t -> t.interval.shiftBy(shiftStart, shiftEnd) }

  /**
   * Extends the end of each interval by a duration. The duration can be negative.
   *
   * See [shiftEndpoints] for a warning about coalescing behavior.
   */
  fun extend(shiftEnd: Duration) = shiftEndpoints(Duration.ZERO, shiftEnd)
}
