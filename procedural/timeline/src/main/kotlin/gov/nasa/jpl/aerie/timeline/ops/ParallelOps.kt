package gov.nasa.jpl.aerie.timeline.ops

import gov.nasa.jpl.aerie.merlin.protocol.types.Duration
import gov.nasa.jpl.aerie.timeline.*
import gov.nasa.jpl.aerie.timeline.collections.Intervals
import gov.nasa.jpl.aerie.timeline.collections.profiles.Numbers
import gov.nasa.jpl.aerie.timeline.collections.profiles.Booleans
import gov.nasa.jpl.aerie.timeline.ops.coalesce.CoalesceNoOp
import gov.nasa.jpl.aerie.timeline.payloads.Connection
import gov.nasa.jpl.aerie.timeline.payloads.IntervalLike
import gov.nasa.jpl.aerie.timeline.payloads.Segment
import gov.nasa.jpl.aerie.timeline.util.map2SegmentLists
import gov.nasa.jpl.aerie.timeline.util.truncateList
import gov.nasa.jpl.aerie.timeline.util.duration.rangeTo

/**
 * Operations mixin for timelines of potentially overlapping objects.
 *
 * Opposite of [SerialSegmentOps].
 */
interface ParallelOps<T: IntervalLike<T>, THIS: ParallelOps<T, THIS>>: GeneralOps<T, THIS>, CoalesceNoOp<T, THIS> {

  override fun isAlwaysSorted() = false

  /** [(DOC)][merge] Combines two timelines together by overlaying them. Does not perform any transformation. */
  infix fun merge(other: GeneralOps<T, *>) = unsafeOperate { opts ->
    collect(opts) + other.collect(opts)
  }

  /** [(DOC)][merge] Combines this timeline with a single payload object by overlaying them. */
  infix fun merge(i: T) = merge(Intervals(i))

  /**
   * [(DOC)][mapIntoProfile] Maps the objects into a parallel profile.
   *
   * Not currently usable because no parallel profile types are implemented.
   * @suppress
   */
  fun <R: Any, RESULT: ParallelOps<Segment<R>, RESULT>> mapIntoProfile(ctor: (Timeline<Segment<R>, RESULT>) -> RESULT, f: (T) -> R) =
      unsafeMap(ctor, BoundsTransformer.IDENTITY, false) { Segment(it.interval, f(it)) }

  /**
   * [(DOC)][flattenIntoProfile] Converts the payload objects into segments and flattens them into a serial profile.
   *
   * After the payload objects are converted into segments, any overlapping segments are
   * resolved by overwriting the earlier segment with the later segment.
   *
   * @param ctor the constructor of the result timeline
   * @param f a function which converts a payload object into the value of the resulting segment
   */
  fun <R: Any, RESULT: SerialSegmentOps<R, RESULT>> flattenIntoProfile(ctor: (Timeline<Segment<R>, RESULT>) -> RESULT, f: (T) -> R) =
      unsafeOperate(ctor) { bounds ->
        val result = collect(bounds).mapTo(mutableListOf()) { Segment(it.interval, f(it)) }
        result.sortWith { l, r -> l.interval.compareStarts(r.interval) }
        result
      }

  /**
   * [(DOC)][reduceIntoProfile] Converts the payload objects into segments and combines them into a serial profile.
   *
   * After the payload objects are converted into segments, any overlapping segments are resolved by combining them
   * with a binary operation similar to a reduce operation (as in functional programming). It is recommended to
   * create the binary operation using the [NullBinaryOperation.reduce] function.
   *
   * ## Example
   *
   * Say you have a timeline of activity instances, each with an integer argument called `count`,
   * and you want to extract a profile of the `count` arguments - but in the event that the activities overlap,
   * you want to add the counts together. Your code would look like this:
   *
   * ```
   * myInstances.reduceIntoProfile(Numbers::new, BinaryOperation.reduce(
   *    (activity) -> activity.inner.count, // convert
   *    (acc, activity) -> acc + activity.inner.count // combine
   * ))
   * ```
   *
   * If the plan has three relevant activities overlapping (A, B, and C):
   * - first the converter will be called on A, starting the accumulator (`acc`)
   * - then the combiner will be called for `acc` and B, updating the accumulator
   * - then the combiner will be called for `acc` and C, etc
   *
   * If the plan also has other relevant activities the overlap sometime else,
   * they will be combined independently of A, B, and C.
   *
   * @param ctor the constructor of the result profile
   * @param op a binary operation for converting and combining the input objects
   */
  fun <R: Any, RESULT: SerialSegmentOps<R, RESULT>> reduceIntoProfile(ctor: (Timeline<Segment<R>, RESULT>) -> RESULT, op: NullBinaryOperation<T, R, R>) =
      unsafeOperate(ctor) { opts ->
        val bounds = opts.bounds
        var acc: List<Segment<R>> = listOf()
        var remaining = collect(opts)
        while (remaining.isNotEmpty()) {
          var previousTime = bounds.start
          var previousInclusivity = bounds.startInclusivity.opposite()
          val partitioned = remaining.partition { obj ->
            val startComparison = obj.interval.start.compareTo(previousTime)
            if (startComparison > 0 || (startComparison == 0 && previousInclusivity != obj.interval.startInclusivity)) {
              previousTime = obj.interval.end
              previousInclusivity = obj.interval.endInclusivity
              true
            } else {
              false
            }
          }
          val batch = partitioned.first.map { Segment(it.interval, it) }
          remaining = partitioned.second
          acc = map2SegmentLists(batch, acc, op)
        }
        if (!opts.truncateMarginal) truncateList(acc, opts)
        else acc
      }

  /**
   * [(DOC)][shiftEndpoints] Shifts the start and end points of each object.
   *
   * The start and end can be shifted by different amounts, stretching or squishing the interval.
   * If the interval is empty after the shift, it is removed.
   *
   * @param shiftStart duration to shift the starts
   * @param shiftEnd duration to shift the ends; defaults to [shiftStart] if not provided
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

  /** [(DOC)][active] Returns a [Booleans] profile that is true when this timeline has an active object. */
  fun active() = flattenIntoProfile(::Booleans) { _ -> true }.assignGaps(Booleans(false))

  /** [(DOC)][countActive] Returns a [Numbers] profile that corresponds to the number of active objects at any given time. */
  fun countActive() = reduceIntoProfile(::Numbers, NullBinaryOperation.reduce(
      { _, _ -> 1 },
      { _, acc, _ -> acc.toInt() + 1}
  )).assignGaps(Numbers(0))

  /**
   * [(DOC)][accumulatedDuration] Creates a Real profile corresponding to the running total of time
   * that this timeline has had an active object.
   *
   * @param unit base unit of time to count. As in, the resulting real profile will increase by
   *             `1` for each `unit` duration spent in a payload object.
   *
   * @see gov.nasa.jpl.aerie.timeline.ops.numeric.SerialNumericOps.integrate for further explanation of [unit].
   */
  fun accumulatedDuration(unit: Duration) = countActive().integrate(unit)

  /**
   * [(DOC)][starts] Truncates each object to only its start time.
   *
   * The new interval is just the start time, whether the original interval
   * included the start or not. Each object's content is unchanged.
   */
  fun starts() = unsafeOperate { opts ->
    val result = collect(CollectOptions(opts.bounds, false))
        .map { it.withNewInterval(Interval.at(it.interval.start)) }
    truncateList(result, opts)
  }

  /**
   * [(DOC)][ends] Truncates each object to only its end time.
   *
   * The new interval is just the end time, whether the original interval
   * included the end or not. Each object's content is unchanged.
   */
  fun ends() = unsafeOperate { opts ->
    val result = collect(CollectOptions(opts.bounds, false))
        .map { it.withNewInterval(Interval.at(it.interval.end)) }
    truncateList(result, opts)
  }

  /**
   * [(DOC)][connectTo] Creates an [Intervals] object of [Connections][Connection] that associate of this timeline's
   * object to the (chronologically) next object in another timeline that starts after this one ends.
   *
   * For a connection from an object A (in this timeline) to an object B (in the other timeline), the interval of the
   * connection is [A.end, B.start]. The original intervals for A and B can be accessed from within the [Connection] object.
   *
   * This operation is not symmetric. All* objects in this timeline will be included in exactly one connection, but not
   * all objects in the other timeline will be included, if there are no objects in this timeline that would connect to it.
   * Similarly, objects in the other timeline can be connected to multiple times.
   *
   * If the other timeline ends prematurely and there are still more objects in this timeline, you can optionally connect
   * to the end of the bounds, in which case [Connection.from] will be `null`. Otherwise, no connection will be generated.
   *
   * @param other the other timeline to connect to
   * @param connectToBounds whether to connect to the end of the bounds if the other timeline ends prematurely
   */
  fun <U: IntervalLike<U>> connectTo(other: ParallelOps<U, *>, connectToBounds: Boolean) =
      unsafeOperate(::Intervals) { opts ->
        val sortedFrom = collect(opts).sortedWith { l, r -> l.interval.compareEnds(r.interval) }
        val sortedTo = other.collect(opts).sortedWith { l, r -> l.interval.compareStarts(r.interval) }
        val result = mutableListOf<Connection<T, U>>()
        var toIndex = 0
        for (from in sortedFrom) {
          val startTime = from.interval.end
          while (toIndex < sortedTo.size && from.interval.compareEndToStart(sortedTo[toIndex].interval) == 1) {
            toIndex++
          }
          if (!connectToBounds && toIndex == sortedTo.size) break
          val endTime: Duration
          val endInclusivity: Interval.Inclusivity
          if (toIndex != sortedTo.size) {
            val to = sortedTo[toIndex]
            endTime = to.interval.start
            result.add(Connection(
                startTime .. endTime,
                from, to
            ))
          } else {
            endTime = opts.bounds.end
            endInclusivity = opts.bounds.endInclusivity
            result.add(Connection(
                Interval.between(startTime, endTime, Interval.Inclusivity.Inclusive, endInclusivity),
                from, null
            ))
          }
        }
        result
      }

  /**
   * [(DOC)][rollingDuration] Calculates the sum of durations of objects in a range leading the current time.
   *
   * This returns a real profile that equals, at each time `t`, the duration of objects in the interval `[t, t+range]`.
   *
   * Real profiles can't actually represent durations, only unitless numbers, so the result is actually calculated
   * as a multiple of the provided [unit].
   *
   * @param range how far into the future to look
   * @param unit the time basis vector of the result; the unit of time that the result counts.
   */
  fun rollingDuration(range: Duration, unit: Duration) =
      accumulatedDuration(unit).shiftedDifference(range)

}
