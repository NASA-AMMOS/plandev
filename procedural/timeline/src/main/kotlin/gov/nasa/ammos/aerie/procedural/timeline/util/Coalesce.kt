package gov.nasa.ammos.aerie.procedural.timeline.util

import gov.nasa.ammos.aerie.procedural.timeline.Interval
import gov.nasa.ammos.aerie.procedural.timeline.payloads.IntervalLike

/** Coalesces a list if the provided [shouldCoalesce] function is not `null`. */
fun <I: IntervalLike<I>> maybeCoalesce(list: List<I>, shouldCoalesce: (I.(I) -> Boolean)?) =
    shouldCoalesce?.let { coalesceList(list, it) } ?: list

/**
 * Flattens overlapping segments into non-overlapping segments with unequal consecutive values.
 *
 * *Input condition*: segments must be sorted such that between each pair of consecutive elements, one of the following is true:
 * - if the values are unequal, the start and end times (including inclusivity) must be strictly increasing
 * - if the values are equal, the start time must be non-decreasing.
 *
 * This input condition is not checked, and violating it is undefined behavior.
 *
 * Empty intervals are removed, and their values are not considered for the purposes of the sorted input condition.
 */
fun <I: IntervalLike<I>> coalesceList(list: List<I>, shouldCoalesce: I.(I) -> Boolean): List<I> {
  val mutableList = list.toMutableList()
  if (mutableList.isEmpty()) return mutableList
  var shortIndex = 0
  var startIndex = 0
  while(mutableList[startIndex].interval.isEmpty()) startIndex++
  var buffer = mutableList[shortIndex]
  for (segment in mutableList.subList(startIndex + 1, mutableList.size)) {
    if (segment.interval.isEmpty()) continue
    val comparison = buffer.interval.compareEndToStart(segment.interval)
    if (comparison == -1) {
      if (!buffer.interval.isEmpty()) mutableList[shortIndex++] = buffer
      buffer = segment
    } else if (comparison == 0) {
      if (buffer.shouldCoalesce(segment)) {
        if (buffer.interval.compareEnds(segment.interval) < 0) {
          buffer = buffer.withNewInterval(
            Interval.between(buffer.interval.start, segment.interval.end, buffer.interval.startInclusivity, segment.interval.endInclusivity)
          )
        }
      } else {
        if (!buffer.interval.isEmpty()) mutableList[shortIndex++] = buffer
        buffer = segment
      }
    } else {
      if (buffer.shouldCoalesce(segment)) {
        if (buffer.interval.compareEnds(segment.interval) < 0) {
          buffer = buffer.withNewInterval(
            Interval.between(buffer.interval.start, segment.interval.end, buffer.interval.startInclusivity, segment.interval.endInclusivity)
          )
        }
      } else {
        buffer = buffer.withNewInterval(
          Interval.between(buffer.interval.start, segment.interval.start, buffer.interval.startInclusivity, segment.interval.startInclusivity.opposite())
        )
        if (!buffer.interval.isEmpty()) mutableList[shortIndex++] = buffer
        buffer = segment
      }
    }
  }
  if (!buffer.interval.isEmpty()) mutableList[shortIndex++] = buffer
  return mutableList.subList(0, shortIndex)
}
