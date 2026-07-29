package gov.nasa.ammos.plandev.procedural.timeline.ops.coalesce

import gov.nasa.ammos.plandev.procedural.timeline.Interval
import gov.nasa.ammos.plandev.procedural.timeline.ops.GeneralOps

/** A coalesce operation for intervals, which always coalesce. */
interface CoalesceIntervalsOp<THIS: CoalesceIntervalsOp<THIS>>: GeneralOps<Interval, THIS> {
  override fun shouldCoalesce() = { _: Interval, _: Interval -> true }
}
