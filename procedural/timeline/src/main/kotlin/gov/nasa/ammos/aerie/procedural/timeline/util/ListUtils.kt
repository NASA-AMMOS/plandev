package gov.nasa.ammos.aerie.procedural.timeline.util

import gov.nasa.ammos.aerie.procedural.timeline.CollectOptions
import gov.nasa.ammos.aerie.procedural.timeline.Interval
import gov.nasa.ammos.aerie.procedural.timeline.payloads.IntervalLike

/**
 * Sanitizes a list of [IntervalLike] objects for use in a timeline.
 *
 * 1. sorts the list by start time (or by end time if two start times are equal)
 * 2. coalesces the list if applicable
 * 3. wraps the list in a collect closure that truncates the list to a given set of [CollectOptions]
 *
 * @param list the list to sanitize
 * @param shouldCoalesce a maybe-null two-argument function of [V]s that decides if they should be coalesced when they
 *                       overlap. If `null`, no coalesce operation is performed.
 *
 * @return a collect closure that produces a sorted, possibly coalesced, and bounded list
 */
fun <V: IntervalLike<V>> preprocessList(list: List<V>, shouldCoalesce: (V.(V) -> Boolean)?): (CollectOptions) -> List<V> {
  val sorted = list.sorted()
  val coalesced = maybeCoalesce(sorted, shouldCoalesce)
  return listCollector(coalesced)
}

/** Returns a function that lazily truncates a given list of timeline objects to the bounds. */
fun <I: IntervalLike<I>> listCollector(list: List<I>) = { opts: CollectOptions -> truncateList(list, opts) }

/** Eagerly truncates a list of timeline objects to known bounds. */
fun <I: IntervalLike<I>> truncateList(list: List<I>, opts: CollectOptions) =
    if (opts.bounds == Interval.MIN_MAX) list
    else if (!opts.truncateMarginal) {
      list.filter {
        val intersection = it.interval.intersection(opts.bounds)
        !intersection.isEmpty()
      }
    } else {
      list.mapNotNull {
        val intersection = it.interval.intersection(opts.bounds)
        if (intersection.isEmpty()) null
        else if (intersection == it.interval) it
        else it.withNewInterval(intersection)
      }
    }

/** Returns a new list of intervals, sorted by start time, or end time in case of a tie. */
fun <I: IntervalLike<I>> List<I>.sorted() = sortedWith { a, b ->
  val startComparison = a.interval.compareStarts(b.interval)
  if (startComparison != 0) startComparison
  else a.interval.compareEnds(b.interval)
}
