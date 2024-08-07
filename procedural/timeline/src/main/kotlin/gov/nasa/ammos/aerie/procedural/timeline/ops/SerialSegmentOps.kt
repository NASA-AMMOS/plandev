package gov.nasa.ammos.aerie.procedural.timeline.ops

import gov.nasa.ammos.aerie.procedural.timeline.*
import gov.nasa.ammos.aerie.procedural.timeline.Interval.Companion.at
import gov.nasa.ammos.aerie.procedural.timeline.collections.profiles.Booleans
import gov.nasa.ammos.aerie.procedural.timeline.collections.profiles.Constants
import gov.nasa.ammos.aerie.procedural.timeline.ops.coalesce.CoalesceSegmentsOp
import gov.nasa.ammos.aerie.procedural.timeline.payloads.Segment
import gov.nasa.ammos.aerie.procedural.timeline.payloads.transpose
import gov.nasa.ammos.aerie.procedural.timeline.util.coalesceList
import gov.nasa.ammos.aerie.procedural.timeline.util.map2SegmentLists
import gov.nasa.ammos.aerie.procedural.timeline.util.truncateList

/**
 * Operations mixin for timelines of ordered, non-overlapping segments (profiles).
 *
 * Opposite of [ParallelOps].
 */
interface SerialSegmentOps<V : Any, THIS: SerialSegmentOps<V, THIS>>: SerialOps<Segment<V>, THIS>, SegmentOps<V, THIS>, CoalesceSegmentsOp<V, THIS> {
  /** Overlays two profiles on each other, asserting that they both cannot be defined at the same time. */
  infix fun zip(other: SerialSegmentOps<V, *>) = map2OptionalValues(other, NullBinaryOperation.zip())

  /** [(DOC)][assignGaps] Fills in gaps in this profile with another profile. */
  // While this is logically the converse of [set], they can't delegate to each other because it would mess up the return type.
  infix fun assignGaps(other: SerialSegmentOps<V, *>) =
      map2OptionalValues(other, NullBinaryOperation.combineOrIdentity { l, _, _, -> l })
  /** [(DOC)][assignGaps] Fills in gaps in this profile with a constant value. */
  infix fun assignGaps(v: V) = assignGaps(Constants(v))

  /** [(DOC)][set] Overwrites this profile with another. Gaps in the argument profile will be filled in with this profile. */
  infix fun set(other: SerialSegmentOps<V, *>) = map2OptionalValues(other, NullBinaryOperation.combineOrIdentity { _, r, _ -> r })

  /**
   * [(DOC)][map2OptionalValues] Performs a local binary operation between two profiles where the result
   * is the same type as this profile.
   */
  fun <W: Any> map2OptionalValues(other: SerialSegmentOps<W, *>, op: NullBinaryOperation<V, W, V?>) = map2OptionalValues(ctor, other, op)

  /**
   * [(DOC)][map2OptionalValues] Performs a local binary operation between two profiles, with special treatment
   * of gaps.
   *
   * The operation will be evaluated on each pair of segments that overlap, with their
   * intersection supplied as the interval argument to the [NullBinaryOperation]. The result of
   * the operation is inserted in the result timeline at that intersection. Additionally,
   * The operation will be evaluated on each segment in the profiles that overlaps with
   * a gap in the other profile, and the gap will be indicated by a `null` in that operand's
   * argument in [NullBinaryOperation]. The operation is not called for intervals that have a gap
   * in both profiles - the result will automatically have a gap there.
   *
   * The binary operation may return `null`, which indicates that the result profile should have
   * a gap.
   *
   * The operation is "local", meaning that while the operation is allowed to know when it is
   * being evaluated, it is not allowed to change where the result segment should be placed.
   * For that, try to shift the results in a separate operation.
   *
   * @param W the other operand's payload type
   * @param R the result's payload type
   * @param RESULT the result's timeline type
   *
   * @param ctor the result timeline's constructor
   * @param other the other operand timeline
   * @param op a binary operation between the two payload types that produces a maybe-null result
   *
   * @return a coalesced profile; an instance of the return type of [ctor]
   */
  fun <W: Any, R: Any, RESULT: GeneralOps<Segment<R>, RESULT>> map2OptionalValues(ctor: (Timeline<Segment<R>, RESULT>) -> RESULT, other: SerialSegmentOps<W, *>, op: NullBinaryOperation<V, W, R?>) =
      unsafeOperate(ctor) { bounds -> map2SegmentLists(collect(bounds), other.collect(bounds), op) }

  /**
   * [(DOC)][flatMap2OptionalValues] Performs a local binary operation that produces profiles, and flattens
   * it into a profile of the same type as this.
   */
  fun <W: Any> flatMap2OptionalValues(other: SerialSegmentOps<W, *>, op: NullBinaryOperation<V, W, SerialSegmentOps<V, *>?>) = flatMap2OptionalValues(ctor, other, op)

  /**
   * [(DOC)][flatMap2OptionalValues] Performs a local binary operation that produces profiles, and flattens it, with
   * special treatment of gaps.
   *
   * Similar to [map2OptionalValues], except it expects the [NullBinaryOperation] to return a profile. Each nested profile
   * is then collected on the interval it corresponds to, and the results are concatenated into a single profile.
   *
   * This is useful for binary operations where at least one of the operand segments represents a value that
   * varies within the segment, such as [gov.nasa.ammos.aerie.procedural.timeline.collections.profiles.Real].
   *
   * @param W the other operand's payload type
   * @param R the result's payload type
   * @param RESULT the result's timeline type
   *
   * @param ctor the result timeline's constructor
   * @param other the other operand timeline
   * @param op a binary operation between the two payload types that produces a maybe-null profile
   *
   * @return a coalesced flattened profile; an instance of the return type of [ctor]
   *
   * @see map2OptionalValues
   */
  fun <W: Any, R: Any, RESULT: GeneralOps<Segment<R>, RESULT>> flatMap2OptionalValues(ctor: (Timeline<Segment<R>, RESULT>) -> RESULT, other: SerialSegmentOps<W, *>, op: NullBinaryOperation<V, W, SerialSegmentOps<R, *>?>) =
      unsafeOperate(ctor) { opts ->
        map2SegmentLists(collect(opts), other.collect(opts), op)
            .flatMap { it.value.collect(CollectOptions(it.interval, true)) }
      }

  /**
   * [(DOC)][detectEdges] Uses a [NullBinaryOperation] as a predicate to highlight edges between segments.
   *
   * The result is a [Booleans] profile, which is `false` inside segments, a gap when
   * this profile is a gap, and possibly `true`, `false`, or a gap on segment edges according
   * to the result of the predicate.
   *
   * The predicate will be called at the edge between two adjacent segments, where the values of
   * the left and right segments are the left and right operands of the predicate, respectively.
   * The predicate will also be call at any edge where a segment meets a gap, in which case the appropriate operand
   * will be `null`.
   *
   * @param edgePredicate a binary operation between operands of type [V] that produces a boolean or `null`
   * @return a [Booleans] object that contains `true` on the edges indicated by the predicate
   */
  fun detectEdges(edgePredicate: NullBinaryOperation<V, V, Boolean?>) = unsafeOperate(::Booleans) { opts ->
    val bounds = opts.bounds
    var buffer: Segment<V>? = null
    val result = collect(CollectOptions(bounds, false))
        .flatMap { currentSegment ->
          val previous = buffer
          buffer = currentSegment
          val currentInterval = currentSegment.interval

          val leftEdgeInterval = at(currentInterval.start)
          val rightEdgeInterval = at(currentInterval.end)

          val rightEdge = edgePredicate(currentSegment.value, null, rightEdgeInterval)

          val leftEdge = if (previous == null || previous.interval.compareEndToStart(currentInterval) == -1) {
            edgePredicate(null, currentSegment.value, leftEdgeInterval)
          } else {
            edgePredicate(previous.value, currentSegment.value, leftEdgeInterval)
          }

          listOfNotNull(
              Segment(leftEdgeInterval, leftEdge).transpose(),
              Segment(
                  Interval.between(currentInterval.start, currentInterval.end, Interval.Inclusivity.Exclusive),
                  false
              ),
              Segment(rightEdgeInterval, rightEdge).transpose()
          )
        }
    truncateList(coalesceList(result, Segment<Boolean>::valueEquals), opts, true, true)
  }

  /**
   * [(DOC)][changes] Returns a [Booleans] that is true whenever this profile changes, and false or gap everywhere else.
   *
   * This includes both continuous changes and discontinuous changes, if the profile can vary continuously.
   */
  fun changes(): Booleans
  // `transitions(from, to)` is a similar function that you expect to also have a declaration here, but this isn't
  // feasible because `Real.transitions` takes doubles as its arguments instead of its normal payload type (LinearEquation)
}
