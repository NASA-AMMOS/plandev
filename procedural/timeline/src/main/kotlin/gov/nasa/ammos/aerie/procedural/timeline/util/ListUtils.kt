package gov.nasa.ammos.aerie.procedural.timeline.util

import gov.nasa.jpl.aerie.merlin.protocol.types.Duration
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
  return listCollector(coalesced, true, shouldCoalesce != null)
}

/** Returns a function that lazily truncates a given list of timeline objects to the bounds. */
fun <I: IntervalLike<I>> listCollector(list: List<I>, isSorted: Boolean = false, isSerial: Boolean = false) =
  { opts: CollectOptions -> truncateList(list, opts, isSorted, isSerial) }

/** Eagerly truncates a list of timeline objects to known bounds. */
fun <I: IntervalLike<I>> truncateList(list: List<I>, opts: CollectOptions, exploitSorted: Boolean, isSerial: Boolean): List<I> =
    if (opts.bounds == Interval.MIN_MAX || list.isEmpty()) list
    else if (!exploitSorted) {
      if (!opts.truncateMarginal) {
        list.filter {
          val intersection = it.interval intersection opts.bounds
          !intersection.isEmpty()
        }
      } else {
        list.mapNotNull {
          val intersection = it.interval intersection opts.bounds
          if (intersection.isEmpty()) null
          else if (intersection == it.interval) it
          else it.withNewInterval(intersection)
        }
      }
    } else {
      var endIndex = binarySearch(list, opts.bounds.end, true, opts.bounds.endInclusivity == Interval.Inclusivity.Inclusive)
        ?: list.size

      if (isSerial) {
        val startIndex = (
            binarySearch(list, opts.bounds.start, false, opts.bounds.startInclusivity == Interval.Inclusivity.Inclusive)
              ?: -1
            ) + 1
        var result = list.subList(startIndex, endIndex)
        if (result.isEmpty()) result
        else if (!opts.truncateMarginal) {
          result
        } else {
          result = result.toMutableList()
          result[0] = result[0].withNewInterval(result[0].interval intersection opts.bounds)

          endIndex = result.size - 1
          result[endIndex] = result[endIndex].withNewInterval(result[endIndex].interval intersection opts.bounds)

          result
        }
      } else {
        if (!opts.truncateMarginal) {
          val guaranteedStartIndex = binarySearch(list, opts.bounds.start, true, false) ?: list.size
          val preList = truncateList(list.subList(0, guaranteedStartIndex), opts, false, false)
          val result = list.subList(guaranteedStartIndex, endIndex)
          preList + result
        } else {
          truncateList(list.subList(0, endIndex), opts, false, false)
        }
      }
    }

/**
 * If [searchByStartTime] is true, returns the index of the FIRST interval that STARTS AFTER OR EQUAL to the target.
 *
 * Alternatively if [searchByStartTime] is false, returns the index of the LAST interval
 * that ENDS BEFORE OR EQUAL to the search target.
 *
 * If [strict] is true, restricts the above to strictly before and strictly after, respectively.
 *
 * If no such interval exists, returns null.
 */
private fun <I: IntervalLike<I>> binarySearch(list: List<I>, target: Duration, searchByStartTime: Boolean, strict: Boolean): Int? {
  // Define a function to compare an interval to the target.
  // [searchByStartTime] determines whether we compare with the start or end of the interval.
  val comparator: (Interval) -> Int =
    if (searchByStartTime) fun(i: Interval): Int {
      val startComparison = i.start.compareTo(target)
      return if (startComparison == 0) {
        when (i.startInclusivity) {
          Interval.Inclusivity.Inclusive -> 0
          Interval.Inclusivity.Exclusive -> 1
        }
      } else startComparison
    }
    else fun(i: Interval): Int {
      val endComparison = i.end.compareTo(target)
      return if (endComparison == 0) {
        when (i.endInclusivity) {
          Interval.Inclusivity.Inclusive -> 0
          Interval.Inclusivity.Exclusive -> -1
        }
      } else endComparison
    }

  // Define a function to calculate the middle index of the search window.
  // searchByStartTime determines whether we round up or down.
  val middleCalculator = if (searchByStartTime) {
    fun(start: Int, end: Int) = (start + end) / 2
  } else {
    fun(start: Int, end: Int): Int {
      val sum = start + end
      return (sum / 2) + (sum % 2)
    }
  }

  var indexStart = 0
  var indexEnd = list.size - 1

  // Define a function to narrow down the search window depending on the comparison result.

  // If searchByStartTime, we add 1 when selecting the right half.
  // If !searchByStartTime, we subtract 1 when selecting the left half.

  // If strict == searchByStartTime, we select the right half on 0's.
  // If strict ^ searchByStartTime, we select the left half on 0's.
  val comparisonAction =
    if (searchByStartTime) {
      if (!strict) {
        fun(c: Int, middle: Int) {
          when (c) {
            -1 -> indexStart = middle + 1
            0, 1 -> indexEnd = middle
            else -> throw IndexOutOfBoundsException()
          }
        }
      } else {
        fun(c: Int, middle: Int) {
          when (c) {
            -1, 0 -> indexStart = middle + 1
            1 -> indexEnd = middle
            else -> throw IndexOutOfBoundsException()
          }
        }
      }
    } else {
      if (!strict) {
        fun(c: Int, middle: Int) {
          when (c) {
            -1, 0 -> indexStart = middle
            1 -> indexEnd = middle - 1
            else -> throw IndexOutOfBoundsException()
          }
        }
      } else {
        fun(c: Int, middle: Int) {
          when (c) {
            -1 -> indexStart = middle
            0, 1 -> indexEnd = middle - 1
            else -> throw IndexOutOfBoundsException()
          }
        }
      }
    }

  // Perform the search.
  while (indexStart != indexEnd) {
    val middle = middleCalculator(indexStart, indexEnd)
    val comparison = comparator(list[middle].interval)
    comparisonAction(comparison, middle)
  }

  // Check if we didn't find any value results.

  // We return null according to these rules:

  // if searchByStartTime, return null if the comparison is "too low"
  // if !searchByStartTime, return null if the comparison is "too high"

  // if strict, "too low" means -1 or 0, and "too high" means 0 or 1
  // if !strict, "too low" means only -1, and "too high" means only 1
  return if (searchByStartTime) {
    if (!strict && comparator(list[indexStart].interval) < 0) null
    else if (strict && comparator(list[indexStart].interval) < 1) null
    else indexStart
  } else {
    if (!strict && comparator(list[indexStart].interval) > 0) null
    else if (strict && comparator(list[indexStart].interval) > -1) null
    else indexStart
  }
}

/** Returns a new list of intervals, sorted by start time, or end time in case of a tie. */
fun <I: IntervalLike<I>> List<I>.sorted() = sortedWith { a, b ->
  val startComparison = a.interval.compareStarts(b.interval)
  if (startComparison != 0) startComparison
  else a.interval.compareEnds(b.interval)
}
