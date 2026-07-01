package gov.nasa.ammos.aerie.procedural.timeline.collections

import gov.nasa.ammos.aerie.procedural.timeline.payloads.IntervalLike
import gov.nasa.ammos.aerie.procedural.timeline.BaseTimeline
import gov.nasa.ammos.aerie.procedural.timeline.ops.ParallelOps
import gov.nasa.ammos.aerie.procedural.timeline.Timeline
import gov.nasa.ammos.aerie.procedural.timeline.ops.NonZeroDurationOps
import gov.nasa.ammos.aerie.procedural.timeline.util.preprocessList

/** A timeline of any [IntervalLike] object with no special operations. */
data class Universal<T: IntervalLike<T>>(private val timeline: Timeline<T, Universal<T>>):
    Timeline<T, Universal<T>> by timeline,
    ParallelOps<T, Universal<T>>,
    NonZeroDurationOps<T, Universal<T>>
{
  constructor(vararg intervals: T): this(intervals.asList())
  constructor(intervals: List<T>): this(BaseTimeline(::Universal, preprocessList(intervals, null)))
}
