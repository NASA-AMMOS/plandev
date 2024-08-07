package gov.nasa.ammos.aerie.procedural.timeline

import gov.nasa.ammos.aerie.procedural.timeline.payloads.IntervalLike

/**
 * Interface of the raw timeline object.
 *
 * Should be implemented by composing a raw timeline in your container
 * and delegating to it with the `by` keyword. See any timeline for examples.
 */
interface Timeline<V: IntervalLike<V>, THIS: Timeline<V, THIS>> {
  /**
   * [(DOC)][collect] Evaluates the stack of operations and produces a list of timeline payload objects.
   *
   * The resulting list may have some property invariants depending on what type of timeline was
   * evaluated. For example, calling `collect` on a profile type will result in an ordered, non-overlapping,
   * coalesced list of segments. The only invariant that all timeline types share is that the list objects
   * will be within the provided bounds.
   */
  fun collect(opts: CollectOptions): List<V>

  /**
   * [(DOC)][collect] A simplified version of [collect].
   *
   * Uses defaults for all other [CollectOptions] fields.
   *
   * @param bounds bounds of evaluation (defaults to [Interval.MIN_MAX] if not provided).
   */
  fun collect(bounds: Interval) = collect(CollectOptions(bounds))

  /** [(DOC)][collect] Collects the timeline for all available time. */
  fun collect() = collect(Interval.MIN_MAX)

  /**
   * [(DOC)][unsafeCast] **UNSAFE!** Casts this timeline type to another type without changing its contents.
   *
   * The payload type [V] must be equal between the two types.
   *
   * This operation allows you to break the invariants of more specialized timeline types. For example, casting
   * [`Intervals<Segment<Boolean>>`][gov.nasa.ammos.aerie.procedural.timeline.collections.Intervals] to [Booleans][gov.nasa.ammos.aerie.procedural.timeline.collections.profiles.Booleans]
   * without sorting and coalescing the segments could result in an invalid profile. In cases like that, it is better to
   * use [flattenIntoProfile][gov.nasa.ammos.aerie.procedural.timeline.ops.ParallelOps.flattenIntoProfile] or [reduceIntoProfile][gov.nasa.ammos.aerie.procedural.timeline.ops.ParallelOps.reduceIntoProfile].
   */
  fun <RESULT: Timeline<V, RESULT>> unsafeCast(ctor: (Timeline<V, RESULT>) -> RESULT): RESULT

  /**
   * This timeline's constructor.
   *
   * Used for operations that produce another timeline of the same type,
   * and therefore don't need the user to provide a constructor manually.
   *
   * It is highly unlikely that users will ever need to access this property,
   * because the [gov.nasa.ammos.aerie.procedural.timeline.ops.GeneralOps.unsafeOperate] function accesses this for them.
   *
   * @suppress
   */
  val ctor: (Timeline<V, THIS>) -> THIS

  /** @suppress */
  fun specialize() = ctor(this)

  /**
   * Caches the result of collecting this timeline, to be reused for future collect requests if possible.
   */
  fun cache(opts: CollectOptions)

  /**
   * [(DOC)][cache] A simplified version of [cache].
   *
   * Uses defaults for all other [CollectOptions] fields.
   *
   * @param bounds bounds of evaluation (defaults to [Interval.MIN_MAX] if not provided).
   */
  fun cache(bounds: Interval) = cache(CollectOptions(bounds))

  /** [(DOC)][collect] Caches the timeline for all available time. */
  fun cache() = cache(Interval.MIN_MAX)
}
