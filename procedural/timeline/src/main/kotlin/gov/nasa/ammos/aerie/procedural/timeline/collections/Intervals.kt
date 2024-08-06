package gov.nasa.ammos.aerie.procedural.timeline.collections

import gov.nasa.ammos.aerie.procedural.timeline.payloads.IntervalLike
import gov.nasa.ammos.aerie.procedural.timeline.BaseTimeline
import gov.nasa.ammos.aerie.procedural.timeline.ops.ParallelOps
import gov.nasa.ammos.aerie.procedural.timeline.Timeline
import gov.nasa.ammos.aerie.procedural.timeline.ops.NonZeroDurationOps
import gov.nasa.ammos.aerie.procedural.timeline.ops.coalesce.CoalesceNoOp
import gov.nasa.ammos.aerie.procedural.timeline.util.preprocessList

/** A timeline of any [IntervalLike] object with no special operations. */
data class Intervals<T: IntervalLike<T>>(private val timeline: Timeline<T, Intervals<T>>):
    Timeline<T, Intervals<T>> by timeline,
    ParallelOps<T, Intervals<T>>,
    NonZeroDurationOps<T, Intervals<T>>
{
  constructor(vararg intervals: T): this(intervals.asList())
  constructor(intervals: List<T>): this(BaseTimeline(::Intervals, preprocessList(intervals, null)))
}
