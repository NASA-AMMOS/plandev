package gov.nasa.jpl.plandev.procedural.timeline.ops.coalesce

import gov.nasa.jpl.plandev.procedural.timeline.Interval
import gov.nasa.jpl.plandev.procedural.timeline.payloads.IntervalLike
import gov.nasa.jpl.plandev.procedural.timeline.ops.GeneralOps

/** A coalesce operation for intervals, which always coalesce. */
interface CoalesceIntervalsOp<THIS: CoalesceIntervalsOp<THIS>>: GeneralOps<Interval, THIS> {
  override fun shouldCoalesce() = { _: Interval, _: Interval -> true }
}
