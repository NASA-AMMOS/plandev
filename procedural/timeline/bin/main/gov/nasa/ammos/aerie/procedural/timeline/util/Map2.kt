package gov.nasa.ammos.aerie.procedural.timeline.util

import gov.nasa.ammos.aerie.procedural.timeline.NullBinaryOperation
import gov.nasa.ammos.aerie.procedural.timeline.Interval
import gov.nasa.ammos.aerie.procedural.timeline.payloads.IntervalLike
import gov.nasa.ammos.aerie.procedural.timeline.payloads.Segment
import gov.nasa.ammos.aerie.procedural.timeline.payloads.transpose

/**
 * Low level routine for performing a binary operation on a pair of segment lists.
 *
 * Assumes both lists are properly coalesced - NOT CHECKED. Violating this assumption is UB.
 *
 * The resulting segment list is defined as follows. (This is a definition of the result, not the algorithm to calculate it.)
 * For each pair of segments `l` and `r` from the left and right lists (respectively):
 * - let `i` be the intersection between `l.interval` and `r.interval`.
 * - if `i` is not empty, let `o` be the output of `op(l.value, r.value, i)`.
 * - if `o` is not `null`, the result will contain a segment with value `o` on the interval `i`.
 * Additionally, for each pair of segment `s` in either list and gap in the other list:
 * - let `i` be the intersection between `s.interval` and the gap.
 * - if `i` is not empty, let `o` be the output of either `op(s.value, null, i)` or `op(null, s.value, i)`, depending on which list the segment came from.
 * - if `o` is not `null`, the result will contain a segment with value `o` on the interval `i`.
 *
 *
 * This routine performs a single pass down each list, with a computational complexity
 * proportional to the total number of segments in both lists.
 */
fun <LEFT, RIGHT, OUT> map2SegmentLists(
    left: List<Segment<LEFT & Any>>,
    right: List<Segment<RIGHT & Any>>,
    op: NullBinaryOperation<LEFT, RIGHT, OUT?>
): List<Segment<OUT & Any>> {
  val result = mutableListOf<Segment<OUT & Any>>()

  var leftIndex = 0
  var rightIndex = 0

  var leftSegment: Segment<LEFT & Any>?
  var rightSegment: Segment<RIGHT & Any>?
  var remainingLeftSegment: Segment<LEFT & Any>? = null
  var remainingRightSegment: Segment<RIGHT & Any>? = null

  while (
      leftIndex < left.size ||
      rightIndex < right.size ||
      remainingLeftSegment != null ||
      remainingRightSegment != null
  ) {
    if (remainingLeftSegment != null) {
      leftSegment = remainingLeftSegment
      remainingLeftSegment = null
    } else if (leftIndex < left.size) {
      leftSegment = left[leftIndex++]
    } else {
      leftSegment = null
    }
    if (remainingRightSegment != null) {
      rightSegment = remainingRightSegment
      remainingRightSegment = null
    } else if (rightIndex < right.size) {
      rightSegment = right[rightIndex++]
    } else {
      rightSegment = null
    }

    if (leftSegment == null) {
      val resultingSegment = rightSegment!!.mapValue { op(null, it.value, it.interval) }.transpose()
      if (resultingSegment != null) result.add(resultingSegment)
    } else if (rightSegment == null) {
      val resultingSegment = leftSegment.mapValue { op(it.value, null, it.interval) }.transpose()
      if (resultingSegment != null) result.add(resultingSegment)
    } else {
      val startComparison = leftSegment.interval.compareStarts(rightSegment.interval)
      if (startComparison == -1) {
        remainingRightSegment = rightSegment
        val endComparison = leftSegment.interval.compareEndToStart(rightSegment.interval)
        if (endComparison < 1) {
          val resultingSegment = leftSegment.mapValue { op(it.value, null, it.interval) }.transpose()
          if (resultingSegment != null) result.add(resultingSegment)
        } else {
          remainingLeftSegment = leftSegment.mapInterval {
            Interval.between(
                rightSegment.interval.start,
                it.interval.end,
                rightSegment.interval.startInclusivity,
                it.interval.endInclusivity
            )
          }
          val resultingSegment = Segment(
              Interval.between(
                  leftSegment.interval.start,
                  rightSegment.interval.start,
                  leftSegment.interval.startInclusivity,
                  rightSegment.interval.startInclusivity.opposite()
              ),
              leftSegment.value
          ).mapValue { op(it.value, null, it.interval) }.transpose()
          if (resultingSegment != null) result.add(resultingSegment)
        }
      } else if (startComparison == 1) {
        remainingLeftSegment = leftSegment
        val endComparison = rightSegment.interval.compareEndToStart(leftSegment.interval)
        if (endComparison < 1) {
          val resultingSegment = rightSegment.mapValue { op(null, it.value, it.interval) }.transpose()
          if (resultingSegment != null) result.add(resultingSegment)
        } else {
          remainingRightSegment = rightSegment.mapInterval {
            Interval.between(
                leftSegment.interval.start,
                it.interval.end,
                leftSegment.interval.startInclusivity,
                it.interval.endInclusivity
            )
          }
          val resultingSegment = Segment(
              Interval.between(
                  rightSegment.interval.start,
                  leftSegment.interval.start,
                  rightSegment.interval.startInclusivity,
                  leftSegment.interval.startInclusivity.opposite()
              ),
              rightSegment.value
          ).mapValue { op(null, it.value, it.interval) }.transpose()
          if (resultingSegment != null) result.add(resultingSegment)
        }
      } else {
        val endComparison = leftSegment.interval.compareEnds(rightSegment.interval)
        if (endComparison == -1) {
          remainingRightSegment = rightSegment.mapInterval {
            Interval.between(
                leftSegment.interval.end,
                it.interval.end,
                leftSegment.interval.endInclusivity.opposite(),
                it.interval.endInclusivity
            )
          }
          val resultingSegment = leftSegment
              .mapValue { op(it.value, rightSegment.value, it.interval) }.transpose()
          if (resultingSegment != null) result.add(resultingSegment)
        } else if (endComparison == 1) {
          remainingLeftSegment = leftSegment.mapInterval {
            Interval.between(
                rightSegment.interval.end,
                it.interval.end,
                rightSegment.interval.endInclusivity.opposite(),
                it.interval.endInclusivity
            )
          }
          val resultingSegment = rightSegment
              .mapValue { op(leftSegment.value, it.value, it.interval) }.transpose()
          if (resultingSegment != null) result.add(resultingSegment)
        } else {
          val resultingSegment = leftSegment
              .mapValue { op(it.value, rightSegment.value, it.interval) }.transpose()
          if (resultingSegment != null) result.add(resultingSegment)
        }
      }
    }
  }

  return result
}

/**
 * Low-level routine for performing a binary operation on a pair of parallel lists.
 *
 * The result is defined as follows: for every object `l` in [left] and every object `r`
 * in [right], if their intervals overlap on an intersection `i`, the operation will be
 * invoked with `(l, r, i)`. If the output of the operation is not `null`, it will be
 * included in the resulting timeline.
 *
 * Always sorts both lists before performing the operation.
 *
 * @param left the left operand list
 * @param right the right operand list
 * @param op a binary operation between the payload types of the operand lists, which also
 *           accepts an intersection interval
 */
fun <LEFT: IntervalLike<LEFT>, RIGHT: IntervalLike<RIGHT>, OUT: IntervalLike<OUT>> map2ParallelLists(
    left: List<LEFT>,
    right: List<RIGHT>,
    isLeftSorted: Boolean,
    isRightSorted: Boolean,
    op: (LEFT, RIGHT, Interval) -> OUT?,
): List<OUT> {
  val leftSorted = if (isLeftSorted) left else left.sorted()
  val rightSorted = if (isRightSorted) right else right.sorted()

  var rightIndex = 0
  var rightLookaheadIndex: Int

  val result = mutableListOf<OUT>()
  for (leftObj in leftSorted) {
    while (rightIndex < right.size && rightSorted[rightIndex].interval.compareEndToStart(leftObj.interval) == -1) {
      rightIndex++
    }

    if (rightIndex == right.size) break

    rightLookaheadIndex = rightIndex
    while (leftObj.interval.compareEndToStart(rightSorted[rightLookaheadIndex].interval) != -1) {
      val rightObj = right[rightLookaheadIndex]
      val intersection = leftObj.interval intersection rightObj.interval

      if (!intersection.isEmpty()) op(leftObj, rightObj, intersection)?.let { result.add(it) }

      rightLookaheadIndex++
      if (rightLookaheadIndex == right.size) break
    }
  }
  return result
}
