package gov.nasa.ammos.aerie.procedural.timeline.payloads

import gov.nasa.ammos.aerie.procedural.timeline.Interval

/**
 * An interface for objects that have a interval-like presence on a timeline.
 *
 * For example, profile segments, activity instances, and plain intervals are all interval-like.
 */
interface IntervalLike<I: IntervalLike<I>> {
  /** The interval this occupies on the timeline. */
  val interval: Interval

  /** Creates a new object that is identical except that it exists on a different interval. */
  fun withNewInterval(i: Interval): I
}
