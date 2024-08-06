package gov.nasa.ammos.aerie.procedural.timeline.ops.coalesce

import gov.nasa.ammos.aerie.procedural.timeline.payloads.Segment
import gov.nasa.ammos.aerie.procedural.timeline.ops.GeneralOps

/** Implements coalescing for segments. */
interface CoalesceSegmentsOp<V: Any, THIS: CoalesceSegmentsOp<V, THIS>>: GeneralOps<Segment<V>, THIS> {
  override fun shouldCoalesce() = Segment<V>::valueEquals
}
