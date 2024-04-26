package gov.nasa.jpl.aerie.timeline.ops

import gov.nasa.jpl.aerie.merlin.protocol.types.Duration
import gov.nasa.jpl.aerie.timeline.*
import gov.nasa.jpl.aerie.timeline.collections.Intervals
import gov.nasa.jpl.aerie.timeline.collections.Windows
import gov.nasa.jpl.aerie.timeline.payloads.IntervalLike
import gov.nasa.jpl.aerie.timeline.payloads.Segment
import gov.nasa.jpl.aerie.timeline.util.coalesceList
import gov.nasa.jpl.aerie.timeline.util.map2ParallelLists
import gov.nasa.jpl.aerie.timeline.util.sorted
import gov.nasa.jpl.aerie.timeline.util.truncateList
import java.util.function.Consumer

/**
 * General operations mixin for all timeline types.
 *
 * @property V collection payload type
 * @property THIS collection type
 */
interface GeneralOps<V: IntervalLike<V>, THIS: GeneralOps<V, THIS>>: Timeline<V, THIS> {
  /** [(DOC)][unsafeOperate] **UNSAFE!** A simpler version of [unsafeOperate] for operations that don't change the timeline type. */
  fun unsafeOperate(f: Timeline<V, THIS>.(opts: CollectOptions) -> List<V>) = unsafeOperate(ctor, f)

  /**
   * [(DOC)][unsafeOperate] **UNSAFE!** The basic, most general operation method. All operations eventually delegate here.
   *
   * This doesn't actually execute the operation, it just creates a [BaseTimeline] object that holds
   * the operation for lazy evaluation later, when [collect] is called. It then wraps the base timeline in
   * the provided constructor (called specialization) and coalesces the timeline if applicable.
   *
   * ## Safety
   *
   * This function is unsafe because all timeline types have mathematical invariants, such as ordered segments
   * for profiles, and this method allows those invariants to be broken if the user is not careful.
   * In particular, all timelines assume the result of [collect] will be contained in the `bounds` argument.
   * These invariants are often maintained automatically, but you should never assume. Violating them is UB.
   * Use at your own risk.
   *
   * @param ctor the constructor of the new timeline type
   * @param f a function which, given this and a [CollectOptions] object, produces a list of payload objects
   *          contained in the bounds. In Java, this is a two-argument function which takes a timeline object
   *          and [CollectOptions]. In Kotlin, this is a one-argument function of the options with a timeline receiver.
   *          (see [the kotlin docs](https://kotlinlang.org/docs/lambdas.html#instantiating-a-function-type))
   */
  fun <R: IntervalLike<R>, RESULT: GeneralOps<R, RESULT>> unsafeOperate(ctor: (Timeline<R, RESULT>) -> RESULT, f: Timeline<V, THIS>.(opts: CollectOptions) -> List<R>): RESULT {
    val result = BaseTimeline(ctor) { f(it) }.specialize()
    return result.shouldCoalesce()?.let {
      BaseTimeline(ctor) { opts ->
        coalesceList(result.collect(opts), it)
      }.specialize()
    } ?: result
  }

  /**
   * [(DOC)][convert] Safely converts to another timeline type that accepts the same payload type.
   *
   * Coalesces if necessary.
   */
  fun <RESULT: GeneralOps<V, RESULT>> convert(ctor: (Timeline<V, RESULT>) -> RESULT): RESULT {
    val result = unsafeCast(ctor)
    return result.shouldCoalesce()?.let {
      BaseTimeline(ctor) { opts ->
        val sorted = result.collect(opts).sorted()
        coalesceList(sorted, it)
      }.specialize()
    } ?: result
  }

  /**
   * [(DOC)][inspect] Inserts a no-op function into the operation stack to allow side effects,
   * such as printing.
   *
   * @param f a function that receives a list of timeline objects and does nothing to them
   */
  fun inspect(f: Consumer<List<V>>) = BaseTimeline(ctor) {
    val list = collect(it)
    f.accept(list)
    list
  }.specialize()

  /**
   * Produces a function that decides if two timeline objects should be coalesced together when they have overlap.
   *
   * All timeline collections are required to add either [gov.nasa.jpl.aerie.timeline.ops.coalesce.CoalesceNoOp] or
   * [gov.nasa.jpl.aerie.timeline.ops.coalesce.CoalesceSegmentsOp] to satisfy this requirement.
   *
   * The user should never need to call this directly, as it is already called by [unsafeOperate].
   *
   * @suppress
   */
  fun shouldCoalesce(): (V.(V) -> Boolean)?

  /**
   * Whether the result of [collect] is guaranteed to be a sorted list.
   *
   * @suppress
   */
  fun isAlwaysSorted(): Boolean

  /**
   * [(DOC)][unset] Unsets everything in a given interval. Timeline objects whose intervals fully contain the rejected interval may be
   * split into two objects.
   *
   * Unfortunately this can't be used for any performance gain; instead of evaluating the timeline twice, once on either
   * side of the rejected interval (and combining the results), it has to evaluate the timeline on the entire original bounds
   * and cut out anything in the rejected interval. This is because using unset in combination with any operation that
   * depends on interval duration may yield incorrect results for objects that border the rejected interval.
   *
   * @param reject the interval on which to delete or truncate any objects
   */
  fun unset(reject: Interval) = unsafeOperate { opts ->
    if (opts.bounds.intersection(reject).isEmpty()) return@unsafeOperate collect(opts)
    collect(opts).flatMap {
      it.interval.minus(reject).map { i -> it.withNewInterval(i) }
    }
  }

  /** [(DOC)][select] Restricts the timeline to only be evaluated in the given interval. */
  fun select(interval: Interval) = unsafeOperate { opts ->
    collect(CollectOptions(opts.bounds.intersection(interval), opts.truncateMarginal))
  }

  /**
   * [(DOC)][isolate] Similar to [filter], but returns an [Intervals] timeline.
   *
   * @param f a predicate which decides if a given payload object's interval should be included in the result.
   */
  fun isolate(f: (V) -> Boolean) = filter(false, f).unsafeCast(::Intervals)

  /**
   * [(DOC)][highlight] Similar to [filter], but produces a coalesced [Windows] object
   * that highlights everything that satisfies the predicate.
   *
   * @param f a predicate that decides if a given payload object's interval should be included in the result.
   */
  fun highlight(f: (V) -> Boolean) =
      unsafeMap(::Intervals, BoundsTransformer.IDENTITY, false) {
        if (f(it)) it.interval
        else Interval.EMPTY
      }.convert(::Windows)

  /** [(DOC)][unsafeMap] **UNSAFE!** A simpler version of [unsafeMap] for operations that don't change the timeline type. */
  fun unsafeMap(boundsTransformer: BoundsTransformer, truncate: Boolean, f: (V) -> V) = unsafeMap(ctor, boundsTransformer, truncate, f)

  /**
   * [(DOC)][unsafeMap] **UNSAFE!** Maps each timeline object to another object, of potentially a different type, at potentially a different
   * time.
   *
   * This operation may require the bounds of evaluation to be changed for the timeline it is called on.
   * This can happen if the intervals of any segment are being shifted. This is what the [boundsTransformer]
   * argument is for. This will ensure that the timeline map is called on will evaluate on the proper bounds.
   *
   * In rare cases, operations might intentionally shift or create intervals outside the requested bounds,
   * requiring them to be truncated. Pass `true` for [truncate] if so (this will add an extra step to truncate the result).
   *
   * @see [unsafeOperate] for an explanation of why this method is unsafe.
   *
   * @param R the result payload type
   * @param RESULT the result timeline type
   *
   * @param ctor the constructor of the result timeline
   * @param boundsTransformer how to transform the bounds for the timeline map is called on
   * @param truncate whether to truncate the result before returning
   * @param f a mapper function that converts each timeline object to another object
   */
  fun <R: IntervalLike<R>, RESULT: GeneralOps<R, RESULT>> unsafeMap(ctor: (Timeline<R, RESULT>) -> RESULT, boundsTransformer: BoundsTransformer, truncate: Boolean, f: (V) -> R) =
      unsafeOperate(ctor) { opts ->
        val mapped = collect(opts.transformBounds(boundsTransformer)).map { f(it) }
        if (truncate) truncateList(mapped, opts)
        else mapped
      }

  /**
   * [(DOC)][unsafeFlatMap] **UNSAFE!** Maps each object to a nested timeline and flattens all the timelines into one.
   *
   * Very similar to [unsafeMap], except that the result of the mapper function is a [Segment] containing a timeline.
   * After each object is mapped, each nested timeline will be collected on its segment's interval, and flattened
   * together into a single timeline.
   *
   * @see [unsafeMap] for explanation of [boundsTransformer] and [truncate]
   * @see [unsafeOperate] for an explanation of why this method is unsafe.
   *
   * @param R the result payload type
   * @param RESULT the result timeline type
   *
   * @param ctor the constructor of the result timeline
   * @param boundsTransformer how to transform the bounds for the timeline map is called on
   * @param truncate whether to truncate the result before returning
   * @param f a mapper function that converts each timeline object to a segment of a nested timeline
   */
  fun <R: IntervalLike<R>, RESULT: GeneralOps<R, RESULT>> unsafeFlatMap(ctor: (Timeline<R, RESULT>) -> RESULT, boundsTransformer: BoundsTransformer, truncate: Boolean, f: (V) -> Segment<GeneralOps<R, *>>) =
      unsafeOperate(ctor) { opts ->
        val mapped = collect(opts.transformBounds(boundsTransformer)).flatMap {
          val nested = f(it)
          nested.value.collect(nested.interval)
        }
        if (truncate) truncateList(mapped, opts)
        else mapped
      }

  /**
   * [(DOC)][unsafeMapIntervals] **UNSAFE!** Maps the interval of each object, leaving the rest of the object unchanged.
   *
   * @see [unsafeMap] for explanation of [boundsTransformer] and [truncate]
   * @see [unsafeOperate] for an explanation of why this method is unsafe.
   *
   * @param boundsTransformer how to transform the bounds for the timeline map is called on
   * @param truncate whether to truncate the result before returning
   * @param f a mapper function that converts each timeline object to an interval to be used as the new interval for that object
   */
  fun unsafeMapIntervals(boundsTransformer: BoundsTransformer, truncate: Boolean, f: (V) -> Interval) = unsafeMap(boundsTransformer, truncate) { v -> v.withNewInterval(f(v)) }

  /**
   * Performs a generalized binary operation between this and another timeline.
   */
  fun <W: IntervalLike<W>, R: IntervalLike<R>, RESULT: GeneralOps<R, RESULT>> unsafeMap2(ctor: (Timeline<R, RESULT>) -> RESULT, other: GeneralOps<W, *>, op: (V, W, Interval) -> R?) =
      unsafeOperate(ctor) { opts ->
        map2ParallelLists(collect(opts), other.collect(opts), isAlwaysSorted(), other.isAlwaysSorted(), op)
      }

  /**
   * [(DOC)][filter] Removes or retains objects based on a predicate.
   *
   * @param f a function which returns `true` if the object is to be retained, or `false` if the object is to be removed.
   * @param preserveMargin whether the predicate needs the full intervals for objects that extend beyond the bounds
   */
  fun filter(preserveMargin: Boolean = false, f: (V) -> Boolean) = unsafeOperate { opts ->
    val result = collect(CollectOptions(opts.bounds, !preserveMargin && opts.truncateMarginal)).filter(f)
    if (preserveMargin && opts.truncateMarginal) truncateList(result, opts)
    else result
  }

  /**
   * [(DOC)][filterByDuration] Removes objects whose duration is outside a given valid interval.
   *
   * Note that while intervals are often used to represent a range of time instants, they are
   * actually defined by relative durations from a zero point which is unknown by the interval
   * (typically the plan start time). This means that intervals can also be used to represent
   * a range of durations too.
   *
   * Objects that extend beyond the evaluation bounds are still considered with their full extent, even
   * if [CollectOptions.truncateMarginal] is `true`. In that case, the margin is truncated after it has
   * passed the filter.
   *
   * @param validInterval objects with durations outside this interval will be removed
   */
  fun filterByDuration(validInterval: Interval) = filter(true) { validInterval.contains(it.interval.duration()) }

  /** [(DOC)][filterShorterThan] Removes objects whose duration is shorter than a given duration. */
  fun filterShorterThan(dur: Duration) = filter(true) { it.interval.duration() >= dur }
  /** [(DOC)][filterLongerThan] Removes objects whose duration is longer than a given duration. */
  fun filterLongerThan(dur: Duration) = filter(true) { it.interval.duration() <= dur }

  /**
   * [(DOC)][filterByWindows] Filters out payload objects whose intervals are not contained in the
   * given Windows timeline.
   *
   * @param truncateMarginal whether objects with only partial overlap with an interval in the windows timeline
   *                         should be truncated to the intersection or included unchanged.
   */
  fun filterByWindows(windows: Windows, truncateMarginal: Boolean = true) =
      if (truncateMarginal) {
        unsafeMap2(ctor, windows) { l, _, i -> l.withNewInterval(i) }
      } else {
        unsafeMap2(::Intervals, windows) { l, _, _ -> l }.unsafeOperate { collect(it).distinct() }.unsafeCast(ctor)
      }

  /** [(DOC)][shift] Uniformly shifts the entire timeline in time (positive shifts toward the future). */
  fun shift(dur: Duration) = unsafeMapIntervals(BoundsTransformer.shift(dur), false) { it.interval.shiftBy(dur) }
}
